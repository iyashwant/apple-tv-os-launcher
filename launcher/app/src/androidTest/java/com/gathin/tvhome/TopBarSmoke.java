package com.gathin.tvhome;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import androidx.recyclerview.widget.RecyclerView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Emulator-only coverage for every header button and late platform focus restoration. */
public final class TopBarSmoke extends Instrumentation {
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
                throw new IllegalStateException("Synthetic header fixtures must run on an emulator");
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            settle();
            runOnMainSync(() -> {
                try { adapter = LauncherSmoke.seedApps(activity, 43); }
                catch (Exception e) { throw new RuntimeException(e); }
                activity.findViewById(R.id.btnInputs).setVisibility(View.VISIBLE);
            });
            settle();
            int[] buttons = {R.id.btnWifi, R.id.btnInputs, R.id.btnSettings};
            for (int column = 0; column < 6; column++) for (int button = 0; button < 3; button++) {
                home(column);
                key(KeyEvent.KEYCODE_DPAD_UP, 60);
                for (int move = 0; move < button; move++) key(KeyEvent.KEYCODE_DPAD_RIGHT, 60);
                check(activity.findViewById(buttons[button]).isFocused(), "Reach header button " + button);
                key(KeyEvent.KEYCODE_DPAD_DOWN, 80);
                expect(column, "Down from button " + button + " restores column " + column);
                key(KeyEvent.KEYCODE_DPAD_DOWN, 80);
                expect(column + 6, "Second Down reaches next row after button " + button);
            }
            for (int column = 0; column < 6; column++) {
                home(column);
                runOnMainSync(() -> {
                    dispatch(KeyEvent.KEYCODE_DPAD_UP);
                    dispatch(KeyEvent.KEYCODE_DPAD_RIGHT);
                    dispatch(KeyEvent.KEYCODE_DPAD_RIGHT);
                    dispatch(KeyEvent.KEYCODE_DPAD_DOWN);
                });
                settle();
                expect(column, "Rapid Up Right Right Down returns to dock");
            }
            // Layout/focus recovery can restore a previous header view after Down requested a tile.
            // Verify the user's Down intent survives that late restoration, rather than being lost.
            for (int button = 0; button < 3; button++) {
                home(4);
                key(KeyEvent.KEYCODE_DPAD_UP, 60);
                for (int move = 0; move < button; move++) key(KeyEvent.KEYCODE_DPAD_RIGHT, 60);
                final View header = activity.findViewById(buttons[button]);
                runOnMainSync(() -> {
                    dispatch(KeyEvent.KEYCODE_DPAD_DOWN);
                    header.requestFocusFromTouch();
                });
                settle();
                expect(4, "Down survives late header focus restoration for button " + button);
            }
            // Boxes without Inputs must retain both remaining paths and their boundaries.
            home(2);
            runOnMainSync(() -> activity.findViewById(R.id.btnInputs).setVisibility(View.GONE));
            key(KeyEvent.KEYCODE_DPAD_UP, 80);
            check(activity.findViewById(R.id.btnWifi).isFocused(), "Wi-Fi is first when Inputs is hidden");
            key(KeyEvent.KEYCODE_DPAD_LEFT, 60);
            check(activity.findViewById(R.id.btnWifi).isFocused(), "Left cannot escape hidden Inputs edge");
            key(KeyEvent.KEYCODE_DPAD_RIGHT, 60);
            check(activity.findViewById(R.id.btnSettings).isFocused(), "Right reaches Settings");
            key(KeyEvent.KEYCODE_DPAD_RIGHT, 60);
            check(activity.findViewById(R.id.btnSettings).isFocused(), "Right cannot escape Settings edge");
            key(KeyEvent.KEYCODE_DPAD_DOWN, 80);
            expect(2, "Settings Down works with Inputs hidden");
            result.putString("stream", "PASS: " + checks + " header navigation checks.\n");
            finish(-1, result);
        } catch (Throwable failure) {
            result.putString("stream", "FAIL after " + checks + " header checks: "
                    + android.util.Log.getStackTraceString(failure) + "\n" + state());
            finish(0, result);
        }
    }
    private void home(int column) {
        runOnMainSync(() -> activity.onNewIntent(new Intent(Intent.ACTION_MAIN)));
        settle();
        runOnMainSync(() -> invoke("focusTile", new Class<?>[]{int.class}, column));
        settle();
    }
    private void key(int code, long delay) {
        sendKeyDownUpSync(code);
        SystemClock.sleep(delay);
        waitForIdleSync();
    }
    private void burst(int code, int count) {
        runOnMainSync(() -> { for (int i = 0; i < count; i++) dispatch(code); });
        settle();
    }
    private void dispatch(int code) {
        long time = SystemClock.uptimeMillis();
        activity.dispatchKeyEvent(new KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0));
        activity.dispatchKeyEvent(new KeyEvent(time, time + 1, KeyEvent.ACTION_UP, code, 0));
    }
    private void settle() { waitForIdleSync(); SystemClock.sleep(650); waitForIdleSync(); }
    private void expect(int position, String action) {
        int[] actual = {-1};
        runOnMainSync(() -> {
            RecyclerView grid = activity.findViewById(R.id.grid);
            View focused = activity.getCurrentFocus();
            RecyclerView.ViewHolder holder = focused == null ? null : grid.findContainingViewHolder(focused);
            if (holder != null) actual[0] = holder.getBindingAdapterPosition();
        });
        if (actual[0] != position) throw new AssertionError(action + ": wanted " + position
                + ", got " + actual[0] + "; " + state());
        checks++;
        if (checks % 12 == 0) {
            Bundle progress = new Bundle();
            progress.putString("stream", "Passed " + checks + ": " + action + "\n");
            sendStatus(0, progress);
        }
    }
    private void check(boolean success, String action) {
        if (!success) throw new AssertionError(action + "; " + state());
        checks++;
    }
    private String state() {
        try {
            return "focused=" + activity.getCurrentFocus() + ", last=" + field("lastFocused")
                    + ", requested=" + field("requestedFocusKey") + ", transition=" + field("gridTransition")
                    + ", expanded=" + field("expanded");
        } catch (Exception e) { return e.toString(); }
    }
    private Object field(String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(activity);
    }
    private void invoke(String name, Class<?>[] types, Object... args) {
        try {
            Method method = MainActivity.class.getDeclaredMethod(name, types);
            method.setAccessible(true); method.invoke(activity, args);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
