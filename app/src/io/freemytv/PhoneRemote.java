package io.freemytv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Your phone as the TV's remote (Settings > Phone Remote): a web page from NotifyServer (/remote, with the pairing
 * key; the QR code in RemoteActivity) with the arrows, OK, Back, Home, volume, play/pause, the apps and typing, and a
 * Special days tab (Celebrate: birthdays, anniversaries, own festivals, reminders; the calendar link for Briefing). Volume goes through AudioManager here; keys, typing and opening apps go through the TV's
 * own ADB (an app can't press keys in other apps, nor open them from the background), one connection kept open while
 * the page is in use, the commands one after the other in the order pressed.
 */
final class PhoneRemote {
    private PhoneRemote() {}

    private static final int MAX_BODY = 8 * 1024;
    private static final ExecutorService ADB = Executors.newSingleThreadExecutor();
    private static Adb adb;
    private static long adbUsed;

    // MARK: - Control Center and TV Cleanup from the phone

    /** What Control Center shows: the sleep timer, free memory, the sound output and the volume. */
    private static JSONObject controlState(Context c) {
        JSONObject o = new JSONObject();
        try {
            long left = Prefs.sleepAt() - System.currentTimeMillis();
            AudioManager audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            o.put("sleep", left > 0 ? (left + 59_999) / 60_000 : 0).put("memory", TvActions.freeMemory(c))
                    .put("sound", TvActions.soundOutput(c))
                    .put("volume", audio == null ? 0 : audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                    .put("max", audio == null ? 100 : audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        } catch (Exception ignored) {
        }
        return o;
    }

    /** A Control Center action; the answer to show, or null when it couldn't. Waits for the slow ones (here, not on the main thread). */
    private static String control(Context c, JSONObject body) {
        String what = body.optString("do");
        switch (what) {
            case "sleep": {
                int min = Math.max(0, Math.min(240, body.optInt("min")));
                Prefs.setSleepAt(min == 0 ? 0 : System.currentTimeMillis() + min * 60_000L);
                return min == 0 ? Ui.tr("Off") : Ui.trf("Off in {n} min", "n", min);
            }
            case "off":
                TvActions.sleepNow(c);
                return Ui.tr("Turn off now");
            case "free":
                return wait(done -> TvActions.freeUp(c, done::got), 15);
            case "speed":
                return wait(done -> TvActions.speedTest(done::got), 20);
            case "saver":
                shell(c, "am start -n " + c.getPackageName() + "/.SettingsActivity --ez " + SettingsActivity.EXTRA_SCREENSAVER + " true");
                return Ui.tr("Screensaver");
            default:
                return null;
        }
    }

    private interface Starter {
        void start(TvActions.Answer done);
    }

    private static String wait(Starter s, int seconds) {
        final String[] got = {null};
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        s.start(v -> {
            got[0] = v;
            latch.countDown();
        });
        try {
            latch.await(seconds, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return got[0];
    }

    /** TV Cleanup's list as the TV has it now (installed apps only). */
    private static JSONArray cleanupState(Context c) {
        List<Cleanup.Item> items = Cleanup.catalogue(c);
        String error = cleanupWait(done -> Cleanup.read(c, items, done));
        JSONArray a = new JSONArray();
        if (error != null) return a;
        java.util.Set<String> mine = Cleanup.recorded(c);
        try {
            for (Cleanup.Item it : items) {
                if (!it.installed) continue;
                a.put(new JSONObject().put("pkg", it.pkg).put("name", it.name).put("what", it.what).put("group", it.group)
                        .put("rec", it.recommended).put("off", it.off).put("mine", mine.contains(it.pkg)));
            }
        } catch (Exception ignored) {
        }
        return a;
    }

    /** {pkg, on} for one app, or {all: "clean"} / {all: "restore"}. */
    private static String cleanup(Context c, JSONObject body) {
        List<Cleanup.Item> items = Cleanup.catalogue(c);
        if (cleanupWait(done -> Cleanup.read(c, items, done)) != null) return null;
        List<Cleanup.Item> todo = new ArrayList<>();
        boolean on;
        String all = body.optString("all");
        if (all.equals("clean")) {
            on = false;
            for (Cleanup.Item it : items) if (it.installed && it.recommended && !it.off) todo.add(it);
        } else if (all.equals("restore")) {
            on = true;
            java.util.Set<String> mine = Cleanup.recorded(c);
            for (Cleanup.Item it : items) if (it.installed && it.off && mine.contains(it.pkg)) todo.add(it);
        } else {
            on = body.optBoolean("on");
            for (Cleanup.Item it : items) if (it.installed && it.pkg.equals(body.optString("pkg"))) todo.add(it);
            if (todo.isEmpty()) return null;
        }
        if (todo.isEmpty()) return Ui.tr("All clean");
        String error = cleanupWait(done -> Cleanup.set(c, todo, on, done));
        return error != null ? error : Ui.trf("Done. {memory}", "memory", TvActions.freeMemory(c));
    }

    private interface CleanupStarter {
        void start(Cleanup.Done done);
    }

    /** Runs a Cleanup step and waits for it (at most 60 s); its error, or null. */
    private static String cleanupWait(CleanupStarter s) {
        final String[] error = {"timed out"};
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        s.start(e -> {
            error[0] = e;
            latch.countDown();
        });
        try {
            latch.await(60, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return error[0];
    }

    /** A photo shared from the phone, into the wallpaper photos (a set, or every day): checked to be a picture. */
    private static boolean savePhoto(Context c, InputStream in, long length, String set) {
        if (length <= 0 || length > 30L * 1024 * 1024) return false;
        java.io.File dir = c.getExternalFilesDir("photos");
        if (dir == null) return false;
        for (String known : Photos.SETS) if (known.equals(set)) dir = new java.io.File(dir, set);
        if (!dir.isDirectory() && !dir.mkdirs()) return false;
        java.io.File part = new java.io.File(dir, "." + Http.randomText(8) + ".part");
        byte[] head = new byte[12];
        try (java.io.FileOutputStream f = new java.io.FileOutputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            long left = length;
            int got = 0;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new IOException("the upload stopped");
                if (got < head.length) System.arraycopy(buf, 0, head, got, Math.min(n, head.length - got));
                got += n;
                f.write(buf, 0, n);
                left -= n;
            }
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            return false;
        }
        String ext = (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 ? ".jpg"
                : (head[0] & 0xFF) == 0x89 && head[1] == 'P' ? ".png"
                : head[0] == 'R' && head[8] == 'W' ? ".webp" : null;
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(part.getPath(), bounds);
        if (ext == null || bounds.outWidth <= 0) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            return false;
        }
        String date = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new java.util.Date());
        return part.renameTo(new java.io.File(dir, date + "_phone" + Http.randomText(10) + ext));
    }

    /** The page's buttons, by name: only these keys can be sent. */
    private static final Map<String, String> KEYS = new HashMap<>();
    static {
        String[][] k = {{"up", "DPAD_UP"}, {"down", "DPAD_DOWN"}, {"left", "DPAD_LEFT"}, {"right", "DPAD_RIGHT"},
            {"ok", "DPAD_CENTER"}, {"back", "BACK"}, {"home", "HOME"}, {"menu", "MENU"}, {"play", "MEDIA_PLAY_PAUSE"},
            {"rew", "MEDIA_REWIND"}, {"ff", "MEDIA_FAST_FORWARD"}, {"power", "POWER"}, {"del", "DEL"},
            {"input", "TV_INPUT"}, {"settings", "SETTINGS"}};
        for (String[] p : k) KEYS.put(p[0], "KEYCODE_" + p[1]);
    }

    /** Apps the page shows first when they're installed (the rest are in "More apps"). */
    private static final String[] FAVOURITES = {"com.netflix.ninja", "com.google.android.youtube.tv",
        "com.amazon.amazonvideo.livingroom", "in.startv.hotstar", "com.sonyliv", "com.crunchyroll.crunchyroid",
        "com.jio.media.jiotvplus", "com.graymatrix.did", "com.spotify.tv.android", "com.suntv.sunnxt", "ahaflix.tv",
        "com.apple.atve.androidtv.appletv"};

    /** Handles the remote's paths; false for any other path (NotifyServer goes on). The key is already checked. */
    static boolean handle(Context c, Http.Request r, InputStream in, OutputStream out, String key) throws IOException {
        String path = r.path;
        if (r.method.equals("GET") && path.equals("/remote")) {
            Http.reply(out, 200, "text/html; charset=utf-8", page(key));
        } else if (r.method.equals("GET") && path.equals("/remote/apps")) {
            Http.reply(out, 200, "application/json", apps(c).toString());
        } else if (r.method.equals("GET") && path.equals("/remote/days")) {
            Http.reply(out, 200, "application/json", days(c).toString());
        } else if (r.method.equals("GET") && path.equals("/remote/calendar")) {
            Http.reply(out, 200, "application/json", "{\"set\":" + !Briefing.calendarUrl(c).isEmpty() + "}");
        } else if (r.method.equals("GET") && path.equals("/remote/home")) {
            Http.reply(out, 200, "application/json", PhoneHome.home(c).toString());
        } else if (r.method.equals("GET") && (path.equals("/remote/wallpaper") || path.equals("/remote/icon") || path.equals("/remote/art"))) {
            byte[] img;
            try {
                img = path.equals("/remote/wallpaper") ? PhoneHome.wallpaper(c)
                        : path.equals("/remote/icon") ? PhoneHome.icon(c, java.net.URLDecoder.decode(r.params.get("p"), "UTF-8"))
                        : PhoneHome.art(c, Integer.parseInt(r.params.get("i")));
            } catch (Exception e) {
                img = null;
            }
            if (img == null) {
                Http.reply(out, 404, "text/plain", "none");
            } else {
                Http.send(out, path.equals("/remote/icon") ? "image/png" : "image/jpeg", null, new java.io.ByteArrayInputStream(img), img.length);
            }
        } else if (r.method.equals("GET") && path.equals("/remote/control")) {
            Http.reply(out, 200, "application/json", controlState(c).toString());
        } else if (r.method.equals("GET") && path.equals("/remote/cleanup")) {
            Http.reply(out, 200, "application/json", cleanupState(c).toString());
        } else if (r.method.equals("POST") && path.equals("/remote/photo")) {
            boolean ok = savePhoto(c, in, r.length(), r.params.containsKey("set") ? r.params.get("set") : "");
            Http.reply(out, ok ? 200 : 400, "application/json", "{\"ok\":" + ok + "}");
        } else if (r.method.equals("POST") && (path.equals("/remote/control") || path.equals("/remote/cleanup"))) {
            JSONObject body;
            try {
                body = new JSONObject(readBody(r, in));
            } catch (Exception e) {
                Http.reply(out, 400, "application/json", "{\"ok\":false}");
                return true;
            }
            String answer = path.equals("/remote/control") ? control(c, body) : cleanup(c, body);
            JSONObject o = new JSONObject();
            try {
                o.put("ok", answer != null).put("text", answer == null ? "" : answer);
            } catch (Exception ignored) {
            }
            Http.reply(out, answer != null ? 200 : 400, "application/json", o.toString());
        } else if (r.method.equals("POST") && path.startsWith("/remote/")) {
            JSONObject body;
            try {
                body = new JSONObject(readBody(r, in));
            } catch (Exception e) {
                Http.reply(out, 400, "application/json", "{\"ok\":false}");
                return true;
            }
            boolean ok = command(c, path.substring("/remote/".length()), body);
            Http.reply(out, ok ? 200 : 400, "application/json", "{\"ok\":" + ok + "}");
        } else {
            return false;
        }
        return true;
    }

    private static String readBody(Http.Request r, InputStream in) throws IOException {
        long length = r.length();
        if (length <= 0) return "{}";
        if (length > MAX_BODY) throw new IOException("too long");
        byte[] body = new byte[(int) length];
        for (int got = 0; got < body.length; ) {
            int n = in.read(body, got, body.length - got);
            if (n < 0) throw new IOException("cut short");
            got += n;
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    private static boolean command(Context c, String what, JSONObject body) {
        switch (what) {
            case "key": {
                String code = KEYS.get(body.optString("k"));
                if (code == null) return false;
                shell(c, "input keyevent " + code);
                return true;
            }
            case "volume": {
                AudioManager audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
                if (audio == null) return false;
                String dir = body.optString("d");
                int adjust = dir.equals("up") ? AudioManager.ADJUST_RAISE : dir.equals("down") ? AudioManager.ADJUST_LOWER
                        : dir.equals("mute") ? AudioManager.ADJUST_TOGGLE_MUTE : 0;
                if (adjust == 0) return false;
                if (adjust == AudioManager.ADJUST_RAISE
                        && audio.getStreamVolume(AudioManager.STREAM_MUSIC) >= VolumeLimit.cap(audio)) return true;   // at the limit
                audio.adjustSuggestedStreamVolume(adjust, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI);
                return true;
            }
            case "text": {
                String text = inputText(body.optString("t"));
                if (text.isEmpty()) return false;
                shell(c, "input text " + text);
                return true;
            }
            case "app": {
                String pkg = body.optString("p");
                boolean known = false;
                for (String[] app : HomeApps.launchable(c)) known |= app[0].equals(pkg);
                Intent i = known ? HomeApps.launchIntent(c.getPackageManager(), pkg) : null;
                ComponentName cn = i == null ? null : i.getComponent();
                if (cn == null) return false;
                shell(c, "am start -n " + cn.flattenToShortString());
                return true;
            }
            case "day-add": {
                // {kind, name, date: yyyy-MM-dd from the phone's date picker, repeat, time: HH:mm}
                String date = body.optString("date"), time = body.optString("time", "09:00");
                if (date.length() != 10) return false;
                try {
                    int minute = time.length() == 5 ? Integer.parseInt(time.substring(0, 2)) * 60 + Integer.parseInt(time.substring(3, 5)) : 9 * 60;
                    JSONObject day = new JSONObject().put("kind", body.optString("kind")).put("name", body.optString("name"))
                            .put("repeat", body.optString("repeat", Celebrate.YEARLY)).put("y", Integer.parseInt(date.substring(0, 4)))
                            .put("m", Integer.parseInt(date.substring(5, 7))).put("d", Integer.parseInt(date.substring(8, 10)))
                            .put("min", minute);
                    return Celebrate.add(c, day);
                } catch (Exception e) {
                    return false;
                }
            }
            case "day-remove":
                return Celebrate.remove(c, body.optInt("i", -1));
            case "calendar":
                return Briefing.setCalendarUrl(c, body.optString("url"));
            case "watch": {   // a Continue watching card on the phone's Home
                String cmd = PhoneHome.watchCommand(c, body.optInt("i", -1));
                if (cmd == null) return false;
                shell(c, cmd);
                return true;
            }
            case "open": {   // a link from the phone opens on the TV (YouTube links in YouTube, others in a browser)
                String url = body.optString("url").trim();
                if (!(url.startsWith("http://") || url.startsWith("https://")) || url.length() > 2000
                        || url.contains("'") || url.contains("\n")) return false;
                shell(c, "am start -a android.intent.action.VIEW -d '" + url + "'");
                return true;
            }
            default:
                return false;
        }
    }

    /**
     * The text for `input text`, safe for the shell: printable ASCII only (the command types nothing else), spaces as
     * %s (its way of typing one; so no % itself), the whole thing in single quotes.
     */
    static String inputText(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 300; i++) {
            char ch = s.charAt(i);
            if (ch == ' ') sb.append("%s");
            else if (ch == '\'') sb.append("'\\''");
            else if (ch == '%') continue;   // "%s" would type a space
            else if (ch > ' ' && ch < 127) sb.append(ch);
        }
        return sb.length() == 0 ? "" : "'" + sb + "'";
    }

    /** Runs it on the TV's ADB, after the commands before it; the connection is opened on the first and kept. */
    private static void shell(Context c, String command) {
        Context app = c.getApplicationContext();
        ADB.execute(() -> {
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    if (adb == null || System.currentTimeMillis() - adbUsed > 5 * 60_000) {
                        if (adb != null) adb.close();
                        adb = Adb.connect(app, 5_000, null);
                    }
                    adb.shell(command);
                    adbUsed = System.currentTimeMillis();
                    return;
                } catch (Exception e) {
                    if (adb != null) adb.close();
                    adb = null;   // try once more on a new connection
                    if (attempt == 1) AppLog.w("phone remote: " + e.getMessage());
                }
            }
        });
    }

    private static JSONArray apps(Context c) {
        List<String[]> all = HomeApps.launchable(c);
        List<String[]> first = new ArrayList<>(), rest = new ArrayList<>();
        for (String fav : FAVOURITES) for (String[] app : all) if (app[0].equals(fav)) first.add(app);
        for (String[] app : all) {
            if (!first.contains(app) && !app[0].equals(c.getPackageName())) rest.add(app);
        }
        Collections.sort(rest, (a, b) -> a[1].toLowerCase(Locale.ROOT).compareTo(b[1].toLowerCase(Locale.ROOT)));
        JSONArray out = new JSONArray();
        try {
            for (String[] app : first) out.put(new JSONObject().put("p", app[0]).put("n", app[1]).put("fav", true));
            for (String[] app : rest) out.put(new JSONObject().put("p", app[0]).put("n", app[1]));
        } catch (Exception ignored) {
        }
        return out;
    }

    private static JSONArray days(Context c) {
        JSONArray out = new JSONArray();
        try {
            for (Celebrate.Day d : Celebrate.days(c)) out.put(d.json());
        } catch (Exception ignored) {
        }
        return out;
    }

    /** The phone's page: Remote and Birthdays tabs. English, like the other phone pages. */
    private static String page(String key) {
        return "<!doctype html><html><head><meta charset=utf-8>"
            + "<meta name=viewport content='width=device-width,initial-scale=1,user-scalable=no'>"
            + "<meta name=theme-color content='#141218'><title>" + Ui.BRAND + " Remote</title><style>"
            + "*{box-sizing:border-box;-webkit-tap-highlight-color:transparent;user-select:none;-webkit-user-select:none}"
            + "html,body{background:#0b0b0d}body{margin:0;font-family:Inter,system-ui,sans-serif;color:#fff;touch-action:manipulation}"
            + "#bg{position:fixed;inset:-6%;background:#141218 center/cover no-repeat;transition:filter .7s ease;z-index:-2;"
            + "animation:drift 75s ease-in-out infinite alternate}#bg.soft{filter:blur(18px) brightness(.62)}"
            + "#shade{position:fixed;inset:0;z-index:-1;background:linear-gradient(180deg,rgba(0,0,0,.25),rgba(0,0,0,.15) 40%,rgba(0,0,0,.65))}"
            + "@keyframes drift{0%{transform:scale(1.04) translate(0,0)}50%{transform:scale(1.1) translate(-1.5%,1%)}100%{transform:scale(1.06) translate(1.2%,-1%)}}"
            + "main{max-width:460px;margin:0 auto;padding:14px 16px 48px}"
            + ".g{background:rgba(255,255,255,.1);-webkit-backdrop-filter:blur(26px) saturate(1.5);backdrop-filter:blur(26px) saturate(1.5);"
            + "border:1px solid rgba(255,255,255,.14);box-shadow:inset 0 1px 0 rgba(255,255,255,.28),0 10px 30px rgba(0,0,0,.25)}"
            + "nav{position:sticky;top:8px;z-index:5;display:flex;gap:4px;border-radius:30px;padding:5px;margin:0 0 18px}"
            + "nav button{flex:1;border:0;border-radius:24px;padding:11px 2px;font-size:14px;font-weight:600;background:none;color:rgba(255,255,255,.75)}"
            + "nav button.on{background:#fff;color:#1c1b1f;box-shadow:0 4px 14px rgba(0,0,0,.3)}"
            + ".row{display:flex;gap:12px;justify-content:space-between;margin:0 0 14px}"
            + ".b{flex:1;border-radius:22px;color:#fff;font-size:16px;font-weight:600;padding:16px 8px;min-height:56px;"
            + "background:rgba(255,255,255,.1);-webkit-backdrop-filter:blur(26px);backdrop-filter:blur(26px);"
            + "border:1px solid rgba(255,255,255,.14);box-shadow:inset 0 1px 0 rgba(255,255,255,.28);transition:transform .15s,background .15s}"
            + ".b:active{background:#fff;color:#1c1b1f;transform:scale(.96)}"
            + ".pad{position:relative;width:280px;height:280px;margin:8px auto 22px;border-radius:50%;background:rgba(255,255,255,.1);"
            + "-webkit-backdrop-filter:blur(26px);backdrop-filter:blur(26px);border:1px solid rgba(255,255,255,.16);"
            + "box-shadow:inset 0 1px 0 rgba(255,255,255,.3),0 14px 40px rgba(0,0,0,.35)}"
            + ".pad button{position:absolute;border:0;background:none;color:#fff;font-size:30px;width:90px;height:90px}"
            + ".pad button:active{color:#d0bcff}"
            + ".pad .u{top:0;left:95px}.pad .d{bottom:0;left:95px}.pad .l{left:0;top:95px}.pad .r{right:0;top:95px}"
            + ".pad .ok{left:75px;top:75px;width:130px;height:130px;border-radius:50%;background:#fff;color:#1c1b1f;"
            + "font-size:22px;font-weight:700;box-shadow:0 8px 24px rgba(0,0,0,.35)}.pad .ok:active{transform:scale(.95)}"
            + ".vol{display:flex;flex-direction:column;gap:12px;flex:1}"
            + "h2{font-size:17px;color:#fff;font-weight:600;margin:26px 4px 12px;text-shadow:0 1px 8px rgba(0,0,0,.4)}"
            + ".apps{display:grid;grid-template-columns:1fr 1fr;gap:10px}"
            + "input,select{width:100%;font-size:17px;padding:14px 16px;border-radius:16px;border:1px solid rgba(255,255,255,.14);"
            + "background:rgba(255,255,255,.1);color:#fff;user-select:text;-webkit-user-select:text;-webkit-backdrop-filter:blur(20px);backdrop-filter:blur(20px)}"
            + "input::placeholder{color:rgba(255,255,255,.55)}select option{color:#000}"
            + ".type{display:flex;gap:10px}.type input{flex:1}.type .b{flex:0 0 96px}"
            + ".bd{display:flex;align-items:center;justify-content:space-between;border-radius:18px;padding:14px 16px;margin:0 0 10px;"
            + "font-size:17px;background:rgba(255,255,255,.1);-webkit-backdrop-filter:blur(26px);backdrop-filter:blur(26px);"
            + "border:1px solid rgba(255,255,255,.14);box-shadow:inset 0 1px 0 rgba(255,255,255,.25)}"
            + ".bd small{display:block;color:rgba(255,255,255,.65);font-size:13px;margin-top:3px}"
            + ".bd button{border:0;background:#fff;color:#1c1b1f;border-radius:14px;padding:8px 14px;font-size:14px;font-weight:600;flex:none;margin-left:10px}"
            + "#msg{position:fixed;left:50%;bottom:24px;transform:translateX(-50%);padding:10px 18px;border-radius:20px;"
            + "background:rgba(20,18,24,.85);-webkit-backdrop-filter:blur(20px);backdrop-filter:blur(20px);font-size:15px;opacity:0;transition:opacity .25s;z-index:9}"
            + "#msg.on{opacity:1}.hide{display:none}"
            + ".greet{font-size:20px;font-weight:600;margin:18px 2px 4px;text-shadow:0 2px 10px rgba(0,0,0,.45)}"
            + ".clock{font-size:104px;font-weight:500;line-height:.9;letter-spacing:-2px;margin:6px 0 0;text-shadow:0 4px 24px rgba(0,0,0,.35)}"
            + ".clock sup{font-size:22px;font-weight:600;letter-spacing:0;vertical-align:top;margin-left:8px;position:relative;top:14px}"
            + ".date{font-size:22px;margin:12px 2px 0;text-shadow:0 2px 10px rgba(0,0,0,.45)}.rem{font-size:15px;margin:8px 2px 0;opacity:.9}"
            + ".chip{display:inline-block;margin:14px 0 0;padding:9px 16px;border-radius:20px;font-size:15px;font-weight:500}"
            + ".shelf{border-radius:28px;padding:14px;margin:26px -4px 0;display:flex;gap:12px;overflow-x:auto;scrollbar-width:none}"
            + ".shelf::-webkit-scrollbar,.scroll::-webkit-scrollbar{display:none}"
            + ".tile{flex:none;width:150px;text-align:center}.tile img{width:150px;height:84px;border-radius:16px;display:block;"
            + "box-shadow:0 6px 16px rgba(0,0,0,.35);transition:transform .18s}.tile span{display:block;font-size:12px;margin-top:7px;opacity:.85}"
            + ".tile:active img{transform:scale(1.08) translateY(-3px);outline:3px solid #fff;outline-offset:3px}"
            + ".scroll{display:flex;gap:12px;overflow-x:auto;scrollbar-width:none;margin:0 -16px;padding:4px 16px 8px}"
            + ".card{flex:none;width:210px}.card .pic{position:relative;width:210px;height:118px;border-radius:16px;overflow:hidden;"
            + "background:#2c2c2e center/cover;box-shadow:0 8px 20px rgba(0,0,0,.35)}.card .pic i{position:absolute;left:12px;right:12px;bottom:10px;"
            + "height:5px;border-radius:3px;background:rgba(255,255,255,.4)}.card .pic i b{display:block;height:100%;border-radius:3px;background:#fff}"
            + ".card .t{font-size:14px;font-weight:600;margin-top:8px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}"
            + ".card .a{font-size:12px;opacity:.7;margin-top:2px}.card:active .pic{transform:scale(1.04);outline:3px solid #fff;outline-offset:2px}"
            + ".match{flex:none;width:230px;border-radius:18px;padding:14px 16px}.match div{display:flex;align-items:center;gap:10px;font-weight:700;font-size:16px;margin:2px 0 8px}"
            + ".match img{width:28px;height:20px;border-radius:3px;object-fit:cover;background:rgba(255,255,255,.2)}"
            + ".match small{display:block;font-size:12px;opacity:.7;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}"
            + ".live{display:inline-block;background:#e5383b;border-radius:6px;padding:2px 7px;font-size:11px;font-weight:700;margin-right:6px}"
            + "#zoom{position:fixed;z-index:20;border-radius:16px;background:#000 center/cover;pointer-events:none;opacity:0;"
            + "transition:all .42s cubic-bezier(.65,0,.35,1)}"
            + "</style></head><body><div id=bg></div><div id=shade></div><div id=zoom></div><main>"
            + "<nav class=g><button id=t0 class=on>Home</button><button id=t1>Remote</button><button id=t3>Control</button>"
            + "<button id=t4>Clean</button><button id=t2>Days</button></nav>"
            + "<section id=home><div class=greet id=greet></div><div class=clock id=clock></div><div class=date id=date></div>"
            + "<div class=rem id=rem></div><div class='chip g' id=wx style='display:none'></div>"
            + "<div class='shelf g' id=shelf></div>"
            + "<div id=cwrap class=hide><h2>Continue watching</h2><div class=scroll id=cw></div></div>"
            + "<div id=ckwrap class=hide><h2>Cricket</h2><div class=scroll id=ck></div></div></section>"
            + "<section id=remote class=hide>"
            + "<div class=row><button class=b data-k=power>⏻ Power</button><button class=b data-k=home>⌂ Home</button>"
            + "<button class=b data-k=back>← Back</button></div>"
            + "<div class=pad><button class=u data-k=up data-hold>▲</button><button class=d data-k=down data-hold>▼</button>"
            + "<button class=l data-k=left data-hold>◀</button><button class=r data-k=right data-hold>▶</button>"
            + "<button class=ok data-k=ok>OK</button></div>"
            + "<div class=row><div class=vol><button class=b data-v=up data-hold>Vol +</button>"
            + "<button class=b data-v=down data-hold>Vol −</button></div>"
            + "<div class=vol><button class=b data-k=play>⏯ Play / Pause</button><button class=b data-v=mute>🔇 Mute</button></div></div>"
            + "<div class=row><button class=b data-k=rew data-hold>⏪</button><button class=b data-k=ff data-hold>⏩</button>"
            + "<button class=b data-k=menu>☰ Menu</button></div>"
            + "<h2>Type on the TV</h2><div class=type><input id=text placeholder='Search words…' autocomplete=off>"
            + "<button class=b id=send>Send</button></div>"
            + "<div class=row style='margin-top:10px'><button class=b data-k=del data-hold>⌫ Delete</button>"
            + "<button class='b' id=paste style='display:none'>📋 Paste from phone</button></div>"
            + "<h2>Open a link on the TV</h2><div class=type><input id=link placeholder='YouTube or web link' autocomplete=off>"
            + "<button class=b id=open>Open</button></div>"
            + "<h2>Apps</h2><div class=apps id=apps></div>"
            + "<select id=more style='margin-top:10px'><option value=''>More apps…</option></select>"
            + "</section>"
            + "<section id=ctl class=hide>"
            + "<div class=bd><span>⏾ Sleep timer<small id=sleepv></small></span></div>"
            + "<div class=row><button class=b data-s=0>Off</button><button class=b data-s=15>15</button>"
            + "<button class=b data-s=30>30</button><button class=b data-s=60>60</button><button class=b data-s=90>90</button></div>"
            + "<div class=bd><span>🧹 Memory<small id=memv></small></span><button id=free>Free up</button></div>"
            + "<div class=bd><span>⚡ Internet speed<small id=speedv></small></span><button id=speed>Test</button></div>"
            + "<div class=bd><span>🔊 Sound output<small id=soundv></small></span></div>"
            + "<div class=row><button class=b id=saver>🌙 Screensaver</button><button class=b id=offnow>⏻ Turn off now</button></div>"
            + "</section>"
            + "<section id=cln class=hide>"
            + "<p style='color:#CAC4D0;line-height:1.5;margin:0 4px 14px'>Switch off ads, tracking, shop demos and apps you "
            + "don't use. Nothing is deleted: each one can come back.</p>"
            + "<div class=row><button class=b id=cleanall>Clean up</button><button class=b id=restoreall>Turn all back on</button></div>"
            + "<div id=clist><p style='color:#938F99;margin:0 4px'>Reading the TV…</p></div>"
            + "</section>"
            + "<section id=bdays class=hide>"
            + "<p style='color:#CAC4D0;line-height:1.5;margin:0 4px 16px'>Birthdays and anniversaries get a greeting with "
            + "confetti on the TV; reminders pop up at their time and show in the morning briefing.</p><div id=list></div>"
            + "<h2>Add</h2><select id=kind style='margin-bottom:10px'><option value=birthday>🎂 Birthday</option>"
            + "<option value=anniversary>💍 Anniversary</option><option value=festival>🪔 Festival or special day</option>"
            + "<option value=reminder>🔔 Reminder</option></select>"
            + "<input id=name maxlength=40 placeholder='Name' style='margin-bottom:10px'>"
            + "<input id=date type=date style='margin-bottom:10px'>"
            + "<div id=rem class=hide><select id=repeat style='margin-bottom:10px'><option value=once>Once</option>"
            + "<option value=monthly>Every month</option><option value=yearly>Every year</option></select>"
            + "<input id=time type=time value='09:00' style='margin-bottom:10px'></div>"
            + "<button class=b id=add style='width:100%'>Add</button>"
            + "<h2>Google Calendar (morning briefing)</h2><p id=calstate style='color:#CAC4D0;margin:0 4px 10px;line-height:1.5'></p>"
            + "<input id=cal placeholder='Secret address in iCal format' autocomplete=off style='margin-bottom:10px'>"
            + "<div class=row><button class=b id=calsave>Save</button><button class=b id=caldel>Remove</button></div>"
            + "<p style='color:#938F99;font-size:14px;line-height:1.5;margin:0 4px'>On a computer: Google Calendar → Settings "
            + "→ your calendar → Integrate calendar → Secret address in iCal format. It stays on the TV.</p>"
            + "</section><div id=msg></div></main><script>"
            + "const K='" + key + "',$=s=>document.getElementById(s),msg=t=>{const m=$('msg');m.textContent=t;m.className='on';"
            + "clearTimeout(msg.t);msg.t=setTimeout(()=>m.className='',2200)};"
            + "const post=(p,o)=>fetch('/remote/'+p+'?k='+K,{method:'POST',headers:{'Content-Type':'application/json'},"
            + "body:JSON.stringify(o)}).then(r=>{if(!r.ok)throw 0;return r}).catch(()=>{msg('The TV didn\\'t answer. Same Wi-Fi?');throw 0});"
            + "const act=b=>b.dataset.k?post('key',{k:b.dataset.k}):post('volume',{d:b.dataset.v});"
            + "document.querySelectorAll('[data-k],[data-v]').forEach(b=>{let t1,t2;"
            + "const stop=()=>{clearTimeout(t1);clearInterval(t2)};"
            + "b.addEventListener('pointerdown',e=>{e.preventDefault();navigator.vibrate&&navigator.vibrate(12);act(b).catch(()=>{});"
            + "if(b.hasAttribute('data-hold'))t1=setTimeout(()=>t2=setInterval(()=>act(b).catch(()=>{}),160),450)});"
            + "['pointerup','pointerleave','pointercancel'].forEach(ev=>b.addEventListener(ev,stop))});"
            + "const send=()=>{const t=$('text').value;if(!t)return;post('text',{t}).then(()=>{$('text').value='';msg('Typed on the TV')}).catch(()=>{})};"
            + "$('send').onclick=send;$('text').onkeydown=e=>{if(e.key==='Enter')send()};"
            + "fetch('/remote/apps?k='+K).then(r=>r.json()).then(a=>{a.forEach(x=>{if(x.fav){const b=document.createElement('button');"
            + "b.className='b';b.textContent=x.n;b.onclick=()=>post('app',{p:x.p}).then(()=>msg('Opening '+x.n)).catch(()=>{});"
            + "$('apps').appendChild(b)}else{const o=document.createElement('option');o.value=x.p;o.textContent=x.n;$('more').appendChild(o)}})});"
            + "$('more').onchange=e=>{const p=e.target.value;if(p)post('app',{p}).then(()=>msg('Opening…')).catch(()=>{});e.target.value=''};"
            + "const M=['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'],"
            + "I={birthday:'🎂',anniversary:'💍',festival:'🪔',reminder:'🔔'},"
            + "hm=n=>{const h=Math.floor(n/60),m=('0'+n%60).slice(-2);return (h%12||12)+':'+m+(h<12?' AM':' PM')},"
            + "when=x=>x.repeat==='monthly'?'Every month, day '+x.d:x.repeat==='once'?x.d+' '+M[x.m-1]+' '+x.y:x.d+' '+M[x.m-1];"
            + "const load=()=>fetch('/remote/days?k='+K).then(r=>r.json()).then(a=>{const l=$('list');l.innerHTML='';"
            + "if(!a.length)l.innerHTML='<p style=\"color:#938F99;margin:0 4px\">Nothing added yet.</p>';"
            + "a.forEach((x,i)=>{const d=document.createElement('div');d.className='bd';const s=document.createElement('span');"
            + "s.textContent=I[x.kind]+' '+x.name;const sm=document.createElement('small');"
            + "sm.textContent=when(x)+(x.kind==='reminder'?' · '+hm(x.min):'');s.appendChild(sm);"
            + "const b=document.createElement('button');b.textContent='Remove';b.onclick=()=>{if(confirm('Remove '+x.name+'?'))"
            + "post('day-remove',{i}).then(load).catch(()=>{})};d.appendChild(s);d.appendChild(b);l.appendChild(d)})});"
            + "const ph={birthday:'Name',anniversary:'Names (e.g. Mom & Dad)',festival:'Festival or day (e.g. Ugadi)',reminder:'What (e.g. Pay electricity bill)'};"
            + "$('kind').onchange=()=>{const k=$('kind').value;$('name').placeholder=ph[k];$('rem').className=k==='reminder'?'':'hide'};"
            + "$('add').onclick=()=>{const name=$('name').value.trim(),date=$('date').value;"
            + "if(!name||!date){msg('Enter a name and a date');return}"
            + "post('day-add',{kind:$('kind').value,name,date,repeat:$('repeat').value,time:$('time').value})"
            + ".then(()=>{$('name').value='';$('date').value='';msg('Added');load()}).catch(()=>{})};"
            + "const cal=()=>fetch('/remote/calendar?k='+K).then(r=>r.json()).then(o=>$('calstate').textContent="
            + "o.set?'✓ Connected: today\\'s events show in the morning briefing.':'Not connected.');"
            + "$('calsave').onclick=()=>{const url=$('cal').value.trim();if(!url){msg('Paste the address first');return}"
            + "post('calendar',{url}).then(()=>{$('cal').value='';msg('Calendar saved');cal()}).catch(()=>msg('That doesn\\'t look like the iCal address'))};"
            + "$('caldel').onclick=()=>post('calendar',{url:''}).then(()=>{msg('Calendar removed');cal()}).catch(()=>{});"
            + "if(window.Nook){$('paste').style.display='';$('paste').onclick=()=>{const t=Nook.clipboard();"
            + "if(!t){msg('The phone clipboard is empty');return}post('text',{t}).then(()=>msg('Pasted on the TV')).catch(()=>{})}}"
            + "$('open').onclick=()=>{const url=$('link').value.trim();if(!/^https?:\\/\\//.test(url)){msg('Paste a link that starts with http');return}"
            + "post('open',{url}).then(()=>{$('link').value='';msg('Opening on the TV')}).catch(()=>{})};"
            + "const J=(p,o)=>fetch('/remote/'+p+'?k='+K,o?{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(o)}:{})"
            + ".then(r=>r.json());"
            + "const ctl=()=>J('control').then(o=>{$('sleepv').textContent=o.sleep?'Off in '+o.sleep+' min':'Off';"
            + "$('memv').textContent=o.memory;$('soundv').textContent=o.sound}).catch(()=>{});"
            + "document.querySelectorAll('[data-s]').forEach(b=>b.onclick=()=>J('control',{do:'sleep',min:+b.dataset.s}).then(o=>{msg(o.text);ctl()}));"
            + "$('free').onclick=()=>{$('memv').textContent='Freeing…';J('control',{do:'free'}).then(o=>{msg(o.text);setTimeout(ctl,1500)})};"
            + "$('speed').onclick=()=>{$('speedv').textContent='Testing…';J('control',{do:'speed'}).then(o=>$('speedv').textContent=o.text)};"
            + "$('saver').onclick=()=>J('control',{do:'saver'}).then(()=>msg('Screensaver on the TV'));"
            + "$('offnow').onclick=()=>{if(confirm('Turn the TV off now?'))J('control',{do:'off'}).then(()=>msg('Turning off'))};"
            + "const G={ads:'Ads and tracking',demo:'Shop demo mode',apps:'Preloaded apps',voice:'Optional'};"
            + "const cln=()=>J('cleanup').then(a=>{const l=$('clist');l.innerHTML='';let g='';"
            + "if(!a.length)l.innerHTML='<p style=\"color:#938F99;margin:0 4px\">Nothing to clean up on this TV.</p>';"
            + "a.forEach(x=>{if(x.group!==g){g=x.group;const h=document.createElement('h2');h.textContent=G[g]||g;l.appendChild(h)}"
            + "const d=document.createElement('div');d.className='bd';const s=document.createElement('span');s.textContent=x.name+(x.rec?'':' · your choice');"
            + "const sm=document.createElement('small');sm.textContent=x.what;s.appendChild(sm);const b=document.createElement('button');"
            + "b.textContent=x.off?'Turn on':'Switch off';b.style.background=x.off?'#4A4458':'#633B48';"
            + "b.onclick=()=>{b.textContent='…';J('cleanup',{pkg:x.pkg,on:x.off}).then(o=>{msg(o.text);cln()})};"
            + "d.appendChild(s);d.appendChild(b);l.appendChild(d)})}).catch(()=>{});"
            + "$('cleanall').onclick=()=>{$('cleanall').textContent='Cleaning…';J('cleanup',{all:'clean'}).then(o=>{msg(o.text);"
            + "$('cleanall').textContent='Clean up';cln()})};"
            + "$('restoreall').onclick=()=>{if(confirm('Turn back on everything Cleanup switched off?'))J('cleanup',{all:'restore'})"
            + ".then(o=>{msg(o.text);cln()})};"
            + "const T=['t0','t1','t3','t4','t2'],S=['home','remote','ctl','cln','bdays'];"
            + "const tab=n=>{T.forEach((t,i)=>$(t).className=i===n?'on':'');S.forEach((t,i)=>$(t).className=i===n?'':'hide');"
            + "$('bg').className=n===0&&scrollY<120?'':'soft';if(n===0)home();if(n===2)ctl();if(n===3)cln();if(n===4){load();cal()};scrollTo(0,0)};"
            + "T.forEach((t,i)=>$(t).onclick=()=>tab(i));"
            + "addEventListener('scroll',()=>{if(!$('home').className)$('bg').className=scrollY>120?'soft':''});"
            + "const H=s=>s.replace(/[&<>\"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;'})[c]);"
            + "const zoom=(el,src,go)=>{const r=el.getBoundingClientRect(),z=$('zoom');z.style.transition='none';"
            + "Object.assign(z.style,{left:r.left+'px',top:r.top+'px',width:r.width+'px',height:r.height+'px',opacity:1,borderRadius:'16px',"
            + "backgroundImage:src?'url('+src+')':'none'});z.offsetWidth;z.style.transition='';"
            + "Object.assign(z.style,{left:'0px',top:'0px',width:innerWidth+'px',height:innerHeight+'px',borderRadius:'0px'});"
            + "go();setTimeout(()=>{z.style.opacity=0},700)};"
            + "let wpName='';const tick=()=>{const d=new Date();let h=d.getHours(),m=('0'+d.getMinutes()).slice(-2),ap=h<12?'AM':'PM';h=h%12||12;"
            + "$('clock').innerHTML=('0'+h).slice(-2)+'<br>'+m+'<sup>'+ap+'</sup>';"
            + "$('date').textContent=d.toLocaleDateString(undefined,{weekday:'long',day:'numeric',month:'long'})};tick();setInterval(tick,15000);"
            + "const at=t=>{if(!t)return'';const d=new Date(t),n=new Date();let s=d.toLocaleTimeString([], {hour:'numeric',minute:'2-digit'});"
            + "return d.toDateString()===n.toDateString()?s:d.toLocaleDateString(undefined,{weekday:'short'})+' '+s};"
            + "const home=()=>J('home').then(o=>{$('greet').textContent=o.greeting||'';$('rem').textContent=o.reminder?'Reminder: '+o.reminder:'';"
            + "if(o.weather){$('wx').style.display='';$('wx').textContent=o.weather+'   '+(o.highLow||'')}"
            + "if(o.wallpaper&&o.wallpaper!==wpName){wpName=o.wallpaper;$('bg').style.backgroundImage='url(/remote/wallpaper?k='+K+'&v='+encodeURIComponent(wpName)+')'}"
            + "const sh=$('shelf');sh.innerHTML='';(o.shelf||[]).forEach(a=>{const t=document.createElement('div');t.className='tile';"
            + "const src='/remote/icon?k='+K+'&p='+encodeURIComponent(a.p);t.innerHTML='<img src=\"'+src+'\"><span>'+H(a.n)+'</span>';"
            + "t.onclick=()=>{navigator.vibrate&&navigator.vibrate(15);zoom(t.firstChild,src,()=>post('app',{p:a.p}).then(()=>msg('Opening '+a.n+' on the TV')).catch(()=>{}))};sh.appendChild(t)});"
            + "const cw=$('cw');cw.innerHTML='';$('cwrap').className=(o.row||[]).length?'':'hide';(o.row||[]).forEach(e=>{const c=document.createElement('div');"
            + "c.className='card';const src='/remote/art?k='+K+'&i='+e.i;c.innerHTML='<div class=pic style=\"background-image:url('+src+')\">'"
            + "+(e.progress>0?'<i><b style=\"width:'+Math.round(e.progress*100)+'%\"></b></i>':'')+'</div><div class=t>'+H(e.title)+'</div><div class=a>'+H(e.app)+'</div>';"
            + "c.onclick=()=>zoom(c.firstChild,src,()=>post('watch',{i:e.i}).then(()=>msg('Opening on the TV')).catch(()=>{}));cw.appendChild(c)});"
            + "const ck=$('ck');ck.innerHTML='';$('ckwrap').className=(o.cricket||[]).length?'':'hide';(o.cricket||[]).forEach(m=>{const c=document.createElement('div');"
            + "c.className='match g';const foot=m.live?'<span class=live>LIVE</span>'+H(m.score||m.status):m.ended?H(m.status):H((m.type?m.type+' · ':'')+'starts '+at(m.at));"
            + "c.innerHTML='<div><img src=\"'+H(m.f1||'')+'\">'+H(m.t1)+'</div><div><img src=\"'+H(m.f2||'')+'\">'+H(m.t2)+'</div><small>'+foot+'</small>';"
            + "c.onclick=()=>msg(m.name);ck.appendChild(c)})}).catch(()=>{});"
            + "tab(0);setInterval(()=>{if(!$('home').className)home()},60000);"
            + "window.nookVolume=d=>post('volume',{d}).catch(()=>{});"
            + "</script></body></html>";
    }
}
