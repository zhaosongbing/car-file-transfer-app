package com.zsb.carfiletransfer.miuix;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

/**
 * MIUIX TopAppBar (large title): title + subtitle with an action slot.
 *
 * <p>Every slot (leading icon, title column, action icons) is placed on the
 * same horizontal centre line. Two things make that reliable:</p>
 * <ul>
 *   <li>the bar keeps an explicit height (see {@link #onMeasure}), so
 *       {@code CENTER_VERTICAL} has a real axis to centre against;</li>
 *   <li>the subtitle is removed from the layout when empty - an empty
 *       {@code TextView} still occupies a line box and used to push the title
 *       above the centre line, which is what made the icon and the title look
 *       misaligned.</li>
 * </ul>
 */
public class MiuixTopAppBar extends LinearLayout {

    private final MiuixText title;
    private final MiuixText subtitle;
    private final LinearLayout actionRow;

    private final Context ctx;
    private final LinearLayout leadingRow;
    private final LinearLayout titleRow;

    public MiuixTopAppBar(Context c, CharSequence titleText, CharSequence subtitleText) {
        super(c);
        ctx = c;
        setOrientation(LinearLayout.HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBaselineAligned(false);

        leadingRow = new LinearLayout(c);
        leadingRow.setOrientation(LinearLayout.HORIZONTAL);
        leadingRow.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        leadingRow.setBaselineAligned(false);
        addView(leadingRow, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        LinearLayout textColumn = new LinearLayout(c);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        textColumn.setBaselineAligned(false);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f);
        textColumn.setLayoutParams(textLp);

        titleRow = new LinearLayout(c);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        titleRow.setBaselineAligned(false);

        title = new MiuixText(c, titleText, MiuixText.Role.DISPLAY);
        title.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        titleRow.addView(title);
        textColumn.addView(titleRow);

        subtitle = new MiuixText(c, subtitleText, MiuixText.Role.CAPTION, MiuixText.Tone.TERTIARY);
        subtitle.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        subLp.topMargin = MiuixTheme.dp(c, 4f);
        subtitle.setLayoutParams(subLp);
        textColumn.addView(subtitle);
        // an empty TextView still reserves a line box and shifts the title up
        setSubtitle(subtitleText);

        addView(textColumn);

        actionRow = new LinearLayout(c);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        actionRow.setBaselineAligned(false);
        addView(actionRow, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    /** Put a control (back chevron, logo) in front of the title. */
    public void setLeading(View v) {
        leadingRow.removeAllViews();
        if (v == null) {
            leadingRow.setVisibility(View.GONE);
            return;
        }
        leadingRow.setVisibility(View.VISIBLE);
        leadingRow.addView(v);
    }

    /** Bar height from the design spec, in dp. */
    public MiuixTopAppBar setHeightDp(float dp) {
        setMinimumHeight(MiuixTheme.dp(ctx, dp));
        return this;
    }

    /** Bar padding, in dp (horizontal / vertical). */
    public MiuixTopAppBar setPaddingDp(float hDp, float vDp) {
        setPadding(MiuixTheme.dp(ctx, hDp), MiuixTheme.dp(ctx, vDp),
                MiuixTheme.dp(ctx, hDp), MiuixTheme.dp(ctx, vDp));
        return this;
    }

    /** Title size, in sp. */
    public MiuixTopAppBar setTitleSizeSp(float sp) {
        title.setSizeSp(sp);
        return this;
    }

    /** Hairline separator under the bar. */
    public MiuixTopAppBar setBottomDivider(boolean show, int color, float heightDp) {
        if (!show) {
            setBackground(null);
            return this;
        }
        int h = Math.max(1, MiuixTheme.dp(ctx, heightDp));
        GradientDrawable g = new GradientDrawable();
        g.setSize(1, h);
        g.setColor(color);
        // draw the line at the bottom edge using a layer-less approach
        setBackground(new BottomLineDrawable(color, h));
        return this;
    }

    /** Draws a single hairline at the bottom of the view. */
    private static final class BottomLineDrawable extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint paint = new android.graphics.Paint();
        private final int h;

        BottomLineDrawable(int color, int height) {
            paint.setColor(color);
            paint.setStyle(android.graphics.Paint.Style.FILL);
            h = height;
        }

        public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            canvas.drawRect(b.left, b.bottom - h, b.right, b.bottom, paint);
        }

        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        public void setColorFilter(android.graphics.ColorFilter cf) {
            paint.setColorFilter(cf);
        }

        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    public void setSubtitle(CharSequence text) {
        boolean empty = text == null || text.length() == 0;
        subtitle.setText(empty ? "" : text);
        subtitle.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    public void setTitle(CharSequence text) {
        title.setText(text);
    }

    /** Append an arbitrary control (pill, status chip) right after the title. */
    public void addTitleSuffix(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.leftMargin = MiuixTheme.dp(ctx, 12f);
        titleRow.addView(v, lp);
    }

    /** Append an arbitrary control to the end side. */
    public void addActionView(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.leftMargin = MiuixTheme.dp(ctx, 12f);
        actionRow.addView(v, lp);
    }

    /** Append an action control (usually a MiuixButton) to the end side. */
    public void addAction(MiuixButton button) {
        addActionView(button);
    }

    public void clearActions() {
        actionRow.removeAllViews();
    }

    /**
     * Solid top-bar background. A plain opaque surface (no frosted / gradient
     * treatment) so scrolling content is cleanly occluded behind the bar.
     */
    public MiuixTopAppBar setSolidBar() {
        setBackground(new ColorDrawable(MiuixTheme.colors().surfaceContainer));
        return this;
    }

    /** Make the title (app name) clickable - e.g. to navigate to another page. */
    public void setTitleOnClickListener(View.OnClickListener l) {
        title.setClickable(true);
        title.setOnClickListener(l);
        titleRow.setClickable(true);
        titleRow.setOnClickListener(l);
    }

    /**
     * Keep the bar's own height even when its children are shorter, so that
     * {@code CENTER_VERTICAL} always centres the icon and the title on the same
     * line.
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int min = getSuggestedMinimumHeight();
        int measured = getMeasuredHeight();
        if (measured < min) {
            setMeasuredDimension(getMeasuredWidth(), min);
        }
    }
}
