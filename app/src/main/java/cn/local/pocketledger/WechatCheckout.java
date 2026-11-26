package cn.local.pocketledger;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageEventsQuery;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Build;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class WechatCheckout {
    static final String PACKAGE = "com.tencent.mm";
    static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final long LOOKBACK = 15 * 60_000L;

    final String route;
    final long openedAt;
    final long startedAt;

    WechatCheckout(String route, long openedAt, long startedAt) {
        this.route = route;
        this.openedAt = openedAt;
        this.startedAt = startedAt;
    }

    String signal() {
        return "wechat-checkout:" + openedAt;
    }

    static boolean hasUsageAccess(Context context) {
        if (Build.VERSION.SDK_INT < 35) return false;
        return context.getSystemService(AppOpsManager.class).unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.getPackageName())
                == AppOpsManager.MODE_ALLOWED;
    }

    static WechatCheckout active(Context context) {
        if (Build.VERSION.SDK_INT < 35 || !hasUsageAccess(context)) return null;
        long now = System.currentTimeMillis();
        UsageEventsQuery query = new UsageEventsQuery.Builder(now - LOOKBACK, now + 1)
                .setPackageNames(PACKAGE)
                .setEventTypes(UsageEvents.Event.ACTIVITY_RESUMED, UsageEvents.Event.ACTIVITY_PAUSED,
                        UsageEvents.Event.ACTIVITY_STOPPED).build();
        UsageEvents events;
        try {
            events = context.getSystemService(UsageStatsManager.class).queryEvents(query);
        } catch (SecurityException ignored) {
            return null;
        }
        if (events == null) return null;
        String foreground = "";
        long resumedAt = 0;
        long bridgeAt = 0;
        WechatCheckout checkout = null;
        UsageEvents.Event event = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            if (!events.getNextEvent(event)) break;
            String activity = event.getClassName();
            if (activity == null) continue;
            if (event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                foreground = activity;
                resumedAt = event.getTimeStamp();
                if (Payment.wechatCheckoutBridge(activity)) bridgeAt = resumedAt;
                if (checkout == null || !checkout.route.equals(activity)) {
                    checkout = identify(activity, resumedAt, bridgeAt);
                }
            } else if (activity.equals(foreground)) {
                foreground = "";
            }
        }
        return checkout != null && checkout.route.equals(foreground) ? checkout : null;
    }

    static WechatCheckout identify(String foreground, long resumedAt, long bridgeAt) {
        if (resumedAt <= 0) return null;
        if (Payment.resultRoute(foreground)) return new WechatCheckout(foreground, resumedAt, resumedAt);
        if (Payment.wechatLiteCheckout(foreground) && bridgeAt > 0 && resumedAt - bridgeAt >= 0
                && resumedAt - bridgeAt <= 30_000) {
            return new WechatCheckout(foreground, resumedAt, bridgeAt);
        }
        return null;
    }
}
