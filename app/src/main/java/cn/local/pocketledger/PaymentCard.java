package cn.local.pocketledger;

import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

final class PaymentCard extends LinearLayout {
    private final TextView amount, detail, save;

    PaymentCard(Context context, String platform, Runnable later, Runnable skip, Runnable record, Runnable edit) {
        super(context);
        setOrientation(VERTICAL);
        setBackground(Ui.bg(context, Ui.PAPER, 24));
        setElevation(Ui.dp(context, 14));
        setPadding(Ui.dp(context, 20), Ui.dp(context, 15), Ui.dp(context, 20), Ui.dp(context, 12));
        addView(Ui.text(context, "随手账本  ·  " + platform, 13, Ui.MUTED, true));
        amount = Ui.text(context, "", 28, Ui.INK, true);
        if (edit != null) {
            amount.setOnClickListener(view -> edit.run());
            amount.setFocusable(true);
        }
        addView(amount);
        detail = Ui.text(context, "", 14, Ui.MUTED, false);
        detail.setMaxLines(2);
        addView(detail);
        LinearLayout actions = Ui.row(context);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        actions.addView(Ui.button(context, "稍后", false, later));
        actions.addView(Ui.button(context, "跳过", false, skip));
        save = Ui.button(context, "", true, record);
        actions.addView(save);
        addView(actions);
    }

    void showPayment(Long cents, String merchant) {
        amount.setText(cents == null ? "已付款" : Payment.format(cents));
        detail.setText(cents == null ? "填写金额后即可记入" : merchant.isEmpty() ? "要记入本月支出吗？" : merchant);
        save.setText(cents == null ? "填写金额" : "记入");
    }

    static PaymentCard preview(Context context, Runnable dismiss) {
        long sampleCents = 2800L;
        PaymentCard sample = new PaymentCard(context, Payment.WECHAT, () -> {
            dismiss.run();
            PaymentPrompts.showFeedback(context, Ledger.PENDING, sampleCents, true);
        }, () -> {
            dismiss.run();
            PaymentPrompts.showFeedback(context, Ledger.SKIPPED, sampleCents, true);
        }, () -> {
            dismiss.run();
            PaymentPrompts.showFeedback(context, Ledger.SAVED, sampleCents, true);
        }, null);
        sample.showPayment(sampleCents, "午餐 · 示例付款");
        return sample;
    }
}
