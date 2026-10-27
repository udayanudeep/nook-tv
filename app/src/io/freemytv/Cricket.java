package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The matches for the home screen's Cricket row (live, starting soon, just finished: India's first, with the teams'
 * flags, downloaded once and kept) and the line under the top buttons (your team's live score, or its next match), and
 * the live score of your team's match (config.properties: cricket_teams, India by default), from CricketData.org's
 * currentMatches with your free key (cricket_key: 100 lookups a day). Looked up only while the home screen is on:
 * every LIVE_EVERY during a match of yours, OFF_EVERY otherwise, so a day never uses up the free lookups. The last
 * answer is kept (the home screen shows it at once after a restart).
 */
final class Cricket {
    private static final String API = "https://api.cricapi.com/v1/currentMatches?offset=0&apikey=";
    private static final long LIVE_EVERY = 15 * 60_000L, OFF_EVERY = 30 * 60_000L, STALE = 45 * 60_000L;

    private static Cricket instance;

    static synchronized Cricket get(Context c) {
        if (instance == null) instance = new Cricket(c.getApplicationContext());
        return instance;
    }

    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile boolean fetching;
    /** "IND 214/3 (38.2)", and the match's status ("India need 76 runs in 70 balls"); empty: no live match of yours. */
    volatile String score = "", status = "";
    /** Bumped when the line changes: the home screen redraws. */
    volatile int version;
    /** The Cricket row's matches, in the order shown (an unmodifiable list, replaced whole). */
    volatile List<Match> matches = java.util.Collections.emptyList();
    private final java.io.File flagDir;
    private final java.util.Map<String, android.graphics.Bitmap> flags = new java.util.HashMap<>();
    private final java.util.Set<String> flagPending = new java.util.HashSet<>();

    /** One match of the row. */
    static final class Match {
        String name = "", type = "", status = "", score = "", id = "";
        String[] teams = {"", ""}, shorts = {"", ""}, flagUrls = {"", ""};
        long startsAt;            // epoch ms (0 = unknown)
        boolean live, ended, ours;
    }

    private Cricket(Context c) {
        cache = c.getSharedPreferences("cricket", Context.MODE_PRIVATE);
        flagDir = new java.io.File(c.getFilesDir(), "flags");
        if (System.currentTimeMillis() - cache.getLong("at", 0) < STALE) {
            score = cache.getString("score", "");
            status = cache.getString("status", "");
        }
        try {   // the last list, at once after a restart (old matches drop out in matchesFrom)
            String saved = cache.getString("data", null);
            if (saved != null) matches = matchesFrom(new JSONArray(saved));
        } catch (Exception ignored) {
        }
    }

    static boolean hasKey() {
        return !Config.CRICKET_KEY.trim().isEmpty();
    }

    void refreshIfStale() {
        if (!hasKey() || fetching) return;
        long now = System.currentTimeMillis(), at = cache.getLong("at", 0);
        long every = score.isEmpty() ? OFF_EVERY : LIVE_EVERY;
        if (!cache.contains("data")) every = 0;   // the row's first list: now, not at the next half hour
        if (now - at < every && now >= at) return;
        if (now < cache.getLong("pausedUntil", 0)) return;   // the day's free lookups are used up
        fetching = true;
        io.execute(() -> {
            try {
                fetch();
            } catch (Exception e) {
                AppLog.w("cricket: " + e.getMessage());
            } finally {
                cache.edit().putLong("at", System.currentTimeMillis()).apply();
                fetching = false;
            }
        });
    }

    private void fetch() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API + Config.CRICKET_KEY.trim()).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        JSONObject o;
        try (InputStream in = c.getInputStream()) {
            o = new JSONObject(read(in));
        } finally {
            c.disconnect();
        }
        JSONObject info = o.optJSONObject("info");
        if (info != null && info.optInt("hitsLimit", 0) > 0 && info.optInt("hitsToday", 0) >= info.optInt("hitsLimit")) {
            // Used up: wait for the next day (the quota resets at midnight UTC).
            long day = 86_400_000L, next = (System.currentTimeMillis() / day + 1) * day;
            cache.edit().putLong("pausedUntil", next).apply();
        }
        if (!"success".equals(o.optString("status"))) throw new Exception(o.optString("reason", "no answer"));
        JSONArray data = o.optJSONArray("data");
        if (data != null) {
            matches = matchesFrom(data);
            cache.edit().putString("data", data.toString()).apply();
            version++;
        }
        JSONObject match = pick(data);
        String s = "", st = "";
        if (match != null) {
            s = scoreLine(match);
            st = match.optString("status", "");
        }
        if (!s.equals(score) || !st.equals(status)) {
            score = s;
            status = st;
            version++;
        }
        cache.edit().putString("score", s).putString("status", st).apply();
    }

    /** The first live match (started, not ended) with one of your teams, in the order you listed them. */
    private static JSONObject pick(JSONArray data) {
        if (data == null) return null;
        List<String> teams = new ArrayList<>();
        for (String t : Config.CRICKET_TEAMS.split(",")) if (!t.trim().isEmpty()) teams.add(t.trim().toLowerCase(Locale.ROOT));
        for (String team : teams) {
            for (int i = 0; i < data.length(); i++) {
                JSONObject m = data.optJSONObject(i);
                if (m == null || !m.optBoolean("matchStarted") || m.optBoolean("matchEnded")) continue;
                JSONArray names = m.optJSONArray("teams");
                for (int j = 0; names != null && j < names.length(); j++) {
                    if (names.optString(j).toLowerCase(Locale.ROOT).equals(team)) return m;
                }
            }
        }
        return null;
    }

    /** The innings being played: "IND 214/3 (38.2)" (the team's short name when the answer has it). */
    private static String scoreLine(JSONObject m) {
        JSONArray scores = m.optJSONArray("score");
        if (scores == null || scores.length() == 0) return "";
        JSONObject last = scores.optJSONObject(scores.length() - 1);
        if (last == null) return "";
        String inning = last.optString("inning", "");   // "India Inning 1"
        int cut = inning.toLowerCase(Locale.ROOT).lastIndexOf(" inning");
        String team = cut > 0 ? inning.substring(0, cut) : inning;
        JSONArray info = m.optJSONArray("teamInfo");
        for (int i = 0; info != null && i < info.length(); i++) {
            JSONObject t = info.optJSONObject(i);
            if (t != null && t.optString("name").equalsIgnoreCase(team) && !t.optString("shortname").isEmpty()) {
                team = t.optString("shortname");
            }
        }
        String runs = last.optInt("r") + "/" + last.optInt("w");
        double overs = last.optDouble("o", -1);
        String ov = overs < 0 ? "" : " (" + (overs == Math.floor(overs) ? String.valueOf((int) overs) : String.valueOf(overs)) + ")";
        return (team + " " + runs + ov).trim();
    }

    // MARK: - The row's matches

    private static final long SHOW_ENDED_FOR = 12 * 3_600_000L, SHOW_AHEAD = 3 * 86_400_000L;
    private static final int MAX_MATCHES = 12;

    private static List<String> yourTeams() {
        List<String> teams = new ArrayList<>();
        for (String t : Config.CRICKET_TEAMS.split(",")) if (!t.trim().isEmpty()) teams.add(t.trim().toLowerCase(Locale.ROOT));
        return teams;
    }

    /** Live ones first, then those starting soonest, then the ones just finished; your team's before the rest. */
    static List<Match> matchesFrom(JSONArray data) {
        List<String> yours = yourTeams();
        List<Match> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        java.text.SimpleDateFormat gmt = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
        gmt.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
        for (int i = 0; i < data.length(); i++) {
            JSONObject m = data.optJSONObject(i);
            if (m == null) continue;
            Match x = new Match();
            x.id = m.optString("id");
            x.name = m.optString("name");
            x.type = m.optString("matchType").toUpperCase(Locale.ROOT);
            x.status = m.optString("status");
            x.ended = m.optBoolean("matchEnded");
            x.live = m.optBoolean("matchStarted") && !x.ended;
            try {
                java.util.Date d = gmt.parse(m.optString("dateTimeGMT"));
                if (d != null) x.startsAt = d.getTime();
            } catch (Exception ignored) {
            }
            if (x.ended && x.startsAt > 0 && now - x.startsAt > SHOW_ENDED_FOR + 10 * 3_600_000L) continue;   // long over
            if (!x.live && !x.ended && x.startsAt > 0 && x.startsAt - now > SHOW_AHEAD) continue;            // far ahead
            if (!x.live && !x.ended && x.startsAt > 0 && now - x.startsAt > 86_400_000L) continue;           // stale "upcoming"
            JSONArray names = m.optJSONArray("teams");
            for (int j = 0; names != null && j < 2 && j < names.length(); j++) {
                x.teams[j] = names.optString(j);
                x.shorts[j] = shortName(x.teams[j]);
                x.ours |= isYours(yours, x.teams[j]);
            }
            JSONArray info = m.optJSONArray("teamInfo");
            for (int j = 0; info != null && j < info.length(); j++) {
                JSONObject t = info.optJSONObject(j);
                if (t == null) continue;
                for (int side = 0; side < 2; side++) {
                    if (t.optString("name").equalsIgnoreCase(x.teams[side])) {
                        if (!t.optString("shortname").isEmpty()) x.shorts[side] = t.optString("shortname");
                        x.flagUrls[side] = t.optString("img");
                    }
                }
            }
            if (x.teams[0].isEmpty()) continue;
            x.score = x.live || x.ended ? scoreLine(m) : "";
            out.add(x);
        }
        java.util.Collections.sort(out, (a, b) -> {
            int ra = rank(a), rb = rank(b);
            if (ra != rb) return ra - rb;
            if (a.ours != b.ours) return a.ours ? -1 : 1;
            return a.ended ? Long.compare(b.startsAt, a.startsAt) : Long.compare(a.startsAt, b.startsAt);
        });
        return java.util.Collections.unmodifiableList(out.size() > MAX_MATCHES ? new ArrayList<>(out.subList(0, MAX_MATCHES)) : out);
    }

    /** One of your teams, or its women's, A or under-19 side ("India Women" is India's too). */
    private static boolean isYours(List<String> yours, String team) {
        String t = team.toLowerCase(Locale.ROOT).trim();
        for (String y : yours) if (t.equals(y) || t.startsWith(y + " ")) return true;
        return false;
    }

    private static int rank(Match m) {
        return m.live ? 0 : m.ended ? 2 : 1;
    }

    /** "India Women" → "IND-W" when the answer has no short name. */
    private static String shortName(String team) {
        String t = team.trim();
        boolean women = t.toLowerCase(Locale.ROOT).endsWith(" women");
        if (women) t = t.substring(0, t.length() - 6).trim();
        String letters = t.replaceAll("[^A-Za-z]", "");
        String code = (letters.length() >= 3 ? letters.substring(0, 3) : letters).toUpperCase(Locale.ROOT);
        return women ? code + "-W" : code;
    }

    /** Your team's match for the line under the top buttons: live first, else its next one; null when none. */
    Match yourMatch() {
        Match next = null;
        for (Match m : matches) {
            if (!m.ours || m.ended) continue;
            if (m.live) return m;
            if (next == null || (m.startsAt > 0 && m.startsAt < next.startsAt)) next = m;
        }
        return next;
    }

    /** A team's flag (small), or null while it downloads (then version goes up and the home screen redraws). */
    android.graphics.Bitmap flag(String url) {
        if (url == null || url.isEmpty()) return null;
        synchronized (flags) {
            if (flags.containsKey(url)) return flags.get(url);
            if (!flagPending.add(url)) return null;
        }
        io.execute(() -> {
            android.graphics.Bitmap b = null;
            java.io.File f = new java.io.File(flagDir, Integer.toHexString(url.hashCode()) + ".img");
            try {
                if (!f.exists()) {
                    flagDir.mkdirs();
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(6000);
                    c.setReadTimeout(8000);
                    try (InputStream in = c.getInputStream(); java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                        byte[] buf = new byte[8192];
                        int total = 0;
                        for (int n; (n = in.read(buf)) > 0 && total < 256 * 1024; total += n) out.write(buf, 0, n);
                    } finally {
                        c.disconnect();
                    }
                }
                b = android.graphics.BitmapFactory.decodeFile(f.getPath());
            } catch (Exception e) {
                AppLog.w("cricket flag: " + e.getMessage());
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            synchronized (flags) {
                flags.put(url, b);   // a failed one stays null (not asked again this run)
            }
            version++;
        });
        return null;
    }

    private static String read(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }
}
