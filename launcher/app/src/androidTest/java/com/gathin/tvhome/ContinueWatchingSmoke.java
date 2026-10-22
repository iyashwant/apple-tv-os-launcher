package com.gathin.tvhome;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import androidx.recyclerview.widget.RecyclerView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Real TV-provider records, native toggle and D-pad round trips. Fixtures run only on an emulator. */
public final class ContinueWatchingSmoke extends Instrumentation {
    private MainActivity home;
    private LauncherSettingsActivity settings;
    private final List<Uri> inserted = new ArrayList<>();
    private int checks;
    private Context context;
    private Rect originalDock;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); int code = Activity.RESULT_OK; context = getTargetContext();
        SharedPreferences features = FeaturePreferences.prefs(context), choices = context.getSharedPreferences(Config.SETTINGS_NAME, 0);
        Map<String, ?> previousFeatures = new HashMap<>(features.getAll()), previousChoices = new HashMap<>(choices.getAll());
        try {
            if (!android.os.Build.PRODUCT.contains("sdk")) throw new IllegalStateException("Continue Watching fixtures require an emulator");
            getUiAutomation().grantRuntimePermission(context.getPackageName(), ContinueWatchingRepository.PERMISSION);
            getUiAutomation().adoptShellPermissionIdentity("com.android.providers.tv.permission.WRITE_EPG_DATA");
            for (Uri table : new Uri[]{ContinueWatchingRepository.WATCH_NEXT, ContinueWatchingRepository.PREVIEW}) {
                try (android.database.Cursor cursor = context.getContentResolver().query(table, new String[]{"_id", "package_name", "internal_provider_id"}, null, null, null)) {
                    while (cursor != null && cursor.moveToNext()) if (context.getPackageName().equals(cursor.getString(1)) && cursor.getString(2) != null && cursor.getString(2).startsWith("fixture-"))
                        inserted.add(android.content.ContentUris.withAppendedId(table, cursor.getLong(0)));
                }
            }
            for (Uri uri : inserted) context.getContentResolver().delete(uri, null, null); inserted.clear();
            check(ContinueWatchingRepository.permitted(context), "The real public TV-listings permission is available");
            for (int i = 0; i < 4; i++) insert(true, "movie" + i, 0, 120_000 + i, 3_600_000, i);
            insert(true, "completed", 0, 3_600_000, 3_600_000, 100);
            insert(true, "next", 1, 120_000, 3_600_000, 101);
            insert(true, "new", 2, 120_000, 3_600_000, 102);
            insert(true, "never-started", 0, 0, 3_600_000, 103);
            insert(false, "movie0", 0, 120_000, 3_600_000, 0);
            insert(false, "preview-only", 0, 120_000, 3_600_000, 120);
            ContinueWatchingRepository.Result queried = ContinueWatchingRepository.load(context, java.util.Collections.emptyList(), null);
            check(!queried.failed && queried.items.size() == 5, "Provider results filter unfinished content and deduplicate both tables");
            check(queried.items.stream().noneMatch(i -> i.title.contains("completed") || i.title.endsWith("next") || i.title.endsWith("new") || i.title.contains("never-started")), "Completed, upcoming and unwatched items stay out");
            check(queried.items.stream().anyMatch(i -> i.title.endsWith("preview-only")), "Unfinished recommendation items supplement Watch Next");
            for (int i = 1; i < queried.items.size(); i++) check(queried.items.get(i-1).engagement >= queried.items.get(i).engagement, "Items are sorted by recent engagement");
            check(ContinueWatchingRepository.load(context, Arrays.asList(context.getPackageName()), null).items.isEmpty(), "Hidden apps do not expose their viewing items");
            ContinueWatchingRepository.Item item = queried.items.get(0);
            List<ContinueWatchingRepository.Item> many = new ArrayList<>();
            for (int i = 0; i < 32; i++) many.add(new ContinueWatchingRepository.Item("key" + i, "fixture.app" + (i % 4), "Item " + i, "", item.appName, item.artwork,
                    new Intent(item.intent).setData(Uri.parse("continue-fixture://cap/" + i)), 10, 100, i, true));
            check(ContinueWatchingRepository.select(many).size() == 24, "Large histories are capped at 24 recent items");
            check(ContinueWatchingRepository.select(many).stream().filter(i -> i.pkg.equals("fixture.app0")).count() == 6, "One app cannot fill the shelf with a large back catalogue");
            check(item.intent.getPackage().equals(context.getPackageName()), "The resume link is bound to its publishing app");
            Intent dangerous = new Intent(item.intent).setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            Intent safe = ContinueWatchingRepository.resumeIntent(context, item.pkg, dangerous.toUri(Intent.URI_INTENT_SCHEME));
            check(safe != null && (safe.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) == 0, "Published links cannot grant private URI access");
            check(ContinueWatchingRepository.resumeIntent(context, "com.missing.source.fixture", item.intent.toUri(Intent.URI_INTENT_SCHEME)) == null, "A missing source app cannot redirect through another app");
            ContinueWatchingRepository.Item duplicate = new ContinueWatchingRepository.Item(item.key, item.pkg, item.title, item.episode, item.appName, item.artwork,
                    item.intent, item.position, item.duration, item.engagement + 10_000, false);
            check(ContinueWatchingRepository.select(new ArrayList<>(Arrays.asList(item, duplicate))).size() == 1, "A program shared in both provider tables appears once");

            features.edit().putBoolean("continue_watching", false).commit(); choices.edit().putInt("columns", 6).putInt(Config.ROWS_BELOW_DOCK_KEY, 0).putBoolean("animations", true).commit();
            settings = (LauncherSettingsActivity) startActivitySync(new Intent(context, LauncherSettingsActivity.class).putExtra(HomePreviewView.DOCK_PACKAGE, context.getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); settle();
            ui(() -> settings.findViewById(R.id.continueWatchingChoice).performClick()); settle();
            check(Config.load(context).continueWatching, "The real Launcher settings toggle enables Continue Watching");
            HomePreviewView preview = settings.findViewById(R.id.settingsPreviewImage);
            for (int i = 0; i < 60 && ((List<?>) field(preview, "continueItems")).isEmpty(); i++) SystemClock.sleep(100); settle();
            check(!((List<?>) field(preview, "continueItems")).isEmpty(), "Settings previews the actual Continue Watching items");
            java.util.concurrent.CountDownLatch decoded = new java.util.concurrent.CountDownLatch(1);
            Bitmap[] poster = new Bitmap[1];
            ContinueWatchingArtwork.load(context, item, image -> { poster[0] = image; decoded.countDown(); });
            check(decoded.await(6, java.util.concurrent.TimeUnit.SECONDS) && poster[0] != null, "Published poster resources decode for the shelf");
            check(Math.max(poster[0].getWidth(), poster[0].getHeight()) <= 512, "Poster thumbnails stay within the memory bound");
            ui(settings::finish); settings = null; settle();
            home = (MainActivity) startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); settle();
            ui(() -> { try {
                seedDock();
                focusDock(0);
            } catch(Exception e) { throw new RuntimeException(e); } }); settle();
            ContinueWatchingView shelf = home.findViewById(R.id.continueWatching);
            for (int i = 0; i < 60 && !shelf.hasItems(); i++) SystemClock.sleep(100); settle();
            check(shelf.hasItems() && shelf.isShown(), "The shelf shows real provider records on Home");
            ui(() -> {
                Rect grid = new Rect(); home.findViewById(R.id.grid).findFocus().getGlobalVisibleRect(grid);
                Rect row = new Rect(); shelf.getGlobalVisibleRect(row);
                check(row.bottom + 12 < grid.top, "Continue Watching sits above the dock with clear space between them");
                originalDock = new Rect(grid);
                check(row.bottom <= home.findViewById(R.id.root).getHeight(), "The entire shelf fits within the TV display");
                check(home.findViewById(R.id.gridHost).getClipBounds() == null, "Continue Watching does not clip or displace the original dock");
                RecyclerView strip = (RecyclerView) field(shelf, "list");
                View card = strip.getChildAt(0); View picture = (View) field(strip.getChildViewHolder(card), "picture");
                check(Math.abs(picture.getWidth() / (float) picture.getHeight() - 16f / 9f) < .03f, "Resume artwork has wide Apple TV landscape proportions");
                check(card.getBackground() == null && card.getForeground() == null, "Resume metadata sits directly on the wallpaper without a framed panel");
            });
            check(shelf.items().stream().allMatch(i -> context.getPackageName().equals(i.pkg)), "Dock focus restricts the shelf to its app alone");
            dockSlideChecks(shelf);
            key(KeyEvent.KEYCODE_DPAD_RIGHT);
            check(!shelf.isShown() && shelf.items().isEmpty(), "An app with no Continue Watching hides every card and the heading");
            key(KeyEvent.KEYCODE_DPAD_LEFT);
            check(shelf.isShown() && shelf.items().stream().allMatch(i -> context.getPackageName().equals(i.pkg)), "Returning to the supported dock app restores only its own items");
            check(ContinueWatchingRepository.forPackage(queried.items, null).isEmpty(), "No dock app never falls back to the mixed-app catalogue");
            ContinueWatchingRepository.Item other = new ContinueWatchingRepository.Item("other-item", "fixture.app1", "Other app show", "", "Other app", item.artwork,
                    new Intent(item.intent).setPackage("fixture.app1"), 20, 100, item.engagement, true);
            List<ContinueWatchingRepository.Item> mixed = new ArrayList<>(queried.items); mixed.add(other);
            ui(() -> {
                try { Method publish = ContinueWatchingView.class.getDeclaredMethod("showItems", List.class); publish.setAccessible(true); publish.invoke(shelf, mixed); }
                catch (Exception e) { throw new RuntimeException(e); }
            }); settle();
            key(KeyEvent.KEYCODE_DPAD_RIGHT);
            check(shelf.items().size() == 1 && "fixture.app1".equals(shelf.items().get(0).pkg), "Moving to another supported app replaces the list without mixing sources");
            key(KeyEvent.KEYCODE_DPAD_LEFT);
            check(shelf.items().stream().noneMatch(i -> "fixture.app1".equals(i.pkg)), "Switching back removes the previous app's cards immediately");
            motionChecks(shelf);
            key(KeyEvent.KEYCODE_DPAD_UP); check(shelf.hasFocus(), "Up from the dock enters Continue Watching");
            int before = (int) field(shelf, "selected"); key(KeyEvent.KEYCODE_DPAD_RIGHT);
            check((int) field(shelf, "selected") == before + 1, "Right selects the next program");
            key(KeyEvent.KEYCODE_DPAD_LEFT); check((int) field(shelf, "selected") == before, "Left selects the previous program");
            key(KeyEvent.KEYCODE_DPAD_DOWN); check(!shelf.hasFocus() && home.getCurrentFocus().getId() == R.id.card, "Down restores the dock tile");
            key(KeyEvent.KEYCODE_DPAD_UP); key(KeyEvent.KEYCODE_DPAD_UP);
            check(home.findViewById(R.id.topBar).hasFocus(), "Up from Continue Watching reaches the header");
            key(KeyEvent.KEYCODE_DPAD_DOWN); check(shelf.hasFocus(), "Down from the header returns to Continue Watching");
            key(KeyEvent.KEYCODE_BACK);
            check(home.getCurrentFocus().getId() == R.id.card, "Back from the shelf restores dock focus");
            ui(() -> {
                AppAdapter apps = (AppAdapter) field(home, "homeAdapter"); HomeLayout layout = apps.snapshotLayout();
                String folder = layout.createFolder(apps.keyAt(2), "Continue test folder"); layout.moveTo(apps.keyAt(3), folder); apps.restoreLayout(layout);
                home.onFolderOpened(folder);
            }); settle();
            check(!shelf.isShown(), "Folder contents never overlap the Continue Watching shelf");
            key(KeyEvent.KEYCODE_BACK); settle(); check(!shelf.isShown(), "Returning to a folder tile does not show another app’s viewing items");
            ui(() -> { try { Method focus = MainActivity.class.getDeclaredMethod("focusTile", int.class); focus.setAccessible(true); focus.invoke(home, 0); }
                catch(Exception e) { throw new RuntimeException(e); } }); settle();
            key(KeyEvent.KEYCODE_DPAD_DOWN);
            check((boolean) field(home, "expanded") && !shelf.isShown(), "Down from the restored dock opens the full app grid");
            key(KeyEvent.KEYCODE_BACK); settle(); check(shelf.isShown(), "Back restores Home with the shelf");
            key(KeyEvent.KEYCODE_DPAD_UP); key(KeyEvent.KEYCODE_DPAD_CENTER); settle();
            check(home.getIntent().getBooleanExtra("continue_fixture", false), "Selecting a real provider card launches its published resume intent");
            check(home.getIntent().getData() != null && "continue-fixture".equals(home.getIntent().getData().getScheme()), "Resume content identity survives launching");

            for (Uri uri : inserted) context.getContentResolver().delete(uri, null, null); inserted.clear();
            for (int i = 0; i < 60 && shelf.hasItems(); i++) SystemClock.sleep(100); settle();
            check(!shelf.hasItems() && !shelf.isShown(), "The shelf disappears when apps remove their last unfinished item");
            check(home.findViewById(R.id.gridHost).getClipBounds() == null, "An empty shelf leaves no clipping or reserved gap");

            settings = (LauncherSettingsActivity) startActivitySync(new Intent(context, LauncherSettingsActivity.class).putExtra(HomePreviewView.DOCK_PACKAGE, context.getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); settle();
            check(!(boolean) field(shelf, "observing") && !(boolean) field(shelf, "active"), "Leaving Home stops provider observation and refreshes");
            ui(() -> settings.findViewById(R.id.continueWatchingChoice).performClick()); settle();
            check(!Config.load(context).continueWatching, "The toggle disables Continue Watching persistently");
            ui(settings::finish); settings = null; ui(home::finish); home = null; settle();
            home = (MainActivity) startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); settle();
            ui(() -> { try {
                seedDock();
                focusDock(0);
            } catch(Exception e) { throw new RuntimeException(e); } }); settle();
            ui(() -> {
                Rect disabledDock = new Rect(); home.findViewById(R.id.grid).findFocus().getGlobalVisibleRect(disabledDock);
                check(originalDock.equals(disabledDock), "The dock has identical bounds with Continue Watching on or off");
            });
            ContinueWatchingView disabled = home.findViewById(R.id.continueWatching);
            check(!disabled.isShown() && !(boolean) field(disabled, "observing"), "Disabled means no shelf and no provider observer");
            result.putString("stream", "PASS: " + checks + " Continue Watching checks.\n");
        } catch (Throwable failure) {
            code = Activity.RESULT_CANCELED; result.putString("stream", "FAIL after " + checks + " Continue Watching checks: " + android.util.Log.getStackTraceString(failure));
        } finally {
            ui(() -> { if (settings != null) settings.finish(); if (home != null) home.finish(); });
            for (Uri uri : inserted) try { context.getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
            FeatureSettingsSmoke.restore(features, previousFeatures); FeatureSettingsSmoke.restore(choices, previousChoices);
            getUiAutomation().dropShellPermissionIdentity();
        }
        finish(code, result);
    }
    private void dockSlideChecks(ContinueWatchingView shelf) {
        View gridHost = home.findViewById(R.id.gridHost);
        ui(() -> {
            float start = gridHost.getTranslationY();
            check(shelf.isShown() && start > 100, "Dock expansion starts with a visible Continue Watching row");
            dispatch(KeyEvent.KEYCODE_DPAD_DOWN);
            check(Math.abs(gridHost.getTranslationY() - start) < 1f, "Hiding Continue Watching must not snap the grid before its slide starts");
            check(!shelf.isShown(), "Continue Watching hides when entering the full grid");
            android.animation.ValueAnimator slide = (android.animation.ValueAnimator) field(home, "gridSlide");
            check(slide != null, "Down starts the original grid animation");
            slide.setCurrentPlayTime(slide.getDuration() / 2);
            check(gridHost.getTranslationY() > 0f && gridHost.getTranslationY() < start, "Down passes through an intermediate grid position");
            slide.end();
        }); settle();
        key(KeyEvent.KEYCODE_DPAD_UP);
        ui(() -> {
            float start = gridHost.getTranslationY();
            dispatch(KeyEvent.KEYCODE_DPAD_UP);
            check(Math.abs(gridHost.getTranslationY() - start) < 1f, "Restoring Continue Watching must not snap the grid before collapsing");
            android.animation.ValueAnimator slide = (android.animation.ValueAnimator) field(home, "gridSlide");
            check(slide != null, "Up starts the original collapse animation");
            slide.setCurrentPlayTime(slide.getDuration() / 2);
            check(gridHost.getTranslationY() > start && gridHost.getTranslationY() < originalDock.top, "Collapse also passes through an intermediate position");
            slide.end();
        }); settle();
        check(shelf.isShown() && !(boolean) field(home, "expanded"), "The row returns after the dock settles");
        ui(() -> {
            dispatch(KeyEvent.KEYCODE_DPAD_DOWN);
            android.animation.ValueAnimator slide = (android.animation.ValueAnimator) field(home, "gridSlide");
            slide.setCurrentPlayTime(slide.getDuration() / 3);
            float before = gridHost.getTranslationY();
            dispatch(KeyEvent.KEYCODE_DPAD_UP); dispatch(KeyEvent.KEYCODE_DPAD_UP);
            check(Math.abs(gridHost.getTranslationY() - before) < 1f, "A rapid Down/Up reversal preserves the current grid position with the row enabled");
        }); settle();
        check(shelf.isShown() && !(boolean) field(home, "expanded"), "A reversed slide restores the dock and its app preview");
    }
    private void dispatch(int code) {
        long now = SystemClock.uptimeMillis();
        home.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
        home.dispatchKeyEvent(new KeyEvent(now, now + 1, KeyEvent.ACTION_UP, code, 0));
    }
    private void motionChecks(ContinueWatchingView shelf) {
        ui(() -> {
            check((boolean) field(shelf, "animations"), "The motion test uses the enabled launcher animation preference");
            shelf.setFocusedPackage("fixture.empty");
            check(shelf.items().isEmpty() && shelf.isShown() && !displayed(shelf).isEmpty(), "Outgoing cards remain attached only while fading away");
            halfway(shelf);
            check(shelf.getAlpha() > 0f && shelf.getAlpha() < 1f && shelf.getTranslationY() > 0f, "Disappearance has an intermediate faded and lowered frame");
            check(shelf.getLayerType() == View.LAYER_TYPE_HARDWARE, "The TV caches the bounded artwork strip while it moves");
            check(shelf.getDescendantFocusability() == android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS, "Outgoing cards cannot receive remote focus");
            endMotion(shelf);
            check(!shelf.isShown() && displayed(shelf).isEmpty(), "The completed exit removes the whole row and outgoing content");
            check(shelf.getLayerType() == View.LAYER_TYPE_NONE, "The outgoing artwork releases its temporary GPU layer");
            shelf.setFocusedPackage(context.getPackageName()); halfway(shelf);
            check(shelf.isShown() && shelf.getAlpha() > 0f && shelf.getAlpha() < 1f && shelf.getTranslationY() > 0f, "Appearance has an intermediate fade and upward slide");
            Object running = field(shelf, "rowAnimator"); float alpha = shelf.getAlpha();
            shelf.present(true, true);
            check(field(shelf, "rowAnimator") == running && shelf.getAlpha() == alpha, "Repeated layout updates do not restart the animation");
            Rect dock = new Rect(); home.findViewById(R.id.grid).findFocus().getGlobalVisibleRect(dock);
            check(originalDock.equals(dock), "The dock stays fixed during the shelf animation");
            endMotion(shelf);
            check(shelf.getLayerType() == View.LAYER_TYPE_NONE, "A settled shelf releases its temporary GPU layer");
            shelf.setFocusedPackage("fixture.app1");
            check(shelf.items().size() == 1 && displayed(shelf).stream().allMatch(i -> context.getPackageName().equals(i.pkg)), "App switching fades the outgoing artwork before replacing it");
            endMotion(shelf);
            check(shelf.getAlpha() == 0f && displayed(shelf).stream().allMatch(i -> "fixture.app1".equals(i.pkg)), "New app artwork replaces the old app at zero opacity");
            halfway(shelf); check(shelf.getAlpha() > 0f && shelf.getAlpha() < 1f, "The new app's cards fade in smoothly"); endMotion(shelf);
            shelf.setFocusedPackage(context.getPackageName()); endMotion(shelf); endMotion(shelf);
            shelf.setFocusedPackage("fixture.empty"); halfway(shelf); alpha = shelf.getAlpha();
            shelf.setFocusedPackage(context.getPackageName());
            check(shelf.getAlpha() == alpha, "A quick return reverses the exit from its current opacity"); endMotion(shelf);
            shelf.setFocusedPackage("fixture.app1"); halfway(shelf);
            shelf.setFocusedPackage("fixture.empty"); shelf.setFocusedPackage(context.getPackageName()); endMotion(shelf);
            check(shelf.isShown() && shelf.getAlpha() == 1f && displayed(shelf).stream().allMatch(i -> context.getPackageName().equals(i.pkg)), "Rapid app changes settle on the latest source without a stale completion");
            shelf.setFocusedPackage("fixture.empty"); halfway(shelf); shelf.stop();
            check(field(shelf, "rowAnimator") == null && !shelf.isShown() && displayed(shelf).isEmpty(), "Leaving Home cancels an in-flight transition without a delayed revival");
            shelf.start(true); shelf.setFocusedPackage(context.getPackageName()); shelf.present(true, false);
            try { Field enabled = ContinueWatchingView.class.getDeclaredField("animations"); enabled.setAccessible(true); enabled.set(shelf, false); }
            catch (Exception e) { throw new RuntimeException(e); }
            shelf.setFocusedPackage("fixture.empty");
            check(!shelf.isShown() && field(shelf, "rowAnimator") == null, "Disabled or reduced motion hides the row immediately");
            shelf.setFocusedPackage(context.getPackageName());
            check(shelf.isShown() && shelf.getAlpha() == 1f && shelf.getTranslationY() == 0f && field(shelf, "rowAnimator") == null, "Disabled or reduced motion presents the final row without an animation");
            try { Field enabled = ContinueWatchingView.class.getDeclaredField("animations"); enabled.setAccessible(true); enabled.set(shelf, true); }
            catch (Exception e) { throw new RuntimeException(e); }
            shelf.setFocusedPackage("fixture.app1");
            check(shelf.focusRemembered(), "Up can request the incoming app's cards while artwork is switching");
            shelf.setFocusedPackage(context.getPackageName()); endMotion(shelf);
        });
        settle();
        check(!shelf.hasFocus(), "A later dock move cancels an earlier pending row-focus request");
    }
    @SuppressWarnings("unchecked") private List<ContinueWatchingRepository.Item> displayed(ContinueWatchingView shelf) {
        return (List<ContinueWatchingRepository.Item>) field(shelf, "displayedItems");
    }
    private void halfway(ContinueWatchingView shelf) {
        android.animation.ValueAnimator motion = (android.animation.ValueAnimator) field(shelf, "rowAnimator");
        check(motion != null, "A transition is active"); motion.setCurrentPlayTime(motion.getDuration() / 2);
    }
    private void endMotion(ContinueWatchingView shelf) {
        android.animation.ValueAnimator motion = (android.animation.ValueAnimator) field(shelf, "rowAnimator");
        check(motion != null, "A transition can complete"); motion.end();
    }
    @SuppressWarnings("unchecked") private void seedDock() throws Exception {
        AppAdapter apps = LauncherSmoke.seedApps(home, 30);
        Map<String, AppAdapter.Entry> inventory = (Map<String, AppAdapter.Entry>) field(apps, "inventory");
        Map<String, AppAdapter.Entry> ordered = new java.util.LinkedHashMap<>();
        int index = 0;
        for (AppAdapter.Entry entry : inventory.values()) {
            if (index++ == 0) entry = new AppAdapter.Entry("Continue source", new ComponentName(context, MainActivity.class), entry.banner, entry.icon);
            ordered.put(entry.key, entry);
        }
        inventory.clear(); inventory.putAll(ordered);
        HomeLayout layout = new HomeLayout(); layout.reconcile(new ArrayList<>(ordered.keySet())); apps.restoreLayout(layout);
    }
    private void focusDock(int slot) throws Exception {
        Method focus = MainActivity.class.getDeclaredMethod("focusTile", int.class); focus.setAccessible(true); focus.invoke(home, slot);
    }
    private void insert(boolean next, String id, int kind, long position, long duration, int recent) {
        Intent intent = new Intent(Intent.ACTION_VIEW).setComponent(new ComponentName(context, MainActivity.class))
                .setData(Uri.parse("continue-fixture://movie/" + id)).putExtra("continue_fixture", true);
        ContentValues values = new ContentValues(); values.put("type", 0); values.put("title", "Continue fixture " + id);
        values.put("content_id", id); values.put("internal_provider_id", "fixture-" + id);
        values.put("intent_uri", intent.toUri(Intent.URI_INTENT_SCHEME)); values.put("poster_art_uri", "android.resource://" + context.getPackageName() + "/raw/aurora_wallpaper");
        values.put("last_playback_position_millis", position); values.put("duration_millis", duration);
        if (next) { values.put("watch_next_type", kind); values.put("last_engagement_time_utc_millis", System.currentTimeMillis() - 600_000 + recent * 1000); }
        Uri uri = context.getContentResolver().insert(next ? ContinueWatchingRepository.WATCH_NEXT : ContinueWatchingRepository.PREVIEW, values);
        if (uri == null) throw new AssertionError("Provider rejected fixture"); inserted.add(uri);
    }
    private Object field(Object object, String name) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private void key(int code) { sendKeyDownUpSync(code); settle(); }
    private void settle() { SystemClock.sleep(550); waitForIdleSync(); }
    private void ui(Runnable action) {
        Throwable[] error = new Throwable[1]; runOnMainSync(() -> { try { action.run(); } catch (Throwable e) { error[0] = e; } });
        if (error[0] != null) throw new AssertionError(error[0]);
    }
    private void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
