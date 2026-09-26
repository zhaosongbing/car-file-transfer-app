package com.zsb.carfiletransfer.miuix;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import com.zsb.carfiletransfer.qr.QrCode;

/**
 * MIUIX-styled QR surface: renders a QR matrix inside a rounded white plate.
 */
public class MiuixQrView extends View {

    private static final int QUIET_ZONE = 2;

    private final Paint lightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint darkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint platePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private boolean[][] modules;
    private int count;
    private String error;
    private float sizeDp;

    public MiuixQrView(Context c, float sizeDp) {
        super(c);
        this.sizeDp = sizeDp;
        platePaint.setColor(0xFFFFFFFF);
        lightPaint.setColor(0xFFFFFFFF);
        darkPaint.setColor(0xFF1A1A1A);
    }

    /** Re-render the view with new content; errors degrade to a placeholder. */
    public void setContent(String content) {
        try {
            if (content == null || content.length() == 0) {
                modules = null;
                error = null;
            } else {
                modules = QrCode.encode(content);
                count = modules.length;
                error = null;
            }
        } catch (Exception e) {
            modules = null;
            error = String.valueOf(e.getMessage());
        }
        invalidate();
    }

    public void setSizeDp(float sizeDp) {
        this.sizeDp = sizeDp;
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int s = MiuixTheme.dp(getContext(), sizeDp);
        setMeasuredDimension(resolveSize(s, wSpec), resolveSize(s, hSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float radius = MiuixTheme.dp(getContext(), MiuixTheme.RADIUS_CARD);
        canvas.drawRoundRect(0, 0, w, h, radius, radius, platePaint);

        if (modules == null) return;

        float total = count + QUIET_ZONE * 2f;
        float cell = Math.min(w, h) / total;
        float offsetX = (w - cell * total) / 2f;
        float offsetY = (h - cell * total) / 2f;

        for (int y = 0; y < count; y++) {
            for (int x = 0; x < count; x++) {
                if (!modules[y][x]) continue;
                float left = offsetX + (x + QUIET_ZONE) * cell;
                float top = offsetY + (y + QUIET_ZONE) * cell;
                canvas.drawRect(left, top, left + cell, top + cell, darkPaint);
            }
        }
    }

    public String getError() {
        return error;
    }
}
