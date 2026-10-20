package com.gathin.tvhome;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.PopupWindow;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Emulator-only menu material, edge placement, remote focus and move decoration regression checks. */
public final class MenuSmoke extends Instrumentation {
    private MainActivity activity;
    private AppAdapter adapter;
    private int checks;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (!android.os.Build.FINGERPRINT.contains("generic")
                    && !android.os.Build.MODEL.contains("Emulator")
                    && !android.os.Build.PRODUCT.contains("sdk"))
                throw new IllegalStateException("Synthetic menu checks must run on an emulator");
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            settle();
            runOnMainSync(() -> {
                try { adapter = LauncherSmoke.seedApps(activity, 29); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            settle();
            verifyBlur();
            List<String> before = new ArrayList<>(adapter.keys());
            for (int position : new int[]{0, 1, 2, 3, 4, 5, 8, 28}) {
                focus(position);
                View card = activity.getCurrentFocus();
                runOnMainSync(() -> activity.onTileLongPressed(position, card));
                settle();
                PopupWindow menu = (PopupWindow) field("tileMenu");
                check(menu != null && menu.isShowing(), "Long press opens menu at " + position);
                check(menu.getContentView().getBackground() instanceof FrostedGlassDrawable,
                        "Menu uses a captured blur, not a translucent fill");
                Rect bounds = new Rect();
                menu.getContentView().getGlobalVisibleRect(bounds);
                check(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= 1920 && bounds.bottom <= 1080,
                        "Menu fits on screen at " + position);
                check(menu.getContentView().findFocus() instanceof TextView
                        && ((TextView) menu.getContentView().findFocus()).getText().toString()
                        .equals(activity.getString(R.string.move_app)), "First action receives focus");
                if (position == 8) capture("menu");
                key(KeyEvent.KEYCODE_DPAD_UP);
                check(((TextView) menu.getContentView().findFocus()).getText().toString()
                        .equals(activity.getString(R.string.move_app)), "Up stays at first action");
                // With the App Library on, Remove from Home follows App settings as the last action.
                boolean library = (Boolean) field("appLibraryEnabled");
                key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_DOWN);
                key(KeyEvent.KEYCODE_DPAD_DOWN);
                check(((TextView) menu.getContentView().findFocus()).getText().toString()
                        .equals(activity.getString(library ? R.string.library_remove_shortcut : R.string.app_settings)),
                        "Down stays within last action");
                key(KeyEvent.KEYCODE_BACK);
                check(field("tileMenu") == null && adapter.keyAt(position).equals(field("lastFocusedKey")),
                        "Back returns to original app");
            }
            focus(8);
            runOnMainSync(() -> activity.onTileLongPressed(8, activity.getCurrentFocus()));
            settle(); key(KeyEvent.KEYCODE_DPAD_CENTER);
            check(field("movingKey") != null, "Move action starts rearranging");
            check(activity.getCurrentFocus().getForeground() == null, "No dashed or solid border covers app artwork");
            check(((View) field("moveIndicator")).getVisibility() == View.VISIBLE, "Glass direction handles appear");
            capture("moving");
            String moving = (String) field("movingKey");
            key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_RIGHT);
            check(adapter.positionOf(moving) == 15, "Move crosses rows and columns");
            check(activity.getCurrentFocus().getForeground() == null, "Rebinding moving tile cannot restore dashed border");
            key(KeyEvent.KEYCODE_BACK);
            check(before.equals(adapter.keys()), "Cancel preserves complete original order");
            check(((View) field("moveIndicator")).getVisibility() == View.GONE, "Cancel removes handles");
            focus(28);
            runOnMainSync(() -> activity.onTileLongPressed(28, activity.getCurrentFocus()));
            settle(); key(KeyEvent.KEYCODE_DPAD_CENTER); key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_DOWN);
            check(adapter.positionOf((String) field("movingKey")) == 28, "Move respects partial last-row boundary");
            key(KeyEvent.KEYCODE_DPAD_CENTER);
            check(field("movingKey") == null && ((View) field("moveIndicator")).getVisibility() == View.GONE,
                    "Save clears move presentation");
            check(before.equals(adapter.keys()), "Unchanged save preserves order");
            verifyDockDuringScrolledMove();
            result.putString("stream", "PASS: " + checks + " menu and move checks.\n");
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL after " + checks + " checks: " + android.util.Log.getStackTraceString(error));
            finish(0, result);
        }
    }

    private void verifyDockDuringScrolledMove() throws Exception {
        focus(8);
        runOnMainSync(() -> activity.onTileLongPressed(8, activity.getCurrentFocus()));
        settle(); key(KeyEvent.KEYCODE_DPAD_CENTER);
        String moving = (String) field("movingKey");
        RecyclerView grid = activity.findViewById(R.id.grid);
        View root = activity.findViewById(R.id.root);
        List<String> misplaced = new ArrayList<>();
        int[] samples = {0};
        android.view.ViewTreeObserver.OnPreDrawListener observer = () -> {
            // Draw only the decoration so tile artwork cannot hide a band in the wrong row.
            Bitmap image = Bitmap.createBitmap(grid.getWidth(), grid.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(image);
            for (int i = 0; i < grid.getItemDecorationCount(); i++)
                if (grid.getItemDecorationAt(i) instanceof DockDecoration)
                    grid.getItemDecorationAt(i).onDraw(canvas, grid, new RecyclerView.State());
            int bottom = -1;
            for (int i = 0; i < grid.getChildCount(); i++) {
                View child = grid.getChildAt(i);
                int position = grid.getChildAdapterPosition(child);
                if (position < 0 || position >= adapter.columns()) continue;
                View card = child.findViewById(R.id.card);
                bottom = Math.max(bottom, child.getTop() + card.getTop() + card.getHeight());
            }
            int pad = Math.round(22 * activity.getResources().getDisplayMetrics().density);
            for (int y = Math.max(0, bottom + pad); y < image.getHeight(); y += 3) {
                if (android.graphics.Color.alpha(image.getPixel(image.getWidth() / 2, y)) != 0) {
                    misplaced.add("band at y=" + y + ", dock bottom=" + bottom);
                    break;
                }
            }
            image.recycle();
            samples[0]++;
            return true;
        };
        runOnMainSync(() -> {
            root.getViewTreeObserver().addOnPreDrawListener(observer);
            long now = SystemClock.uptimeMillis();
            activity.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, 0));
            activity.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN, 0));
            // Focus can scroll the viewport during the same traversal as the reordered layout.
            grid.scrollBy(0, Math.round(96 * activity.getResources().getDisplayMetrics().density));
        });
        settle();
        runOnMainSync(() -> root.getViewTreeObserver().removeOnPreDrawListener(observer));
        capture("move-third-row");
        check(adapter.positionOf(moving) == 14, "Second-row Move reaches the third row");
        check(samples[0] > 3, "Row move samples multiple animation frames");
        check(misplaced.isEmpty(), "Dock backdrop stays with first row during a scrolled move: " + misplaced);
        key(KeyEvent.KEYCODE_BACK);
    }

    private void verifyBlur() {
        runOnMainSync(() -> {
            View checker = new View(activity) {
                final Paint paint = new Paint();
                @Override protected void onDraw(Canvas canvas) {
                    for (int y = 0; y < 180; y += 12) for (int x = 0; x < 260; x += 12) {
                        paint.setColor(((x / 12 + y / 12) % 2 == 0) ? 0xFFFFFFFF : 0xFF000000);
                        canvas.drawRect(x, y, x + 12, y + 12, paint);
                    }
                }
            };
            checker.layout(0, 0, 260, 180);
            Bitmap original = Bitmap.createBitmap(260, 180, Bitmap.Config.ARGB_8888);
            checker.draw(new Canvas(original));
            FrostedGlassDrawable material = new FrostedGlassDrawable(checker, new Rect(0, 0, 260, 180), 1, 26);
            material.setBounds(0, 0, 260, 180);
            Bitmap blurred = Bitmap.createBitmap(260, 180, Bitmap.Config.ARGB_8888);
            material.draw(new Canvas(blurred));
            check(energy(blurred) * 5 < energy(original), "Glass removes sharp high-frequency backdrop details");
            check(android.graphics.Color.alpha(blurred.getPixel(130, 90)) == 255,
                    "Menu backdrop fully replaces sharp content underneath");
            check(android.graphics.Color.alpha(blurred.getPixel(0, 0)) == 0, "Glass clips to rounded silhouette");
            original.recycle(); blurred.recycle();
        });
    }
    private long energy(Bitmap image) {
        long sum = 0;
        for (int y = 30; y < 150; y++) for (int x = 30; x < 230; x++)
            sum += Math.abs(android.graphics.Color.red(image.getPixel(x, y))
                    - android.graphics.Color.red(image.getPixel(x - 1, y)));
        return sum;
    }
    private void focus(int position) {
        runOnMainSync(() -> {
            try {
                Method expand = MainActivity.class.getDeclaredMethod("setExpanded", boolean.class);
                expand.setAccessible(true); expand.invoke(activity, position >= adapter.columns());
                Method method = MainActivity.class.getDeclaredMethod("focusTile", int.class);
                method.setAccessible(true); method.invoke(activity, position);
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        settle();
    }
    private Object field(String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(activity);
    }
    private void key(int code) { sendKeyDownUpSync(code); settle(); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(600); waitForIdleSync(); }
    private void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
    private void capture(String name) throws Exception {
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        File directory = new File(getTargetContext().getExternalFilesDir(null), "menu-smoke");
        directory.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        bitmap.recycle();
    }
}
