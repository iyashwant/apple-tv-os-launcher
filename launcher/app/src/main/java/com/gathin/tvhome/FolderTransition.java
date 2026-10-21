package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.PathInterpolator;

/** Live GPU motion: rounded tile outlines stay native; no full-screen software copies. */
final class FolderTransition {
    private final View root, backdrop, home;
    private final View[] content;
    private final boolean reducedMotion;
    /** Launcher settings' Animations switch: off, the folder opens and closes in place on the next frame. */
    private final boolean animated;
    private ValueAnimator animator;
    private ViewTreeObserver.OnPreDrawListener pending;
    private boolean running;
    private float progress;

    FolderTransition(View root, View backdrop, View home, boolean reducedMotion, boolean animated, View... content) {
        this.root = root;
        this.backdrop = backdrop;
        this.home = home;
        this.reducedMotion = reducedMotion;
        this.animated = animated;
        this.content = content;
    }

    boolean isRunning() { return running; }

    void start(boolean opening, Runnable finished) {
        float from = running ? progress : opening ? 0f : 1f;
        cancel();
        running = true;
        apply(from);
        pending = () -> {
            root.getViewTreeObserver().removeOnPreDrawListener(pending);
            pending = null;
            for (View view : content) if (view.getVisibility() == View.VISIBLE)
                view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            animator = ValueAnimator.ofFloat(from, opening ? 1f : 0f);
            animator.setDuration(!animated ? 0 : reducedMotion ? 120 : opening ? 260 : 220);
            animator.setInterpolator(new PathInterpolator(.2f, 0f, .2f, 1f));
            animator.addUpdateListener(value -> apply((float) value.getAnimatedValue()));
            animator.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    animator = null;
                    running = false;
                    for (View view : content) view.setLayerType(View.LAYER_TYPE_NONE, null);
                    finished.run();
                }
            });
            animator.start();
            return true;
        };
        root.getViewTreeObserver().addOnPreDrawListener(pending);
        root.invalidate();
    }

    private void apply(float value) {
        progress = value;
        backdrop.setAlpha(value);
        // The opaque copy of Home's live background crossfades its tiles out. Fading the Home ViewGroup too
        // creates a bounded alpha layer: Android cuts off the dock, tile shadows and
        // rounded edges drawn outside that group's bounds, despite clipChildren=false.
        // Keep its native rendering intact underneath the incoming backdrop.
        home.setAlpha(1f);
        float scale = reducedMotion || !animated ? 1f : .96f + .04f * value;
        for (View view : content) {
            view.setAlpha(value);
            view.setScaleX(scale);
            view.setScaleY(scale);
        }
    }

    void cancel() {
        if (pending != null) {
            root.getViewTreeObserver().removeOnPreDrawListener(pending);
            pending = null;
        }
        if (animator != null) {
            animator.removeAllListeners();
            animator.removeAllUpdateListeners();
            animator.cancel();
            animator = null;
        }
        running = false;
        home.setAlpha(1f);
        for (View view : content) {
            view.setLayerType(View.LAYER_TYPE_NONE, null);
            view.setAlpha(1f);
            view.setScaleX(1f);
            view.setScaleY(1f);
        }
    }
}
