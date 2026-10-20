package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** App-coloured, diffused focus light outside the tile. The artwork stays completely untouched. */
public class GlassView extends View {
    static final float HALO_INSET_DP = 12f;
    private final Paint light = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tile = new RectF();
    private final float density;
    private float radius;
    private int tint;
    /** A festive theme's colour (Festival), or 0: while set, the light keeps it instead of each app's colour. */
    private int accent;
    private Bitmap mask;
    private boolean maskDirty = true;

    public GlassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        radius = 22f * density;
        setTint(0xFFB7C1C5);
    }

    void setTint(int color) {
        int opaque = 0xFF000000 | (accent != 0 ? accent : color);
        if (tint == opaque) return;
        tint = opaque;
        light.setColorFilter(new PorterDuffColorFilter(opaque, PorterDuff.Mode.SRC_IN));
        invalidate();
    }

    /** Locks the light to this colour (a festival's diya gold); 0 hands it back to the focused app's colour. */
    void setAccent(int argb) {
        accent = argb;
        if (argb != 0) setTint(argb);
    }

    void setRadius(float px) {
        if (radius == px) return;
        radius = px;
        maskDirty = true;
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        maskDirty = true;
    }

    private void rebuildMask() {
        if (mask != null) mask.recycle();
        mask = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
        Canvas cached = new Canvas(mask);
        float inset = HALO_INSET_DP * density;
        tile.set(inset, inset, getWidth() - inset, getHeight() - inset);
        maskPaint.setColor(0xFFFFFFFF);
        // Skia blurs into this tiny cache once per tile size, never into a live software layer.
        maskPaint.setShadowLayer(4.5f * density, 0f, 2f * density, 0xB8FFFFFF);
        cached.drawRoundRect(tile, radius, radius, maskPaint);
        maskPaint.clearShadowLayer();
        maskPaint.setXfermode(new android.graphics.PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        cached.drawRoundRect(tile, radius, radius, maskPaint);
        maskPaint.setXfermode(null);
        maskDirty = false;
    }

    /** One bitmap draw: the fade alpha goes straight onto it instead of through an offscreen layer. */
    @Override public boolean hasOverlappingRendering() { return false; }

    @Override protected void onDraw(Canvas canvas) {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        if (maskDirty) rebuildMask();
        canvas.drawBitmap(mask, 0, 0, light);
    }
}
