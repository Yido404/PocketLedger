package cn.local.pocketledger;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public final class PaymentScreen extends AccessibilityService {
    private static final int UNKNOWN_RECEIPT_WINDOW = -2;
    static PaymentScreen connected;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable inspect = () -> {
        inspectQueued = false;
        inspectWindow();
    };
    private final Runnable settle = this::inspectWindow;
    private final Runnable alipayProbe = this::inspectAlipayResult;
    private final Runnable receiptWatch = this::updateReceiptButton;
    private final Runnable checkoutWatch = this::watchWechatCheckout;
    private final Runnable expire = this::next;
    private final ArrayDeque<Long> queued = new ArrayDeque<>();
    private WindowManager windows;
    private PaymentCard overlay;
    private TextView receiptButton;
    private long overlayId;
    private String route = "", activePackage = "", signal = "";
    private long checkoutAt = -1;
    private long receiptUntil;
    private Payment lastRead;
    private String lastAlipaySignal = "";
    private boolean lastAlipayHadAmount;
    private int bootCount;
    private int windowId = -1, scannedWindow = -1, scans;
    private boolean scanning;
    private long observedCheckout, nextCheckoutScan;
    private int checkoutAttempts;
    private boolean checkoutMatched;
    private boolean receiverRegistered;
    private boolean inspectQueued;

    static boolean permissionEnabled(Context context) {
        String services = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (services == null) return false;
        ComponentName target = new ComponentName(context, PaymentScreen.class);
        for (String name : services.split(":")) {
            if (target.equals(ComponentName.unflattenFromString(name))) return true;
        }
        return false;
    }

    private final BroadcastReceiver sleep = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                PaymentScreen.this.removeOverlay();
                dismissReceiptLookup();
                handler.removeCallbacks(checkoutWatch);
                while (!queued.isEmpty()) PaymentPrompts.notifyPending(context, queued.removeFirst());
            } else {
                restartCheckoutWatch();
                scheduleAlipayProbe();
            }
        }
    };

    @Override protected void onServiceConnected() {
        connected = this;
        windows = getSystemService(WindowManager.class);
        bootCount = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, 0);
        if (!receiverRegistered) {
            IntentFilter powerActions = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            powerActions.addAction(Intent.ACTION_SCREEN_ON);
            registerReceiver(sleep, powerActions);
            receiverRegistered = true;
        }
        restartCheckoutWatch();
        scheduleAlipayProbe();
        BackgroundPaymentService.paymentConnectionsChanged();
    }

    void restartCheckoutWatch() {
        handler.removeCallbacks(checkoutWatch);
        if (connected == this && getSystemService(PowerManager.class).isInteractive()) {
            handler.post(checkoutWatch);
        }
    }

    private void watchWechatCheckout() {
        if (connected != this || !getSystemService(PowerManager.class).isInteractive()) return;
        if (!PaymentOcr.autoEnabled(this) || !WechatCheckout.hasUsageAccess(this)
                || getSystemService(KeyguardManager.class).isKeyguardLocked()) {
            handler.postDelayed(checkoutWatch, 1800);
            return;
        }
        WechatCheckout.WORKER.execute(() -> {
            WechatCheckout checkout = WechatCheckout.active(getApplicationContext());
            handler.post(() -> {
                if (connected != this || !getSystemService(PowerManager.class).isInteractive()) return;
                if (checkout != null && PaymentOcr.autoEnabled(this)) inspectWechatCheckout(checkout);
                if (!handler.hasCallbacks(checkoutWatch)) handler.postDelayed(checkoutWatch, 650);
            });
        });
    }

    private void inspectWechatCheckout(WechatCheckout checkout) {
        if (observedCheckout != checkout.openedAt) {
            observedCheckout = checkout.openedAt;
            nextCheckoutScan = 0;
            checkoutAttempts = 0;
            checkoutMatched = false;
            android.util.Log.d("PocketLedgerCheckout", "WeChat checkout window opened");
        }
        if (checkoutMatched || scanning || receiptUntil > SystemClock.elapsedRealtime()
                || nextCheckoutScan > SystemClock.elapsedRealtime()) return;
        if (checkoutAttempts > 0 && System.currentTimeMillis() - checkout.openedAt > 3 * 60_000) return;
        int focus = checkoutWindow();
        if (focus < 0) return;
        checkoutAttempts++;
        nextCheckoutScan = SystemClock.elapsedRealtime() + Math.min(checkoutAttempts * 650L, 2500);
        scanWindow(false, checkout, focus);
    }

    private int checkoutWindow() {
        for (AccessibilityWindowInfo window : getWindows()) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION || !window.isFocused()) continue;
            if ("微信".contentEquals(window.getTitle() == null ? "" : window.getTitle())) return window.getId();
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) continue;
            try {
                CharSequence pkg = root.getPackageName();
                if (pkg != null && WechatCheckout.PACKAGE.contentEquals(pkg)) return window.getId();
            } finally {
                root.recycle();
            }
        }
        return -1;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence packageName = event.getPackageName();
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (packageName == null || Payment.ALIPAY_PACKAGE.contentEquals(packageName)) scheduleAlipayProbe();
            return;
        }
        if (packageName == null || Payment.platform(packageName.toString()) == null) return;
        if (!activePackage.contentEquals(packageName)) {
            route = "";
            clearRead();
            activePackage = packageName.toString();
        }
        if (event.getWindowId() >= 0) windowId = event.getWindowId();
        boolean windowChanged = event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        if (windowChanged && event.getClassName() != null) {
            String className = event.getClassName().toString();
            boolean appActivity = className.startsWith(activePackage)
                    || (Payment.ALIPAY.equals(Payment.platform(activePackage)) && className.startsWith("com.alipay."));
            if (appActivity && !route.equals(className)) {
                if (Payment.wechatCheckoutBridge(className)) {
                    checkoutAt = SystemClock.elapsedRealtime();
                    clearRead();
                } else if (!Payment.wechatLiteCheckout(className)) {
                    clearRead();
                }
                route = className;
            }
        }
        if (Payment.ALIPAY_PACKAGE.contentEquals(packageName)) {
            if (windowChanged || !handler.hasCallbacks(alipayProbe)) scheduleAlipayProbe();
            if (Payment.alipayCashierRoute(route)) return;
        }
        boolean checkout = checkoutVisible();
        if (!checkout && !readingReceipt()) return;
        if (checkout && !event.getText().isEmpty()) {
            Set<String> eventText = new LinkedHashSet<>();
            for (CharSequence item : event.getText()) add(eventText, item);
            add(eventText, event.getContentDescription());
            submitCheckout(String.join("\n", eventText));
        }
        if (windowChanged) {
            handler.removeCallbacks(settle);
            handler.postDelayed(settle, 150);
        }
        if (!inspectQueued) {
            inspectQueued = true;
            handler.postDelayed(inspect, 25);
        }
    }

    private void scheduleAlipayProbe() {
        if (Build.VERSION.SDK_INT < 29 || !handler.hasCallbacks(alipayProbe)) {
            handler.postDelayed(alipayProbe, 85);
        }
    }

    private void inspectAlipayResult() {
        if (connected != this || !getSystemService(PowerManager.class).isInteractive()
                || getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
        for (AccessibilityWindowInfo window : getWindows()) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION || !window.isFocused()) continue;
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) continue;
            try {
                if (Payment.ALIPAY_PACKAGE.equals(String.valueOf(root.getPackageName()))) {
                    readAlipaySummary(root, window.getId());
                }
            } finally {
                root.recycle();
            }
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            if (Payment.ALIPAY_PACKAGE.equals(String.valueOf(root.getPackageName())) && root.getWindowId() >= 0) {
                readAlipaySummary(root, root.getWindowId());
            }
        } finally {
            root.recycle();
        }
    }

    private void readAlipaySummary(AccessibilityNodeInfo root, int id) {
        String screenSignal = "alipay-window:" + bootCount + ":" + id;
        if (screenSignal.equals(lastAlipaySignal) && lastAlipayHadAmount) return;
        Rect area = new Rect();
        root.getBoundsInScreen(area);
        if (area.isEmpty()) return;
        AlipaySummary summary = new AlipaySummary(area.top + area.height() * 36 / 100);
        summary.collect(root, 0);
        Payment payment = Payment.alipayResult(String.join("\n", summary.header), summary.hasCompletion,
                Payment.alipayCashierRoute(route), System.currentTimeMillis(), screenSignal);
        if (payment == null || (payment.cents == null && screenSignal.equals(lastAlipaySignal))) return;
        lastAlipaySignal = screenSignal;
        lastAlipayHadAmount = payment.cents != null;
        Ledger.Result result = PaymentPrompts.receive(this, payment);
        android.util.Log.d("PocketLedgerCheckout", "Alipay success; new=" + result.fresh
                + ", amount=" + lastAlipayHadAmount);
    }

    private static final class AlipaySummary {
        final Set<String> header = new LinkedHashSet<>();
        final int headerBottom;
        final Rect bounds = new Rect();
        int remaining = 320;
        boolean hasCompletion;

        AlipaySummary(int headerBottom) { this.headerBottom = headerBottom; }

        void collect(AccessibilityNodeInfo node, int depth) {
            if (--remaining < 0 || depth > 25 || !node.isVisibleToUser() || node.isPassword()) return;
            node.getBoundsInScreen(bounds);
            if (bounds.top < headerBottom) {
                add(header, node.getText());
                add(header, node.getContentDescription());
            }
            if (!hasCompletion) {
                hasCompletion = completion(node.getText()) || completion(node.getContentDescription());
            }
            for (int i = 0; i < node.getChildCount() && remaining > 0; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child == null) continue;
                try {
                    collect(child, depth + 1);
                } finally {
                    child.recycle();
                }
            }
        }

        private boolean completion(CharSequence text) {
            if (text == null || text.length() > 12) return false;
            String value = Payment.normalize(text.toString());
            return value.equals("完成") || value.equals("返回商家") || value.equals("返回商户");
        }
    }

    private boolean checkoutVisible() {
        if (Payment.wechatLiteCheckout(route)) {
            return checkoutAt >= 0 && SystemClock.elapsedRealtime() - checkoutAt < 5 * 60_000;
        }
        return Payment.paymentRoute(route) || Payment.resultRoute(route);
    }

    private boolean readingReceipt() {
        return receiptUntil > SystemClock.elapsedRealtime() && Payment.WECHAT.equals(Payment.platform(activePackage));
    }

    void startReceiptLookup() {
        android.util.Log.d("PocketLedgerReceipt", "Receipt lookup armed");
        receiptUntil = SystemClock.elapsedRealtime() + 3 * 60_000;
        scannedWindow = -1;
        handler.removeCallbacks(receiptWatch);
        handler.postDelayed(receiptWatch, 350);
    }

    private void inspectWindow() {
        boolean lookup = readingReceipt();
        if (!checkoutVisible() && !lookup) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        Set<String> lines = new LinkedHashSet<>();
        if (root != null) {
            try {
                if (root.getPackageName() == null || !activePackage.contentEquals(root.getPackageName())) return;
                if (root.getWindowId() >= 0) windowId = root.getWindowId();
                collect(root, lines, new int[]{420}, 0);
            } finally {
                root.recycle();
            }
        }
        String body = String.join("\n", lines);
        if (lookup && submitReceipt(body)) return;
        if (checkoutVisible()) submitCheckout(body);
        boolean checkoutWindow = checkoutVisible() && Payment.WECHAT.equals(Payment.platform(activePackage))
                && (Payment.wechatLiteCheckout(route) || Payment.resultRoute(route))
                && (lastRead == null || lastRead.cents == null) && PaymentOcr.autoEnabled(this)
                && !WechatCheckout.hasUsageAccess(this);
        if (!lookup && checkoutWindow) scanWindow(false, null, windowId);
    }

    private int selectedReceiptWindow() {
        for (AccessibilityWindowInfo window : getWindows()) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION
                    || !(window.isFocused() || window.isActive())) continue;
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) return window.getId();
            try {
                CharSequence name = root.getPackageName();
                // In explicit receipt mode, the selected WeChat window can omit accessibility metadata.
                if (name != null && "com.tencent.mm".contentEquals(name)) return window.getId();
                return UNKNOWN_RECEIPT_WINDOW;
            } finally {
                root.recycle();
            }
        }
        return UNKNOWN_RECEIPT_WINDOW;
    }

    private void updateReceiptButton() {
        if (receiptUntil <= SystemClock.elapsedRealtime()) {
            dismissReceiptLookup();
            return;
        }
        if (receiptButton == null && Build.VERSION.SDK_INT >= 34 && overlay == null && !scanning) {
            TextView button = Ui.button(this, "识别这笔", true, this::scanReceipt);
            button.setElevation(Ui.dp(this, 10));
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(-2, -2,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.BOTTOM | Gravity.END;
            params.x = Ui.dp(this, 18);
            params.y = Ui.dp(this, 28);
            params.setTitle("随手账本识别账单");
            try {
                windows.addView(button, params);
                receiptButton = button;
                android.util.Log.d("PocketLedgerReceipt", "Receipt button displayed");
            } catch (RuntimeException e) {
                android.util.Log.d("PocketLedgerReceipt", "Receipt button unavailable: " + e.getClass().getSimpleName());
            }
        }
        handler.postDelayed(receiptWatch, 450);
    }

    private void scanReceipt() {
        int active = selectedReceiptWindow();
        if (scanning) return;
        windowId = active;
        activePackage = "com.tencent.mm";
        scannedWindow = -1;
        removeReceiptButton();
        scanWindow(true, null, windowId);
    }

    private void removeReceiptButton() {
        if (receiptButton != null && receiptButton.isAttachedToWindow()) windows.removeViewImmediate(receiptButton);
        receiptButton = null;
    }

    private void dismissReceiptLookup() {
        receiptUntil = 0;
        handler.removeCallbacks(receiptWatch);
        removeReceiptButton();
    }

    private boolean submitReceipt(String body) {
        Payment receipt = Payment.wechatReceipt(body, System.currentTimeMillis());
        if (receipt == null) return false;
        dismissReceiptLookup();
        Ledger.Result result = PaymentPrompts.receive(this, receipt);
        if (!result.fresh) {
            Ledger.Entry existing = Ledger.get(this).entry(result.id);
            if (existing != null && existing.state == Ledger.PENDING) {
                if (!offer(result.id)) PaymentPrompts.notifyPending(this, result.id);
            } else {
                android.widget.Toast.makeText(this, "这笔付款已处理，可在账本中查看", android.widget.Toast.LENGTH_SHORT).show();
            }
        }
        return true;
    }

    private void scanWindow(boolean receipt, WechatCheckout checkout, int capturedWindow) {
        if (Build.VERSION.SDK_INT < 34 || (!receipt && capturedWindow < 0) || scanning) return;
        if (checkout == null) {
            if (scannedWindow != capturedWindow) {
                scannedWindow = capturedWindow;
                scans = 0;
            }
            if (scans >= (receipt ? 3 : 2)) return;
            scans++;
        }
        scanning = true;
        TakeScreenshotCallback callback = new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult screenshot) {
                PaymentOcr.WORKER.execute(() -> {
                    String text = "";
                    try (HardwareBuffer buffer = screenshot.getHardwareBuffer()) {
                        Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.getColorSpace());
                        if (hardware != null) {
                            try {
                                Bitmap image = hardware.copy(Bitmap.Config.ARGB_8888, false);
                                if (image != null) {
                                    try {
                                        text = PaymentOcr.read(getApplicationContext(), image, receipt);
                                    } finally {
                                        image.recycle();
                                    }
                                }
                            } finally {
                                hardware.recycle();
                            }
                        }
                    } catch (IOException | RuntimeException | LinkageError ignored) {
                        // The notification and accessibility text paths still work without OCR.
                    }
                    String recognized = text;
                    handler.post(() -> scanFinished(capturedWindow, receipt, checkout, recognized, 0));
                });
            }

            @Override public void onFailure(int error) {
                scanFinished(capturedWindow, receipt, checkout, "", error);
            }
        };
        if (receipt) android.util.Log.d("PocketLedgerReceipt", "Selected receipt capture requested");
        if (capturedWindow == UNKNOWN_RECEIPT_WINDOW) takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), callback);
        else takeScreenshotOfWindow(capturedWindow, getMainExecutor(), callback);
    }

    private void scanFinished(int capturedWindow, boolean receipt, WechatCheckout checkout, String text, int error) {
        scanning = false;
        if (connected != this) return;
        if (checkout != null) {
            if (observedCheckout != checkout.openedAt || !PaymentOcr.autoEnabled(this)) return;
            if (error == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) return;
            Payment detected = Payment.detect(Payment.WECHAT, "", text, checkout.route,
                    checkout.startedAt, Payment.SCREEN, checkout.signal());
            if (detected != null) {
                checkoutMatched = detected.cents != null;
                PaymentPrompts.receive(this, new Payment(detected.platform, detected.cents, detected.merchant,
                        detected.order, detected.time, Payment.CHECKOUT, detected.signal));
            }
            android.util.Log.d("PocketLedgerCheckout", "Checkout capture status=" + error
                    + ", success=" + (detected != null) + ", amount=" + checkoutMatched);
            return;
        }
        if (receipt) {
            android.util.Log.d("PocketLedgerReceipt", "Selected receipt capture finished, status=" + error);
            if (!readingReceipt() || submitReceipt(text)) return;
            Toast.makeText(this, error == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
                    ? "微信限制读取这个画面，可手动补记"
                    : error == 0 ? "没读到付款详情，请打开具体账单再试"
                    : "暂时无法读取微信画面，请再试一次", Toast.LENGTH_SHORT).show();
            return;
        } else {
            if (windowId != capturedWindow || !checkoutVisible() || !PaymentOcr.autoEnabled(this)) return;
            submitCheckout(text);
            if (lastRead != null && lastRead.cents != null) return;
        }
        if (error == 0 || error == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
            handler.removeCallbacks(settle);
            handler.postDelayed(settle, 650);
        }
    }

    private void submitCheckout(String body) {
        String key = signal.isEmpty() ? UUID.randomUUID().toString() : signal;
        Payment p = Payment.detect(Payment.platform(activePackage), "", body, route, System.currentTimeMillis(),
                Payment.SCREEN, "screen:" + key);
        if (p == null) return;
        if (lastRead != null && ((!p.order.isEmpty() && !lastRead.order.isEmpty() && !p.order.equals(lastRead.order))
                || (p.cents != null && lastRead.cents != null && !p.cents.equals(lastRead.cents)))) {
            key = UUID.randomUUID().toString();
            p = new Payment(p.platform, p.cents, p.merchant, p.order, p.time, p.source, "screen:" + key);
        } else if (lastRead != null) {
            boolean addedAmount = lastRead.cents == null && p.cents != null;
            boolean addedOrder = lastRead.order.isEmpty() && !p.order.isEmpty();
            boolean addedMerchant = lastRead.merchant.isEmpty() && !p.merchant.isEmpty();
            if (!addedAmount && !addedOrder && !addedMerchant) return;
            p = new Payment(p.platform, p.cents == null ? lastRead.cents : p.cents,
                    p.merchant.isEmpty() ? lastRead.merchant : p.merchant,
                    p.order.isEmpty() ? lastRead.order : p.order, lastRead.time, p.source, p.signal);
        }
        signal = key;
        lastRead = p;
        PaymentPrompts.receive(this, p);
    }

    private void clearRead() {
        signal = "";
        lastRead = null;
    }

    private static void collect(AccessibilityNodeInfo node, Set<String> lines, int[] budget, int depth) {
        if (--budget[0] < 0 || depth > 25 || !node.isVisibleToUser() || node.isPassword()) return;
        add(lines, node.getText());
        add(lines, node.getContentDescription());
        for (int i = 0; i < node.getChildCount() && budget[0] > 0; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                collect(child, lines, budget, depth + 1);
            } finally {
                child.recycle();
            }
        }
    }

    private static void add(Set<String> lines, CharSequence text) {
        if (text == null || text.length() > 200) return;
        String value = Payment.normalize(text.toString());
        if (!value.isEmpty()) lines.add(value);
    }

    boolean offer(long id) {
        if (!getSystemService(PowerManager.class).isInteractive()
                || getSystemService(KeyguardManager.class).isKeyguardLocked()) return false;
        if (overlay != null) {
            if (overlayId != id && !queued.contains(id)) queued.addLast(id);
            return true;
        }
        Ledger.Entry row = Ledger.get(this).entry(id);
        if (row == null || row.state != Ledger.PENDING) return true;
        Runnable edit = () -> {
            PaymentPrompts.openEditor(this, id, true);
            next();
        };
        PaymentCard panel = new PaymentCard(this, row.platform, this::next, () -> {
            PaymentPrompts.decide(this, id, Ledger.SKIPPED);
            next();
        }, () -> {
            Ledger.Entry current = Ledger.get(this).entry(id);
            if (current == null || current.state != Ledger.PENDING) {
                next();
            } else if (current.cents == null) {
                edit.run();
            } else {
                PaymentPrompts.decide(this, id, Ledger.SAVED);
                next();
            }
        }, edit);
        panel.showPayment(row.cents, row.merchant);
        return display(panel, id);
    }

    boolean preview() {
        if (overlay != null) return false;
        PaymentCard sample = new PaymentCard(this, Payment.WECHAT, this::next, this::next, this::next, null);
        sample.showPayment(2800L, "午餐 · 示例付款");
        return display(sample, 0);
    }

    private boolean display(PaymentCard panel, long id) {
        int width = Math.min(getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 32), Ui.dp(this, 420));
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(width, -2,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.y = Ui.dp(this, 16);
        params.setTitle("随手账本付款提示");
        try {
            windows.addView(panel, params);
        } catch (RuntimeException e) {
            return false;
        }
        overlay = panel;
        overlayId = id;
        handler.postDelayed(expire, 16_000);
        return true;
    }

    void refresh(long id) {
        if (overlay == null || id != overlayId) return;
        Ledger.Entry row = Ledger.get(this).entry(id);
        if (row == null) return;
        overlay.showPayment(row.cents, row.merchant);
    }

    private void next() {
        removeOverlay();
        while (overlay == null && !queued.isEmpty()) {
            long id = queued.removeFirst();
            if (!offer(id)) PaymentPrompts.notifyPending(this, id);
        }
    }

    private void removeOverlay() {
        handler.removeCallbacks(expire);
        if (overlay != null && overlay.isAttachedToWindow()) windows.removeViewImmediate(overlay);
        overlay = null;
        overlayId = 0;
    }

    private void cleanUp() {
        if (connected == this) {
            connected = null;
            BackgroundPaymentService.paymentConnectionsChanged();
        }
        handler.removeCallbacksAndMessages(null);
        inspectQueued = false;
        checkoutAt = -1;
        dismissReceiptLookup();
        clearRead();
        removeOverlay();
        while (!queued.isEmpty()) PaymentPrompts.notifyPending(this, queued.removeFirst());
        if (receiverRegistered) {
            unregisterReceiver(sleep);
            receiverRegistered = false;
        }
    }

    @Override public void onInterrupt() { next(); }
    @Override public boolean onUnbind(Intent intent) { cleanUp(); return super.onUnbind(intent); }
    @Override public void onDestroy() { cleanUp(); super.onDestroy(); }
}
