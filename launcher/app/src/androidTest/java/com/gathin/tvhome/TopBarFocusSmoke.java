package com.gathin.tvhome;

import android.app.Instrumentation;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import java.util.Locale;

/** Renders the real header layouts without changing installed apps, preferences or TV inputs. */
public final class TopBarFocusSmoke extends Instrumentation {
    private int checks;

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Throwable[] failureOnMain = new Throwable[1];
            runOnMainSync(() -> {
                try {
                    for (String language : new String[]{"en", "he"}) {
                        for (int density : new int[]{240, 320}) {
                            for (String network : new String[]{"Wi-Fi", "A very long network name with VPN"}) {
                                renderHeader(language, density, network);
                            }
                        }
                    }
                } catch (Throwable failure) {
                    failureOnMain[0] = failure;
                }
            });
            if (failureOnMain[0] != null) throw failureOnMain[0];
            result.putString("stream", "PASS: " + checks + " focused header outlines rendered unclipped.\n");
            finish(-1, result);
        } catch (Throwable failure) {
            result.putString("stream", "FAIL after " + checks + " outlines: "
                    + android.util.Log.getStackTraceString(failure) + "\n");
            finish(0, result);
        }
    }

    private void renderHeader(String language, int density, String network) {
        Configuration config = new Configuration(getTargetContext().getResources().getConfiguration());
        config.setLocale(Locale.forLanguageTag(language));
        config.densityDpi = density;
        config.screenWidthDp = 960;
        config.screenHeightDp = 540;
        Context context = getTargetContext().createConfigurationContext(config);
        View root = LayoutInflater.from(context).inflate(R.layout.activity_main, null);
        ((TextView) root.findViewById(R.id.greeting)).setText("Good evening");
        ((TextView) root.findViewById(R.id.btnWifi)).setText(network);
        int width = 960 * density / 160, height = 540 * density / 160;
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        for (int id : new int[]{R.id.btnSettings, R.id.btnWifi, R.id.btnInputs}) {
            View button = root.findViewById(id);
            if (!button.requestFocus()) throw new AssertionError("Cannot focus header " + id);
            // The settled advanced-effects zoom from MainActivity's focus listener.
            button.setScaleX(1.04f);
            button.setScaleY(1.04f);
            int x = 0, y = 0;
            for (View ancestor = button; ancestor != root; ancestor = (View) ancestor.getParent()) {
                x += ancestor.getLeft();
                y += ancestor.getTop();
            }
            Bitmap actual = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Bitmap expected = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            try {
                root.draw(new Canvas(actual));
                Canvas reference = new Canvas(expected);
                reference.drawColor(Color.BLACK);
                reference.translate(x, y);
                reference.scale(button.getScaleX(), button.getScaleY(),
                        button.getPivotX(), button.getPivotY());
                button.draw(reference);
                int exterior = 0;
                for (int py = y - 4; py < y + button.getHeight() + 4; py++) {
                    for (int px = x - 8; px < x + button.getWidth() + 8; px++) {
                        if (px >= x && px < x + button.getWidth()
                                && py >= y && py < y + button.getHeight()) continue;
                        int target = expected.getPixel(px, py);
                        if (Color.red(target) < 180 || Color.green(target) < 180
                                || Color.blue(target) < 180) continue;
                        int pixel = actual.getPixel(px, py);
                        if (Math.abs(Color.red(pixel) - Color.red(target)) > 8
                                || Math.abs(Color.green(pixel) - Color.green(target)) > 8
                                || Math.abs(Color.blue(pixel) - Color.blue(target)) > 8) {
                            throw new AssertionError(language + " @" + density + "dpi "
                                    + context.getResources().getResourceEntryName(id)
                                    + " focus is clipped at " + px + "," + py);
                        }
                        exterior++;
                    }
                }
                if (exterior == 0) throw new AssertionError("No scaled outline pixels checked");
                checks++;
            } finally {
                actual.recycle();
                expected.recycle();
                button.setScaleX(1f);
                button.setScaleY(1f);
            }
        }
    }
}
