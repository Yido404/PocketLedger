package cn.local.pocketledger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

final class PaymentPrompts {
    static final String CHANNEL = "payments";
    static final String ENTRY = "entry";

    static Ledger.Result receive(Context context, Payment payment) {
        Ledger ledger = Ledger.get(context);
        Ledger.Result result = ledger.receive(payment);
        if (result.fresh || result.enriched) ledger.changed();
        PaymentScreen screen = PaymentScreen.connected;
        if (result.fresh) {
            if (screen == null || !screen.offer(result.id)) notifyPending(context, result.id);
        } else if (result.enriched) {
            if (screen != null) screen.refresh(result.id);
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            for (android.service.notification.StatusBarNotification active : manager.getActiveNotifications()) {
                if (("payment:" + result.id).equals(active.getTag())) notifyPending(context, result.id);
            }
        }
        return result;
    }

    static void channel(Context context) {
        NotificationChannel channel = new NotificationChannel(CHANNEL, "付款记账提示", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("无法直接显示弹框时，显示待确认的付款");
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    static boolean notificationsEnabled(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return manager.areNotificationsEnabled() && (channel == null || channel.getImportance() > 0);
    }

    static void notifyPending(Context context, long id) {
        Ledger.Entry row = Ledger.get(context).entry(id);
        if (row == null || row.state != Ledger.PENDING || !notificationsEnabled(context)) return;
        channel(context);
        PendingIntent open = PendingIntent.getActivity(context, 0, editor(context, id),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(row.platform + (row.cents == null ? " · 已付款" : " · " + Payment.format(row.cents)))
                .setContentText(row.cents == null ? "点此填写金额" : row.merchant.isEmpty() ? "要记入本月支出吗？" : row.merchant)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setAutoCancel(true).setOnlyAlertOnce(true).setContentIntent(open);
        builder.addAction(new Notification.Action.Builder(null, row.cents == null ? "填写金额" : "记入",
                row.cents == null ? open : action(context, id, Ledger.SAVED)).build());
        builder.addAction(new Notification.Action.Builder(null, "跳过", action(context, id, Ledger.SKIPPED)).build());
        context.getSystemService(NotificationManager.class).notify("payment:" + id, 1, builder.build());
    }

    private static PendingIntent action(Context context, long id, int state) {
        Intent intent = new Intent(context, PaymentAction.class).setData(Uri.parse("ledger:action/" + id + "/" + state))
                .putExtra(ENTRY, id).putExtra("state", state);
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE);
    }

    private static Intent editor(Context context, long id) {
        return new Intent(context, MainActivity.class).setData(Uri.parse("ledger:entry/" + id))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(ENTRY, id);
    }

    static void openEditor(Context context, long id, boolean editAmount) {
        context.startActivity(editor(context, id).putExtra("edit_amount", editAmount));
    }

    static void cancel(Context context, long id) {
        context.getSystemService(NotificationManager.class).cancel("payment:" + id, 1);
    }

    static void decide(Context context, long id, int state) {
        Ledger ledger = Ledger.get(context);
        Ledger.Entry row = ledger.entry(id);
        if (row != null && row.state == Ledger.PENDING && ledger.decide(id, state, null, null)) {
            cancel(context, id);
            showFeedback(context, state, row.cents, false);
        }
    }

    static void showFeedback(Context context, int state, Long cents, boolean preview) {
        String message;
        if (state == Ledger.SAVED) {
            message = cents == null ? "已记入账本" : "已记入 " + Payment.format(cents);
        } else if (state == Ledger.SKIPPED) {
            message = "已跳过这笔付款";
        } else {
            message = "已留在待确认";
        }
        Toast.makeText(context, preview ? "示例 · " + message : message, Toast.LENGTH_SHORT).show();
    }
}
