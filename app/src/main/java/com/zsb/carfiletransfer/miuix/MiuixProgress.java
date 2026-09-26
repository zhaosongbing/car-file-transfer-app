package com.zsb.carfiletransfer.miuix;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * MIUIX LinearProgress: determinate progress bar with rounded ends.
 */
public class MiuixProgress extends View {

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float heightDp = 6f;
    private int progress;

    public MiuixProgress(Context c) {
        this(c, 6f);
    }

    public MiuixProgress(Context c, float heightDp) {
        super(c);
        this.heightDp = heightDp;
        trackPaint.setColor(MiuixTheme.colors().surfaceContainer);
        barPaint.setColor(MiuixTheme.colors().primary);
    }

    /** @param progress 0..100 */
    public void setProgress(int progress) {
        this.progress = Math.max(0, Math.min(100, progress));
        invalidate();
    }

    public int getProgress() {
        return progress;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int h = MiuixTheme.dp(getContext(), heightDp);
        setMeasuredDimension(resolveSize(MiuixTheme.dp(getContext(), 120f), wSpec),
                resolveSize(h, hSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float r = h / 2f;
        canvas.drawRoundRect(0, 0, w, h, r, r, trackPaint);
        float filled = w * (progress / 100f);
        if (filled > 0) canvas.drawRoundRect(0, 0, filled, h, r, r, barPaint);
    }
}
