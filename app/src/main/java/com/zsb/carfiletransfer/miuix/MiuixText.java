package com.zsb.carfiletransfer.miuix;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * MIUIX Text: typography with the MIUIX type scale already applied.
 */
public class MiuixText extends TextView {

    /** MIUIX type-scale roles. */
    public enum Role {
        DISPLAY(MiuixTheme.TEXT_DISPLAY, true),
        TITLE(MiuixTheme.TEXT_TITLE, true),
        SUBTITLE(MiuixTheme.TEXT_SUBTITLE, true),
        BODY(MiuixTheme.TEXT_BODY, false),
        BODY_SMALL(MiuixTheme.TEXT_BODY_SMALL, false),
        CAPTION(MiuixTheme.TEXT_CAPTION, false),
        MICRO(MiuixTheme.TEXT_MICRO, false);

        final float sizeSp;
        final boolean strong;

        Role(float sizeSp, boolean strong) {
            this.sizeSp = sizeSp;
            this.strong = strong;
        }
    }

    /** Semantic text colours. */
    public enum Tone {
        PRIMARY, SECONDARY, TERTIARY, BRAND, ERROR, SUCCESS, WARNING
    }

    public MiuixText(Context c, CharSequence text, Role role) {
        this(c, text, role, Tone.PRIMARY);
    }

    public MiuixText(Context c, CharSequence text, Role role, Tone tone) {
        super(c);
        setText(text);
        setTextSize(TypedValue.COMPLEX_UNIT_SP, role.sizeSp);
        setTypeface(Typeface.create("sans-serif",
                role.strong ? Typeface.BOLD : Typeface.NORMAL));
        setTextColor(colorOf(tone));
        setIncludeFontPadding(false);
    }

    public MiuixText setTone(Tone tone) {
        setTextColor(colorOf(tone));
        return this;
    }

    public MiuixText setRole(Role role) {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, role.sizeSp);
        setTypeface(Typeface.create("sans-serif",
                role.strong ? Typeface.BOLD : Typeface.NORMAL));
        return this;
    }

    private static int colorOf(Tone tone) {
        MiuixTheme.MiuixColorScheme s = MiuixTheme.colors();
        switch (tone) {
            case SECONDARY:
                return 0xFF5A5C60;
            case TERTIARY:
                return s.onSurfaceVariant;
            case BRAND:
                return s.primary;
            case ERROR:
                return s.error;
            case SUCCESS:
                return s.success;
            case WARNING:
                return s.warning;
            case PRIMARY:
            default:
                return s.onSurface;
        }
    }
}
