package com.zsb.carfiletransfer.miuix;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.lang.ref.WeakReference;

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

    // Real-time backdrop blur (frosted glass when scrollable content passes
    // underneath the bar). We re-render the content region directly behind the
    // bar into a small downscaled bitmap, box-blur it, then use it (tinted) as
    // the bar background. This is a true backdrop blur of in-app content - not
    // a translucent gradient.
    private WeakReference<View> blurSource;
    private ScrollView blurScroller;
    private Bitmap blurBmp;
    private Canvas blurCanvas;
    private final Paint blurPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean blurEnabled = false;
    private boolean blurPending = false;
    private int blurDownScale = 4;   // capture at 1/4 resolution for speed
    private int blurRadius = 7;      // box-blur radius on the downscaled bitmap
    private final Runnable blurTask = new Runnable() {
        public void run() {
            blurPending = false;
            updateBlur();
        }
    };

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
     * Frosted / transparent gradient treatment for the bar background.
     *
     * <p>Renders a vertical gradient from a translucent surface tint at the top
     * fading to fully transparent at the bottom - the "透明渐变" look of a glass
     * bar. A true backdrop blur would need scrollable content rendered behind
     * the bar (the pages do not currently do that), so this gradient is the
     * visible treatment and keeps the title / icon text crisp.</p>
     */
    public MiuixTopAppBar setTransparentBlur() {
        int base = MiuixTheme.colors().surfaceContainer;
        int r = Color.red(base), g = Color.green(base), b = Color.blue(base);
        int top = Color.argb((int) (0.82f * 255f), r, g, b);
        int bottom = Color.argb(0, r, g, b);
        GradientDrawable grad = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        setBackground(grad);
        return this;
    }

    /**
     * Enable a real-time frosted-glass backdrop blur. The bar must be laid out as
     * an overlay on top of {@code scroller}, and {@code content} is the view that
     * lives inside the scroller (it is re-rendered into a small bitmap to produce
     * the blurred region behind the bar).
     */
    public void enableBackdropBlur(View content, ScrollView scroller) {
        blurSource = new WeakReference<>(content);
        blurScroller = scroller;
        blurEnabled = true;
        if (scroller != null) {
            scroller.getViewTreeObserver().addOnScrollChangedListener(
                    new ViewTreeObserver.OnScrollChangedListener() {
                        public void onScrollChanged() {
                            requestBlurUpdate();
                        }
                    });
        }
        getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    public void onGlobalLayout() {
                        requestBlurUpdate();
                    }
                });
        requestBlurUpdate();
    }

    private void requestBlurUpdate() {
        if (!blurEnabled || blurPending) return;
        blurPending = true;
        post(blurTask);
    }

    private void updateBlur() {
        if (!blurEnabled) return;
        View src = blurSource != null ? blurSource.get() : null;
        if (src == null || blurScroller == null
                || getWidth() == 0 || getHeight() == 0) return;
        int w = getWidth();
        int h = getHeight();
        int sw = Math.max(1, w / blurDownScale);
        int sh = Math.max(1, h / blurDownScale);
        if (blurBmp == null || blurBmp.getWidth() != sw || blurBmp.getHeight() != sh) {
            blurBmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            blurCanvas = new Canvas(blurBmp);
        }
        blurCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        int scrollY = blurScroller.getScrollY();
        blurCanvas.save();
        // map content rect [0..w, scrollY..scrollY+h] into the small bitmap
        blurCanvas.translate(0f, -scrollY);
        blurCanvas.scale(1f / blurDownScale, 1f / blurDownScale);
        src.draw(blurCanvas);
        blurCanvas.restore();
        Bitmap blurred = boxBlur(blurBmp, blurRadius);
        if (blurred == null) blurred = blurBmp;
        BitmapDrawable bd = new BitmapDrawable(getResources(), blurred);
        bd.setGravity(Gravity.FILL);
        bd.setFilterBitmap(true);
        int base = MiuixTheme.colors().surfaceContainer;
        int r = Color.red(base), g = Color.green(base), b = Color.blue(base);
        // frosted tint: keeps the title / icon text readable over the blur
        ColorDrawable tint = new ColorDrawable(Color.argb(150, r, g, b));
        LayerDrawable layers = new LayerDrawable(new Drawable[]{bd, tint});
        setBackground(layers);
    }

    /** Cheap separable box blur on a small bitmap (clamped edges). */
    private static Bitmap boxBlur(Bitmap src, int radius) {
        if (radius < 1) return src;
        int w = src.getWidth();
        int h = src.getHeight();
        int[] px = new int[w * h];
        int[] tmp = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        int div = radius * 2 + 1;
        for (int y = 0; y < h; y++) {
            int ti = y * w;
            for (int x = 0; x < w; x++) {
                int r = 0, g = 0, b = 0, a = 0, c = 0;
                for (int k = -radius; k <= radius; k++) {
                    int xx = x + k;
                    if (xx < 0) xx = 0;
                    else if (xx >= w) xx = w - 1;
                    int p = px[ti + xx];
                    r += (p >> 16) & 0xff;
                    g += (p >> 8) & 0xff;
                    b += p & 0xff;
                    a += (p >> 24) & 0xff;
                    c++;
                }
                tmp[ti + x] = ((a / c) << 24) | ((r / c) << 16) | ((g / c) << 8) | (b / c);
            }
        }
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int r = 0, g = 0, b = 0, a = 0, c = 0;
                for (int k = -radius; k <= radius; k++) {
                    int yy = y + k;
                    if (yy < 0) yy = 0;
                    else if (yy >= h) yy = h - 1;
                    int p = tmp[yy * w + x];
                    r += (p >> 16) & 0xff;
                    g += (p >> 8) & 0xff;
                    b += p & 0xff;
                    a += (p >> 24) & 0xff;
                    c++;
                }
                px[y * w + x] = ((a / c) << 24) | ((r / c) << 16) | ((g / c) << 8) | (b / c);
            }
        }
        src.setPixels(px, 0, w, 0, 0, w, h);
        return src;
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
