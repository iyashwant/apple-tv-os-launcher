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

/** Remote focus regressions, including input that arrives before layout/scroll completes. Emulator only. */
public final class NavigationSmoke extends Instrumentation {
    private MainActivity activity;
    private AppAdapter adapter;
    private int checks;
    private boolean skipMatrix;
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        skipMatrix = "true".equals(args.getString("skipMatrix"));
        start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (!android.os.Build.FINGERPRINT.contains("generic")
                    && !android.os.Build.MODEL.contains("Emulator")
                    && !android.os.Build.PRODUCT.contains("sdk"))
                throw new IllegalStateException("Synthetic navigation fixtures must run on an emulator");
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            settle();
            runOnMainSync(() -> {
                try { adapter = LauncherSmoke.seedApps(activity, 43); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            settle();
            // Every column must cross every row, including recycled/off-screen rows and a short last row.
            for (int column = 0; !skipMatrix && column < 6; column++) {
                home(column);
                int expected = column;
                for (int row = 0; row < 7; row++) {
                    key(KeyEvent.KEYCODE_DPAD_DOWN, 90);
                    expected = AppOrder.target(expected, 43, 6, AppOrder.Direction.DOWN);
                    expect(expected, "Down column " + column + " row " + row);
                }
                for (int row = 0; row < 7; row++) {
                    key(KeyEvent.KEYCODE_DPAD_UP, 90);
                    expected = AppOrder.target(expected, 43, 6, AppOrder.Direction.UP);
                    expect(expected, "Up column " + column + " row " + row);
                }
            }
            // A burst can be delivered in the same frame on a busy TV; it must advance from intent,
            // rather than repeatedly target the next row from the still-focused old child.
            home(2);
            burst(KeyEvent.KEYCODE_DPAD_DOWN, 5);
            expect(32, "Five queued Down presses during expansion");
            burst(KeyEvent.KEYCODE_DPAD_UP, 4);
            expect(8, "Four queued Up presses across recycled rows");
            burst(KeyEvent.KEYCODE_DPAD_RIGHT, 3);
            expect(11, "Queued Right stops at row boundary");
            burst(KeyEvent.KEYCODE_DPAD_LEFT, 6);
            expect(6, "Queued Left stops at row boundary");
            burst(KeyEvent.KEYCODE_DPAD_DOWN, 12);
            // The App Library row follows the final row: held Down ends there, and Up comes back to the same column.
            expect(43, "Held Down passes the final row and stops on the App Library");
            key(KeyEvent.KEYCODE_DPAD_UP, 100);
            expect(42, "Up from the App Library returns to the final row's column");
            key(KeyEvent.KEYCODE_DPAD_UP, 100);
            expect(36, "Up leaves short final row");
            // Home during a layout transition must cancel its old deferred focus work.
            home(4);
            runOnMainSync(() -> {
                dispatch(KeyEvent.KEYCODE_DPAD_DOWN);
                activity.onNewIntent(new Intent(Intent.ACTION_MAIN));
            });
            settle();
            expect(0, "Home cancels pending row request");
            key(KeyEvent.KEYCODE_DPAD_DOWN, 100);
            expect(6, "Navigation resumes after interrupted expansion");
            // A holder may already exist while its card temporarily cannot accept focus.
            home(0);
            runOnMainSync(() -> {
                RecyclerView grid = (RecyclerView) fieldUnchecked("grid");
                View target = grid.findViewHolderForAdapterPosition(1).itemView.findViewById(R.id.card);
                target.setFocusable(false);
                dispatch(KeyEvent.KEYCODE_DPAD_RIGHT);
                target.postDelayed(() -> target.setFocusable(true), 100);
            });
            settle();
            expect(1, "Retry attached card that initially refuses focus");
            check(field("requestedFocusKey") == null, "Successful retry clears pending request");
            // Top-bar round trips must return to the remembered dock column.
            key(KeyEvent.KEYCODE_DPAD_UP, 100);
            check(activity.findViewById(R.id.topBar).hasFocus(), "Up from dock reaches top bar");
            key(KeyEvent.KEYCODE_DPAD_DOWN, 100);
            expect(1, "Down from top bar restores dock column");
            setInTouchMode(true);
            settle();
            runOnMainSync(() -> invoke("focusTile", new Class<?>[]{int.class}, 1));
            settle();
            expect(1, "Explicit restoration exits touch mode");
            key(KeyEvent.KEYCODE_DPAD_DOWN, 100);
            expect(7, "Remote Down works after touch-mode restoration");
            home(3);
            runOnMainSync(() -> {
                long time = SystemClock.uptimeMillis();
                for (int i = 0; i < 5; i++) activity.dispatchKeyEvent(new KeyEvent(time, time + i * 30,
                        KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, i));
                activity.dispatchKeyEvent(new KeyEvent(time, time + 160,
                        KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN, 0));
            });
            settle();
            expect(33, "Held-key repeats advance while rows attach");
            // Mixed directions must also use the latest logical target, not an old visible row.
            home(1);
            runOnMainSync(() -> {
                dispatch(KeyEvent.KEYCODE_DPAD_DOWN); dispatch(KeyEvent.KEYCODE_DPAD_DOWN);
                dispatch(KeyEvent.KEYCODE_DPAD_RIGHT); dispatch(KeyEvent.KEYCODE_DPAD_DOWN);
                dispatch(KeyEvent.KEYCODE_DPAD_LEFT); dispatch(KeyEvent.KEYCODE_DPAD_UP);
            });
            settle();
            expect(13, "Mixed queued directions preserve row and column");
            java.util.List<String> beforeMove = new java.util.ArrayList<>(adapter.keys());
            runOnMainSync(() -> activity.onTileLongPressed(13, activity.getCurrentFocus()));
            settle();
            key(KeyEvent.KEYCODE_DPAD_CENTER, 100);
            check(field("movingKey") != null, "Action menu starts rearranging");
            burst(KeyEvent.KEYCODE_DPAD_DOWN, 4);
            expect(37, "Queued Down carries moving app across rows");
            check(beforeMove.get(13).equals(adapter.keyAt(37)), "Rearrangement follows app identity");
            key(KeyEvent.KEYCODE_BACK, 100);
            settle();
            check(beforeMove.equals(adapter.keys()), "Cancel restores order after vertical rearranging");
            expect(13, "Cancel restores focus to moved app's original row");
            key(KeyEvent.KEYCODE_DPAD_UP, 100);
            expect(7, "Normal row navigation resumes after rearranging");
            // Folder spans differ from Home; include enough apps to recycle folder rows.
            final String[] folder = new String[1];
            runOnMainSync(() -> {
                HomeLayout layout = adapter.snapshotLayout();
                java.util.List<String> keys = adapter.keys();
                folder[0] = layout.createFolder(keys.get(0), "Navigation fixture");
                for (int i = 1; i < 14; i++) layout.moveTo(keys.get(i), folder[0]);
                adapter.restoreLayout(layout);
                activity.onFolderOpened(folder[0]);
            });
            settle();
            for (int column = 0; column < AppAdapter.FOLDER_COLUMNS; column++) {
                final int c = column;
                runOnMainSync(() -> invoke("focusTile", new Class<?>[]{int.class}, c));
                settle();
                int expected = c;
                for (int row = 0; row < (14 + AppAdapter.FOLDER_COLUMNS - 1) / AppAdapter.FOLDER_COLUMNS - 1; row++) {
                    key(KeyEvent.KEYCODE_DPAD_DOWN, 90);
                    expected = AppOrder.centeredTarget(expected, 14, AppAdapter.FOLDER_COLUMNS, AppOrder.Direction.DOWN);
                    expect(expected, "Folder Down column " + c + " row " + row);
                }
                for (int row = 0; row < (14 + AppAdapter.FOLDER_COLUMNS - 1) / AppAdapter.FOLDER_COLUMNS - 1; row++) {
                    key(KeyEvent.KEYCODE_DPAD_UP, 90);
                    expected = AppOrder.centeredTarget(expected, 14, AppAdapter.FOLDER_COLUMNS, AppOrder.Direction.UP);
                    expect(expected, "Folder Up column " + c + " row " + row);
                }
                key(KeyEvent.KEYCODE_DPAD_UP, 90);
                check(activity.findViewById(R.id.folderTitle).isFocused(), "Folder Up reaches title");
                key(KeyEvent.KEYCODE_DPAD_DOWN, 90);
                expect(expected, "Folder title returns to same column");
            }
            key(KeyEvent.KEYCODE_BACK, 100);
            check(((AppAdapter) field("adapter")).activeFolder() == null, "Back closes navigated folder");
            // A Home intent delivered while paused must discard both pending navigation and folder.
            runOnMainSync(() -> {
                callActivityOnPause(activity);
                activity.onNewIntent(new Intent(Intent.ACTION_MAIN));
                callActivityOnResume(activity);
            });
            settle();
            expect(0, "Resume after Home starts at first dock tile");
            key(KeyEvent.KEYCODE_DPAD_DOWN, 100);
            expect(AppOrder.target(0, adapter.appCount(), adapter.columns(), AppOrder.Direction.DOWN),
                    "Rows remain navigable after resume");
            result.putString("stream", "PASS: " + checks + " navigation checks.\n");
            finish(-1, result);
        } catch (Throwable failure) {
            result.putString("stream", "FAIL after " + checks + " navigation checks: "
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
            RecyclerView grid = (RecyclerView) fieldUnchecked("grid");
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
    private Object fieldUnchecked(String name) {
        try { return field(name); } catch (Exception e) { throw new RuntimeException(e); }
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
