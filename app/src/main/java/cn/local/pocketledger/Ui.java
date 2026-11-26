package cn.local.pocketledger;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int BACK = 0xfff5f6f0, PAPER = 0xffffffff, INK = 0xff172921, MUTED = 0xff58675f,
            GREEN = 0xff275749, SOFT = 0xffe7eee5, LINE = 0xffe4e8df;

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable bg(Context context, int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(context, radius));
        return shape;
    }

    static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    static LinearLayout row(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    static TextView text(Context context, String value, int size, int color, boolean bold) {
        TextView label = new TextView(context);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(color);
        if (bold) label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return label;
    }

    static TextView button(Context context, String value, boolean primary, Runnable action) {
        TextView button = text(context, value, 14, primary ? PAPER : GREEN, true);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(context, 48));
        button.setPadding(dp(context, 13), 0, dp(context, 13), 0);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2c648979),
                bg(context, primary ? GREEN : Color.TRANSPARENT, 13), bg(context, PAPER, 13)));
        button.setFocusable(true);
        button.setOnClickListener(v -> action.run());
        button.setAccessibilityDelegate(new android.view.View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(android.view.View host,
                    android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Button");
            }
        });
        return button;
    }
}
