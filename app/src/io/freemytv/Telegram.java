package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wallpapers from your Telegram channel, at full quality, with nothing missed however long the TV was off.
 *
 * A bot you made (config.properties: telegram_bot_token) is an admin of the channel. New posts arrive as updates
 * (getUpdates, every EVERY while the home screen is on). Telegram keeps undelivered updates for a day only, so the TV
 * also walks the channel by post number: the bot forwards post N to your private chat with it (you sent it /start
 * once; only an admin of the channel is accepted), the photo is read from that copy, and the copy is deleted at once.
 * That also brings in the photos posted before the bot was added, SCAN_PER_SYNC at a time.
 *
 * Photos go into the photos folder (Photos) as "yyyy-MM-dd_tg<file_unique_id>.jpg", the largest size Telegram has; a
 * caption with #morning, #afternoon, #evening, #night or #favourites (#fav) puts the photo in that set. The token stays
 * in the app; it never goes to the log.
 */
final class Telegram {
    private Telegram() {}

    private static final long EVERY = 10 * 60_000L;
    /** The Bot API downloads files up to 20 MB. */
    private static final long MAX_BYTES = 20L * 1024 * 1024;
    /** Posts looked at by number per visit, and how many missing numbers past the newest known mean "the end". */
    private static final int SCAN_PER_SYNC = 60, END_AFTER = 10;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static volatile boolean running;

    static boolean configured() {
        return !Config.TELEGRAM_TOKEN.trim().isEmpty();
    }

