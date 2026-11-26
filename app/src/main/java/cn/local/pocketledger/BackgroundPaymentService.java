package cn.local.pocketledger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.util.Log;

public final class BackgroundPaymentService extends Service {
    static final String OPEN_SETTINGS = "cn.local.pocketledger.OPEN_SETTINGS";
    private static final String PREFERENCES = "background_payment";
    private static final String ENABLED = "enabled";
    private static final String CHANNEL = "background_payment";
    private static final int NOTIFICATION_ID = 24;
    private static BackgroundPaymentService running;

    private String shownStatus;
    private final ContentObserver accessChanges = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override public void onChange(boolean selfChange) {
            updateStatus();
        }
    };

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, MODE_PRIVATE);
    }

    static boolean enabled(Context context) {
        return preferences(context).getBoolean(ENABLED, false);
    }

    static boolean setEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(ENABLED, enabled).apply();
        if (enabled) return startIfEnabled(context);
        context.stopService(new Intent(context, BackgroundPaymentService.class));
        return true;
    }

    static boolean startIfEnabled(Context context) {
        if (!enabled(context) || running != null) return true;
        try {
            context.startForegroundService(new Intent(context, BackgroundPaymentService.class));
            return true;
        } catch (IllegalStateException | SecurityException rejected) {
            Log.w("PocketLedgerBackground", "Background payment service start was blocked", rejected);
            return false;
        }
    }

    static void paymentConnectionsChanged() {
        if (running != null) running.updateStatus();
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "后台付款识别", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("显示随手账本在后台运行及付款识别的状态");
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, accessChanges);
        getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED), false, accessChanges);
        getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor("enabled_notification_listeners"), false, accessChanges);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!enabled(this)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        shownStatus = status();
        Notification notification = notification(shownStatus);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        running = this;
        ComponentName listener = new ComponentName(this, PaymentNotifications.class);
        if (!PaymentNotifications.connected && getSystemService(NotificationManager.class)
                .isNotificationListenerAccessGranted(listener)) {
            NotificationListenerService.requestRebind(listener);
        }
        return START_STICKY;
    }

    private String status() {
        if (!PaymentScreen.permissionEnabled(this)) return "付款页面识别已关闭，点此开启";
        if (PaymentScreen.connected == null) return "付款页面识别正在连接";
        if (!PaymentNotifications.connected) return "付款页面识别运行中 · 通知识别未连接";
        return "付款页面和通知识别运行中";
    }

    private Notification notification(String status) {
        Intent open = new Intent(this, MainActivity.class).setAction(OPEN_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent dashboard = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("随手账本 · 后台运行中")
                .setContentText(status)
                .setContentIntent(dashboard)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false);
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return builder.build();
    }

    private void updateStatus() {
        if (running != this) return;
        String current = status();
        if (!current.equals(shownStatus)) {
            shownStatus = current;
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(current));
        }
    }

    @Override public void onDestroy() {
        if (running == this) running = null;
        getContentResolver().unregisterContentObserver(accessChanges);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    public static final class StartupReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
                startIfEnabled(context);
            }
        }
    }
}
