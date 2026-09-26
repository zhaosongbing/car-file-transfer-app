package com.zsb.carfiletransfer.miuix;

import android.content.Context;
import android.view.Gravity;
import android.widget.FrameLayout;

/**
 * MIUIX Button: filled / tinted action button with MIUIX sizes and colours.
 */
public class MiuixButton extends FrameLayout {

    /** MIUIX button sizes. */
    public enum Size {
        SMALL(36f, MiuixTheme.TEXT_BODY_SMALL, 16f, MiuixTheme.RADIUS_BUTTON_SMALL),
        MEDIUM(44f, MiuixTheme.TEXT_BODY, 20f, MiuixTheme.RADIUS_BUTTON),
        LARGE(54f, MiuixTheme.TEXT_SUBTITLE, 24f, MiuixTheme.RADIUS_BUTTON);

        final float heightDp;
        final float textSp;
        final float padHDp;
        final float radiusDp;

        Size(float heightDp, float textSp, float padHDp, float radiusDp) {
            this.heightDp = heightDp;
            this.textSp = textSp;
            this.padHDp = padHDp;
            this.radiusDp = radiusDp;
        }
    }

    /** MIUIX button colour roles. */
    public enum Color {
        PRIMARY, NEUTRAL, DANGER, SUCCESS
    }

    private final MiuixText label;
    private final Size size;
    private final Color color;
    private boolean filled = true;

    public MiuixButton(Context c, CharSequence text) {
        this(c, text, Size.MEDIUM, Color.PRIMARY);
    }

    public MiuixButton(Context c, CharSequence text, Size size, Color color) {
        super(c);
        this.size = size;
        this.color = color;

        int h = MiuixTheme.dp(c, size.heightDp);
        int ph = MiuixTheme.dp(c, size.padHDp);

        label = new MiuixText(c, text, MiuixText.Role.BODY);
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, size.textSp);
        label.setTypeface(android.graphics.Typeface.create("sans-serif",
                android.graphics.Typeface.BOLD));
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        addView(label, lp);

        setMinimumHeight(h);
        setPadding(ph, 0, ph, 0);
        setClickable(true);
        setFocusable(true);
        applyStyle();
    }

    /** Filled button (default) or a tinted, low-emphasis button. */
    public MiuixButton setFilled(boolean filled) {
        this.filled = filled;
        applyStyle();
        return this;
    }

    public void setText(CharSequence text) {
        label.setText(text);
    }

    public CharSequence getText() {
        return label.getText();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        applyStyle();
    }

    private int containerColor() {
        MiuixTheme.MiuixColorScheme s = MiuixTheme.colors();
        switch (color) {
            case DANGER:
                return s.error;
            case SUCCESS:
                return s.success;
            case NEUTRAL:
                return filled ? s.surfaceContainer : 0x00000000;
            case PRIMARY:
            default:
                return s.primary;
        }
    }

    private int contentColor() {
        MiuixTheme.MiuixColorScheme s = MiuixTheme.colors();
        if (!isEnabled()) return s.disabledContent;
        if (color == Color.NEUTRAL) return s.onSurface;
        return s.onPrimary;
    }

    private void applyStyle() {
        MiuixTheme.MiuixColorScheme s = MiuixTheme.colors();
        int radius = MiuixTheme.dp(getContext(), size.radiusDp);
        int normal = containerColor();
        int pressed = MiuixTheme.darken(normal, 0.12f);
        int disabled = color == Color.NEUTRAL ? s.surfaceContainer : s.disabledContainer;
        setBackground(MiuixTheme.pressable(normal, pressed, disabled, radius));
        label.setTextColor(contentColor());
    }
}