    /** From the home screen's tick: a look at the channel when the last one is old enough. */
    static void syncIfDue(Context c) {
        if (!configured() || running) return;
        final Context app = c.getApplicationContext();
        SharedPreferences sp = app.getSharedPreferences("telegram", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis(), at = sp.getLong("at", 0);
        if (now - at < EVERY && now >= at) return;
        running = true;
        IO.execute(() -> {
            try {
                int got = sync(app, sp);
                if (got > 0) AppLog.w("telegram: " + got + " new photo" + (got == 1 ? "" : "s"));
            } catch (Exception e) {
                AppLog.w("telegram: " + safe(e.getMessage()));
            } finally {
                sp.edit().putLong("at", System.currentTimeMillis()).apply();
                running = false;
            }
        });
    }

    private static int sync(Context c, SharedPreferences sp) throws Exception {
        File dir = c.getExternalFilesDir("photos");
        if (dir == null) return 0;
        int got = readUpdates(c, dir, sp);
        connectOwner(sp);
        got += scan(c, dir, sp);
        return got;
    }

    // MARK: - New posts (updates)

    /** New channel posts (their photos saved, their numbers remembered) and /start from you in the bot's chat. */
    private static int readUpdates(Context c, File dir, SharedPreferences sp) throws Exception {
        long offset = sp.getLong("offset", 0);
        String allowed = URLEncoder.encode("[\"channel_post\",\"message\"]", "UTF-8");
        JSONObject o = call("getUpdates", "timeout=0&allowed_updates=" + allowed + (offset > 0 ? "&offset=" + offset : ""));
        if (!o.optBoolean("ok")) throw new Exception(o.optString("description", "no answer"));
        JSONArray updates = o.optJSONArray("result");
        Set<String> seen = new HashSet<>(sp.getStringSet("seen", new HashSet<>()));
        int got = 0;
        for (int i = 0; updates != null && i < updates.length(); i++) {
            JSONObject u = updates.optJSONObject(i);
            if (u == null) continue;
            offset = Math.max(offset, u.optLong("update_id") + 1);
            JSONObject msg = u.optJSONObject("message");
            if (msg != null && msg.optString("text").trim().startsWith("/start")) {
                JSONObject from = msg.optJSONObject("from");
                if (from != null) sp.edit().putLong("candidate", from.optLong("id")).apply();   // checked in connectOwner
                continue;
            }
            JSONObject post = u.optJSONObject("channel_post");
            if (post == null || !fromChannel(post.optJSONObject("chat"))) continue;
            long channel = post.getJSONObject("chat").optLong("id");
            int number = post.optInt("message_id");
            sp.edit().putLong("channel", channel).putInt("newest", Math.max(sp.getInt("newest", 0), number)).apply();
            try {
                if (save(c, dir, post, post.optLong("date"))) got++;
            } catch (Exception e) {
                AppLog.w("telegram: a photo didn't download: " + safe(e.getMessage()));
            }
            seen.add(String.valueOf(number));   // the walk by number needn't forward this one again
        }
        // Only the numbers the walk hasn't reached yet are worth keeping.
        int scanned = sp.getInt("scanned", 0);
        Set<String> keep = new HashSet<>();
        for (String s : seen) if (Integer.parseInt(s) > scanned) keep.add(s);
        sp.edit().putLong("offset", offset).putStringSet("seen", keep).apply();
        return got;
    }

    /** Only the channel in telegram_channel (its @username or id); any channel when that's empty. */
    private static boolean fromChannel(JSONObject chat) {
        if (chat == null) return false;
        String want = Config.TELEGRAM_CHANNEL.trim();
        if (want.isEmpty()) return true;
        if (want.startsWith("@")) want = want.substring(1);
        return want.equalsIgnoreCase(chat.optString("username")) || want.equals(String.valueOf(chat.optLong("id")));
    }

    /**
     * The person who sent /start becomes the one the bot forwards posts to, if they are the channel's owner or one of
     * its admins (anyone can message a bot: no one else gets your channel's photos). They get a message saying so.
     */
    private static void connectOwner(SharedPreferences sp) throws Exception {
        long candidate = sp.getLong("candidate", 0), channel = sp.getLong("channel", 0);
        if (candidate == 0 || channel == 0 || candidate == sp.getLong("owner", 0)) return;
        JSONObject member = call("getChatMember", "chat_id=" + channel + "&user_id=" + candidate);
        String status = member.optJSONObject("result") == null ? "" : member.getJSONObject("result").optString("status");
        sp.edit().remove("candidate").apply();
        if (!status.equals("creator") && !status.equals("administrator")) {
            AppLog.w("telegram: /start from someone who isn't an admin of the channel: ignored");
            return;
        }
        sp.edit().putLong("owner", candidate).apply();
        call("sendMessage", "chat_id=" + candidate + "&text=" + URLEncoder.encode(Ui.BRAND + " is connected: the photos "
                + "in your channel go to the TV as wallpapers (older ones too, a few at a time).", "UTF-8"));
        AppLog.w("telegram: connected");
    }

    // MARK: - Every post, by number

    /**
     * The channel post by post, from the first: each one the updates didn't bring is forwarded to your chat with the
     * bot, read and deleted. Missing numbers (deleted posts) are skipped; END_AFTER missing past the newest known number
     * means there's nothing more yet (the next visit looks again from there).
     */
    private static int scan(Context c, File dir, SharedPreferences sp) throws Exception {
        long owner = sp.getLong("owner", 0), channel = sp.getLong("channel", 0);
        if (owner == 0 || channel == 0) return 0;
        Set<String> seen = sp.getStringSet("seen", new HashSet<>());
        int scanned = sp.getInt("scanned", 0), newest = sp.getInt("newest", 0), misses = 0, got = 0;
        for (int n = scanned + 1, budget = SCAN_PER_SYNC; budget > 0; n++, budget--) {
            if (seen.contains(String.valueOf(n))) {
                scanned = n;
                continue;
            }
            JSONObject r = call("forwardMessage", "chat_id=" + owner + "&from_chat_id=" + channel + "&message_id=" + n
                    + "&disable_notification=true");
            Thread.sleep(1100);   // Telegram allows a bot about one message a second in the same chat
            if (r.optBoolean("ok")) {
                JSONObject copy = r.getJSONObject("result");
                call("deleteMessage", "chat_id=" + owner + "&message_id=" + copy.optInt("message_id"));
                misses = 0;
                scanned = n;
                newest = Math.max(newest, n);
                try {
                    if (save(c, dir, copy, originalDate(copy))) got++;
                } catch (Exception e) {
                    AppLog.w("telegram: a photo didn't download: " + safe(e.getMessage()));
                }
            } else if (r.optInt("error_code") == 429) {
                break;   // too fast: Telegram says when to come back; the next visit does
            } else if (n <= newest) {
                scanned = n;   // a deleted post
            } else if (++misses >= END_AFTER) {
                break;   // past the end, for now
            }
        }
        sp.edit().putInt("scanned", scanned).putInt("newest", newest).apply();
        return got;
    }

    /** A forwarded copy's date is today's: the post's own date is in forward_origin (or forward_date before that). */
    private static long originalDate(JSONObject copy) {
        JSONObject origin = copy.optJSONObject("forward_origin");
        if (origin != null && origin.optLong("date") > 0) return origin.optLong("date");
        return copy.optLong("forward_date", copy.optLong("date"));
    }

    // MARK: - Saving

    /** The message's photo (the largest size) or image file, into its set's folder; false if it has none or it's here. */
    private static boolean save(Context c, File dir, JSONObject msg, long date) throws Exception {
        String fileId = null, unique = null;
        long size = 0;
        JSONArray sizes = msg.optJSONArray("photo");
        if (sizes != null && sizes.length() > 0) {
            JSONObject biggest = sizes.getJSONObject(sizes.length() - 1);
            fileId = biggest.optString("file_id");
            unique = biggest.optString("file_unique_id");
            size = biggest.optLong("file_size");
        }
        JSONObject doc = msg.optJSONObject("document");
        if (fileId == null && doc != null && doc.optString("mime_type").startsWith("image/")) {
            fileId = doc.optString("file_id");
            unique = doc.optString("file_unique_id");
            size = doc.optLong("file_size");
        }
        if (fileId == null || fileId.isEmpty()) return false;
        if (size > MAX_BYTES) {
            AppLog.w("telegram: a photo over 20 MB was skipped (Telegram's bots can't download it)");
            return false;
        }
        File target = new File(dir, set(msg.optString("caption")));
        if (!target.isDirectory() && !target.mkdirs()) return false;
        String day = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(date * 1000));
        String name = day + "_tg" + unique.replaceAll("[^A-Za-z0-9_-]", "");
        String suffix = "_tg" + unique.replaceAll("[^A-Za-z0-9_-]", "");
        for (File f : Photos.list(c)) if (f.getName().contains(suffix + ".")) return false;   // already here

        JSONObject file = call("getFile", "file_id=" + URLEncoder.encode(fileId, "UTF-8"));
        String path = file.optJSONObject("result") == null ? "" : file.getJSONObject("result").optString("file_path");
        if (path.isEmpty()) throw new Exception("no file path");
        File part = new File(target, "." + name + ".part");
        HttpURLConnection conn = (HttpURLConnection) new URL("https://api.telegram.org/file/bot"
                + Config.TELEGRAM_TOKEN.trim() + "/" + path).openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);
        try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        } finally {
            conn.disconnect();
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(part.getPath(), bounds);
        String ext = bounds.outMimeType == null ? null : bounds.outMimeType.equals("image/png") ? ".png"
                : bounds.outMimeType.equals("image/webp") ? ".webp" : bounds.outMimeType.equals("image/jpeg") ? ".jpg" : null;
        if (ext == null || bounds.outWidth <= 0) {   // a HEIC or something else the TV can't show
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            return false;
        }
        return part.renameTo(new File(target, name + ext));
    }

    /** "#morning" (#afternoon, #evening, #night, #favourites or #fav; #diwali, #sankranti, #christmas, #newyear) in the caption: that set's folder. */
    private static String set(String caption) {
        String text = caption == null ? "" : caption.toLowerCase(Locale.ROOT);
        if (text.contains("#fav")) return "favourites";
        for (String set : Photos.SETS) if (text.contains("#" + set)) return set;
        return "";
    }

    // MARK: - Bot API

    /** A Bot API call (GET with these query parameters); its JSON answer, errors included ("ok": false). */
    private static JSONObject call(String method, String query) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("https://api.telegram.org/bot" + Config.TELEGRAM_TOKEN.trim()
                + "/" + method + "?" + query).openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(20_000);
        try {
            InputStream in = conn.getResponseCode() >= 400 ? conn.getErrorStream() : conn.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            for (int n; in != null && (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            String body = b.toString("UTF-8");
            return body.isEmpty() ? new JSONObject().put("ok", false) : new JSONObject(body);
        } finally {
            conn.disconnect();
        }
    }

    /** An error message without the token in it (Telegram's URLs carry it). */
    private static String safe(String message) {
        if (message == null) return "unknown error";
        String token = Config.TELEGRAM_TOKEN.trim();
        return token.isEmpty() ? message : message.replace(token, "<token>");
    }
}
