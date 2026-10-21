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
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import java.lang.reflect.Method;

/** The glass must leave breathing room around both enlarged end tiles, at every supported column count. */
public final class DockPaddingSmoke extends Instrumentation {
    private MainActivity home;
    private int checks;
    private float minimumGap = Float.MAX_VALUE;

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_OK;
        SharedPreferences choices = getTargetContext().getSharedPreferences(Config.SETTINGS_NAME, 0);
        boolean hadColumns = choices.contains("columns");
        int originalColumns = choices.getInt("columns", 6);
        try {
            if (!android.os.Build.PRODUCT.contains("sdk")) throw new IllegalStateException("Synthetic dock fixture requires an emulator");
            Config.load(getTargetContext());
            for (int columns = 4; columns <= 7; columns++) {
                choices.edit().putInt("columns", columns).commit();
                home = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                runOnMainSync(() -> {
                    try { LauncherSmoke.seedApps(home, 24); }
                    catch (Exception e) { throw new RuntimeException(e); }
                });
                settle();
                for (int position : new int[]{0, columns - 1}) {
                    final int focused = position;
                    runOnMainSync(() -> invoke("focusTile", int.class, focused));
                    settle();
                    runOnMainSync(() -> measureGap(focused));
                }
                runOnMainSync(home::finish);
                settle();
                home = null;
            }
            result.putString("stream", "PASS: " + checks + " dock edge checks; minimum focused gap " + minimumGap + "dp.\n");
        } catch (Throwable failure) {
            code = Activity.RESULT_CANCELED;
            result.putString("stream", "FAIL after " + checks + " dock checks: " + android.util.Log.getStackTraceString(failure));
        } finally {
            if (home != null) runOnMainSync(home::finish);
            SharedPreferences.Editor edit = choices.edit();
            if (hadColumns) edit.putInt("columns", originalColumns); else edit.remove("columns");
            edit.commit();
        }
        finish(code, result);
    }

    private void measureGap(int position) {
        RecyclerView grid = home.findViewById(R.id.grid);
        View item = grid.findViewHolderForAdapterPosition(position).itemView;
        View card = item.findViewById(R.id.card);
        if (!card.isFocused()) throw new AssertionError("Edge tile did not receive focus: " + position);
        Bitmap glass = Bitmap.createBitmap(grid.getWidth(), grid.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            grid.getItemDecorationAt(0).onDraw(new Canvas(glass), grid, new RecyclerView.State());
            int y = Math.round(item.getTop() + card.getTop() + card.getHeight() / 2f);
            int left = 0, right = glass.getWidth() - 1;
            while (left < right && Color.alpha(glass.getPixel(left, y)) < 16) left++;
            while (right > left && Color.alpha(glass.getPixel(right, y)) < 16) right--;
            float outset = card.getWidth() * (card.getScaleX() - 1f) / 2f;
            float tileLeft = item.getLeft() + card.getLeft() - outset;
            float tileRight = item.getLeft() + card.getLeft() + card.getWidth() + outset;
            int columns = ((AppAdapter) grid.getAdapter()).columns();
            float density = home.getResources().getDisplayMetrics().density;
            float gap = (position == 0 ? tileLeft - left : right - tileRight) / density;
            minimumGap = Math.min(minimumGap, gap);
            if (gap < 8f) throw new AssertionError(columns + " columns, edge " + position + ": only " + gap + "dp inside dock");
            checks++;
            if (left < 1 || right >= grid.getWidth() - 1) throw new AssertionError("Dock border reaches viewport clip");
            checks++;
        } finally { glass.recycle(); }
    }

    private void invoke(String name, Class<?> type, Object argument) {
        try { Method method = MainActivity.class.getDeclaredMethod(name, type); method.setAccessible(true); method.invoke(home, argument); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private void settle() { waitForIdleSync(); SystemClock.sleep(450); waitForIdleSync(); }
}
