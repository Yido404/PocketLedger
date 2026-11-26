package cn.local.pocketledger;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int PICK_BILL = 20;
    private static final int ALLOW_NOTIFICATIONS = 21;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("M月d日 HH:mm").withZone(ZONE);
    private static final String[] PLATFORMS = {Payment.ALIPAY, Payment.WECHAT, Payment.OTHER};
    private static final ExecutorService IMPORTER = Executors.newSingleThreadExecutor();
    private final Runnable ledgerChanged = this::render;
    private YearMonth month = YearMonth.now(ZONE);
    private Ledger ledger;
    private String page = "home";
    private ScrollView scroll;
    private boolean importing;
    private boolean dialogOpen;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
            getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        }
        ledger = Ledger.get(this);
        PaymentPrompts.channel(this);
        if (state != null) {
            page = state.getString("page", "home");
            month = YearMonth.parse(state.getString("month", month.toString()));
        }
        render();
        if (state == null) handleIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("page", page);
        out.putString("month", month.toString());
        super.onSaveInstanceState(out);
    }

    @Override protected void onResume() {
        super.onResume();
        ledger.observe(ledgerChanged);
        BackgroundPaymentService.startIfEnabled(this);
        render();
    }

    @Override protected void onPause() {
        ledger.forget(ledgerChanged);
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (page.equals("home")) super.onBackPressed();
        else navigate("home");
    }

    private void handleIntent(Intent intent) {
        if (BackgroundPaymentService.OPEN_SETTINGS.equals(intent.getAction())) {
            navigate("settings");
            return;
        }
        long id = intent.getLongExtra(PaymentPrompts.ENTRY, -1);
        if (id > 0) {
            Ledger.Entry row = ledger.entry(id);
            if (row != null && row.state == Ledger.PENDING) {
                if (intent.getBooleanExtra("edit_amount", false)) edit(row);
                else confirm(row);
            }
        }
    }

    private int dp(int value) { return Ui.dp(this, value); }

    private void render() {
        int offset = scroll == null ? 0 : scroll.getScrollY();
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BACK);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            }
            return insets;
        });
        LinearLayout body = Ui.column(this);
        body.setPadding(dp(22), dp(12), dp(22), dp(24));
        scroll.addView(body);
        setContentView(scroll);
        switch (page) {
            case "pending": renderPending(body, Ledger.PENDING); break;
            case "skipped": renderPending(body, Ledger.SKIPPED); break;
            case "settings": renderSettings(body); break;
            default: renderHome(body); break;
        }
        ScrollView current = scroll;
        current.post(() -> current.scrollTo(0, offset));
    }

    private void navigate(String destination) {
        page = destination;
        scroll = null;
        render();
    }

    private void toolbar(LinearLayout body, String title, String action, Runnable callback) {
        LinearLayout bar = Ui.row(this);
        if (!page.equals("home")) {
            TextView back = Ui.button(this, "‹", false, () -> navigate("home"));
            back.setTextSize(25);
            back.setContentDescription("返回首页");
            bar.addView(back);
        }
        bar.addView(Ui.text(this, title, 24, Ui.INK, true), weighted());
        if (action != null) bar.addView(Ui.button(this, action, false, callback));
        body.addView(bar);
        space(body, 15);
    }

    private void renderHome(LinearLayout body) {
        toolbar(body, "随手账本", "设置", () -> navigate("settings"));
        long start = month.atDay(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
        long end = month.plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
        List<Ledger.Entry> records = ledger.entries(Ledger.SAVED, start, end);
        long total = 0, alipay = 0, wechat = 0;
        for (Ledger.Entry entry : records) {
            if (entry.cents == null) continue;
            total += entry.cents;
            if (Payment.ALIPAY.equals(entry.platform)) alipay += entry.cents;
            if (Payment.WECHAT.equals(entry.platform)) wechat += entry.cents;
        }

        LinearLayout summary = card(Ui.GREEN, 21);
        LinearLayout months = Ui.row(this);
        TextView period = Ui.text(this, month.getYear() + " 年 " + month.getMonthValue() + " 月", 14, 0xffe4eee6, true);
        months.addView(period, weighted());
        months.addView(monthButton("‹", "上个月", -1));
        months.addView(monthButton("›", "下个月", 1));
        summary.addView(months);
        summary.addView(Ui.text(this, month.equals(YearMonth.now(ZONE)) ? "本月支出" : "当月支出", 14, 0xffd5e9dd, false));
        TextView amount = Ui.text(this, Payment.format(total), 40, Ui.PAPER, true);
        amount.setLetterSpacing(-0.025f);
        summary.addView(amount);
        space(summary, 16);
        LinearLayout breakdown = Ui.row(this);
        breakdown.addView(platformTotal(Payment.ALIPAY, alipay), weighted());
        breakdown.addView(platformTotal(Payment.WECHAT, wechat), weighted());
        if (total != alipay + wechat) breakdown.addView(platformTotal("其他", total - alipay - wechat), weighted());
        summary.addView(breakdown);
        body.addView(summary);
        space(body, 12);

        int pending = ledger.countPending();
        LinearLayout inbox = card(Ui.PAPER, 12);
        LinearLayout inboxRow = Ui.row(this);
        inboxRow.addView(Ui.text(this, "待确认", 16, Ui.INK, true), weighted());
        inboxRow.addView(Ui.button(this, pending > 0 ? pending + " 笔待处理  ›" : "查看  ›", false,
                () -> navigate("pending")));
        inbox.addView(inboxRow);
        body.addView(inbox);
        if (!screenEnabled()) {
            space(body, 8);
            body.addView(Ui.button(this, "付款页面识别已关闭  ·  去开启  ›", false,
                    () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        }

        space(body, 13);
        LinearLayout header = Ui.row(this);
        header.addView(Ui.text(this, "交易明细", 18, Ui.INK, true), weighted());
        header.addView(Ui.button(this, "＋ 补记", true, () -> edit(null)));
        body.addView(header);
        space(body, 8);
        renderRows(body, records, "这个月还没有记入的支出");
    }

    private TextView monthButton(String label, String description, int change) {
        TextView button = Ui.button(this, label, false, () -> {
            month = month.plusMonths(change);
            render();
        });
        button.setTextColor(Ui.PAPER);
        button.setTextSize(25);
        button.setMinWidth(dp(44));
        button.setContentDescription(description);
        return button;
    }

    private LinearLayout platformTotal(String platform, long cents) {
        LinearLayout block = Ui.column(this);
        block.addView(Ui.text(this, platform, 12, 0xffd5e9dd, false));
        block.addView(Ui.text(this, Payment.format(cents), 16, Ui.PAPER, true));
        return block;
    }

    private void renderPending(LinearLayout body, int state) {
        boolean pending = state == Ledger.PENDING;
        toolbar(body, pending ? "待确认" : "已跳过", pending ? "已跳过" : null, () -> navigate("skipped"));
        renderRows(body, ledger.entries(state, Long.MIN_VALUE, Long.MAX_VALUE),
                pending ? "没有待确认的付款" : "没有已跳过的付款");
    }

    private void renderRows(LinearLayout body, List<Ledger.Entry> entries, String empty) {
        LinearLayout list = card(Ui.PAPER, 12);
        if (entries.isEmpty()) {
            TextView hint = Ui.text(this, empty, 15, Ui.MUTED, false);
            hint.setGravity(Gravity.CENTER);
            hint.setMinHeight(dp(88));
            list.addView(hint);
        }
        for (Ledger.Entry entry : entries) {
            if (list.getChildCount() > 0) {
                View divider = new View(this);
                divider.setBackgroundColor(Ui.LINE);
                list.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
            }
            LinearLayout line = Ui.row(this);
            line.setMinimumHeight(dp(64));
            line.setPadding(dp(4), dp(6), dp(4), dp(6));
            LinearLayout words = Ui.column(this);
            TextView name = Ui.text(this, entry.merchant.isEmpty() ? entry.platform : entry.merchant, 16, Ui.INK, true);
            name.setMaxLines(1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            words.addView(name);
            words.addView(Ui.text(this, entry.platform + " · " + TIMESTAMP.format(Instant.ofEpochMilli(entry.time)),
                    12, Ui.MUTED, false));
            line.addView(words, weighted());
            TextView amount = Ui.text(this, entry.cents == null ? "待填金额" : Payment.format(entry.cents),
                    16, Ui.GREEN, true);
            amount.setPadding(dp(8), 0, 0, 0);
            line.addView(amount);
            line.setFocusable(true);
            line.setOnClickListener(v -> {
                if (entry.state == Ledger.PENDING) confirm(entry);
                else edit(entry);
            });
            list.addView(line);
        }
        body.addView(list);
    }

    private void confirm(Ledger.Entry row) {
        if (dialogOpen) return;
        String detail = row.cents == null ? "付款成功，填写金额后即可记入。" : Payment.format(row.cents);
        if (!row.merchant.isEmpty()) detail += "\n" + row.merchant;
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(row.platform + "付款")
                .setMessage(detail)
                .setPositiveButton(row.cents == null ? "填写金额" : "记入", (d, which) -> {
                    if (row.cents == null) {
                        d.dismiss();
                        getWindow().getDecorView().post(() -> edit(row));
                    } else PaymentPrompts.decide(this, row.id, Ledger.SAVED);
                })
                .setNeutralButton("改金额", row.cents == null ? null : (d, which) -> {
                    d.dismiss();
                    getWindow().getDecorView().post(() -> edit(row));
                })
                .setNegativeButton("跳过", (d, which) -> PaymentPrompts.decide(this, row.id, Ledger.SKIPPED))
                .create();
        if (row.cents == null) dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "稍后",
                (d, which) -> PaymentPrompts.showFeedback(this, Ledger.PENDING, null, false));
        showDialog(dialog);
    }

    private void edit(Ledger.Entry row) {
        if (dialogOpen) return;
        LinearLayout fields = Ui.column(this);
        fields.setPadding(dp(24), dp(8), dp(24), dp(2));
        fields.addView(Ui.text(this, "金额（元）", 13, Ui.MUTED, true));
        EditText amount = new EditText(this);
        amount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        amount.setFilters(new InputFilter[]{new InputFilter.LengthFilter(12)});
        amount.setHint("0.00");
        amount.setSingleLine();
        if (row != null && row.cents != null) amount.setText(Payment.decimal(row.cents));
        fields.addView(amount);
        space(fields, 8);
        EditText merchant = new EditText(this);
        merchant.setHint("商户或备注（可选）");
        merchant.setSingleLine();
        merchant.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        if (row != null) merchant.setText(row.merchant);
        fields.addView(merchant);
        Spinner platform = new Spinner(this);
        if (row == null) {
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, PLATFORMS);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            platform.setAdapter(adapter);
            platform.setSelection(2);
            space(fields, 7);
            fields.addView(platform, new LinearLayout.LayoutParams(-1, dp(48)));
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(row == null ? "补记一笔" : row.platform)
                .setView(fields).setPositiveButton(row != null && row.state == Ledger.SAVED ? "保存" : "记入", null)
                .setNegativeButton("取消", null);
        if (row != null && row.state == Ledger.SAVED) builder.setNeutralButton("撤回到待确认", (d, which) -> {
            ledger.decide(row.id, Ledger.PENDING, null, null);
        });
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Long cents = Payment.money(amount.getText().toString());
            if (cents == null) {
                amount.setError("请输入大于 0 的金额，最多两位小数");
                return;
            }
            String description = merchant.getText().toString().trim();
            if (row == null) {
                ledger.receive(new Payment(PLATFORMS[platform.getSelectedItemPosition()], cents, description, "",
                        System.currentTimeMillis(), Payment.MANUAL, ""));
                month = YearMonth.now(ZONE);
                ledger.changed();
            } else if (ledger.decide(row.id, Ledger.SAVED, cents, description)) {
                PaymentPrompts.cancel(this, row.id);
                if (row.state != Ledger.SAVED) PaymentPrompts.showFeedback(this, Ledger.SAVED, cents, false);
            }
            dialog.dismiss();
        });
        amount.requestFocus();
        if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    }

    private void showDialog(AlertDialog dialog) {
        dialogOpen = true;
        dialog.setOnDismissListener(d -> dialogOpen = false);
        dialog.show();
    }

    private void renderSettings(LinearLayout body) {
        toolbar(body, "设置", null, null);
        LinearLayout settings = card(Ui.PAPER, 15);
        boolean backgroundEnabled = BackgroundPaymentService.enabled(this);
        setting(settings, "后台保持运行", "开启后显示常驻通知，手机重启后恢复后台服务。",
                backgroundEnabled ? "关闭" : "开启", () -> {
                    if (!BackgroundPaymentService.setEnabled(this, !backgroundEnabled)) {
                        Toast.makeText(this, "后台服务暂未启动，请重新打开账本重试", Toast.LENGTH_LONG).show();
                    }
                    render();
                });
        setting(settings, "付款页面识别", "读取支付宝和微信付款结果页上可访问的文字，并弹出记账框。",
                screenEnabled() ? "已开启" : "去开启", () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        setting(settings, "付款通知识别", "从支付宝和微信的系统通知中识别付款。",
                listenerEnabled() ? "已开启" : "去开启", () -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        setting(settings, "账本提醒", "付款弹框无法显示时，在通知栏留下提醒。",
                PaymentPrompts.notificationsEnabled(this) ? "已开启" : "去开启", this::enableNotifications);
        if (Build.VERSION.SDK_INT >= 34) {
            setting(settings, "微信画面识别", "本机识别微信收银台里的付款成功画面。",
                    PaymentOcr.autoEnabled(this) ? "已开启" : "开启", this::toggleWechatOcr);
        }
        if (Build.VERSION.SDK_INT >= 35) {
            setting(settings, "微信收银台检测", "用系统的使用情况权限识别微信收银台何时打开。",
                    WechatCheckout.hasUsageAccess(this) ? "已授权" : "去授权", this::requestCheckoutAccess);
        }
        body.addView(settings);
        space(body, 14);
        LinearLayout tools = card(Ui.PAPER, 15);
        setting(tools, "导入个人账单", "选择解压后的支付宝 CSV 或微信 XLSX。新增支出进入待确认。",
                importing ? "导入中…" : "选择文件", this::selectBill);
        setting(tools, "补识别微信付款", "打开刚才那笔微信账单详情，把漏掉的付款加入待确认。",
                "去微信", this::recoverWechat);
        setting(tools, "查看提示示例", "用一笔示例付款体验确认流程。",
                "预览", this::preview);
        body.addView(tools);
        space(body, 12);
        TextView privateNote = Ui.text(this, "账本数据只保存在这台手机。", 13, Ui.MUTED, false);
        privateNote.setPadding(dp(4), 0, 0, 0);
        body.addView(privateNote);
    }

    private void recoverWechat() {
        PaymentScreen screen = PaymentScreen.connected;
        if (screen == null) {
            Toast.makeText(this, "请先开启付款页面识别", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent wechat = getPackageManager().getLaunchIntentForPackage("com.tencent.mm");
        if (wechat == null) {
            Toast.makeText(this, "找不到微信", Toast.LENGTH_SHORT).show();
            return;
        }
        showDialog(new AlertDialog.Builder(this).setTitle("补识别微信付款")
                .setMessage("打开 微信 → 我 → 服务 → 钱包 → 账单，点进要补记的那一笔，再点右下角「识别这笔」。画面仅在本机识别，用完即丢弃。")
                .setNegativeButton("取消", null).setPositiveButton("打开微信", (d, which) -> {
                    screen.startReceiptLookup();
                    startActivity(wechat);
                }).create());
    }

    private void toggleWechatOcr() {
        boolean enabled = PaymentOcr.autoEnabled(this);
        showDialog(new AlertDialog.Builder(this).setTitle("微信画面识别")
                .setMessage(enabled ? "关闭后仍可从微信通知、可读取的页面文字和账单导入记账。"
                        : "微信收银台不提供可读取文字时，账本会短暂读取收银及结果窗口的画面，在手机上识别付款成功和金额。画面用完即丢弃。")
                .setNegativeButton("取消", null).setPositiveButton(enabled ? "关闭" : "开启", (d, which) -> {
                    PaymentOcr.setAutoEnabled(this, !enabled);
                    render();
                    if (!enabled && Build.VERSION.SDK_INT >= 35 && !WechatCheckout.hasUsageAccess(this)) {
                        getWindow().getDecorView().post(this::requestCheckoutAccess);
                    }
                }).create());
    }

    private void requestCheckoutAccess() {
        showDialog(new AlertDialog.Builder(this).setTitle("微信收银台检测")
                .setMessage("微信有时不提供收银台的页面内容。授权后，账本只查询微信最近打开的窗口，用于识别收银台，查询结果不保存。")
                .setNegativeButton("取消", null)
                .setPositiveButton("去系统设置", (d, which) ->
                        startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                .setData(Uri.parse("package:" + getPackageName())))).create());
    }

    private void preview() {
        PaymentScreen screen = PaymentScreen.connected;
        if (screen != null && screen.preview()) return;
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.setContentView(PaymentCard.preview(this, dialog::dismiss));
        dialog.show();
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(420)), -2);
            WindowManager.LayoutParams parameters = window.getAttributes();
            parameters.y = dp(16);
            window.setAttributes(parameters);
        }
    }

    private void setting(LinearLayout list, String name, String hint, String action, Runnable run) {
        LinearLayout line = Ui.row(this);
        line.setPadding(0, dp(9), 0, dp(9));
        LinearLayout copy = Ui.column(this);
        copy.addView(Ui.text(this, name, 16, Ui.INK, true));
        TextView description = Ui.text(this, hint, 13, Ui.MUTED, false);
        description.setPadding(0, dp(4), 0, 0);
        copy.addView(description);
        line.addView(copy, weighted());
        line.addView(Ui.button(this, action, false, run));
        list.addView(line);
    }

    private boolean screenEnabled() {
        return PaymentScreen.permissionEnabled(this);
    }

    private boolean listenerEnabled() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        return manager.isNotificationListenerAccessGranted(new ComponentName(this, PaymentNotifications.class));
    }

    private void enableNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED && (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
                || !getPreferences(0).getBoolean("notification_asked", false))) {
            getPreferences(0).edit().putBoolean("notification_asked", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, ALLOW_NOTIFICATIONS);
        } else {
            startActivity(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                    .putExtra(Settings.EXTRA_CHANNEL_ID, PaymentPrompts.CHANNEL));
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == ALLOW_NOTIFICATIONS) render();
    }

    private void selectBill() {
        if (importing) return;
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        startActivityForResult(pick, PICK_BILL);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_BILL || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        importing = true;
        render();
        IMPORTER.execute(() -> {
            String resultText;
            int added = 0;
            try (InputStream file = getContentResolver().openInputStream(uri)) {
                if (file == null) throw new java.io.IOException("无法打开账单文件");
                added = ledger.importAll(BillImport.read(file));
                resultText = added == 0 ? "没有新增的待确认支出" : "已新增 " + added + " 笔待确认支出";
            } catch (Exception e) {
                resultText = "导入失败。请选择解压后的个人对账 CSV 或 XLSX。";
            }
            String message = resultText;
            int count = added;
            runOnUiThread(() -> {
                importing = false;
                ledger.changed();
                Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
                if (!isDestroyed() && count > 0) navigate("pending");
            });
        });
    }

    private LinearLayout card(int color, int padding) {
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.bg(this, color, 20));
        card.setPadding(dp(padding), dp(padding), dp(padding), dp(padding));
        return card;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, -2, 1);
    }

    private void space(LinearLayout view, int height) {
        view.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height)));
    }
}
