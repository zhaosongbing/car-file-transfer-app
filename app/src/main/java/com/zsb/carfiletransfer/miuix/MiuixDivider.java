package com.zsb.carfiletransfer.miuix;

import android.content.Context;
import android.view.View;

/**
 * MIUIX HorizontalDivider: a hairline separator, optionally indented.
 */
public class MiuixDivider extends View {

    public MiuixDivider(Context c) {
        this(c, 0f);
    }

    /** @param startIndentDp left inset, matching MIUIX dividerWith* helpers. */
    public MiuixDivider(Context c, float startIndentDp) {
        super(c);
        setBackgroundColor(MiuixTheme.colors().divider);
        int h = Math.max(1, MiuixTheme.dp(c, 0.6f));
        int indent = MiuixTheme.dp(c, startIndentDp);
        setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, h));
        setPadding(indent, 0, 0, 0);
    }
}
