package cn.local.pocketledger;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class BillImport {
    private static final int LIMIT = 24 * 1024 * 1024;
    private static final ZoneId BILL_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-M-d H:m[:s]");

    static List<Payment> read(InputStream stream) throws Exception {
        byte[] file = readBytes(stream, LIMIT);
        List<List<String>> rows = file.length > 3 && file[0] == 'P' && file[1] == 'K'
                ? workbook(file) : csv(decode(file));
        return payments(rows, Payment.digest(file));
    }

    private static List<Payment> payments(List<List<String>> rows, String fingerprint) throws IOException {
        List<Payment> results = new ArrayList<>();
        Header header = null;
        String platformHint = "";
        for (int i = 0; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (header == null) {
                String line = value(row, 0);
                if (line.contains("微信支付")) platformHint = Payment.WECHAT;
                if (line.contains("支付宝")) platformHint = Payment.ALIPAY;
                Header found = new Header(row, platformHint);
                if (found.valid()) header = found;
                continue;
            }
            String direction = value(row, header.direction);
            String status = value(row, header.status);
            String type = value(row, header.type);
            if (!direction.equals("支出") || (type + status).matches(
                    ".*(?:提现|充值|退款|转入|转出|失败|关闭|取消|撤销|未支付|待支付|待付款|待收款|未领取|已退回|已退款|已过期).*")) continue;
            Long cents = Payment.money(value(row, header.amount));
            long time = date(value(row, header.date));
            if (cents == null || time == 0) continue;
            String order = value(row, header.order).replaceFirst("^['\\x60]", "");
            if (!order.matches("[0-9]{15,40}")) order = "";
            results.add(new Payment(header.platform, cents, value(row, header.merchant), order, time, Payment.IMPORT,
                    "import:" + fingerprint + ":" + i));
        }
        if (header == null) throw new IOException("未找到支付宝或微信的个人对账表头");
        return results;
    }

    private static final class Header {
        final int date, direction, amount, type, status, merchant, order;
        final String platform;

        Header(List<String> row, String hint) {
            date = column(row, "交易时间", "付款时间", "交易日期");
            direction = column(row, "收/支", "收支", "收支类型", "资金流向");
            amount = column(row, "金额(元)", "金额", "交易金额(元)", "交易金额");
            type = column(row, "交易类型", "交易分类", "业务类型");
            status = column(row, "交易状态", "当前状态", "状态");
            merchant = column(row, "交易对方", "商户名称", "对方名称");
            order = column(row, "交易单号", "交易订单号", "支付宝交易号", "微信支付单号", "交易号");
            platform = !hint.isEmpty() ? hint : column(row, "当前状态", "支付方式") >= 0 ? Payment.WECHAT
                    : column(row, "交易分类", "交易订单号", "收/付款方式") >= 0 ? Payment.ALIPAY : "";
        }

        boolean valid() { return date >= 0 && direction >= 0 && amount >= 0 && !platform.isEmpty(); }
    }

    private static int column(List<String> row, String... names) {
        for (int i = 0; i < row.size(); i++) {
            String value = value(row, i);
            for (String name : names) if (name.equals(value)) return i;
        }
        return -1;
    }

    private static String value(List<String> row, int index) {
        return index < 0 || index >= row.size() ? "" : Payment.normalize(row.get(index));
    }

    private static long date(String text) {
        try {
            if (text.matches("[0-9]{5}(?:\\.[0-9]+)?")) {
                long seconds = Math.round(Double.parseDouble(text) * 86400);
                return LocalDate.of(1899, 12, 30).atStartOfDay().plusSeconds(seconds)
                        .atZone(BILL_ZONE).toInstant().toEpochMilli();
            }
            return LocalDateTime.parse(text.replace('/', '-').replace('T', ' '), DATE)
                    .atZone(BILL_ZONE).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return 0;
        }
    }

    private static byte[] readBytes(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        for (int count; (count = in.read(buffer)) != -1;) {
            if (count > max - out.size()) throw new IOException("账单文件过大，请分段导出");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    private static String decode(byte[] raw) {
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException e) {
            return new String(raw, Charset.forName("GB18030"));
        }
    }

    private static List<List<String>> csv(String text) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"' && (quoted || field.length() == 0)) {
                if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else quoted = !quoted;
            } else if (!quoted && (ch == ',' || ch == '\n' || ch == '\r')) {
                row.add(field.toString());
                field.setLength(0);
                if (ch != ',') {
                    rows.add(row);
                    row = new ArrayList<>();
                    if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                }
            } else field.append(ch);
        }
        if (quoted) throw new IOException("账单文件内容不完整");
        if (!row.isEmpty() || field.length() > 0) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    private static List<List<String>> workbook(byte[] raw) throws Exception {
        byte[] sheet = null;
        List<String> strings = new ArrayList<>();
        int remaining = LIMIT;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(raw))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                String name = entry.getName();
                if (name.equals("xl/sharedStrings.xml")) {
                    byte[] contents = readBytes(zip, remaining);
                    remaining -= contents.length;
                    strings = sharedStrings(contents);
                } else if (name.equals("xl/worksheets/sheet1.xml")) {
                    sheet = readBytes(zip, remaining);
                    remaining -= sheet.length;
                } else {
                    // Bound all expanded entries, including metadata skipped by the importer.
                    byte[] buffer = new byte[8192];
                    for (int count; (count = zip.read(buffer)) != -1;) {
                        remaining -= count;
                        if (remaining < 0) throw new IOException("账单文件过大，请分段导出");
                    }
                }
            }
        }
        if (sheet == null) throw new IOException("请选择解压后的 CSV 或 XLSX 账单");
        return sheetRows(sheet, strings);
    }

    private static XmlPullParser xml(byte[] bytes) throws Exception {
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
        parser.setInput(new ByteArrayInputStream(bytes), null);
        return parser;
    }

    private static List<String> sharedStrings(byte[] bytes) throws Exception {
        XmlPullParser parser = xml(bytes);
        List<String> results = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        for (int token; (token = parser.next()) != XmlPullParser.END_DOCUMENT;) {
            if (token == XmlPullParser.START_TAG && parser.getName().equals("si")) value.setLength(0);
            if (token == XmlPullParser.START_TAG && parser.getName().equals("t")) value.append(parser.nextText());
            if (token == XmlPullParser.END_TAG && parser.getName().equals("si")) results.add(value.toString());
        }
        return results;
    }

    private static List<List<String>> sheetRows(byte[] bytes, List<String> strings) throws Exception {
        XmlPullParser parser = xml(bytes);
        List<List<String>> results = new ArrayList<>();
        List<String> row = new ArrayList<>();
        String type = "";
        int column = 0;
        StringBuilder value = new StringBuilder();
        for (int token; (token = parser.next()) != XmlPullParser.END_DOCUMENT;) {
            if (token == XmlPullParser.START_TAG) {
                switch (parser.getName()) {
                    case "row": row = new ArrayList<>(); break;
                    case "c":
                        type = parser.getAttributeValue(null, "t");
                        column = workbookColumn(parser.getAttributeValue(null, "r"), row.size());
                        value.setLength(0);
                        break;
                    case "v":
                    case "t": value.append(parser.nextText()); break;
                    default: break;
                }
            } else if (token == XmlPullParser.END_TAG && parser.getName().equals("c") && column < 128) {
                while (row.size() <= column) row.add("");
                String text = value.toString();
                if ("s".equals(type) && text.matches("[0-9]{1,7}")) {
                    int index = Integer.parseInt(text);
                    text = index < strings.size() ? strings.get(index) : "";
                }
                row.set(column, text);
            } else if (token == XmlPullParser.END_TAG && parser.getName().equals("row")) results.add(row);
        }
        return results;
    }

    private static int workbookColumn(String reference, int fallback) {
        if (reference == null) return fallback;
        int index = 0;
        for (char c : reference.toCharArray()) {
            if (c < 'A' || c > 'Z') break;
            index = index * 26 + c - 'A' + 1;
            if (index > 128) return 128;
        }
        return index == 0 ? fallback : index - 1;
    }
}
