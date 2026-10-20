package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

/** One remote-focus target with an iOS-style icon and a trailing value or switch. */
public final class SettingsRowView extends TextView {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private Drawable icon;
    private int iconColor;
    private String value = "";
    private boolean toggle, checked, chevron;

    public SettingsRowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
    }

    void setIcon(int resource, int color) {
        icon = getContext().getDrawable(resource).mutate();
        iconColor = color;
        invalidate();
    }

    void setToggle(boolean enabled) {
        toggle = true;
        checked = enabled;
        setContentDescription(getText() + ", " + getContext().getString(enabled ? R.string.on : R.string.off));
        invalidate();
    }

    void setValue(String text, boolean disclosure) {
        toggle = false;
        value = text;
        chevron = disclosure;
        setContentDescription(getText() + (text.isEmpty() ? "" : ", " + text));
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        float cy = getHeight() / 2f;
        float iconX = rtl ? getWidth() - dp(46) : dp(18);
        if (icon != null) {
            paint.setColor(iconColor);
            canvas.drawRoundRect(iconX, cy - dp(14), iconX + dp(28), cy + dp(14), dp(7), dp(7), paint);
            icon.setBounds(Math.round(iconX + dp(5)), Math.round(cy - dp(9)),
                    Math.round(iconX + dp(23)), Math.round(cy + dp(9)));
            icon.draw(canvas);
        }
        float end = rtl ? dp(20) : getWidth() - dp(20);
        if (toggle) {
            float left = rtl ? end : end - dp(38);
            paint.setColor(checked ? 0xFF30D158 : isFocused() ? 0xFFB8BCC4 : 0xFF555A65);
            canvas.drawRoundRect(left, cy - dp(11), left + dp(38), cy + dp(11), dp(11), dp(11), paint);
            paint.setColor(0xFFFFFFFF);
            boolean knobRight = checked != rtl;
            canvas.drawCircle(left + dp(knobRight ? 27 : 11), cy, dp(9), paint);
        } else {
            paint.setColor(isFocused() ? 0xFF666970 : 0xFFB7BBC6);
            paint.setTextSize(getTextSize() * .94f);
            paint.setTypeface(getTypeface());
            paint.setTextAlign(rtl ? Paint.Align.LEFT : Paint.Align.RIGHT);
            float textEnd = end + (chevron ? dp(rtl ? 18 : -18) : 0);
            canvas.drawText(value, textEnd, cy - (paint.ascent() + paint.descent()) / 2, paint);
            if (chevron) {
                float x = end + dp(rtl ? 2 : -2);
                Path path = new Path();
                path.moveTo(x + dp(rtl ? 4 : -4), cy - dp(4));
                path.lineTo(x, cy);
                path.lineTo(x + dp(rtl ? 4 : -4), cy + dp(4));
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1.6f));
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                canvas.drawPath(path, paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (toggle) {
            info.setClassName("android.widget.Switch");
            info.setCheckable(true);
            info.setChecked(checked);
        }
    }

    private float dp(float value) { return value * density; }
}
