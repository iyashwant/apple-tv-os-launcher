package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import java.util.ArrayList;
import java.util.List;

/** Rebuild the three header materials only after their background or geometry has settled. */
final class HeaderGlass {
    interface Scene { void draw(Canvas canvas); }

    private final View root, scene;
    private final ViewGroup buttons;
    private final float density;
    private boolean active;
    private final Runnable refresh = this::capture;

    HeaderGlass(View root, ViewGroup buttons, Scene renderer) {
        this.root = root;
        this.buttons = buttons;
        density = root.getResources().getDisplayMetrics().density;
        scene = new View(root.getContext()) {
            @Override protected void onDraw(Canvas canvas) { renderer.draw(canvas); }
        };
        buttons.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (l != ol || t != ot || r != or || b != ob) schedule();
        });
    }

    void setActive(boolean value) {
        active = value;
        root.removeCallbacks(refresh);
        if (value) schedule();
    }

    void schedule() {
        root.removeCallbacks(refresh);
        if (active) root.postDelayed(refresh, 650);
    }

    private void capture() {
        if (!active || !buttons.isShown() || root.getWidth() == 0 || root.getHeight() == 0) return;
        List<View> visible = new ArrayList<>();
        List<Rect> regions = new ArrayList<>();
        Rect capture = new Rect();
        for (int i = 0; i < buttons.getChildCount(); i++) {
            View button = buttons.getChildAt(i);
            if (!button.isShown() || button.getWidth() == 0 || button.getHeight() == 0) continue;
            Rect region = new Rect(0, 0, button.getWidth(), button.getHeight());
            // Coordinates belong to the resting layout, not a focused scale.
            ((ViewGroup) root).offsetDescendantRectToMyCoords(button, region);
            capture.union(region);
            visible.add(button);
            regions.add(region);
        }
        if (visible.isEmpty()) return;
        int padding = Math.round(36 * density);
        capture.inset(-padding, -padding);
        capture.intersect(0, 0, root.getWidth(), root.getHeight());
        scene.layout(0, 0, root.getWidth(), root.getHeight());
        // One tiny strip, shared by every capsule. No controls, labels or old glass are captured.
        Bitmap backdrop = FolderBackdrop.capture(scene, capture, Math.max(1, capture.width() / 8),
                Math.max(1, capture.height() / 8), 6);
        for (int i = 0; i < visible.size(); i++) {
            View button = visible.get(i);
            button.setBackground(new FrostedGlassDrawable(backdrop,
                    FrostedGlassDrawable.crop(regions.get(i), capture, backdrop), density,
                    button.getHeight() / (2 * density)));
        }
        // Old snapshots are left to the render thread/GC; a previously queued frame may still use one.
    }
}
