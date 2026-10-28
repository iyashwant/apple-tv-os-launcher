package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

/** A cached, diffused backdrop with a lightly tinted frost and a soft continuous rim. */
final class FrostedGlassDrawable extends Drawable {
    private final Bitmap backdrop;
    private final Rect source;
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint frost = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selection = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF rect = new RectF(), inner = new RectF();
    private final float radius, density;
    private final boolean focusFill;
    private boolean focused;
    private int alpha = 255;

    FrostedGlassDrawable(View home, Rect region, float density, float radiusDp) {
        this.density = density;
        radius = radiusDp * density;
        focusFill = false;
        // Include the surroundings: the blur must diffuse colour across the panel's edge instead
        // of smearing a cropped logo against it. The cache is still only a few dozen pixels wide.
        int padding = Math.round(36 * density);
        Rect capture = new Rect(Math.max(0, region.left - padding), Math.max(0, region.top - padding),
                Math.min(home.getWidth(), region.right + padding),
                Math.min(home.getHeight(), region.bottom + padding));
        backdrop = FolderBackdrop.capture(home, capture, Math.max(1, capture.width() / 8),
                Math.max(1, capture.height() / 8), 6);
        source = crop(region, capture, backdrop);
        configurePaints();
    }

    /** Header capsules share one small scene-only snapshot; their labels never enter the blur. */
    FrostedGlassDrawable(Bitmap backdrop, Rect source, float density, float radiusDp) {
        this.backdrop = backdrop;
        this.source = new Rect(source);
        this.density = density;
        radius = radiusDp * density;
        focusFill = true;
        configurePaints();
    }

    static Rect crop(Rect region, Rect capture, Bitmap bitmap) {
        float x = bitmap.getWidth() / (float) Math.max(1, capture.width());
        float y = bitmap.getHeight() / (float) Math.max(1, capture.height());
        return new Rect(Math.max(0, Math.round((region.left - capture.left) * x)),
                Math.max(0, Math.round((region.top - capture.top) * y)),
                Math.min(bitmap.getWidth(), Math.max(1, Math.round((region.right - capture.left) * x))),
                Math.min(bitmap.getHeight(), Math.max(1, Math.round((region.bottom - capture.top) * y))));
    }

    private void configurePaints() {
        frost.setColor(0x38FFFFFF);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(.75f * density);
        innerRim.setStyle(Paint.Style.STROKE);
        innerRim.setStrokeWidth(.4f * density);
        innerRim.setColor(0x1AFFFFFF);
        selection.setColor(0xF5FFFFFF);
    }

    @Override protected void onBoundsChange(Rect bounds) {
        rect.set(bounds);
        rect.inset(density * .5f, density * .5f);
        clip.reset();
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
        body.setShader(new LinearGradient(0, bounds.top, bounds.right, bounds.bottom,
                new int[]{0x647A869E, 0x506F7483, 0x628A7E87}, null, Shader.TileMode.CLAMP));
        rim.setShader(new LinearGradient(0, bounds.top, bounds.right, bounds.bottom,
                new int[]{0x80FFFFFF, 0x30FFFFFF, 0x58FFFFFF}, null, Shader.TileMode.CLAMP));
    }

    @Override public boolean isStateful() { return focusFill; }

    @Override protected boolean onStateChange(int[] state) {
        boolean next = false;
        for (int value : state) if (value == android.R.attr.state_focused
                || value == android.R.attr.state_pressed) next = true;
        if (focused == next) return false;
        focused = next;
        invalidateSelf();
        return true;
    }

    @Override public void draw(Canvas canvas) {
        int save = canvas.save();
        canvas.clipPath(clip);
        canvas.drawBitmap(backdrop, source, rect, image);
        canvas.drawRoundRect(rect, radius, radius, frost);
        canvas.drawRoundRect(rect, radius, radius, body);
        if (focusFill && focused) canvas.drawRoundRect(rect, radius, radius, selection);
        canvas.restoreToCount(save);
        canvas.drawRoundRect(rect, radius, radius, rim);
        inner.set(rect);
        inner.inset(density, density);
        canvas.drawRoundRect(inner, Math.max(0, radius - density), Math.max(0, radius - density), innerRim);
    }

    @Override public void setAlpha(int alpha) {
        this.alpha = alpha;
        image.setAlpha(alpha);
        frost.setAlpha(alpha * 0x38 / 255);
        body.setAlpha(alpha);
        rim.setAlpha(alpha);
        innerRim.setAlpha(alpha * 0x1A / 255);
        selection.setAlpha(alpha * 0xF5 / 255);
        invalidateSelf();
    }
    @Override public int getAlpha() { return alpha; }
    @Override public void setColorFilter(ColorFilter filter) { image.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public void getOutline(android.graphics.Outline outline) { outline.setRoundRect(getBounds(), radius); }
}
