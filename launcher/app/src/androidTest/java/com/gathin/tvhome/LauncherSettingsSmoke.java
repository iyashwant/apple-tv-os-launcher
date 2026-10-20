package com.gathin.tvhome;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Emulator-only settings round trips; restores all original on-TV preferences. */
public final class LauncherSettingsSmoke extends Instrumentation {
    private MainActivity home;
    private LauncherSettingsActivity settings;
    private ActivityMonitor homeMonitor, settingsMonitor;
    private int checks;

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_OK;
        SharedPreferences choices = null;
        Map<String, ?> original = null;
        try {
            if (!android.os.Build.FINGERPRINT.contains("generic")
                    && !android.os.Build.PRODUCT.contains("sdk"))
                throw new IllegalStateException("Settings fixtures must run on an emulator");
            choices = getTargetContext().getSharedPreferences(Config.SETTINGS_NAME, 0);
            original = new HashMap<>(choices.getAll());
            choices.edit().remove("navigation_sounds").remove("wallpaper_choice").commit();
            Config theme = Config.load(getTargetContext());
            home = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            settle();
            homeMonitor = addMonitor(MainActivity.class.getName(), null, false);
            settingsMonitor = addMonitor(LauncherSettingsActivity.class.getName(), null, false);

            openSettings();
            boolean sounds = theme.navigationSounds;
            checkRow(R.id.soundsChoice, getTargetContext().getString(R.string.settings_sounds) + ", "
                    + getTargetContext().getString(sounds ? R.string.on : R.string.off));
            checkRemoteRows();
            // Test both values regardless of the theme's default, with sound flags updating immediately.
            for (int i = 0; i < 2; i++) {
                activate(R.id.soundsChoice);
                sounds = !sounds;
                check(Config.load(getTargetContext()).navigationSounds == sounds,
                        "Sound choice overrides the theme");
                final boolean enabled = sounds;
                ui(() -> checkSoundFlags(settings.findViewById(android.R.id.content), enabled));
            }
            // Leave sounds off for the first Home round trip.
            if (sounds) activate(R.id.soundsChoice);
            activate(R.id.wallpaperChoice);
            check(Config.WALLPAPER_AURORA.equals(Config.load(getTargetContext()).wallpaperChoice),
                    "First wallpaper activation selects Aurora");
            backToHome();
            check(!home.findViewById(R.id.btnSettings).isSoundEffectsEnabled(),
                    "Returning Home applies the muted setting to header buttons");
            check(!home.findViewById(R.id.quickLauncherSettings).isSoundEffectsEnabled(),
                    "Returning Home applies the muted setting to quick settings");
            expectAurora();
            ui(() -> {
                try {
                    Method expand = MainActivity.class.getDeclaredMethod("setExpanded", boolean.class);
                    expand.setAccessible(true);
                    expand.invoke(home, true);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            settle();
            expectAurora();

            openSettings();
            checkRow(R.id.wallpaperChoice, getTargetContext().getString(R.string.settings_wallpaper) + ", "
                    + getTargetContext().getString(R.string.wallpaper_aurora));
            checkRow(R.id.soundsChoice, getTargetContext().getString(R.string.settings_sounds) + ", "
                    + getTargetContext().getString(R.string.off));
            activate(R.id.soundsChoice);
            activate(R.id.wallpaperChoice);
            check(Config.WALLPAPER_OFF.equals(Config.load(getTargetContext()).wallpaperChoice),
                    "Next wallpaper activation selects Off");
            backToHome();
            check(!(root().getChildAt(0) instanceof ImageView), "Off removes the wallpaper from Home");
            check(home.findViewById(R.id.btnSettings).isSoundEffectsEnabled(),
                    "Returning Home restores enabled navigation sounds");

            openSettings();
            activate(R.id.wallpaperChoice);
            check(!choices.contains("wallpaper_choice"), "Cycling to Theme removes the wallpaper override");
            activate(R.id.wallpaperChoice);
            activate(R.id.followThemeChoice);
            check(!choices.contains("navigation_sounds") && !choices.contains("wallpaper_choice"),
                    "Follow theme resets sound and wallpaper choices");
            check(Config.load(getTargetContext()).navigationSounds == theme.navigationSounds,
                    "Follow theme restores theme sounds");
            check(Config.WALLPAPER_THEME.equals(Config.load(getTargetContext()).wallpaperChoice),
                    "Follow theme restores the theme wallpaper");
            ui(() -> check(settings.findViewById(R.id.followThemeChoice).isSelected(),
                    "Follow theme has the selected state after resetting choices"));
            result.putString("stream", "PASS: " + checks + " launcher settings checks.\n");
        } catch (Throwable failure) {
            code = Activity.RESULT_CANCELED;
            result.putString("stream", "FAIL after " + checks + " launcher settings checks: "
                    + android.util.Log.getStackTraceString(failure));
        } finally {
            ui(() -> {
                if (settings != null) settings.finish();
                if (home != null) home.finish();
            });
            waitForIdleSync();
            if (homeMonitor != null) removeMonitor(homeMonitor);
            if (settingsMonitor != null) removeMonitor(settingsMonitor);
            if (choices != null && original != null) restore(choices, original);
        }
        finish(code, result);
    }

    private void openSettings() {
        // A fresh monitor is needed for each activity instance (ActivityMonitor retains its last hit).
        removeMonitor(settingsMonitor);
        settingsMonitor = addMonitor(LauncherSettingsActivity.class.getName(), null, false);
        if (home.findViewById(R.id.quickLauncherSettings) == null) {
            ui(() -> home.findViewById(R.id.btnSettings).performClick());
            settle();
        }
        ui(() -> home.findViewById(R.id.quickLauncherSettings).performClick());
        settings = (LauncherSettingsActivity) waitForMonitorWithTimeout(settingsMonitor, 5000);
        check(settings != null, "Quick settings opens Launcher settings");
        settle();
    }

    private void backToHome() {
        removeMonitor(homeMonitor);
        homeMonitor = addMonitor(MainActivity.class.getName(), null, false);
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        home = (MainActivity) waitForMonitorWithTimeout(homeMonitor, 5000);
        settings = null;
        check(home != null, "Back recreates Home with changed preferences");
        settle();
    }

    private void activate(int id) {
        ui(() -> check(settings.findViewById(id).requestFocusFromTouch(),
                "Setting accepts D-pad focus"));
        settle();
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        waitForIdleSync();
    }

    private void checkRow(int id, String text) {
        ui(() -> check(text.contentEquals(settings.findViewById(id).getContentDescription()),
                "Setting label reflects the saved choice"));
    }

    private void checkRemoteRows() {
        java.util.List<Integer> rows = new java.util.ArrayList<>();
        rows.add(R.id.wallpaperChoice);
        rows.add(R.id.animationsChoice);
        if (settings.findViewById(R.id.advancedChoice).isEnabled()) rows.add(R.id.advancedChoice);
        rows.add(R.id.soundsChoice);
        rows.add(R.id.libraryChoice);
        rows.add(R.id.statsChoice);
        rows.add(R.id.followThemeChoice);
        rows.add(R.id.defaultHomeChoice);
        rows.add(R.id.tvSettingsChoice);
        for (int id : rows) {
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
            settle();
            ui(() -> {
                View row = settings.findViewById(id);
                check(row.hasFocus(), "D-pad reaches each settings row in order");
                android.graphics.Rect visible = new android.graphics.Rect();
                row.getGlobalVisibleRect(visible);
                check(visible.width() == row.getWidth() && visible.height() == row.getHeight(),
                        "Focused full-width row remains fully visible on all edges: "
                                + settings.getResources().getResourceEntryName(id) + " " + visible
                                + " expected " + row.getWidth() + "x" + row.getHeight());
                check(row.getScaleX() == 1f && row.getScaleY() == 1f,
                        "Focused rows stay within their measured width");
            });
        }
    }

    private ViewGroup root() { return home.findViewById(R.id.root); }

    private void expectAurora() {
        ui(() -> {
            ViewGroup root = root();
            check(root.getChildAt(0) instanceof ImageView, "Bundled Aurora decodes behind Home");
            Bitmap frame = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
            root.draw(new Canvas(frame));
            int pixel = frame.getPixel(2, root.getHeight() / 5);
            frame.recycle();
            check(Color.green(pixel) > Color.red(pixel) + 15 && Color.blue(pixel) > Color.red(pixel) + 15,
                    "Teal Aurora is visible on the rendered Home background");
        });
    }

    private void checkSoundFlags(View view, boolean enabled) {
        check(view.isSoundEffectsEnabled() == enabled, "Sound flags match the toggle throughout settings");
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) checkSoundFlags(group.getChildAt(i), enabled);
        }
    }

    @SuppressWarnings("unchecked")
    private void restore(SharedPreferences choices, Map<String, ?> original) {
        SharedPreferences.Editor editor = choices.edit().clear();
        for (Map.Entry<String, ?> entry : original.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof Set) editor.putStringSet(key, (Set<String>) value);
        }
        editor.commit();
    }

    /** Report UI assertions on the instrumentation thread so fixture restoration still runs. */
    private void ui(Runnable action) {
        Throwable[] failure = new Throwable[1];
        runOnMainSync(() -> {
            try { action.run(); }
            catch (Throwable error) { failure[0] = error; }
        });
        if (failure[0] instanceof Error) throw (Error) failure[0];
        if (failure[0] instanceof RuntimeException) throw (RuntimeException) failure[0];
        if (failure[0] != null) throw new RuntimeException(failure[0]);
    }

    private void settle() { SystemClock.sleep(500); waitForIdleSync(); }
    private void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
