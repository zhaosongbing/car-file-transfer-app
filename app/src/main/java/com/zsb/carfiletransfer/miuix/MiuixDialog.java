package com.zsb.carfiletransfer.miuix;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/**
 * MIUIX Dialog / SuperDialog: rounded surface with title, message, optional
 * content view and up to two action buttons.
 */
public class MiuixDialog {

    public interface OnActionListener {
        void onAction(MiuixDialog dialog);
    }

    private final Dialog dialog;

    private MiuixDialog(Dialog dialog) {
        this.dialog = dialog;
    }

    public void dismiss() {
        try {
            dialog.dismiss();
        } catch (Exception ignored) {
        }
    }

    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

    public static class Builder {

        private final Context c;
        private CharSequence title;
        private CharSequence message;
        private View content;
        private CharSequence positiveText;
        private CharSequence negativeText;
        private CharSequence neutralText;
        private OnActionListener positiveListener;
        private OnActionListener negativeListener;
        private OnActionListener neutralListener;
        private boolean cancelable = true;
        private float widthDp = 420f;
        private float maxMessageHeightDp = 320f;
        private boolean messageScrollable = true;

        public Builder(Context c) {
            this.c = c;
        }

        public Builder setTitle(CharSequence t) {
            title = t;
            return this;
        }

        public Builder setMessage(CharSequence m) {
            message = m;
            return this;
        }

        public Builder setContent(View v) {
            content = v;
            return this;
        }

        public Builder setPositive(CharSequence text, OnActionListener l) {
            positiveText = text;
            positiveListener = l;
            return this;
        }

        public Builder setNegative(CharSequence text, OnActionListener l) {
            negativeText = text;
            negativeListener = l;
            return this;
        }

        /** Third, low-emphasis action - rendered on the far left of the row. */
        public Builder setNeutral(CharSequence text, OnActionListener l) {
            neutralText = text;
            neutralListener = l;
            return this;
        }

        public Builder setCancelable(boolean cancelable) {
            this.cancelable = cancelable;
            return this;
        }

        /** Dialog width from the design spec, in dp. */
        public Builder setWidthDp(float dp) {
            widthDp = dp;
            return this;
        }

        /** Card corner radius from the design spec, in dp. */
        public Builder setRadiusDp(float dp) {
            radiusDp = dp;
            return this;
        }

        private float radiusDp = MiuixTheme.RADIUS_DIALOG;

        /** Card content padding from the design spec, in dp. */
        public Builder setPaddingDp(float dp) {
            paddingDp = dp;
            return this;
        }

        private float paddingDp = 24f;

        /** Cap for how tall the scrollable message area may grow, in dp. */
        public Builder setMaxMessageHeightDp(float dp) {
            this.maxMessageHeightDp = dp;
            return this;
        }

        /** Turn off message scrolling when the text is known to be short. */
        public Builder setMessageScrollable(boolean scrollable) {
            this.messageScrollable = scrollable;
            return this;
        }

        public MiuixDialog show() {
            final MiuixDialog[] ref = new MiuixDialog[1];
            Dialog d = new Dialog(c);
            d.requestWindowFeature(Window.FEATURE_NO_TITLE);
            if (d.getWindow() != null) {
                d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                d.getWindow().setDimAmount(0.35f);
            }
            d.setCancelable(cancelable);

            MiuixCard card = new MiuixCard(c, radiusDp);
            card.setContentPadding(paddingDp);
            int width = MiuixTheme.dp(c, widthDp);
            int screen = c.getResources().getDisplayMetrics().widthPixels;
            int max = (int) (screen * 0.86f);
            card.setLayoutParams(new ViewGroup.LayoutParams(Math.min(width, max),
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            if (title != null && title.length() > 0) {
                MiuixText t = new MiuixText(c, title, MiuixText.Role.TITLE);
                card.addView(t);
            }
            if (message != null && message.length() > 0) {
                MiuixText m = new MiuixText(c, message, MiuixText.Role.BODY,
                        MiuixText.Tone.SECONDARY);
                if (messageScrollable) {
                    // Long text (release notes) must never push the action row
                    // off screen - scroll inside a capped area instead.
                    MaxHeightScrollView sv =
                            new MaxHeightScrollView(c, MiuixTheme.dp(c, maxMessageHeightDp));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.topMargin = MiuixTheme.dp(c, 10f);
                    sv.setLayoutParams(lp);
                    sv.addView(m, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
                    card.addView(sv);
                } else {
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.topMargin = MiuixTheme.dp(c, 10f);
                    m.setLayoutParams(lp);
                    card.addView(m);
                }
            }
            if (content != null) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = MiuixTheme.dp(c, 14f);
                content.setLayoutParams(lp);
                card.addView(content);
            }

            if (positiveText != null || negativeText != null || neutralText != null) {
                LinearLayout row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.END);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                rowLp.topMargin = MiuixTheme.dp(c, 20f);
                row.setLayoutParams(rowLp);

                if (neutralText != null) {
                    MiuixButton neu = new MiuixButton(c, neutralText,
                            MiuixButton.Size.MEDIUM, MiuixButton.Color.NEUTRAL);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.rightMargin = MiuixTheme.dp(c, 12f);
                    neu.setLayoutParams(lp);
                    neu.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) {
                            if (neutralListener != null && ref[0] != null) {
                                neutralListener.onAction(ref[0]);
                            } else if (ref[0] != null) {
                                ref[0].dismiss();
                            }
                        }
                    });
                    row.addView(neu);
                }
                if (negativeText != null) {
                    MiuixButton neg = new MiuixButton(c, negativeText,
                            MiuixButton.Size.MEDIUM, MiuixButton.Color.NEUTRAL);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.rightMargin = MiuixTheme.dp(c, 12f);
                    neg.setLayoutParams(lp);
                    neg.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) {
                            if (negativeListener != null && ref[0] != null) {
                                negativeListener.onAction(ref[0]);
                            } else if (ref[0] != null) {
                                ref[0].dismiss();
                            }
                        }
                    });
                    row.addView(neg);
                }
                if (positiveText != null) {
                    MiuixButton pos = new MiuixButton(c, positiveText,
                            MiuixButton.Size.MEDIUM, MiuixButton.Color.PRIMARY);
                    pos.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) {
                            if (positiveListener != null && ref[0] != null) {
                                positiveListener.onAction(ref[0]);
                            } else if (ref[0] != null) {
                                ref[0].dismiss();
                            }
                        }
                    });
                    row.addView(pos);
                }
                card.addView(row);
            }

            d.setContentView(card);
            d.show();

            MiuixDialog wrapper = new MiuixDialog(d);
            // late binding so listeners can dismiss the dialog themselves
            ref[0] = wrapper;
            return wrapper;
        }
    }

    /**
     * A scroll container that never grows taller than {@code maxHeightPx}, so a
     * long message scrolls inside the card instead of overflowing off screen.
     */
    private static final class MaxHeightScrollView extends ScrollView {

        private final int maxHeightPx;

        MaxHeightScrollView(Context c, int maxHeightPx) {
            super(c);
            this.maxHeightPx = maxHeightPx;
            setVerticalScrollBarEnabled(true);
            setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int capped = View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST);
            super.onMeasure(widthSpec, capped);
        }
    }
}
