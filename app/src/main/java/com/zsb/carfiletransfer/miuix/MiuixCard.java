package com.zsb.carfiletransfer.miuix;

import android.content.Context;
import android.widget.LinearLayout;

/**
 * MIUIX Card: a rounded surface container that groups related content.
 */
public class MiuixCard extends LinearLayout {

    private final float radiusDp;

    public MiuixCard(Context c) {
        this(c, MiuixTheme.RADIUS_CARD);
    }

    public MiuixCard(Context c, float radiusDp) {
        super(c);
        this.radiusDp = radiusDp;
        setOrientation(LinearLayout.VERTICAL);
        setBackground(MiuixTheme.rounded(MiuixTheme.colors().surface,
                MiuixTheme.dp(c, radiusDp)));
        setPadding(MiuixTheme.dp(c, MiuixTheme.SPACE_CARD),
                MiuixTheme.dp(c, MiuixTheme.SPACE_CARD),
                MiuixTheme.dp(c, MiuixTheme.SPACE_CARD),
                MiuixTheme.dp(c, MiuixTheme.SPACE_CARD));
    }

    /** Uniform content padding, in dp. */
    public MiuixCard setContentPadding(float dp) {
        int p = MiuixTheme.dp(getContext(), dp);
        setPadding(p, p, p, p);
        return this;
    }

    /** Per-side content padding, in dp: left / top / right / bottom. */
    public MiuixCard setContentPaddingDp(float l, float t, float r, float b) {
        setPadding(MiuixTheme.dp(getContext(), l), MiuixTheme.dp(getContext(), t),
                MiuixTheme.dp(getContext(), r), MiuixTheme.dp(getContext(), b));
        return this;
    }

    public MiuixCard setCardBackground(int color) {
        setBackground(MiuixTheme.rounded(color, MiuixTheme.dp(getContext(), radiusDp)));
        return this;
    }

    /** Surface + hairline outline, the treatment used by the design spec's cards. */
    public MiuixCard setOutline(int strokeColor, float strokeDp) {
        setBackground(MiuixTheme.outlined(MiuixTheme.colors().surface, strokeColor,
                MiuixTheme.dp(getContext(), radiusDp),
                Math.max(1, MiuixTheme.dp(getContext(), strokeDp))));
        return this;
    }

    public float getRadiusDp() {
        return radiusDp;
    }
}
