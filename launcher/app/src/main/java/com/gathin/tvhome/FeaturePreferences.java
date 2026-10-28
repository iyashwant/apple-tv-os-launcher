package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** On-TV feature choices and app/remote customizations. Theme pushes never erase personal app or key choices. */
final class FeaturePreferences {
    static final String NAME = "launcher_features";
    static final String SCENES = "scenes";
    static final String SCENE_PREFIX = "scene:";
    static final String[] APPEARANCE = {"wallpaper_rotation_minutes", "wallpaper_order", "wallpaper_true_color",
            "wallpaper_dim", "dock_opacity", "clock_wallpaper", "clock_glow", "clock_wallpaper_dim", "open_on_start_network_wait", "sky_intensity", "home_idle_clock_minutes", "races_enabled", "continue_watching", "city", "latitude", "longitude"};

    static SharedPreferences prefs(Context context) { return context.getSharedPreferences(NAME, 0); }

    static JSONObject overlay(Context context, JSONObject theme) {
        try {
            JSONObject out = theme == null ? new JSONObject() : new JSONObject(theme.toString());
            Map<String, ?> saved = prefs(context).getAll();
            for (String key : APPEARANCE) if (saved.containsKey(key)) out.put(key, saved.get(key));
            for (String key : new String[]{"launch_actions", "app_labels", "app_banners"}) {
                JSONObject local = object(prefs(context).getString(key, "{}"));
                JSONArray entries = out.optJSONArray(key);
                JSONArray merged = new JSONArray();
                if (entries != null) for (int i = 0; i < entries.length(); i++) {
                    String entry = entries.optString(i, ""); int equals = entry.indexOf('=');
                    if (equals <= 0 || !local.has(entry.substring(0, equals).trim())) merged.put(entries.get(i));
                }
                Iterator<String> packages = local.keys();
                while (packages.hasNext()) {
                    String pkg = packages.next();
                    if (!local.getString(pkg).isEmpty()) merged.put(pkg + "=" + local.getString(pkg));
                }
                out.put(key, merged);
            }
            JSONArray keys = out.optJSONArray("app_keys");
            JSONArray merged = keys == null ? new JSONArray() : new JSONArray(keys.toString());
            JSONObject mappings = object(prefs(context).getString("remote_mappings", "{}"));
            Iterator<String> buttons = mappings.keys();
            while (buttons.hasNext()) {
                String button = buttons.next();
                merged.put(button + ":" + mappings.getString(button));
            }
            out.put("app_keys", merged);
            if (saved.containsKey("races_enabled"))
                out.put("race_calendar", prefs(context).getBoolean("races_enabled", false)
                        ? RaceCalendarStore.load(context).optJSONArray("races") : new JSONArray());
            return out;
        } catch (Exception ignored) { return theme; }
    }

    static JSONObject object(String json) {
        try { return new JSONObject(json); } catch (Exception ignored) { return new JSONObject(); }
    }

    static void putApp(Context context, String key, String pkg, String value) {
        try {
            JSONObject entries = object(prefs(context).getString(key, "{}"));
            if (value == null) entries.remove(pkg); else entries.put(pkg, value.trim());
            prefs(context).edit().putString(key, entries.toString()).apply();
        } catch (Exception ignored) { }
    }

    static void clearAppearance(Context context) {
        SharedPreferences.Editor edit = prefs(context).edit();
        for (String key : APPEARANCE) if (!key.equals("city") && !key.equals("latitude") && !key.equals("longitude")) edit.remove(key);
        edit.apply();
    }

    static boolean hasAppearanceChoices(Context context) {
        for (String key : APPEARANCE) if (!key.equals("city") && !key.equals("latitude") && !key.equals("longitude")
                && prefs(context).contains(key)) return true;
        return false;
    }

    /** Shell-only setup through ThemeProvider; reject unknown or incorrectly typed keys atomically. */
    static void importSettings(Context context, JSONObject values) throws Exception {
        SharedPreferences.Editor edit = prefs(context).edit();
        Iterator<String> keys = values.keys();
        while (keys.hasNext()) {
            String key = keys.next(); Object value = values.get(key);
            if (key.equals("wallpaper_choice")) {
                if (!(value instanceof String)) throw new IllegalArgumentException(key);
                continue;
            }
            if (!java.util.Arrays.asList(APPEARANCE).contains(key)) throw new IllegalArgumentException(key);
            if (key.equals("races_enabled") || key.equals("wallpaper_true_color") || key.equals("continue_watching") || key.equals("clock_wallpaper") || key.equals("clock_glow")) {
                if (!(value instanceof Boolean)) throw new IllegalArgumentException(key);
                edit.putBoolean(key, (Boolean) value);
            } else if (key.equals("wallpaper_rotation_minutes") || key.equals("home_idle_clock_minutes")
                    || key.equals("wallpaper_dim") || key.equals("sky_intensity") || key.equals("dock_opacity")
                    || key.equals("clock_wallpaper_dim") || key.equals("open_on_start_network_wait")) {
                int max = key.equals("wallpaper_dim") || key.equals("clock_wallpaper_dim") ? 90
                        : key.equals("open_on_start_network_wait") ? 600
                        : key.equals("sky_intensity") || key.equals("dock_opacity") ? 100 : 1440;
                if (key.equals("dock_opacity") && value instanceof Number && ((Number)value).doubleValue()<30)
                    throw new IllegalArgumentException(key);
                if (!(value instanceof Number) || ((Number) value).doubleValue() != ((Number) value).intValue()
                        || ((Number) value).intValue() < 0 || ((Number) value).intValue() > max)
                    throw new IllegalArgumentException(key);
                edit.putInt(key, ((Number) value).intValue());
            } else {
                if (!(value instanceof String) || ((String) value).length() > 160) throw new IllegalArgumentException(key);
                if (key.equals("wallpaper_order") && !WallpaperRotation.validOrder((String) value))
                    throw new IllegalArgumentException(key);
                if (key.equals("latitude") || key.equals("longitude")) {
                    double number = Double.parseDouble((String) value);
                    if (!Double.isFinite(number) || Math.abs(number) > (key.equals("latitude") ? 90 : 180))
                        throw new IllegalArgumentException(key);
                }
                edit.putString(key, (String) value);
            }
        }
        if (!edit.commit()) throw new java.io.IOException("Settings could not be saved");
        if (values.has("wallpaper_choice")) context.getSharedPreferences(Config.SETTINGS_NAME, 0).edit()
                .putString(Config.WALLPAPER_CHOICE_KEY, values.getString("wallpaper_choice")).commit();
    }
}
