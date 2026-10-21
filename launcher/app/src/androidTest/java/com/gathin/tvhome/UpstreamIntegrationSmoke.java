package com.gathin.tvhome;

import android.app.Activity;
import android.content.ComponentCallbacks2;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.app.AlertDialog;

import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Emulator-only integration checks for upstream features on the fork's Home and settings. */
public final class UpstreamIntegrationSmoke extends Instrumentation {
    private MainActivity home;
    private Activity settings;
    private int checks;

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_OK;
        Map<SharedPreferences, Map<String, ?>> saved = new HashMap<>();
        Map<File, byte[]> files = new HashMap<>();
        Map<File, Long> timestamps = new HashMap<>();
        File images = null, originalImages = null;
        try {
            if (!android.os.Build.FINGERPRINT.contains("generic") && !android.os.Build.PRODUCT.contains("sdk"))
                throw new IllegalStateException("Integration fixtures must run on an emulator");
            Context ctx = getTargetContext();
            for (String name : new String[]{Config.SETTINGS_NAME, "wallpaper_rotation"}) {
                SharedPreferences prefs = ctx.getSharedPreferences(name, 0);
                saved.put(prefs, new HashMap<>(prefs.getAll()));
            }
            File dir = Config.dir(ctx);
            if (dir == null) throw new IllegalStateException("External storage unavailable");
            File config = new File(dir, Config.FILE_NAME);
            File cache = new File(ctx.getFilesDir(), Config.FILE_NAME);
            for (File file : new File[]{config, cache}) {
                files.put(file, file.isFile() ? Files.readAllBytes(file.toPath()) : null);
                timestamps.put(file, file.lastModified());
            }
            images = new File(dir, "wallpapers");
            originalImages = new File(dir, "wallpapers.integration-backup-" + System.nanoTime());
            if (images.exists() && !images.renameTo(originalImages)) throw new IllegalStateException("Cannot preserve wallpapers");
            if (!images.mkdirs()) throw new IllegalStateException("Cannot create fixture wallpapers");
            for (int i = 1; i <= 2; i++) {
                Bitmap bitmap = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(i == 1 ? Color.BLUE : Color.MAGENTA);
                try (FileOutputStream out = new FileOutputStream(new File(images, "0" + i + ".jpg"))) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
                }
                bitmap.recycle();
            }
            Files.write(config.toPath(), ("{\"wallpaper\":true,\"wallpaper_rotation_minutes\":20,"
                    + "\"wallpaper_order\":\"sequential\",\"wallpaper_true_color\":true,"
                    + "\"navigation_sounds\":false,\"ambient_glow\":false,\"cinematic_home\":true,"
                    + "\"show_network_pill\":false,\"library_button\":true,\"app_library\":true,"
                    + "\"module_cards\":false,\"home_idle_clock_minutes\":10}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            SharedPreferences choices = ctx.getSharedPreferences(Config.SETTINGS_NAME, 0);
            choices.edit().clear().commit();
            Config theme = Config.load(ctx);
            check(theme.wallpapers.size() == 2 && theme.wallpaperTrueColor, "Theme collection and true color load together");
            check(WallpaperRotation.ORDER_SEQUENTIAL.equals(theme.wallpaperOrder), "Sequential playback survives configuration loading");
            check(!theme.navigationSounds && theme.settingsKeyPanel, "Fork sound and glass settings routes survive the upstream configuration");
            choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, Config.WALLPAPER_AURORA).commit();
            check(Config.load(ctx).wallpapers.isEmpty(), "Aurora replaces the collection");
            choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, Config.WALLPAPER_OFF).commit();
            check(!Config.load(ctx).wallpaperEnabled, "Off disables external pictures");
            choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, "02.jpg").commit();
            Config picked = Config.load(ctx);
            check(picked.wallpapers.size() == 1 && "02.jpg".equals(picked.wallpaper.getName()), "Filename selection pins the requested picture");
            choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, "missing.jpg").commit();
            check(Config.load(ctx).wallpapers.size() == 2, "A removed picture falls back to the theme collection");
            choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).commit();
            Method overlay = Config.class.getDeclaredMethod("readOverlay", Context.class, File.class, boolean.class);
            overlay.setAccessible(true);
            JSONObject cached = (JSONObject) overlay.invoke(null, ctx, config, false);
            check(cached != null && !cached.getBoolean("navigation_sounds"), "Boot before storage mounts uses the cached fork configuration");

            home = (MainActivity) startActivitySync(new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            settle();
            ui(() -> {
                check(home.findViewById(R.id.btnWifi).getVisibility() == View.GONE, "Network pill can be hidden");
                check(home.findViewById(R.id.btnLibraryTop).getVisibility() == View.VISIBLE, "Glass Library shortcut is available");
                check(home.findViewById(R.id.heroArt).getVisibility() == View.VISIBLE, "Cinematic shelf uses the fork layout");
                home.findViewById(R.id.btnInputs).setVisibility(View.GONE);
                invoke(home, "focusTile", new Class<?>[]{int.class}, 0);
            });
            settle();
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
            settle();
            ui(() -> check(home.findViewById(R.id.btnLibraryTop).isFocused(), "Up skips hidden Inputs and Wi-Fi to reach Library"));
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
            settle();
            ui(() -> check(home.findViewById(R.id.btnSettings).isFocused(), "Right reaches the fork's gear button"));
            ui(() -> home.findViewById(R.id.btnSettings).performClick());
            settle();
            ui(() -> check(home.findViewById(R.id.quickLauncherSettings) != null, "Gear still opens glass quick settings"));
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            settle();

            AppAdapter[] adapter = new AppAdapter[1];
            List<String> before = new ArrayList<>();
            ui(() -> {
                adapter[0] = (AppAdapter) ((RecyclerView) home.findViewById(R.id.grid)).getAdapter();
                before.addAll(adapter[0].keys());
                home.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW);
                check(adapter[0].keys().equals(before), "Low memory while visible keeps every tile and folder");
            });
            settings = startActivitySync(new Intent(ctx, LauncherSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            settle();
            ui(() -> {
                home.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW);
                check(((RecyclerView) home.findViewById(R.id.grid)).getAdapter() == null, "Low memory while hidden releases tile views");
                settings.findViewById(R.id.wallpaperChoice).performClick();
            });
            settle();
            Field picker = LauncherSettingsActivity.class.getDeclaredField("wallpaperPicker");
            picker.setAccessible(true);
            AlertDialog dialog = (AlertDialog) picker.get(settings);
            check(dialog != null && "02.jpg".equals(dialog.getListView().getAdapter().getItem(4)),
                    "Native wallpaper picker includes the collection");
            ui(() -> check(dialog.getListView().performItemClick(null, 4, 4), "Collection row accepts selection"));
            settle();
            check("02.jpg".equals(Config.load(ctx).wallpaperChoice), "The glass picker saves a filename without losing Aurora/Off choices: " + choices.getAll());
            ui(() -> settings.findViewById(R.id.rowsChoice).performClick());
            check(choices.contains(Config.ROWS_BELOW_DOCK_KEY), "Grouped rows control saves its override");
            ui(() -> settings.findViewById(R.id.followThemeChoice).performClick());
            check(!choices.contains(Config.ROWS_BELOW_DOCK_KEY) && !choices.contains(Config.WALLPAPER_CHOICE_KEY), "Follow theme resets both new controls");
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            settings = null;
            settle();
            ui(() -> {
                RecyclerView grid = home.findViewById(R.id.grid);
                check(grid.getAdapter() != null && ((AppAdapter) grid.getAdapter()).keys().equals(before),
                        "Returning from memory pressure restores app order and folders");
            });
            result.putString("stream", "PASS: " + checks + " upstream/fork integration checks.\n");
        } catch (Throwable failure) {
            code = Activity.RESULT_CANCELED;
            result.putString("stream", "FAIL after " + checks + " integration checks: " + android.util.Log.getStackTraceString(failure));
        } finally {
            ui(() -> { if (settings != null) settings.finish(); if (home != null) home.finish(); });
            waitForIdleSync();
            if (images != null && images.isDirectory()) {
                File[] fixtures = images.listFiles();
                if (fixtures != null) for (File file : fixtures) file.delete();
                images.delete();
            }
            if (originalImages != null && originalImages.exists()) originalImages.renameTo(images);
            for (Map.Entry<File, byte[]> file : files.entrySet()) try {
                if (file.getValue() == null) file.getKey().delete();
                else {
                    Files.write(file.getKey().toPath(), file.getValue());
                    file.getKey().setLastModified(timestamps.get(file.getKey()));
                }
            } catch (Exception ignored) { }
            for (Map.Entry<SharedPreferences, Map<String, ?>> entry : saved.entrySet()) restore(entry.getKey(), entry.getValue());
        }
        finish(code, result);
    }

    private void ui(Runnable action) { runOnMainSync(action); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(650); waitForIdleSync(); }
    private void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); checks++; }
    private static void invoke(Object target, String name, Class<?>[] types, Object... args) {
        try { Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); m.invoke(target, args); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private static void restore(SharedPreferences prefs, Map<String, ?> original) {
        SharedPreferences.Editor edit = prefs.edit().clear();
        for (Map.Entry<String, ?> entry : original.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (value instanceof String) edit.putString(key, (String) value);
            else if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) edit.putInt(key, (Integer) value);
            else if (value instanceof Long) edit.putLong(key, (Long) value);
            else if (value instanceof Float) edit.putFloat(key, (Float) value);
            else if (value instanceof Set) edit.putStringSet(key, (Set<String>) value);
        }
        edit.commit();
    }
}
