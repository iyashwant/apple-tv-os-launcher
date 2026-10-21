package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Actual rendered preview/background checks; emulator fixtures never change the physical TV's data. */
public final class AppearanceSmoke extends Instrumentation {
    private MainActivity home;
    private LauncherSettingsActivity settings;
    private FeatureSettingsActivity feature;
    private int checks;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); int code = Activity.RESULT_OK;
        Context context = getTargetContext();
        SharedPreferences choices = context.getSharedPreferences(Config.SETTINGS_NAME, 0);
        SharedPreferences features = FeaturePreferences.prefs(context), rotation = context.getSharedPreferences("wallpaper_rotation", 0);
        Map<String, ?> oldChoices = new HashMap<>(choices.getAll()), oldFeatures = new HashMap<>(features.getAll()), oldRotation = new HashMap<>(rotation.getAll());
        try {
            if (!android.os.Build.PRODUCT.contains("sdk")) throw new IllegalStateException("Appearance fixtures require an emulator");
            features.edit().putInt("sky_intensity", 0).putInt("wallpaper_dim", 0).putInt("wallpaper_rotation_minutes", 5)
                    .putString("wallpaper_order", WallpaperRotation.ORDER_SEQUENTIAL).commit();
            choices.edit().putInt("columns", 6).putInt(Config.ROWS_BELOW_DOCK_KEY, 0).putString(Config.WALLPAPER_CHOICE_KEY, Config.WALLPAPER_AURORA).commit();
            settings = (LauncherSettingsActivity) startActivitySync(new Intent(context, LauncherSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            HomePreviewView preview = settings.findViewById(R.id.settingsPreviewImage);
            awaitPreview(preview);
            Bitmap six = shot(preview);
            ui(() -> settings.findViewById(R.id.columns4).performClick()); settle();
            Bitmap four = shot(preview);
            check(differences(six, four, .55f) > 200, "Changing six columns to four changes the actual dock preview");
            ui(() -> settings.findViewById(R.id.columns7).performClick()); settle();
            Bitmap seven = shot(preview);
            check(differences(four, seven, .55f) > 200, "Seven-column layout has different rendered tile positions");
            four.recycle(); seven.recycle(); six.recycle();
            ui(() -> settings.findViewById(R.id.columns6).performClick()); settle();
            Bitmap oneRow = shot(preview);
            ui(() -> settings.findViewById(R.id.rowsChoice).performClick()); settle();
            Bitmap twoRows = shot(preview);
            check(differences(oneRow, twoRows, .3f) > 200, "Visible-row choice moves and expands the preview grid");
            oneRow.recycle(); twoRows.recycle();

            ActivityMonitor monitor = addMonitor(FeatureSettingsActivity.class.getName(), null, false);
            ui(() -> settings.findViewById(R.id.wallpaperChoice).performClick());
            feature = (FeatureSettingsActivity) waitForMonitorWithTimeout(monitor, 4000); removeMonitor(monitor);
            check(feature != null, "Wallpaper opens its native settings screen"); settle();
            HomePreviewView wallpaperPreview = feature.findViewById(R.id.featurePreview); awaitPreview(wallpaperPreview);
            Bitmap aurora = shot(wallpaperPreview);
            choose("wallpaper_source", 4); awaitPreview(wallpaperPreview);
            Bitmap scene = shot(wallpaperPreview);
            check(differences(aurora, scene, 0) > 500, "Choosing a Scene immediately changes the on-screen preview wallpaper");
            choose("wallpaper_dim", 4); settle(); Bitmap dimmed = shot(wallpaperPreview);
            check(brightness(dimmed) < brightness(scene) * .6, "Wallpaper dimming immediately darkens the preview");
            ui(feature::finish); feature = null; settle(); awaitPreview(preview);
            Bitmap parent = shot(preview);
            check(Math.abs(brightness(parent) - brightness(dimmed)) < 3, "The parent settings preview adopts wallpaper changes on return");
            aurora.recycle(); scene.recycle(); dimmed.recycle(); parent.recycle();
            ui(settings::finish); settings = null; settle();

            features.edit().putInt("wallpaper_dim", 35).putInt("sky_intensity", 25).commit();
            for (String source : new String[]{Config.WALLPAPER_AURORA, FeaturePreferences.SCENES, Config.WALLPAPER_OFF}) {
                choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, source).commit();
                home = (MainActivity) startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); settle();
                String[] folder = new String[1];
                ui(() -> {
                    try {
                        AppAdapter adapter = LauncherSmoke.seedApps(home, 24);
                        HomeLayout layout = adapter.snapshotLayout(); List<String> keys = adapter.keys();
                        folder[0] = layout.createFolder(keys.get(7), "Background check"); layout.moveTo(keys.get(8), folder[0]); adapter.restoreLayout(layout);
                        java.lang.reflect.Method expand = MainActivity.class.getDeclaredMethod("setExpanded", boolean.class); expand.setAccessible(true); expand.invoke(home, true);
                        java.lang.reflect.Method focus = MainActivity.class.getDeclaredMethod("focusKey", String.class, int.class); focus.setAccessible(true); focus.invoke(home, folder[0], 0);
                    } catch (Exception e) { throw new RuntimeException(e); }
                }); settle();
                ui(() -> home.findViewById(R.id.ambient).setVisibility(View.GONE)); settle();
                Bitmap before = getUiAutomation().takeScreenshot();
                ui(() -> home.getCurrentFocus().performClick()); settle();
                Bitmap inside = getUiAutomation().takeScreenshot();
                sameBackground(before, inside, source + " is retained behind the open folder"); before.recycle();
                if (source.equals(FeaturePreferences.SCENES)) {
                    String first = rotation.getString("image", "");
                    rotation.edit().putLong("changed", System.currentTimeMillis() - 10 * 60_000L).commit();
                    ui(() -> ((WallpaperPlayer) field(home, "wallpaperPlayer")).update());
                    for (int i = 0; i < 50 && first.equals(rotation.getString("image", "")); i++) SystemClock.sleep(100);
                    check(!first.equals(rotation.getString("image", "")), "Wallpaper rotation advances while a folder is open"); settle();
                    Bitmap rotated = getUiAutomation().takeScreenshot();
                    check(differences(inside, rotated, 0) > 1000, "The open folder displays the newly rotated picture"); inside.recycle(); inside = rotated;
                }
                sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); settle();
                Bitmap after = getUiAutomation().takeScreenshot(); sameBackground(inside, after, "Closing the folder returns to the same current background");
                check(folder[0].equals(field(home, "lastFocusedKey")), "Back restores folder focus");
                inside.recycle(); after.recycle(); ui(home::finish); home = null; settle();
            }
            result.putString("stream", "PASS: " + checks + " rendered appearance checks.\n");
        } catch (Throwable failure) {
            code = Activity.RESULT_CANCELED; result.putString("stream", "FAIL after " + checks + " appearance checks: " + android.util.Log.getStackTraceString(failure));
        } finally {
            ui(() -> { if (feature != null) feature.finish(); if (settings != null) settings.finish(); if (home != null) home.finish(); });
            FeatureSettingsSmoke.restore(choices, oldChoices); FeatureSettingsSmoke.restore(features, oldFeatures); FeatureSettingsSmoke.restore(rotation, oldRotation);
        }
        finish(code, result);
    }
    private void choose(String key, int index) {
        ui(() -> feature.findViewById(R.id.featureRows).findViewWithTag(key).performClick()); settle();
        ui(() -> ((AlertDialog) field(feature, "activeDialog")).getListView().performItemClick(null, index, index)); settle();
    }
    private void awaitPreview(HomePreviewView view) {
        for (int i = 0; i < 60; i++) {
            boolean[] ready = new boolean[1];
            ui(() -> ready[0] = !((List<?>) field(view, "tiles")).isEmpty() && field(view, "wallpaper") != null);
            if (ready[0]) { settle(); return; } SystemClock.sleep(100);
        }
        check(false, "Preview artwork and wallpaper finish loading");
    }
    private Bitmap shot(View view) {
        Bitmap[] bitmap = new Bitmap[1]; ui(() -> { bitmap[0] = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888); view.draw(new Canvas(bitmap[0])); }); return bitmap[0];
    }
    private int differences(Bitmap a, Bitmap b, float start) {
        int count = 0;
        for (int y = (int)(a.getHeight() * start); y < a.getHeight(); y += 2) for (int x = 0; x < a.getWidth(); x += 2)
            if (a.getPixel(x, y) != b.getPixel(x, y)) count++;
        return count;
    }
    private double brightness(Bitmap image) {
        long total = 0; int count = 0;
        for (int y = image.getHeight() / 3; y < image.getHeight() / 2; y++) {
            int color = image.getPixel(8, y); total += ((color >> 16) & 255) + ((color >> 8) & 255) + (color & 255); count += 3;
        }
        return total / (double) count;
    }
    private void sameBackground(Bitmap a, Bitmap b, String message) {
        for (int[] point : new int[][]{{8, a.getHeight() / 4}, {8, a.getHeight() / 2}, {8, a.getHeight() * 3 / 4}}) {
            int before = a.getPixel(point[0], point[1]), after = b.getPixel(point[0], point[1]);
            for (int shift : new int[]{0, 8, 16}) check(Math.abs(((before >> shift) & 255) - ((after >> shift) & 255)) <= 3, message + " at " + point[0] + "," + point[1] + " expected=" + Integer.toHexString(before) + " actual=" + Integer.toHexString(after));
        }
    }
    private Object field(Object object, String name) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private void ui(Runnable action) {
        Throwable[] error = new Throwable[1]; runOnMainSync(() -> { try { action.run(); } catch (Throwable e) { error[0] = e; } });
        if (error[0] != null) throw new AssertionError(error[0]);
    }
    private void settle() { SystemClock.sleep(700); waitForIdleSync(); }
    private void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
