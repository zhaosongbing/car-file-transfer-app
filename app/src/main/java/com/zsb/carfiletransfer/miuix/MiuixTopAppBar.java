package com.zsb.carfiletransfer.miuix;

import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;

/**
 * MIUIX TopAppBar (large title): title + subtitle with an action slot.
 */
public class MiuixTopAppBar extends LinearLayout {

    private final MiuixText title;
    private final MiuixText subtitle;
    private final LinearLayout actionRow;

    public MiuixTopAppBar(Context c, CharSequence titleText, CharSequence subtitleText) {
        super(c);
        setOrientation(LinearLayout.HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout textColumn = new LinearLayout(c);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f);
        textColumn.setLayoutParams(textLp);

        title = new MiuixText(c, titleText, MiuixText.Role.DISPLAY);
        textColumn.addView(title);

        subtitle = new MiuixText(c, subtitleText, MiuixText.Role.CAPTION, MiuixText.Tone.TERTIARY);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        subLp.topMargin = MiuixTheme.dp(c, 4f);
        subtitle.setLayoutParams(subLp);
        textColumn.addView(subtitle);

        addView(textColumn);

        actionRow = new LinearLayout(c);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        addView(actionRow, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    public void setSubtitle(CharSequence text) {
        subtitle.setText(text);
    }

    public void setTitle(CharSequence text) {
        title.setText(text);
    }

    /** Append an action control (usually a MiuixButton) to the end side. */
    public void addAction(MiuixButton button) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.leftMargin = MiuixTheme.dp(getContext(), 8f);
        actionRow.addView(button, lp);
    }

    public void clearActions() {
        actionRow.removeAllViews();
    }
}
