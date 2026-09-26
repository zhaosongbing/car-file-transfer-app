package com.zsb.carfiletransfer.miuix;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;

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
        private OnActionListener positiveListener;
        private OnActionListener negativeListener;
        private boolean cancelable = true;

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

        public Builder setCancelable(boolean cancelable) {
            this.cancelable = cancelable;
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

            MiuixCard card = new MiuixCard(c, MiuixTheme.RADIUS_DIALOG);
            card.setContentPadding(24f);
            int width = MiuixTheme.dp(c, 420f);
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
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = MiuixTheme.dp(c, 10f);
                m.setLayoutParams(lp);
                card.addView(m);
            }
            if (content != null) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = MiuixTheme.dp(c, 14f);
                content.setLayoutParams(lp);
                card.addView(content);
            }

            if (positiveText != null || negativeText != null) {
                LinearLayout row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.END);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                rowLp.topMargin = MiuixTheme.dp(c, 20f);
                row.setLayoutParams(rowLp);

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
}
