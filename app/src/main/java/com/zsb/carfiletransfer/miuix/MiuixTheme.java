package com.zsb.carfiletransfer.miuix;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;

/**
 * MIUIX design tokens: colours, corner radii, typography scale and spacing.
 * Mirrors the Miuix `ColorScheme` / theme objects, expressed for the
 * platform View toolkit.
 */
public final class MiuixTheme {

    private MiuixTheme() {
    }

    /** Colour tokens of the MIUIX light colour scheme. */
    public static final class MiuixColorScheme {
        public final int primary;
        public final int onPrimary;
        public final int primaryContainer;
        public final int background;
        public final int surface;
        public final int surfaceVariant;
        public final int surfaceContainer;
        public final int onSurface;
        public final int onSurfaceVariant;
        public final int outline;
        public final int divider;
        public final int error;
        public final int onError;
        public final int success;
        public final int warning;
        public final int disabledContainer;
        public final int disabledContent;

        private MiuixColorScheme() {
            primary = 0xFF0F7FFF;
            onPrimary = 0xFFFFFFFF;
            primaryContainer = 0xFFE1EFFF;
            background = 0xFFF2F3F5;
            surface = 0xFFFFFFFF;
            surfaceVariant = 0xFFF7F8FA;
            surfaceContainer = 0xFFEBEDF0;
            onSurface = 0xFF1A1A1A;
            onSurfaceVariant = 0xFF8A8A8E;
            outline = 0xFFD0D3D8;
            divider = 0xFFE6E8EB;
            error = 0xFFF44336;
            onError = 0xFFFFFFFF;
            success = 0xFF34C759;
            warning = 0xFFFF9500;
            disabledContainer = 0xFFE9EBEE;
            disabledContent = 0xFFB2B5BA;
        }

        /** The default light scheme used across the app. */
        public static MiuixColorScheme light() {
            return Holder.INSTANCE;
        }

        private static final class Holder {
            private static final MiuixColorScheme INSTANCE = new MiuixColorScheme();
        }
    }

    // ---- corner radius (dp) ----
    public static final float RADIUS_CARD = 20f;
    public static final float RADIUS_DIALOG = 28f;
    public static final float RADIUS_BUTTON = 16f;
    public static final float RADIUS_BUTTON_SMALL = 12f;
    public static final float RADIUS_BADGE = 10f;
    public static final float RADIUS_FIELD = 14f;

    // ---- spacing (dp) ----
    public static final float SPACE_SCREEN = 24f;
    public static final float SPACE_CARD = 16f;
    public static final float SPACE_ITEM = 12f;
    public static final float SPACE_TIGHT = 8f;

    // ---- typography (sp) ----
    public static final float TEXT_DISPLAY = 30f;
    public static final float TEXT_TITLE = 22f;
    public static final float TEXT_SUBTITLE = 18f;
    public static final float TEXT_BODY = 16f;
    public static final float TEXT_BODY_SMALL = 14f;
    public static final float TEXT_CAPTION = 13f;
    public static final float TEXT_MICRO = 12f;

    public static MiuixColorScheme colors() {
        return MiuixColorScheme.light();
    }

    public static int dp(Context c, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                value, c.getResources().getDisplayMetrics()));
    }

    public static int sp(Context c, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                value, c.getResources().getDisplayMetrics()));
    }

    /** Solid rounded rectangle. */
    public static GradientDrawable rounded(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    /** Rounded rectangle with a 1px outline. */
    public static GradientDrawable outlined(int fill, int stroke, float radiusPx, int strokePx) {
        GradientDrawable g = rounded(fill, radiusPx);
        g.setStroke(strokePx, stroke);
        return g;
    }

    /** Rounded drawable with normal / pressed / disabled states. */
    public static StateListDrawable pressable(int normal, int pressed, int disabled, float radiusPx) {
        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{-android.R.attr.state_enabled}, rounded(disabled, radiusPx));
        sld.addState(new int[]{android.R.attr.state_pressed}, rounded(pressed, radiusPx));
        sld.addState(new int[]{android.R.attr.state_focused}, rounded(pressed, radiusPx));
        sld.addState(new int[]{}, rounded(normal, radiusPx));
        return sld;
    }

    /** Dim a colour towards its pressed state. */
    public static int darken(int color, float amount) {
        int a = (color >>> 24);
        int r = Math.max(0, Math.round(((color >> 16) & 0xFF) * (1f - amount)));
        int g = Math.max(0, Math.round(((color >> 8) & 0xFF) * (1f - amount)));
        int b = Math.max(0, Math.round((color & 0xFF) * (1f - amount)));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
