package cn.local.pocketledger;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;

import com.googlecode.tesseract.android.TessBaseAPI;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class PaymentOcr {
    static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    static final String PREFERENCES = "payment_detection";
    static final String WECHAT_OCR = "wechat_ocr";
    private static TessBaseAPI engine;

    static boolean autoEnabled(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(WECHAT_OCR, false);
    }

    static void setAutoEnabled(Context context, boolean value) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(WECHAT_OCR, value).apply();
        if (value) WORKER.execute(() -> {
            try {
                engine(context.getApplicationContext());
            } catch (IOException | RuntimeException | LinkageError ignored) {
                // A later scan can retry initialization.
            }
        });
    }

    static String read(Context context, Bitmap screenshot, boolean receipt) throws IOException {
        TessBaseAPI tess = engine(context);
        Bitmap image = prepare(screenshot, receipt);
        try {
            tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT);
            tess.setImage(image);
            String text = normalize(tess.getUTF8Text());
            if (!receipt) {
                // The success tick is sometimes read as an extra glyph before the short heading.
                text = text.replaceAll("(?m)^\\h*[多●✓✔√☑]\\h*(?=支付成功\\h*$)", "")
                        .replaceAll("(?m)^\\h*[Yy]\\h*(?=\\d+\\.\\d{2}\\h*$)", "¥");
            }
            return text;
        } finally {
            tess.clear();
            image.recycle();
        }
    }

    private static TessBaseAPI engine(Context context) throws IOException {
        if (engine != null) return engine;
        File root = new File(context.getNoBackupFilesDir(), "ocr-v1");
        File models = new File(root, "tessdata");
        if (!models.isDirectory() && !models.mkdirs()) throw new IOException("Cannot create OCR model directory");
        for (String name : new String[]{"chi_sim.traineddata", "eng.traineddata"}) {
            File target = new File(models, name);
            if (target.isFile()) continue;
            File temp = new File(models, name + ".tmp");
            try (InputStream in = context.getAssets().open(name); FileOutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[32 * 1024];
                for (int n; (n = in.read(buffer)) != -1;) out.write(buffer, 0, n);
            }
            if (!temp.renameTo(target)) throw new IOException("Cannot install OCR model");
        }
        TessBaseAPI created = new TessBaseAPI();
        if (!created.init(root.getAbsolutePath(), "chi_sim+eng", TessBaseAPI.OEM_LSTM_ONLY)) {
            created.recycle();
            throw new IOException("Cannot initialize OCR");
        }
        engine = created;
        return created;
    }

    private static Bitmap prepare(Bitmap screenshot, boolean receipt) {
        int width = screenshot.getWidth();
        int height = screenshot.getHeight();
        int margin = width / 20;
        Rect crop = new Rect(margin, receipt ? height / 12 : height / 25, width - margin,
                receipt ? height * 7 / 10 : height / 2);
        int outputWidth = Math.min(crop.width(), 1024);
        int outputHeight = Math.max(1, Math.round((float) crop.height() * outputWidth / crop.width()));
        Bitmap prepared = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(0);
        int background = screenshot.getPixel(margin, height / 5);
        if (Color.red(background) + Color.green(background) + Color.blue(background) < 350) {
            matrix.postConcat(new ColorMatrix(new float[]{
                    -1, 0, 0, 0, 255,
                    0, -1, 0, 0, 255,
                    0, 0, -1, 0, 255,
                    0, 0, 0, 1, 0
            }));
        }
        paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        new Canvas(prepared).drawBitmap(screenshot, crop, new Rect(0, 0, outputWidth, outputHeight), paint);
        return prepared;
    }

    private static String normalize(String text) {
        return Payment.normalize(text)
                .replaceAll("(?<=\\p{IsHan})\\h+(?=\\p{IsHan})", "")
                .replaceAll("(?<=\\d)\\h*(?=[年月日])|(?<=[年月])\\h*(?=\\d)", "")
                .replaceAll("(?<=\\d)\\h*([.:])\\h*(?=\\d)", "$1")
                .replaceAll("(?m)^\\h*[一－−—–]\\h*(?=\\d)", "-");
    }
}
