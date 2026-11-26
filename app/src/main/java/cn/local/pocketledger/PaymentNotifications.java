package cn.local.pocketledger;

import android.app.Notification;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class PaymentNotifications extends NotificationListenerService {
    static boolean connected;

    @Override public void onListenerConnected() {
        connected = true;
        BackgroundPaymentService.paymentConnectionsChanged();
        long now = System.currentTimeMillis();
        StatusBarNotification[] active = getActiveNotifications();
        if (active == null) return;
        for (StatusBarNotification notification : active) {
            if (now - notification.getPostTime() <= 15 * 60_000) onNotificationPosted(notification);
        }
    }

    @Override public void onListenerDisconnected() {
        connected = false;
        BackgroundPaymentService.paymentConnectionsChanged();
    }

    @Override public void onDestroy() {
        connected = false;
        BackgroundPaymentService.paymentConnectionsChanged();
        super.onDestroy();
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        String platform = Payment.platform(sbn.getPackageName());
        if (platform == null) return;
        Notification notification = sbn.getNotification();
        if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        Bundle extras = notification.extras;
        if (extras == null) return;
        String title = Payment.normalize(String.valueOf(extras.getCharSequence(Notification.EXTRA_TITLE, "")));
        Set<String> pieces = new LinkedHashSet<>();
        for (String key : new String[]{Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT}) {
            CharSequence text = extras.getCharSequence(key);
            if (text != null) pieces.add(text.toString());
        }
        long time = sbn.getPostTime();
        if (Payment.WECHAT.equals(platform)) {
            CharSequence conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
            if (conversation != null && Payment.wechatSender(conversation.toString())) title = conversation.toString();
            Parcelable[] bundled = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
            if (bundled != null && bundled.length > 0) {
                List<Notification.MessagingStyle.Message> messages =
                        Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundled);
                if (!messages.isEmpty()) {
                    Notification.MessagingStyle.Message message = messages.get(messages.size() - 1);
                    CharSequence sender = message.getSender();
                    if (sender != null && Payment.wechatSender(sender.toString())) title = sender.toString();
                    if (Payment.wechatSender(title) && message.getText() != null) {
                        pieces.clear();
                        pieces.add(message.getText().toString());
                        if (message.getTimestamp() > 0) time = message.getTimestamp();
                    }
                }
            }
        }
        if (pieces.isEmpty()) {
            CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines != null) {
                for (CharSequence line : lines) if (line != null) pieces.add(line.toString());
            }
        }
        if (pieces.isEmpty()) return;
        String body = String.join("\n", pieces);
        String signal = Payment.digest("notice:" + sbn.getKey() + ":" + time);
        Payment payment = Payment.detect(platform, title, body, "", time, Payment.NOTICE, signal);
        if (payment != null) PaymentPrompts.receive(this, payment);
    }
}
