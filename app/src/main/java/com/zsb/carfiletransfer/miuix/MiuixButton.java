package com.zsb.carfiletransfer.miuix;

import android.content.Context;
import android.graphics.drawable.StateListDrawable;
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

    // design-spec overrides (0 / null means "keep the MIUIX token")
    private float radiusOverrideDp = -1f;
    private float padHDp = -1f;
    private float padVDp = -1f;
    private float labelSizeSp = -1f;
    private int containerOverride = 0;
    private int contentOverride = 0;
    private boolean outlined = false;
    private int outlineColor = 0;

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

    /** Pill / custom corner radius from the design spec. */
    public MiuixButton setRadiusDp(float radiusDp) {
        radiusOverrideDp = radiusDp;
        applyStyle();
        return this;
    }

    /** Explicit horizontal / vertical inner padding, in dp. */
    public MiuixButton setPaddingDp(float horizontalDp, float verticalDp) {
        padHDp = horizontalDp;
        padVDp = verticalDp;
        setPadding(MiuixTheme.dp(getContext(), horizontalDp),
                MiuixTheme.dp(getContext(), verticalDp),
                MiuixTheme.dp(getContext(), horizontalDp),
                MiuixTheme.dp(getContext(), verticalDp));
        return this;
    }

    /** Explicit label size, in sp. */
    public MiuixButton setLabelSizeSp(float sp) {
        labelSizeSp = sp;
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp);
        return this;
    }

    public MiuixButton setLabelWeight(int weight) {
        label.setWeight(weight);
        return this;
    }

    /** Outlined (bordered) treatment instead of a filled surface. */
    public MiuixButton setOutlined(boolean outlined, int strokeColor) {
        this.outlined = outlined;
        this.outlineColor = strokeColor;
        applyStyle();
        return this;
    }

    /** Force the container / content colours (used for the design spec's chip set). */
    public MiuixButton setColors(int container, int content) {
        containerOverride = container;
        contentOverride = content;
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
        int radius = MiuixTheme.dp(getContext(),
                radiusOverrideDp >= 0 ? radiusOverrideDp : size.radiusDp);
        int normal = containerOverride != 0 ? containerOverride : containerColor();
        int pressed = MiuixTheme.darken(normal, 0.12f);
        int disabled = color == Color.NEUTRAL ? s.surfaceContainer : s.disabledContainer;
        if (outlined) {
            int stroke = outlineColor != 0 ? outlineColor : normal;
            int sw = Math.max(1, MiuixTheme.dp(getContext(), 1f));
            StateListDrawable sld = new StateListDrawable();
            sld.addState(new int[]{-android.R.attr.state_enabled},
                    MiuixTheme.outlined(s.disabledContainer, s.outline, radius, sw));
            sld.addState(new int[]{android.R.attr.state_pressed},
                    MiuixTheme.outlined(normal, stroke, radius, sw));
            sld.addState(new int[]{android.R.attr.state_focused},
                    MiuixTheme.outlined(normal, stroke, radius, sw));
            sld.addState(new int[]{},
                    MiuixTheme.outlined(0x00000000, stroke, radius, sw));
            setBackground(sld);
        } else {
            setBackground(MiuixTheme.pressable(normal, pressed, disabled, radius));
        }
        label.setTextColor(contentOverride != 0 ? contentOverride : contentColor());
    }
}
