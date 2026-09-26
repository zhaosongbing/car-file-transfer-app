package com.zsb.carfiletransfer.miuix;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * MIUIX TextField: outlined single-line input with an optional label.
 */
public class MiuixTextField extends LinearLayout {

    private final TextView labelView;
    private final EditText input;

    public MiuixTextField(Context c, CharSequence label, CharSequence hint) {
        super(c);
        setOrientation(LinearLayout.VERTICAL);

        labelView = new MiuixText(c, label, MiuixText.Role.CAPTION, MiuixText.Tone.TERTIARY);
        addView(labelView, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        input = new EditText(c);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, MiuixTheme.TEXT_BODY);
        input.setTextColor(MiuixTheme.colors().onSurface);
        input.setHintTextColor(MiuixTheme.colors().onSurfaceVariant);
        input.setBackground(MiuixTheme.outlined(MiuixTheme.colors().surfaceVariant,
                MiuixTheme.colors().outline,
                MiuixTheme.dp(c, MiuixTheme.RADIUS_FIELD),
                Math.max(1, MiuixTheme.dp(c, 1f))));
        input.setIncludeFontPadding(false);
        int hPad = MiuixTheme.dp(c, 12f);
        int vPad = MiuixTheme.dp(c, 10f);
        input.setPadding(hPad, vPad, hPad, vPad);
        input.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = MiuixTheme.dp(c, 6f);
        addView(input, lp);
    }

    public String getText() {
        Editable e = input.getText();
        return e == null ? "" : e.toString().trim();
    }

    public void setText(CharSequence text) {
        input.setText(text == null ? "" : text);
    }

    public void setLabel(CharSequence text) {
        labelView.setText(text);
    }

    public EditText getEditText() {
        return input;
    }

    /** Register a watcher on the inner EditText. */
    public void addTextChangedListener(TextWatcher watcher) {
        input.addTextChangedListener(watcher);
    }
}
