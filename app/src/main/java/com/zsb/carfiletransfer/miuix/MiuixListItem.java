package com.zsb.carfiletransfer.miuix;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * MIUIX List item: leading badge + title / summary + trailing slot.
 */
public class MiuixListItem extends LinearLayout {

    private final Context ctx;
    private final LinearLayout textColumn;
    private final LinearLayout trailingColumn;
    private final MiuixText title;
    private final MiuixText summary;
    private TextView badge;

    public MiuixListItem(Context c) {
        super(c);
        ctx = c;
        setOrientation(LinearLayout.HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        int vPad = MiuixTheme.dp(c, 12f);
        int hPad = MiuixTheme.dp(c, 12f);
        setPadding(hPad, vPad, hPad, vPad);
        setBackground(MiuixTheme.pressable(MiuixTheme.colors().surface,
                MiuixTheme.colors().surfaceVariant,
                MiuixTheme.colors().surface,
                MiuixTheme.dp(c, 14f)));

        textColumn = new LinearLayout(c);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f);
        addView(textColumn, textLp);

        title = new MiuixText(c, "", MiuixText.Role.BODY);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        textColumn.addView(title);

        summary = new MiuixText(c, "", MiuixText.Role.CAPTION, MiuixText.Tone.TERTIARY);
        textColumn.addView(summary);

        trailingColumn = new LinearLayout(c);
        trailingColumn.setOrientation(LinearLayout.HORIZONTAL);
        trailingColumn.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        LinearLayout.LayoutParams trailingLp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        trailingLp.leftMargin = MiuixTheme.dp(c, 8f);
        addView(trailingColumn, trailingLp);
    }

    public MiuixListItem setTitle(CharSequence t) {
        title.setText(t);
        return this;
    }

    public MiuixListItem setSummary(CharSequence s) {
        if (s == null || s.length() == 0) {
            summary.setVisibility(View.GONE);
        } else {
            summary.setVisibility(View.VISIBLE);
            summary.setText(s);
        }
        return this;
    }

    /** Leading file-type badge, MIUIX rounded-square style. */
    public MiuixListItem setBadge(CharSequence text, int color) {
        if (badge == null) {
            badge = new TextView(ctx);
            int size = MiuixTheme.dp(ctx, 44f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.rightMargin = MiuixTheme.dp(ctx, 12f);
            badge.setLayoutParams(lp);
            badge.setGravity(Gravity.CENTER);
            badge.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f);
            badge.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            badge.setTextColor(0xFFFFFFFF);
            addView(badge, 0);
        }
        badge.setText(text);
        badge.setBackground(MiuixTheme.rounded(color, MiuixTheme.dp(ctx, MiuixTheme.RADIUS_BADGE)));
        return this;
    }

    /** Leading file-type badge with the design spec's size and radius. */
    public MiuixListItem setBadge(CharSequence text, int color, float sizeDp, float radiusDp) {
        if (badge == null) {
            badge = new TextView(ctx);
            badge.setGravity(Gravity.CENTER);
            badge.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f);
            badge.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            badge.setTextColor(0xFFFFFFFF);
            addView(badge, 0);
        }
        int size = MiuixTheme.dp(ctx, sizeDp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.rightMargin = MiuixTheme.dp(ctx, 12f);
        badge.setLayoutParams(lp);
        badge.setText(text);
        badge.setBackground(MiuixTheme.rounded(color, MiuixTheme.dp(ctx, radiusDp)));
        return this;
    }

    /** Per-side row padding, in dp. */
    public MiuixListItem setItemPaddingDp(float l, float t, float r, float b) {
        setPadding(MiuixTheme.dp(ctx, l), MiuixTheme.dp(ctx, t),
                MiuixTheme.dp(ctx, r), MiuixTheme.dp(ctx, b));
        return this;
    }

    /** Distance between the badge / text column / trailing slot, in dp. */
    public MiuixListItem setGapDp(float dp) {
        int g = MiuixTheme.dp(ctx, dp);
        setPadding(getPaddingLeft(), getPaddingTop(), getPaddingRight(), getPaddingBottom());
        if (badge != null) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) badge.getLayoutParams();
            lp.rightMargin = g;
            badge.setLayoutParams(lp);
        }
        LinearLayout.LayoutParams tlp = (LinearLayout.LayoutParams) trailingColumn.getLayoutParams();
        tlp.leftMargin = g;
        trailingColumn.setLayoutParams(tlp);
        return this;
    }

    /** Row corner radius from the design spec. */
    public MiuixListItem setRadiusDp(float dp) {
        setBackground(MiuixTheme.rounded(MiuixTheme.colors().surface, MiuixTheme.dp(ctx, dp)));
        return this;
    }

    /** Row with a hairline outline. */
    public MiuixListItem setOutline(int strokeColor, float strokeDp, float radiusDp) {
        setBackground(MiuixTheme.outlined(MiuixTheme.colors().surface, strokeColor,
                MiuixTheme.dp(ctx, radiusDp),
                Math.max(1, MiuixTheme.dp(ctx, strokeDp))));
        return this;
    }

    /** Title size, in sp. */
    public MiuixListItem titleSizeSp(float sp) {
        title.setSizeSp(sp);
        return this;
    }

    /** Summary size, in sp. */
    public MiuixListItem summarySizeSp(float sp) {
        summary.setSizeSp(sp);
        return this;
    }

    /** Title weight: 400 regular, 500 medium, 600 semi-bold, 700 bold. */
    public MiuixListItem titleWeight(int weight) {
        title.setWeight(weight);
        return this;
    }

    /** Make the text column take the remaining width. */
    public MiuixListItem textColumnWeight(float weight) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) textColumn.getLayoutParams();
        lp.width = 0;
        lp.weight = weight;
        textColumn.setLayoutParams(lp);
        return this;
    }

    /** Append (instead of replace) a trailing control. */
    public MiuixListItem addTrailingView(View v) {
        if (v != null) trailingColumn.addView(v);
        return this;
    }

    public MiuixListItem setTrailingText(CharSequence text) {
        trailingColumn.removeAllViews();
        if (text == null || text.length() == 0) return this;
        MiuixText t = new MiuixText(ctx, text, MiuixText.Role.CAPTION, MiuixText.Tone.TERTIARY);
        trailingColumn.addView(t);
        return this;
    }

    public MiuixListItem setTrailingView(View v) {
        trailingColumn.removeAllViews();
        if (v != null) trailingColumn.addView(v);
        return this;
    }

    public MiuixListItem setOnItemClick(OnClickListener l) {
        setClickable(true);
        setFocusable(true);
        setOnClickListener(l);
        return this;
    }

    public String getTitleText() {
        return title.getText().toString();
    }
}
