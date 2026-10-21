package com.gathin.tvhome;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;

/** Cached F1 sessions, with a bundled calendar available before the first network update. */
final class RaceCalendarStore {
    private static final Object LOCK = new Object();
    private static AtomicFile file(Context context) { return new AtomicFile(new File(context.getFilesDir(), "race-calendar.json")); }

    static JSONObject load(Context context) {
        synchronized (LOCK) { return loadLocked(context); }
    }

    private static JSONObject loadLocked(Context context) {
        try {
            JSONObject saved = new JSONObject(new String(file(context).readFully(), StandardCharsets.UTF_8));
            if (saved.optInt("season") == LocalDate.now().getYear() && saved.optJSONArray("races") != null) return saved;
        } catch (Exception ignored) { }
        try (InputStream in = context.getAssets().open("race-calendar.json")) {
            byte[] data = new byte[128 * 1024]; int count = 0, n;
            while (count < data.length && (n = in.read(data, count, data.length - count)) > 0) count += n;
            JSONObject bundled = new JSONObject(new String(data, 0, count, StandardCharsets.UTF_8));
            if (bundled.optInt("season") == LocalDate.now().getYear()) return bundled;
        } catch (Exception ignored) { }
        return new JSONObject();
    }

    static JSONObject refresh(Context context) throws Exception {
        int season = LocalDate.now().getYear();
        JSONObject response = FeatureNetwork.get("https://api.jolpi.ca/ergast/f1/" + season + ".json?limit=100");
        JSONObject result = convert(response, season);
        if (result.getJSONArray("races").length() == 0) throw new java.io.IOException("Calendar is not available yet");
        synchronized (LOCK) {
            AtomicFile file = file(context); FileOutputStream out = null;
            try {
                out = file.startWrite(); out.write(result.toString().getBytes(StandardCharsets.UTF_8));
                file.finishWrite(out); out = null;
            } finally { if (out != null) file.failWrite(out); }
        }
        return result;
    }

    static void refreshIfNeeded(Context context, Runnable updated) {
        if (!FeaturePreferences.prefs(context).getBoolean("races_enabled", false)) return;
        long now = System.currentTimeMillis();
        long lastAttempt = FeaturePreferences.prefs(context).getLong("calendar_last_attempt", 0);
        if (now - load(context).optLong("updated_at", 0) < 7L * 24 * 60 * 60 * 1000
                || now - lastAttempt < 6L * 60 * 60 * 1000) return;
        FeaturePreferences.prefs(context).edit().putLong("calendar_last_attempt", now).apply();
        Context app = context.getApplicationContext();
        FeatureNetwork.WORKER.execute(() -> {
            try { refresh(app); new android.os.Handler(android.os.Looper.getMainLooper()).post(updated); }
            catch (Exception ignored) { /* Keep the last usable calendar when offline. */ }
        });
    }

    static JSONObject convert(JSONObject response, int season) throws Exception {
        JSONObject table = response.getJSONObject("MRData").getJSONObject("RaceTable");
        if (table.getInt("season") != season) throw new IllegalArgumentException("Wrong calendar season");
        JSONArray input = table.getJSONArray("Races"), races = new JSONArray();
        String[] keys = {"FirstPractice", "SecondPractice", "ThirdPractice", "SprintQualifying", "Sprint", "Qualifying"};
        String[] titles = {"FP1", "FP2", "FP3", "SPRINT QUALI", "SPRINT", "QUALIFYING"};
        for (int i = 0; i < Math.min(100, input.length()); i++) {
            JSONObject race = input.optJSONObject(i); if (race == null) continue;
            JSONArray sessions = new JSONArray();
            for (int j = 0; j < keys.length; j++) {
                JSONObject session = race.optJSONObject(keys[j]);
                if (session == null && keys[j].equals("SprintQualifying")) session = race.optJSONObject("SprintShootout");
                addSession(sessions, session, titles[j]);
            }
            int count = sessions.length(); addSession(sessions, race, "RACE");
            if (sessions.length() == count) continue;
            JSONObject circuit = race.optJSONObject("Circuit");
            JSONObject location = circuit == null ? null : circuit.optJSONObject("Location");
            int round = race.optInt("round", 0); if (round <= 0 || round > 99) continue;
            races.put(new JSONObject().put("round", round).put("name", race.optString("raceName", "Grand Prix"))
                    .put("place", location == null ? "" : location.optString("locality", "")).put("sessions", sessions));
        }
        return new JSONObject().put("season", season).put("updated_at", System.currentTimeMillis()).put("races", races);
    }

    private static void addSession(JSONArray sessions, JSONObject session, String title) {
        if (session == null) return;
        try {
            String start = session.getString("date") + "T" + session.getString("time");
            Instant.parse(start);
            sessions.put(new JSONObject().put("name", title).put("start", start));
        } catch (Exception ignored) { }
    }
}
