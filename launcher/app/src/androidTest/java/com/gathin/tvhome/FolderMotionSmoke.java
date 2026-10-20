package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.app.Instrumentation;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Existing-inventory TV checks: no saved names, membership, app order or settings are modified. */
public final class FolderMotionSmoke extends Instrumentation {
    private volatile MainActivity activity;
    private RecyclerView home;
    private AppAdapter homeAdapter;
    private String folder;
    private int checks;
    private boolean cornersOnly;
    private final List<String> measurements = new ArrayList<>();

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        cornersOnly = args != null && "true".equals(args.getString("cornersOnly"));
        start();
    }
    @Override public void callActivityOnResume(Activity resumed) {
        super.callActivityOnResume(resumed);
        if (resumed instanceof MainActivity) activity = (MainActivity) resumed;
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            getTargetContext().startActivity(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            for (int i = 0; i < 100 && activity == null; i++) SystemClock.sleep(100);
            if (activity == null) throw new AssertionError("Launcher did not resume within ten seconds");
            runOnMainSync(() -> activity.onNewIntent(new Intent(Intent.ACTION_MAIN)));
            settle();
            runOnMainSync(() -> {
                if (android.os.Build.PRODUCT.contains("sdk")) {
                    try {
                        AppAdapter fixture = LauncherSmoke.seedApps(activity, 24);
                        HomeLayout layout = fixture.snapshotLayout();
                        List<String> apps = fixture.keys();
                        folder = layout.createFolder(apps.get(7), "Motion check");
                        for (int i = 8; i < 13; i++) layout.moveTo(apps.get(i), folder);
                        fixture.restoreLayout(layout);
                    } catch (Exception e) { throw new RuntimeException(e); }
                }
                home = (RecyclerView) field(activity, "homeGrid");
                homeAdapter = (AppAdapter) field(activity, "homeAdapter");
                if (folder == null) for (String key : homeAdapter.keys()) {
                    if (homeAdapter.folder(key) != null) { folder = key; break; }
                }
                if (folder == null) throw new IllegalStateException("An existing visible folder is required");
                if (homeAdapter.positionOf(folder) >= homeAdapter.columns())
                    invoke("setExpanded", new Class<?>[]{boolean.class}, true);
                invoke("focusKey", new Class<?>[]{String.class, int.class}, folder, 0);
            });
            settle();
            for (int i = 0; !cornersOnly && i < 3; i++) {
                measure("open " + (i + 1), true, () -> activity.getCurrentFocus().performClick());
                check(folder.equals(((AppAdapter) field(activity, "adapter")).activeFolder()), "Click opens the selected folder");
                measure("close " + (i + 1), false, () -> dispatch(KeyEvent.KEYCODE_BACK));
                check(field(activity, "grid") == home && field(activity, "adapter") == homeAdapter,
                        "Back returns to the retained Home grid and adapter");
                check(folder.equals(field(activity, "lastFocusedKey")), "Back restores the original folder tile");
            }
            Bitmap homeFrame = getUiAutomation().takeScreenshot();
            List<Point> corners = cornerSamples();
            freeze("opening", true, homeFrame, corners);
            checkBlackBackdrop();
            freeze("closing", false, homeFrame, corners);
            homeFrame.recycle();
            runOnMainSync(() -> activity.getCurrentFocus().performClick());
            SystemClock.sleep(70);
            runOnMainSync(() -> dispatch(KeyEvent.KEYCODE_BACK));
            settle();
            check(((AppAdapter) field(activity, "adapter")).activeFolder() == null
                    && activity.findViewById(R.id.folderGridHost).getVisibility() != View.VISIBLE,
                    "Back during opening reverses and releases the folder");
            result.putString("stream", String.join("\n", measurements) + "\nPASS: " + checks + " folder motion checks.\n");
            finish(-1, result);
        } catch (Throwable failure) {
            result.putString("stream", String.join("\n", measurements) + "\nFAIL: "
                    + android.util.Log.getStackTraceString(failure));
            finish(0, result);
        }
    }

    private void measure(String label, boolean opening, Runnable action) {
        View root = activity.findViewById(R.id.root), panel = activity.findViewById(R.id.folderPanel);
        RecyclerView.ViewHolder retained = home.findViewHolderForAdapterPosition(homeAdapter.positionOf(folder));
        List<long[]> frames = new ArrayList<>();
        long[] start = {0}, cost = {0};
        int[] updates = {0};
        RecyclerView.AdapterDataObserver changes = new RecyclerView.AdapterDataObserver() {
            @Override public void onChanged() { updates[0]++; }
            @Override public void onItemRangeChanged(int first, int count) { updates[0]++; }
        };
        ViewTreeObserver.OnPreDrawListener observer = () -> {
            frames.add(new long[]{SystemClock.uptimeMillis() - start[0], Math.round(panel.getAlpha() * 1000),
                    ((FolderTransition) field(activity, "folderMotion")).isRunning() ? 1 : 0});
            return true;
        };
        runOnMainSync(() -> {
            homeAdapter.registerAdapterDataObserver(changes);
            root.getViewTreeObserver().addOnPreDrawListener(observer);
            start[0] = SystemClock.uptimeMillis();
            action.run();
            cost[0] = SystemClock.uptimeMillis() - start[0];
        });
        settle();
        runOnMainSync(() -> {
            root.getViewTreeObserver().removeOnPreDrawListener(observer);
            homeAdapter.unregisterAdapterDataObserver(changes);
        });
        long first = -1, complete = -1;
        int intermediate = 0;
        for (long[] frame : frames) {
            if (frame[1] > 0 && frame[1] < 1000) {
                if (first < 0) first = frame[0];
                intermediate++;
            }
            if (frame[2] == 0 && complete < 0) complete = frame[0];
        }
        measurements.add(label + ": handler=" + cost[0] + "ms, first motion=" + first
                + "ms, complete=" + complete + "ms, intermediate frames=" + intermediate);
        check(first >= 0 && first < 180, label + " begins without a perceptible wait");
        check(complete > 0 && complete < 550, label + " finishes well below one second");
        check(intermediate >= 3, label + " renders intermediate frames");
        if (!opening) {
            check(cost[0] < 80 && updates[0] == 0, "Back does no Home list rebuild or screen capture");
            check(home.findViewHolderForAdapterPosition(homeAdapter.positionOf(folder)) == retained,
                    "Back keeps the original Home holder");
        }
    }

    /** Check actual rendered corner pixels, including the dock drawn beyond its parent's bounds. */
    private List<Point> cornerSamples() {
        List<Point> points = new ArrayList<>();
        runOnMainSync(() -> {
            Rect dock = new Rect();
            for (int i = 0; i < home.getChildCount(); i++) {
                View child = home.getChildAt(i), card = child.findViewById(R.id.card);
                if (card == null) continue;   // the App Library row has no tile
                if (card.isFocused()) continue;
                Rect bounds = new Rect();
                if (!card.getGlobalVisibleRect(bounds)) continue;
                int position = home.getChildAdapterPosition(child);
                if (position >= 0 && position < homeAdapter.columns()) dock.union(bounds);
                int radius = Math.round(AppAdapter.cornerRadius(card.getWidth(),
                        activity.getResources().getDisplayMetrics().density));
                for (int offset : new int[]{2, radius / 2, radius}) {
                    points.add(new Point(bounds.left + offset, bounds.top + offset));
                    points.add(new Point(bounds.right - offset - 1, bounds.top + offset));
                    points.add(new Point(bounds.left + offset, bounds.bottom - offset - 1));
                    points.add(new Point(bounds.right - offset - 1, bounds.bottom - offset - 1));
                }
            }
            if (!dock.isEmpty()) {
                View host = activity.findViewById(R.id.gridHost);
                int[] at = new int[2]; host.getLocationOnScreen(at);
                // These pixels belong to the rounded dock, but lie outside the Home alpha layer.
                for (int y : new int[]{dock.centerY() - 12, dock.centerY(), dock.centerY() + 12}) {
                    points.add(new Point(at[0] - 8, y));
                    points.add(new Point(at[0] + host.getWidth() + 8, y));
                }
            }
            Rect panel = new Rect(), title = new Rect();
            // The panel is GONE on Home, so use its precomputed layout parameters instead.
            View content = activity.findViewById(R.id.folderPanel);
            android.widget.FrameLayout.LayoutParams layout = (android.widget.FrameLayout.LayoutParams) content.getLayoutParams();
            int width = activity.findViewById(R.id.root).getWidth();
            panel.set(layout.getMarginStart() - 12, layout.topMargin - 12,
                    width - layout.getMarginEnd() + 12, layout.topMargin + layout.height + 12);
            int nameHeight = Math.round(76 * activity.getResources().getDisplayMetrics().density);
            title.set(panel.left, panel.top - nameHeight, panel.right, panel.top);
            points.removeIf(p -> panel.contains(p.x, p.y) || title.contains(p.x, p.y)
                    || p.x < 0 || p.y < 0 || p.x >= activity.findViewById(R.id.root).getWidth()
                    // A TV's persistent mute/volume OSD belongs to the system and does not fade.
                    || p.y >= activity.findViewById(R.id.root).getHeight() - 128);
        });
        check(points.size() >= 12, "Visible Home corners are available for pixel comparison");
        return points;
    }

    private void freeze(String name, boolean opening, Bitmap baseline, List<Point> corners) throws Exception {
        runOnMainSync(() -> {
            if (opening) activity.getCurrentFocus().performClick();
            else dispatch(KeyEvent.KEYCODE_BACK);
        });
        ValueAnimator[] value = new ValueAnimator[1];
        for (int i = 0; i < 40 && value[0] == null; i++) {
            runOnMainSync(() -> {
                value[0] = (ValueAnimator) field(field(activity, "folderMotion"), "animator");
                if (value[0] != null) value[0].pause();
            });
            if (value[0] == null) SystemClock.sleep(8);
        }
        check(value[0] != null, "An in-flight animation is available to inspect");
        for (float fraction : new float[]{.05f, .15f, .35f, .6f, .85f}) {
            float[] alpha = new float[1];
            runOnMainSync(() -> {
                value[0].setCurrentFraction(fraction);
                alpha[0] = activity.findViewById(R.id.folderBackdrop).getAlpha();
            });
            SystemClock.sleep(100);
            Bitmap shot = getUiAutomation().takeScreenshot();
            saveShot(shot, name + "-" + Math.round(fraction * 100));
            int maxError = 0;
            Point worst = null;
            for (Point p : corners) {
                int before = baseline.getPixel(p.x, p.y), actual = shot.getPixel(p.x, p.y);
                for (int shift : new int[]{0, 8, 16}) {
                    int expected = Math.round(((before >> shift) & 255) * (1f - alpha[0]));
                    int error = Math.abs(((actual >> shift) & 255) - expected);
                    if (error > maxError) { maxError = error; worst = p; }
                }
            }
            shot.recycle();
            measurements.add(name + " " + fraction + ": " + corners.size()
                    + " corner samples, maximum colour error=" + maxError + " at " + worst);
            check(maxError <= 12, name + " preserves Home's rounded edges through the black fade");
        }
        runOnMainSync(() -> value[0].resume());
        settle();
    }

    private void checkBlackBackdrop() throws Exception {
        Bitmap shot = getUiAutomation().takeScreenshot();
        saveShot(shot, "folder-open-black");
        check(shot.getPixel(shot.getWidth() / 4, 8) == Color.BLACK
                && shot.getPixel(shot.getWidth() * 3 / 4, 8) == Color.BLACK
                && shot.getPixel(8, shot.getHeight() / 2) == Color.BLACK,
                "The open folder has a solid black backdrop");
        shot.recycle();
    }

    private void saveShot(Bitmap shot, String name) throws Exception {
        File directory = new File(getTargetContext().getExternalFilesDir(null), "folder-motion");
        directory.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) {
            shot.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    private void dispatch(int code) {
        long time = SystemClock.uptimeMillis();
        activity.dispatchKeyEvent(new KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0));
        activity.dispatchKeyEvent(new KeyEvent(time, time + 1, KeyEvent.ACTION_UP, code, 0));
    }
    private void settle() { waitForIdleSync(); SystemClock.sleep(650); waitForIdleSync(); }
    private void check(boolean good, String message) { if (!good) throw new AssertionError(message); checks++; }
    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    private void invoke(String name, Class<?>[] signature, Object... args) {
        try {
            Method method = MainActivity.class.getDeclaredMethod(name, signature);
            method.setAccessible(true); method.invoke(activity, args);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
