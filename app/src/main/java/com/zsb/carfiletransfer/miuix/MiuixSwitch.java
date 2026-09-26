package com.zsb.carfiletransfer.miuix;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/**
 * MIUIX Switch: a two-state toggle drawn with the MIUIX track / thumb shape.
 */
public class MiuixSwitch extends View {

    public interface OnCheckedChangeListener {
        void onCheckedChanged(MiuixSwitch view, boolean checked);
    }

    private static final float W_DP = 52f;
    private static final float H_DP = 32f;
    private static final float PAD_DP = 3f;

    private boolean checked;
    private float anim;
    private OnCheckedChangeListener listener;
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator animator;

    public MiuixSwitch(Context c) {
        super(c);
        setClickable(true);
        setFocusable(true);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(Math.max(1, MiuixTheme.dp(c, 1f)));
        borderPaint.setColor(MiuixTheme.colors().outline);
    }

    public boolean isChecked() {
        return checked;
    }

    public void setChecked(boolean checked) {
        setChecked(checked, true);
    }

    public void setChecked(boolean checked, boolean animate) {
        if (this.checked == checked) {
            this.anim = checked ? 1f : 0f;
            invalidate();
            return;
        }
        this.checked = checked;
        if (animator != null) animator.cancel();
        if (!animate) {
            anim = checked ? 1f : 0f;
            invalidate();
            if (listener != null) listener.onCheckedChanged(this, checked);
            return;
        }
        final float from = anim;
        final float to = checked ? 1f : 0f;
        animator = ValueAnimator.ofFloat(from, to);
        animator.setDuration(180L);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) {
                anim = ((Float) a.getAnimatedValue()).floatValue();
                invalidate();
            }
        });
        animator.start();
        if (listener != null) listener.onCheckedChanged(this, checked);
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener l) {
        this.listener = l;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        if (event.getAction() == MotionEvent.ACTION_UP) {
            setChecked(!checked, true);
            performClick();
            return true;
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MiuixTheme.dp(getContext(), W_DP);
        int h = MiuixTheme.dp(getContext(), H_DP);
        setMeasuredDimension(resolveSize(w, wSpec), resolveSize(h, hSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float pad = MiuixTheme.dp(getContext(), PAD_DP);
        float r = (h - 2 * pad) / 2f;

        MiuixTheme.MiuixColorScheme s = MiuixTheme.colors();
        int track = isEnabled()
                ? (checked ? s.primary : s.surfaceContainer)
                : s.disabledContainer;
        trackPaint.setColor(track);
        canvas.drawRoundRect(0, 0, w, h, h / 2f, h / 2f, trackPaint);
        canvas.drawRoundRect(borderPaint.getStrokeWidth() / 2, borderPaint.getStrokeWidth() / 2,
                w - borderPaint.getStrokeWidth() / 2, h - borderPaint.getStrokeWidth() / 2,
                h / 2f, h / 2f, borderPaint);

        float cx = pad + r + anim * (w - 2 * pad - 2 * r);
        thumbPaint.setColor(isEnabled() ? 0xFFFFFFFF : 0xFFF2F2F2);
        canvas.drawCircle(cx, h / 2f, r, thumbPaint);
    }
}
