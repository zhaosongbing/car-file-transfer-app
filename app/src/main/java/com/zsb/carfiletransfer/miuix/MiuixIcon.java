package com.zsb.carfiletransfer.miuix;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

/**
 * MIUIX Icon: stroked vector glyphs drawn on a square canvas.
 *
 * <p>Text glyphs such as "‹" or "↑" sit on the font's own baseline, so they
 * never line up exactly with a title rendered next to them. Drawing the shape
 * on a fixed square box keeps every bar icon on the same optical centre line.</p>
 */
public class MiuixIcon extends View {

    public enum Shape {
        CHEVRON_LEFT, CHEVRON_RIGHT, ARROW_UP, TRASH, SEARCH,
        /** Standard "back" arrow: a horizontal stem plus a chevron head. */
        BACK,
        /** Two overlapping rounded rectangles - the Android copy glyph. */
        COPY,
        /** A cross mark - used for dialog close affordances. */
        CLOSE,
        /** A handset - the "phone connects to car hotspot" option. */
        PHONE,
        /** A car silhouette - the "car connects to phone hotspot" option. */
        CAR,
        /** Wi-Fi signal arcs - a generic connection glyph. */
        WIFI
    }

    private final Shape shape;
    private final Paint paint;
    private int color;
    private float strokeDp = 2f;
    private float sizeDp = 24f;

    public MiuixIcon(Context c, Shape shape) {
        this(c, shape, 24f, MiuixTheme.colors().onSurface);
    }

