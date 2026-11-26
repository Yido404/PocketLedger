package cn.local.pocketledger;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Payment {
    static final String ALIPAY = "支付宝";
    static final String ALIPAY_PACKAGE = "com.eg.android.AlipayGphone";
    static final String WECHAT = "微信支付";
    static final String OTHER = "其他";
    static final int SCREEN = 1, NOTICE = 2, IMPORT = 4, MANUAL = 8, DEMO = 16, RECEIPT = 32, CHECKOUT = 64;
    private static final String COMPLETED = "支付成功|付款成功|支付完成|付款完成|交易完成|转账成功|红包发送成功|红包已发送|扣款成功|交易成功";
    private static final String NUMBER = "([0-9]{1,9}(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)(?![0-9.,])";
    private static final Pattern LABELED = Pattern.compile(
            "(?:实付金额|实际支付|付款金额|支付金额|消费金额|扣款金额|实付|成功支付|成功付款|付款成功|支付成功|已支付|已付款|完成支付|支出|消费|扣款成功)"
                    + "[：:\\s]*([¥]?)\\s*" + NUMBER + "(\\s*元)?");
    private static final Pattern CURRENCY = Pattern.compile(
            "¥\\s*" + NUMBER + "|" + NUMBER + "\\s*元");
    private static final Pattern AMOUNT_FIELD = Pattern.compile(
            "(?:实付金额|实际支付|付款金额|支付金额|消费金额|扣款金额)[：:\\s]+\\s*" + NUMBER);
    private static final Pattern POSITIVE_INPUT = Pattern.compile("[0-9]{1,9}(?:\\.[0-9]{1,2})?");
    private static final Pattern SUCCESS = Pattern.compile(COMPLETED
            + "|已成功(?:付款|支付|扣款)|你已(?:付款|支付)|您已(?:付款|支付)|已付款|已支付|完成支付|成功支付|成功付款|[微]信支付凭证|支付宝付款凭证|(?:自动)?扣费成功");
    private static final Pattern EXCLUDE = Pattern.compile(
            "退款|已撤销|已关闭|撤销成功|付款失败|支付失败|未支付|待付款|待支付|待确认收款|提现|充值成功|转入成功|到账通知|收款成功|收款到账|成功收款|已收款|收钱到账|收入金额|付款码已开启|验证码");
    private static final Pattern ORDER = Pattern.compile(
            "(?:支付宝交易号|微信支付单号|微信支付订单号|微信支付交易号|交易单号|交易订单号|交易号)[：:\\s]*([0-9]{15,40})(?![0-9])");
    private static final Pattern MERCHANT = Pattern.compile(
            "(?:商户名称|商户全称|商家名称|收款方|收款人|交易对方|付款给)[：:\\s]*([^\\r\\n]{1,45})");
    private static final Pattern SCREEN_TITLE = Pattern.compile("(?m)^\\h*(?:支付宝|微信)?(?:" + COMPLETED
            + ")[!！。]?\\h*(?:(?:[¥: ]\\h*|(?=[0-9]))" + NUMBER + "(?:\\h*元)?)?\\h*$");
    private static final Pattern COMPLETE_BUTTON = Pattern.compile(
            "(?m)^\\h*(?:完成|查看账单|返回(?:商家|商户|小程序|应用)(?:页面)?)\\h*$");
    private static final Pattern ALIPAY_SUCCESS_AMOUNT = Pattern.compile(
            "(?m)^\\h*(?:支付成功|付款成功|支付完成)\\h*[:：]?\\h*¥\\h*" + NUMBER + "\\h*$");
    private static final Pattern WECHAT_SENDER = Pattern.compile(
            "(?:\\[[0-9]+条\\])?\\s*(?:微信支付|微信团队·微信支付)(?:凭证|[：: ].*)?");
    private static final Pattern WECHAT_APP_TITLE = Pattern.compile("微信(?:\\([0-9]+条新消息\\))?");
    private static final Pattern WECHAT_BODY_SENDER = Pattern.compile("^(?:\\[[0-9]+条\\]\\s*)?微信支付[：:]");
    private static final Pattern RECEIPT_DATE = Pattern.compile(
            "(?:支付时间|付款时间|交易时间)[：:\\s]*(\\d{4})[-年/.](\\d{1,2})[-月/.](\\d{1,2})日?\\s+(\\d{1,2}:\\d{2}(?::\\d{2})?)");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("uuuu/M/d H:mm[:ss]");
    private static final Pattern DEBIT = Pattern.compile("(?m)^\\h*[-−]\\h*¥?\\h*" + NUMBER + "(?:\\h*元)?\\h*$");
    private static final Pattern PAYMENT_ROUTE = Pattern.compile(
            "wallet|payui|payresult|paymentresult|payactivity|cashier|transfer|luckymoney|payee|qrcodepay|paysuccess|h5activity|nebulamultiactivity");

    final String platform;
    final Long cents;
    final String merchant;
    final String order;
    final long time;
    final int source;
    final String signal;

    Payment(String platform, Long cents, String merchant, String order, long time, int source, String signal) {
        this.platform = platform;
        this.cents = cents;
        this.merchant = merchant;
        this.order = order;
        this.time = time;
        this.source = source;
        this.signal = signal;
    }

    static String platform(String packageName) {
        if ("com.tencent.mm".equals(packageName)) return WECHAT;
        if (ALIPAY_PACKAGE.equals(packageName)) return ALIPAY;
        return null;
    }

    static boolean paymentRoute(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return PAYMENT_ROUTE.matcher(lower).find() && !lower.contains("bill") && !lower.contains("detail");
    }

    static boolean resultRoute(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("payresult") || lower.contains("paymentresult") || lower.contains("paysuccess");
    }

    static boolean alipayCashierRoute(String name) {
        return "com.alipay.android.msp.ui.views.MspContainerActivity".equals(name);
    }

    static Payment alipayResult(String header, boolean hasCompletion, boolean knownCashier, long time, String signal) {
        header = normalize(header);
        if (!hasCompletion || EXCLUDE.matcher(header).find()) return null;
        Matcher combined = ALIPAY_SUCCESS_AMOUNT.matcher(header);
        if (combined.find()) {
            Long cents = money(combined.group(1));
            return cents == null ? null : payment(ALIPAY, header, cents, time, SCREEN, signal);
        }
        if (!SCREEN_TITLE.matcher(header).find() || (!knownCashier && !header.contains("交易方式"))) return null;
        Long cents = detectCents(header);
        return cents == null && !knownCashier ? null : payment(ALIPAY, header, cents, time, SCREEN, signal);
    }

    static boolean wechatCheckoutBridge(String name) {
        return "com.tencent.mm.plugin.wallet_index.ui.OrderHandlerUI".equals(name);
    }

    static boolean wechatLiteCheckout(String name) {
        return name != null && name.startsWith("com.tencent.mm.plugin.lite.ui.WxaLiteApp");
    }

    static boolean wechatSender(String name) {
        return WECHAT_SENDER.matcher(normalize(name)).matches();
    }

    static Payment detect(String platform, String title, String body, String route, long time, int source, String signal) {
        title = normalize(title);
        body = normalize(body);
        String all = title + "\n" + body;
        if (EXCLUDE.matcher(all).find()) return null;
        if (source == SCREEN) {
            Matcher result = SCREEN_TITLE.matcher(body);
            if (!result.find()) return null;
            Long cents = detectCents(all);
            if (cents == null && result.group(1) != null) cents = money(result.group(1));
            if (!resultRoute(route) && !(paymentRoute(route) || wechatLiteCheckout(route))) return null;
            if (!resultRoute(route) && cents == null && !COMPLETE_BUTTON.matcher(body).find()) return null;
            return payment(platform, all, cents, time, source, signal);
        } else {
            if (WECHAT.equals(platform) && !wechatSender(title)
                    && !(WECHAT_APP_TITLE.matcher(title).matches() && WECHAT_BODY_SENDER.matcher(body).find())) return null;
            if (!SUCCESS.matcher(all).find() && !(WECHAT.equals(platform)
                    && Pattern.compile("消费金额|实付金额|支付金额|付款金额").matcher(all).find()
                    && detectCents(all) != null)) return null;
        }
        return payment(platform, all, detectCents(all), time, source, signal);
    }

    private static Payment payment(String platform, String all, Long cents, long time, int source, String signal) {
        Matcher order = ORDER.matcher(all);
        Matcher merchant = MERCHANT.matcher(all);
        return new Payment(platform, cents, merchant.find() ? merchant.group(1).trim() : "",
                order.find() ? order.group(1) : "", time, source, signal);
    }

    static Payment wechatReceipt(String body, long now) {
        String text = normalize(body);
        Matcher order = ORDER.matcher(text);
        Matcher date = RECEIPT_DATE.matcher(text);
        if (!order.find() || !date.find()
                || Pattern.compile("已退款|全额退款|退款成功|支付失败|已撤销|提现成功|充值成功|已收款|收款到账").matcher(text).find()) return null;
        Matcher debit = DEBIT.matcher(text);
        Long cents = debit.find() ? money(debit.group(1)) : null;
        if (cents == null) {
            if (!Pattern.compile("支付成功|付款成功|支付完成|商户消费|消费金额|付款金额").matcher(text).find()) return null;
            cents = detectCents(text);
        }
        if (cents == null) return null;
        try {
            long time = LocalDateTime.parse(date.group(1) + "/" + date.group(2) + "/" + date.group(3) + " " + date.group(4),
                    DATE_TIME).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
            if (now - time > 48 * 60 * 60_000L || time > now + 60_000) return null;
            return payment(WECHAT, text, cents, time, RECEIPT, "receipt:" + digest(order.group(1)));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Long detectCents(String text) {
        Matcher fields = AMOUNT_FIELD.matcher(text);
        if (fields.find()) {
            Long cents = money(fields.group(1));
            if (cents != null) return cents;
        }
        Matcher labeled = LABELED.matcher(text);
        while (labeled.find()) {
            if (labeled.group(1).isEmpty() && labeled.group(3) == null) continue;
            Long cents = money(labeled.group(2));
            if (cents != null) return cents;
        }
        Matcher currency = CURRENCY.matcher(text);
        Long only = null;
        while (currency.find()) {
            Long cents = money(currency.group(1) != null ? currency.group(1) : currency.group(2));
            if (cents == null) continue;
            if (only != null && !only.equals(cents)) return null;
            only = cents;
        }
        if (only != null) return only;
        // Some result pages expose the amount as a standalone decimal node.
        Matcher decimal = Pattern.compile("(?m)^\\s*([0-9]{1,9}\\.[0-9]{2})\\s*$").matcher(text);
        while (decimal.find()) {
            Long cents = money(decimal.group(1));
            if (only != null && !only.equals(cents)) return null;
            only = cents;
        }
        return only;
    }

    static String normalize(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace("\u200b", "").replace("\ufeff", "").trim();
    }

    static Long money(String value) {
        String clean = normalize(value).replace(",", "").replace("¥", "").replace("元", "").trim();
        if (!POSITIVE_INPUT.matcher(clean).matches()) return null;
        long cents = new BigDecimal(clean).movePointRight(2).longValueExact();
        return cents > 0 ? cents : null;
    }

    static String decimal(long cents) {
        return BigDecimal.valueOf(cents, 2).toPlainString();
    }

    static String format(long cents) {
        return "¥" + String.format(Locale.CHINA, "%,.2f", BigDecimal.valueOf(cents, 2));
    }

    static String digest(String value) {
        return digest(value.getBytes(StandardCharsets.UTF_8));
    }

    static String digest(byte[] value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder hex = new StringBuilder();
            for (byte item : bytes) {
                hex.append(Character.forDigit((item >>> 4) & 15, 16));
                hex.append(Character.forDigit(item & 15, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
