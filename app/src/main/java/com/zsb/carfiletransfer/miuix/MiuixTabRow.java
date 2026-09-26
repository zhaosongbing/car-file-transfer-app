package com.zsb.carfiletransfer.miuix;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

/**
 * MIUIX TabRow: text tabs with an animated underline indicator.
 */
public class MiuixTabRow extends LinearLayout {

    public interface OnTabSelectedListener {
        void onTabSelected(int index, String title);
    }

    private final Context ctx;
    private final String[] tabs;
    private final MiuixText[] labels;
    private final View[] indicators;
    private final LinearLayout[] columns;
    private int selected;
    private OnTabSelectedListener listener;
    private boolean chipStyle = false;
    private float chipGapDp = 12f;
    private float chipPadHDp = 18f;
    private float chipPadVDp = 8f;
    private float chipTextSp = 14f;

    public MiuixTabRow(Context c, String[] tabs, int selected) {
        super(c);
        this.ctx = c;
        this.tabs = tabs;
        this.labels = new MiuixText[tabs.length];
        this.indicators = new View[tabs.length];
        this.columns = new LinearLayout[tabs.length];
        this.selected = selected;
        setOrientation(LinearLayout.HORIZONTAL);

        for (int i = 0; i < tabs.length; i++) {
            final int index = i;
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LayoutParams.WRAP_CONTENT, 1f);
            col.setLayoutParams(lp);
            int vPad = MiuixTheme.dp(c, 10f);
            col.setPadding(0, vPad, 0, vPad);

            columns[i] = col;
            MiuixText label = new MiuixText(c, tabs[i], MiuixText.Role.BODY);
            label.setSingleLine(true);
            label.setGravity(Gravity.CENTER);
            labels[i] = label;
            col.addView(label);

            View indicator = new View(c);
            int ih = MiuixTheme.dp(c, 3f);
            int iw = MiuixTheme.dp(c, 28f);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(iw, ih);
            ilp.topMargin = MiuixTheme.dp(c, 8f);
            ilp.gravity = Gravity.CENTER_HORIZONTAL;
            indicator.setLayoutParams(ilp);
            indicator.setBackground(MiuixTheme.rounded(MiuixTheme.colors().primary, ih / 2f));
            indicators[i] = indicator;
            col.addView(indicator);

            col.setClickable(true);
            col.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    setSelected(index);
                    if (listener != null) {
                        listener.onTabSelected(index, MiuixTabRow.this.tabs[index]);
                    }
                }
            });
            addView(col);
        }
        refresh();
    }

    public void setOnTabSelectedListener(OnTabSelectedListener l) {
        this.listener = l;
    }

    /**
     * Switch between the underlined MIUIX tab row and the design spec's
     * filter-chip row (hug-width pills, selected chip filled).
     */
    public MiuixTabRow setChipStyle(boolean chip) {
        chipStyle = chip;
        for (int i = 0; i < columns.length; i++) {
            LinearLayout col = columns[i];
            LinearLayout.LayoutParams lp;
            if (chip) {
                lp = new LinearLayout.LayoutParams(
                        LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
                lp.rightMargin = i == columns.length - 1 ? 0 : MiuixTheme.dp(ctx, chipGapDp);
            } else {
                lp = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
            }
            col.setLayoutParams(lp);
            col.setPadding(MiuixTheme.dp(ctx, chip ? chipPadHDp : 0f),
                    MiuixTheme.dp(ctx, chip ? chipPadVDp : 10f),
                    MiuixTheme.dp(ctx, chip ? chipPadHDp : 0f),
                    MiuixTheme.dp(ctx, chip ? chipPadVDp : 10f));
            labels[i].setSizeSp(chip ? chipTextSp : MiuixTheme.TEXT_BODY);
        }
        refresh();
        return this;
    }

    public void setSelected(int index) {
        if (index < 0 || index >= tabs.length) return;
        this.selected = index;
        refresh();
    }

    public int getSelected() {
        return selected;
    }

    private void refresh() {
        MiuixTheme.MiuixColorScheme s = MiuixTheme.colors();
        int pill = MiuixTheme.dp(ctx, 9999f);
        for (int i = 0; i < labels.length; i++) {
            boolean on = i == selected;
            labels[i].setTypeface(Typeface.create("sans-serif",
                    on ? Typeface.BOLD : Typeface.NORMAL));
            if (chipStyle) {
                indicators[i].setVisibility(View.GONE);
                labels[i].setTextColor(on ? s.onPrimary : s.onSurface);
                columns[i].setBackground(MiuixTheme.rounded(
                        on ? s.onSurface : MiuixTheme.colors().surface, pill));
            } else {
                indicators[i].setVisibility(on ? View.VISIBLE : View.INVISIBLE);
                labels[i].setTextColor(on ? s.onSurface : s.onSurfaceVariant);
                columns[i].setBackground(null);
            }
        }
    }
}
