package com.zsb.carfiletransfer.miuix;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.util.DisplayMetrics;
import android.view.WindowManager;

/**
 * MIUIX window size class.
 *
 * MIUIX splits a window into three size classes so a single layout can adapt
 * from a phone to a car head unit:
 *
 * <pre>
 *   COMPACT   width &lt; 600dp    - phone, single column, stacked panes
 *   MEDIUM    600dp .. 839dp   - small tablet / portrait head unit
 *   EXPANDED  width &gt;= 840dp   - car head unit landscape (design draft 1194dp)
 * </pre>
 *
 * Call {@link #current(Activity)} whenever the configuration changes; the
 * values are cheap to recompute and must never be cached across rotations.
 */
public final class MiuixWindowSizeClass {

    /** Compact / medium / expanded break points, in dp. */
    public static final int BREAKPOINT_MEDIUM = 600;
    public static final int BREAKPOINT_EXPANDED = 840;

    public enum SizeClass {
        COMPACT,
        MEDIUM,
        EXPANDED
    }

    private final int widthDp;
    private final int heightDp;
    private final SizeClass sizeClass;

    private MiuixWindowSizeClass(int widthDp, int heightDp) {
        this.widthDp = widthDp;
        this.heightDp = heightDp;
        this.sizeClass = classify(widthDp);
    }

    /**
     * Measure the usable window of the given activity, in dp. Falls back to the
     * real display metrics when the window manager is unavailable.
     */
    public static MiuixWindowSizeClass current(Activity activity) {
        int w;
        int h;
        try {
            DisplayMetrics dm = new DisplayMetrics();
            WindowManager wm = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
            wm.getDefaultDisplay().getRealMetrics(dm);
            w = Math.round(dm.widthPixels / dm.density);
            h = Math.round(dm.heightPixels / dm.density);
        } catch (Throwable t) {
            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            w = Math.round(dm.widthPixels / dm.density);
            h = Math.round(dm.heightPixels / dm.density);
        }
        return new MiuixWindowSizeClass(w, h);
    }

    /** Re-evaluate from a raw configuration (used on config changes). */
    public static MiuixWindowSizeClass from(Configuration config) {
        int w = config.screenWidthDp > 0 ? config.screenWidthDp : 0;
        int h = config.screenHeightDp > 0 ? config.screenHeightDp : 0;
        return new MiuixWindowSizeClass(w, h);
    }

    public static SizeClass classify(int widthDp) {
        if (widthDp >= BREAKPOINT_EXPANDED) return SizeClass.EXPANDED;
        if (widthDp >= BREAKPOINT_MEDIUM) return SizeClass.MEDIUM;
        return SizeClass.COMPACT;
    }

    /** Window width in dp. */
    public int getWidthDp() {
        return widthDp;
    }

    /** Window height in dp. */
    public int getHeightDp() {
        return heightDp;
    }

    public SizeClass getSizeClass() {
        return sizeClass;
    }

    public boolean isCompact() {
        return sizeClass == SizeClass.COMPACT;
    }

    public boolean isExpanded() {
        return sizeClass == SizeClass.EXPANDED;
    }

    /**
     * True when the window is wide enough for the two pane (QR + side column)
     * layout of the design draft.
     */
    public boolean isTwoPane() {
        return widthDp >= 720;
    }

    @Override
    public String toString() {
        return sizeClass + " " + widthDp + "x" + heightDp + "dp";
    }
}
