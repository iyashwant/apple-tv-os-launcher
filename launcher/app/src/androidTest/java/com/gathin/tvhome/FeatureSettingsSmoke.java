package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Emulator-only round trips through the actual native feature controls, with all personal settings restored. */
public final class FeatureSettingsSmoke extends Instrumentation {
    private FeatureSettingsActivity screen;
    private MainActivity idleHome;
    private int checks;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle(); int code = Activity.RESULT_OK;
        Context context = getTargetContext();
        SharedPreferences features = FeaturePreferences.prefs(context), choices = context.getSharedPreferences(Config.SETTINGS_NAME, 0);
        Map<String, ?> previousFeatures = new HashMap<>(features.getAll()), previousChoices = new HashMap<>(choices.getAll());
        File image = new File(context.getCacheDir(), "feature-fixture.jpg"), banner = null;
        byte[] previousBanner = null;
        try {
            if (!android.os.Build.PRODUCT.contains("sdk")) throw new IllegalStateException("Feature fixtures require an emulator");
            features.edit().clear().commit(); choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).commit();
            check(SceneFiles.images(context).size() == 14, "All 14 Scenes ship offline inside the APK");
            open(FeatureSettingsActivity.WALLPAPER);
            runOnMainSync(() -> {
                screen.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER));
                check(dialog() == null, "The opening confirm release cannot activate a second settings row");
            });
            click("wallpaper_source"); select(3);
            Config config = Config.load(context);
            check(FeaturePreferences.SCENES.equals(config.wallpaperChoice) && config.wallpapers.size() == 14, "Scenes collection is selected from the TV UI");
            check(config.wallpaperEnabled && config.wallpaperRotationMinutes == 60, "Scenes work even when an existing theme disabled wallpaper");
            click("wallpaper_rotation_minutes"); select(2);
            check(Config.load(context).wallpaperRotationMinutes == 15, "Rotation interval reaches runtime configuration");
            click("wallpaper_order"); select(0);
            check(WallpaperRotation.ORDER_SEQUENTIAL.equals(Config.load(context).wallpaperOrder), "Sequential order is selectable");
            click("wallpaper_order"); select(1);
            check(WallpaperRotation.ORDER_SHUFFLE.equals(Config.load(context).wallpaperOrder), "Shuffle order is selectable");
            click("wallpaper_true_color");
            check(Config.load(context).wallpaperTrueColor, "Full-color toggle reaches the cropped decoder");
            click("wallpaper_dim"); select(2);
            check(Config.load(context).wallpaperDim == 35, "Dimming is configurable on the TV");
            click("wallpaper_source"); select(4);
            check(Config.load(context).wallpaperChoice.startsWith(FeaturePreferences.SCENE_PREFIX)
                    && Config.load(context).wallpapers.size() == 1, "One bundled Scene can be pinned");
            close(); open(FeatureSettingsActivity.WALLPAPER);
            check(WallpaperRotation.ORDER_SHUFFLE.equals(Config.load(context).wallpaperOrder), "Playback choices survive reopening settings");
            close();

            FeaturePreferences.importSettings(context, new JSONObject().put("city", "Fixture city").put("latitude", "17.385")
                    .put("longitude", "78.4867").put("sky_intensity", 50).put("races_enabled", true));
            open(FeatureSettingsActivity.SKY);
            check(Config.load(context).skyIntensity == 50, "Sky intensity can override a theme");
            java.lang.reflect.Method sun = SkyView.class.getDeclaredMethod("sunTimes"); sun.setAccessible(true);
            check(sun.invoke(new SkyView(context, null)) != null, "Saved city coordinates reach local sunrise calculation");
            int season = RaceCalendarStore.load(context).optInt("season");
            if (season == java.time.LocalDate.now().getYear()) check(!Config.load(context).raceCalendar.isEmpty(), "Bundled calendar is usable without downloading");
            click("races_enabled");
            check(Config.load(context).raceCalendar.isEmpty(), "Race panel can be turned off without deleting its calendar");
            boolean rejected = false;
            try { FeaturePreferences.importSettings(context, new JSONObject().put("sky_intensity", 75).put("longitude", "999")); }
            catch (Exception expected) { rejected = true; }
            check(rejected && Config.load(context).skyIntensity == 50, "Invalid setup does not partially change choices");
            close();

            open(FeatureSettingsActivity.CLOCK);
            click("home_idle_clock_minutes"); select(2);
            check(Config.load(context).homeIdleClockMinutes == 10, "Idle timer is adjustable on the TV");
            ActivityMonitor monitor = addMonitor(ClockActivity.class.getName(), null, false);
            click("clock_preview"); Activity clock = waitForMonitorWithTimeout(monitor, 4000);
            check(clock != null, "Clock preview opens the real clock scene");
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER); settle();
            check(clock.isFinishing() || clock.isDestroyed(), "The wake key closes the clock"); removeMonitor(monitor); close();

            ActivityMonitor idleMonitor = addMonitor(ClockActivity.class.getName(), null, false);
            idleHome = (MainActivity) startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            settle();
            Field idleDelay = MainActivity.class.getDeclaredField("idleClockMs"); idleDelay.setAccessible(true);
            check(idleDelay.getLong(idleHome) == 600_000L, "Saved 10-minute timer reaches Home");
            runOnMainSync(() -> {
                try {
                    idleDelay.setLong(idleHome, 350L);
                    java.lang.reflect.Method restart = MainActivity.class.getDeclaredMethod("restartIdleClock");
                    restart.setAccessible(true); restart.invoke(idleHome);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            Activity idleClock = waitForMonitorWithTimeout(idleMonitor, 4000);
            check(idleClock != null, "Home inactivity actually opens the clock");
            // Restore the real interval before waking Home: a loaded emulator may take longer
            // than the 350 ms fixture delay to finish its resume transition.
            runOnMainSync(() -> {
                try { idleDelay.setLong(idleHome, 600_000L); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER); settle();
            runOnMainSync(idleHome::finish); idleHome = null; removeMonitor(idleMonitor);

            open(FeatureSettingsActivity.APPS);
            View list = screen.findViewById(R.id.featureRows);
            View app = findApp(list); check(app != null, "App customization lists installed launchable apps");
            String pkg = ((String) app.getTag()).substring(4);
            banner = AppArtwork.file(context, pkg); if (banner.isFile()) previousBanner = Files.readAllBytes(banner.toPath());
            runOnMainSync(app::performClick); settle();
            click("app_label"); enter("Fixture display name");
            check("Fixture display name".equals(Config.load(context).appLabels.get(pkg)), "Custom label reaches Home inventory");
            click("app_action");
            AlertDialog actionDialog = dialog();
            runOnMainSync(() -> {
                EditText input = findInput(actionDialog.getWindow().getDecorView()); input.setText("invalid.fixture.action");
                actionDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                check(input.getError() != null, "Unresolvable launch action is rejected");
            });
            runOnMainSync(actionDialog::dismiss); settle();
            click("app_banner"); select(1);
            check("icon".equals(Config.load(context).appBanners.get(pkg)), "App icon can replace a banner");
            click("app_banner"); select(3);
            java.lang.reflect.Method catalog = FeatureSettingsActivity.class.getDeclaredMethod("catalog"); catalog.setAccessible(true);
            @SuppressWarnings("unchecked") java.util.List<android.content.pm.ResolveInfo> donors = (java.util.List<android.content.pm.ResolveInfo>) catalog.invoke(screen);
            int donorIndex = donors.get(0).activityInfo.packageName.equals(pkg) ? 1 : 0;
            check(donors.size() > donorIndex, "Icon picker offers a different installed app");
            String donor = donors.get(donorIndex).activityInfo.packageName;
            select(donorIndex);
            String reference = AppArtwork.APP_ICON_PREFIX + donor;
            check(reference.equals(Config.load(context).appBanners.get(pkg)), "Another app's icon selection is saved for the target app");
            AppAdapter adapted = new AppAdapter(context, null, 6, Config.load(context));
            AppAdapter.Entry linked = adapted.previewApp(pkg);
            check(linked != null && linked.pkg.equals(pkg) && linked.banner == null && linked.icon != null,
                    "Home retains the target launch destination while replacing its artwork");
            Bitmap actualIcon = iconBitmap(linked.icon), donorIcon = iconBitmap(context.getPackageManager().getApplicationIcon(donor));
            check(actualIcon.sameAs(donorIcon), "Home actually renders the donor app icon"); actualIcon.recycle(); donorIcon.recycle();
            close(); open(FeatureSettingsActivity.APPS);
            runOnMainSync(() -> screen.findViewById(R.id.featureRows).findViewWithTag("app:" + pkg).performClick()); settle();
            check(reference.equals(Config.load(context).appBanners.get(pkg)), "Icon replacement persists after reopening customization");
            click("app_banner"); select(3); runOnMainSync(() -> dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
            check(reference.equals(Config.load(context).appBanners.get(pkg)), "Cancelling the icon picker preserves the current icon");
            FeaturePreferences.putApp(context, "app_banners", pkg, AppArtwork.APP_ICON_PREFIX + "com.missing.icon.fixture");
            AppAdapter.Entry fallback = new AppAdapter(context, null, 6, Config.load(context)).previewApp(pkg);
            check(fallback != null && fallback.icon != null && fallback.pkg.equals(pkg), "An uninstalled donor safely falls back to the target app icon");
            Bitmap fixture = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888); fixture.eraseColor(Color.BLUE);
            try (FileOutputStream out = new FileOutputStream(image)) { fixture.compress(Bitmap.CompressFormat.JPEG, 90, out); }
            fixture.recycle(); AppArtwork.importImage(context, pkg, Uri.fromFile(image));
            FeaturePreferences.putApp(context, "app_banners", pkg, "custom");
            check(AppArtwork.load(context, pkg) != null, "User image is copied into bounded private artwork storage");
            FeaturePreferences.clearAppearance(context);
            check("Fixture display name".equals(Config.load(context).appLabels.get(pkg)), "App customization survives Follow theme");
            click("app_reset");
            check(!Config.load(context).appLabels.containsKey(pkg) && !banner.exists(), "Reset removes custom app label and artwork");
            close();

            open(FeatureSettingsActivity.REMOTE); click("remote_learn");
            check(!RemoteLearning.handle(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME)), "Home is never captured by learning");
            check(!RemoteLearning.handle(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN)), "D-pad navigation is never captured");
            runOnMainSync(() -> {
                long now = SystemClock.uptimeMillis();
                RemoteLearning.handle(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, 196, 0, 0, 1, 485));
                RemoteLearning.handle(new KeyEvent(now, now + 1, KeyEvent.ACTION_UP, 196, 0, 0, 1, 485));
            });
            settle(); select(0);
            check(RemoteKeys.parseAppScancodes(Config.load(context).appKeys).containsKey(485), "Learning saves the physical scancode and selected app");
            FeaturePreferences.clearAppearance(context);
            check(RemoteKeys.parseAppScancodes(Config.load(context).appKeys).containsKey(485), "Remote assignment survives theme reset");
            click("remote_learn"); runOnMainSync(() -> dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
            check(!RemoteLearning.handle(new KeyEvent(KeyEvent.ACTION_UP, 196)), "Cancel removes the learning listener");
            result.putString("stream", "PASS: " + checks + " native feature settings checks.\n");
        } catch (Throwable failure) {
            code = Activity.RESULT_CANCELED;
            result.putString("stream", "FAIL after " + checks + " feature checks: " + android.util.Log.getStackTraceString(failure));
        } finally {
            if (idleHome != null) runOnMainSync(idleHome::finish);
            close(); restore(features, previousFeatures); restore(choices, previousChoices); image.delete();
            if (banner != null) try { if (previousBanner == null) banner.delete(); else Files.write(banner.toPath(), previousBanner); } catch (Exception ignored) { }
        }
        finish(code, result);
    }
    private void open(String page) { screen = (FeatureSettingsActivity) startActivitySync(FeatureSettingsActivity.intent(getTargetContext(), page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); settle(); }
    private void close() { if (screen != null) { runOnMainSync(screen::finish); settle(); screen = null; } }
    private void click(String key) { runOnMainSync(() -> { View row = screen.findViewById(R.id.featureRows).findViewWithTag(key); check(row != null, "Feature row exists: " + key); row.performClick(); }); settle(); }
    private AlertDialog dialog() {
        try { Field field = FeatureSettingsActivity.class.getDeclaredField("activeDialog"); field.setAccessible(true); return (AlertDialog) field.get(screen); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private void select(int index) { runOnMainSync(() -> check(dialog().getListView().performItemClick(null, index, index), "Native choice accepts selection")); settle(); }
    private void enter(String value) { runOnMainSync(() -> { AlertDialog dialog = dialog(); findInput(dialog.getWindow().getDecorView()).setText(value); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); }); settle(); }
    private EditText findInput(View view) { if (view instanceof EditText) return (EditText) view; if (view instanceof android.view.ViewGroup) for (int i=0;i<((android.view.ViewGroup)view).getChildCount();i++) { EditText found=findInput(((android.view.ViewGroup)view).getChildAt(i)); if(found!=null)return found; } return null; }
    private View findApp(View view) { if(view.getTag() instanceof String && ((String)view.getTag()).startsWith("app:"))return view; if(view instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)view).getChildCount();i++){View found=findApp(((android.view.ViewGroup)view).getChildAt(i));if(found!=null)return found;}return null; }
    private void settle() { waitForIdleSync(); SystemClock.sleep(250); waitForIdleSync(); }
    private Bitmap iconBitmap(android.graphics.drawable.Drawable icon) {
        Bitmap result = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        android.graphics.drawable.Drawable copy = icon.getConstantState() == null ? icon : icon.getConstantState().newDrawable().mutate();
        copy.setBounds(0, 0, 96, 96); copy.draw(new android.graphics.Canvas(result)); return result;
    }
    private void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
    static void restore(SharedPreferences prefs, Map<String, ?> values) {
        SharedPreferences.Editor edit = prefs.edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            String key=entry.getKey(); Object value=entry.getValue();
            if(value instanceof String)edit.putString(key,(String)value);else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);
            else if(value instanceof Integer)edit.putInt(key,(Integer)value);else if(value instanceof Long)edit.putLong(key,(Long)value);
            else if(value instanceof Float)edit.putFloat(key,(Float)value);else if(value instanceof Set)edit.putStringSet(key,(Set<String>)value);
        }
        edit.commit();
    }
}
