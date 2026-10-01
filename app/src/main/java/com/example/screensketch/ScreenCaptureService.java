package com.example.screensketch;

import android.app.*;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.pdf.PdfDocument;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScreenCaptureService extends Service {
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean handled = new AtomicBoolean(false);
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = new Notification.Builder(this, "screen_sketch_capture")
                .setContentTitle("Screen Sketch")
                .setContentText("화면을 저장하는 중입니다…")
                .setSmallIcon(R.drawable.ic_pen)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(2002, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(2002, notification);
        }

        if (intent == null) {
            fail("캡처 정보가 없습니다.");
            return START_NOT_STICKY;
        }

        final int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        final Intent resultData;
        if (Build.VERSION.SDK_INT >= 33) {
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        } else {
            //noinspection deprecation
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        }
        final String mode = intent.getStringExtra(CaptureRequestActivity.EXTRA_MODE);

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            fail("화면 캡처 권한 정보를 받지 못했습니다.");
            return START_NOT_STICKY;
        }

        handler.postDelayed(() -> beginCapture(resultCode, resultData,
                mode == null ? CaptureRequestActivity.MODE_PNG : mode), 350);
        return START_NOT_STICKY;
    }

    private void beginCapture(int resultCode, Intent resultData, String mode) {
        try {
            int width;
            int height;
            int density = getResources().getDisplayMetrics().densityDpi;
            WindowManager wm = getSystemService(WindowManager.class);

            if (Build.VERSION.SDK_INT >= 30) {
                Rect b = wm.getMaximumWindowMetrics().getBounds();
                width = b.width();
                height = b.height();
            } else {
                DisplayMetrics dm = new DisplayMetrics();
                //noinspection deprecation
                wm.getDefaultDisplay().getRealMetrics(dm);
                width = dm.widthPixels;
                height = dm.heightPixels;
                density = dm.densityDpi;
            }

            MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mgr.getMediaProjection(resultCode, resultData);
            if (projection == null) {
                fail("MediaProjection을 시작하지 못했습니다.");
                return;
            }

            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    cleanup(false);
                }
            }, handler);

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            final int captureWidth = width;
            final int captureHeight = height;
            final int captureDensity = density;

            imageReader.setOnImageAvailableListener(reader -> {
                if (handled.get()) return;
                Image image = reader.acquireLatestImage();
                if (image == null) return;
                if (!handled.compareAndSet(false, true)) {
                    image.close();
                    return;
                }
                try {
                    Bitmap bitmap = imageToBitmap(image, captureWidth, captureHeight);
                    compositeAnnotations(bitmap);
                    processResult(bitmap, mode);
                } catch (Exception e) {
                    sendResult(false, "저장 중 오류: " + e.getMessage(), null);
                } finally {
                    image.close();
                    restoreOverlay();
                    cleanup(true);
                    stopSelf();
                }
            }, handler);

            virtualDisplay = projection.createVirtualDisplay(
                    "ScreenSketchCapture",
                    captureWidth,
                    captureHeight,
                    captureDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    handler);

            handler.postDelayed(() -> {
                if (handled.compareAndSet(false, true)) {
                    sendResult(false, "화면 캡처 시간이 초과되었습니다.", null);
                    restoreOverlay();
                    cleanup(true);
                    stopSelf();
                }
            }, 5000);

        } catch (Exception e) {
            fail("화면 캡처 시작 실패: " + e.getMessage());
        }
    }

    private Bitmap imageToBitmap(Image image, int width, int height) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * width;
        int paddedWidth = width + rowPadding / pixelStride;

        Bitmap padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
        if (cropped != padded) padded.recycle();
        return cropped;
    }

    private void compositeAnnotations(Bitmap bitmap) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float sx = bitmap.getWidth() / (float) Math.max(1, dm.widthPixels);
        float sy = bitmap.getHeight() / (float) Math.max(1, dm.heightPixels);
        Canvas canvas = new Canvas(bitmap);
        StrokeStore.get().drawTo(canvas, sx, sy);
    }

    private void processResult(Bitmap bitmap, String mode) throws Exception {
        if (CaptureRequestActivity.MODE_PDF.equals(mode)) {
            String name = "ScreenSketch_" + timestamp() + ".pdf";
            savePdfToDownloads(bitmap, name);
            sendResult(true, "PDF 저장 완료: 다운로드/ScreenSketch/" + name, null);
        } else if (CaptureRequestActivity.MODE_PRINT.equals(mode)) {
            File file = new File(getCacheDir(), "ScreenSketch_Print.pdf");
            writePdf(bitmap, new FileOutputStream(file));
            sendResult(true, "인쇄 준비 완료", file.getAbsolutePath());
        } else {
            String name = "ScreenSketch_" + timestamp() + ".png";
            savePng(bitmap, name);
            sendResult(true, "PNG 저장 완료: 사진/Pictures/ScreenSketch/" + name, null);
        }
        bitmap.recycle();
    }

    private void savePng(Bitmap bitmap, String name) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ScreenSketch");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("이미지 저장 위치 생성 실패");
            try (OutputStream out = cr.openOutputStream(uri)) {
                if (out == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw new IllegalStateException("PNG 쓰기 실패");
                }
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, values, null, null);
        } else {
            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "ScreenSketch");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("폴더 생성 실패");
            try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
        }
    }

    private void savePdfToDownloads(Bitmap bitmap, String name) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ScreenSketch");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("PDF 저장 위치 생성 실패");
            try (OutputStream out = cr.openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("PDF 출력 스트림 생성 실패");
                writePdf(bitmap, out);
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, values, null, null);
        } else {
            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "ScreenSketch");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("폴더 생성 실패");
            try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
                writePdf(bitmap, out);
            }
        }
    }

    private void writePdf(Bitmap bitmap, OutputStream output) throws Exception {
        PdfDocument doc = new PdfDocument();
        try {
            PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(
                    bitmap.getWidth(), bitmap.getHeight(), 1).create();
            PdfDocument.Page page = doc.startPage(pageInfo);
            page.getCanvas().drawBitmap(bitmap, 0, 0, null);
            doc.finishPage(page);
            doc.writeTo(output);
        } finally {
            doc.close();
            try { output.close(); } catch (Exception ignored) {}
        }
    }

    private String timestamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    private void fail(String message) {
        if (handled.compareAndSet(false, true)) {
            sendResult(false, message, null);
            restoreOverlay();
        }
        cleanup(true);
        stopSelf();
    }

    private void sendResult(boolean success, String message, String filePath) {
        Intent result = new Intent(CaptureRequestActivity.ACTION_CAPTURE_RESULT)
                .setPackage(getPackageName())
                .putExtra(CaptureRequestActivity.EXTRA_SUCCESS, success)
                .putExtra(CaptureRequestActivity.EXTRA_MESSAGE, message);
        if (filePath != null) result.putExtra(CaptureRequestActivity.EXTRA_FILE_PATH, filePath);
        sendBroadcast(result);
    }

    private void restoreOverlay() {
        try {
            Intent restore = new Intent(this, OverlayDrawingService.class)
                    .setAction(OverlayDrawingService.ACTION_CAPTURE_RESTORE);
            startService(restore);
        } catch (Exception ignored) {}
    }

    private void cleanup(boolean stopProjection) {
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignored) {}
        virtualDisplay = null;
        try { if (imageReader != null) imageReader.close(); } catch (Exception ignored) {}
        imageReader = null;
        if (stopProjection) {
            try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        }
        projection = null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(
                    "screen_sketch_capture",
                    "Screen Sketch Capture",
                    NotificationManager.IMPORTANCE_LOW);
            c.setDescription("PNG/PDF 저장 및 인쇄용 일회성 화면 캡처");
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
