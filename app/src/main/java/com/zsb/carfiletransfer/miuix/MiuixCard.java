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

    public MiuixCard setCardBackground(int color) {
        setBackground(MiuixTheme.rounded(color, MiuixTheme.dp(getContext(), radiusDp)));
        return this;
    }

    public float getRadiusDp() {
        return radiusDp;
    }
}