    public MiuixIcon(Context c, Shape shape, float sizeDp, int color) {
        super(c);
        this.shape = shape;
        this.sizeDp = sizeDp;
        this.color = color;
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    public MiuixIcon setStrokeDp(float dp) {
        strokeDp = dp;
        invalidate();
        return this;
    }

    public MiuixIcon setIconColor(int c) {
        color = c;
        invalidate();
        return this;
    }

    /** Wrap the icon in a square slot of {@code sizeDp} so it centres cleanly. */
    public MiuixIcon setSizeDp(float dp) {
        sizeDp = dp;
        requestLayout();
        invalidate();
        return this;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int s = MiuixTheme.dp(getContext(), sizeDp);
        setMeasuredDimension(s, s);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float u = Math.min(w, h);

        paint.setColor(color);
        paint.setStrokeWidth(Math.max(1f, MiuixTheme.dp(getContext(), strokeDp)));

        switch (shape) {
            case CHEVRON_LEFT:
                drawChevron(canvas, cx, cy, u, -1f);
                break;
            case CHEVRON_RIGHT:
                drawChevron(canvas, cx, cy, u, 1f);
                break;
            case ARROW_UP:
                drawArrowUp(canvas, cx, cy, u);
                break;
            case BACK:
                drawBack(canvas, cx, cy, u);
                break;
            case COPY:
                drawCopy(canvas, cx, cy, u);
                break;
            case CLOSE:
                drawClose(canvas, cx, cy, u);
                break;
            case PHONE:
                drawPhone(canvas, cx, cy, u);
                break;
            case CAR:
                drawCar(canvas, cx, cy, u);
                break;
            case WIFI:
                drawWifi(canvas, cx, cy, u);
                break;
            case TRASH:
                drawTrash(canvas, cx, cy, u);
                break;
            case SEARCH:
            default:
                drawSearch(canvas, cx, cy, u);
                break;
        }
    }

    private void drawChevron(Canvas canvas, float cx, float cy, float u, float dir) {
        float a = u * 0.22f;
        Path p = new Path();
        p.moveTo(cx + dir * a * 0.5f, cy - a);
        p.lineTo(cx - dir * a * 0.5f, cy);
        p.lineTo(cx + dir * a * 0.5f, cy + a);
        canvas.drawPath(p, paint);
    }

    private void drawArrowUp(Canvas canvas, float cx, float cy, float u) {
        float a = u * 0.30f;
        canvas.drawLine(cx, cy + a, cx, cy - a, paint);
        Path p = new Path();
        p.moveTo(cx - a * 0.72f, cy - a * 0.28f);
        p.lineTo(cx, cy - a);
        p.lineTo(cx + a * 0.72f, cy - a * 0.28f);
        canvas.drawPath(p, paint);
    }

    /**
     * The platform back affordance: a stroke running left-to-right with the
     * chevron head on the left. A bare chevron reads as a "greater than" sign
     * at small sizes, which is why the list page used to look wrong.
     */
    private void drawBack(Canvas canvas, float cx, float cy, float u) {
        float a = u * 0.26f;
        float right = cx + a * 1.30f;
        float tipX = cx - a * 1.10f;
        canvas.drawLine(tipX, cy, right, cy, paint);
        Path p = new Path();
        p.moveTo(tipX + a * 0.90f, cy - a);
        p.lineTo(tipX, cy);
        p.lineTo(tipX + a * 0.90f, cy + a);
        canvas.drawPath(p, paint);
    }

    private void drawTrash(Canvas canvas, float cx, float cy, float u) {
        float a = u * 0.30f;
        canvas.drawLine(cx - a * 0.85f, cy - a * 0.55f, cx + a * 0.85f, cy - a * 0.55f, paint);
        canvas.drawLine(cx - a * 0.35f, cy - a * 0.85f, cx + a * 0.35f, cy - a * 0.85f, paint);
        canvas.drawLine(cx - a * 0.65f, cy - a * 0.30f, cx - a * 0.55f, cy + a * 0.85f, paint);
        canvas.drawLine(cx + a * 0.65f, cy - a * 0.30f, cx + a * 0.55f, cy + a * 0.85f, paint);
        canvas.drawLine(cx - a * 0.70f, cy + a * 0.85f, cx + a * 0.70f, cy + a * 0.85f, paint);
    }

    private void drawSearch(Canvas canvas, float cx, float cy, float u) {
        float r = u * 0.26f;
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawCircle(cx - r * 0.22f, cy - r * 0.22f, r, paint);
        canvas.drawLine(cx + r * 0.48f, cy + r * 0.48f, cx + r, cy + r, paint);
    }

    /** Square layout params, vertically centred - used for bar slots. */
    public static LinearLayout.LayoutParams slotParams(Context c, float sizeDp) {
        int s = MiuixTheme.dp(c, sizeDp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
        lp.gravity = Gravity.CENTER_VERTICAL;
        return lp;
    }

    /** Two overlapping rounded rectangles: the copy glyph. */
    private void drawCopy(Canvas canvas, float cx, float cy, float u) {
        float r = u * 0.12f;
        float s = u * 0.27f;
        // back sheet, offset up-left
        float bx = cx - u * 0.12f;
        float by = cy - u * 0.12f;
        canvas.drawRoundRect(bx - s, by - s, bx + s, by + s, r, r, paint);
        // front sheet, offset down-right
        float fx = cx + u * 0.12f;
        float fy = cy + u * 0.12f;
        canvas.drawRoundRect(fx - s, fy - s, fx + s, fy + s, r, r, paint);
    }

    /** A simple cross mark: the close glyph. */
    private void drawClose(Canvas canvas, float cx, float cy, float u) {
        float a = u * 0.28f;
        canvas.drawLine(cx - a, cy - a, cx + a, cy + a, paint);
        canvas.drawLine(cx - a, cy + a, cx + a, cy - a, paint);
    }

    /** A handset glyph: a rounded phone body with a home indicator. */
    private void drawPhone(Canvas canvas, float cx, float cy, float u) {
        float w = u * 0.44f;
        float h = u * 0.70f;
        float left = cx - w / 2f;
        float top = cy - h / 2f;
        float r = u * 0.10f;
        canvas.drawRoundRect(left, top, left + w, top + h, r, r, paint);
        float lw = u * 0.18f;
        canvas.drawLine(cx - lw / 2f, top + h - u * 0.12f,
                cx + lw / 2f, top + h - u * 0.12f, paint);
    }

    /** A car silhouette: lower body + cabin + two wheels. */
    private void drawCar(Canvas canvas, float cx, float cy, float u) {
        float left = cx - u * 0.32f;
        float right = cx + u * 0.32f;
        float bodyTop = cy - u * 0.06f;
        float bodyBot = cy + u * 0.14f;
        Path p = new Path();
        p.moveTo(left, bodyBot);
        p.lineTo(left + u * 0.04f, bodyTop);
        p.lineTo(right - u * 0.12f, bodyTop);
        p.lineTo(right, bodyBot);
        p.close();
        canvas.drawPath(p, paint);
        Path cab = new Path();
        cab.moveTo(left + u * 0.16f, bodyTop);
        cab.lineTo(left + u * 0.24f, cy - u * 0.20f);
        cab.lineTo(right - u * 0.22f, cy - u * 0.20f);
        cab.lineTo(right - u * 0.12f, bodyTop);
        canvas.drawPath(cab, paint);
        float wr = u * 0.08f;
        canvas.drawCircle(left + u * 0.16f, bodyBot + wr * 0.5f, wr, paint);
        canvas.drawCircle(right - u * 0.14f, bodyBot + wr * 0.5f, wr, paint);
    }

    /** Wi-Fi signal: three nested arcs above a centre dot. */
    private void drawWifi(Canvas canvas, float cx, float cy, float u) {
        float dotY = cy + u * 0.20f;
        canvas.drawCircle(cx, dotY, u * 0.045f, paint);
        for (int i = 0; i < 3; i++) {
            float r = u * (0.20f + i * 0.09f);
            canvas.drawArc(cx - r, dotY - r, cx + r, dotY + r,
                    205f, 130f, false, paint);
        }
    }
}
