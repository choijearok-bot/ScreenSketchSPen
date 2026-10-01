package com.example.screensketch;

import android.app.Activity;
import android.print.PrintManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

public class CaptureRequestActivity extends Activity {
    public static final String EXTRA_MODE = "mode";
    public static final String MODE_PNG = "PNG";
    public static final String MODE_PDF = "PDF";
    public static final String MODE_PRINT = "PRINT";
    public static final String ACTION_CAPTURE_RESULT = "com.example.screensketch.CAPTURE_RESULT";
    public static final String EXTRA_SUCCESS = "success";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_FILE_PATH = "file_path";

    private static final int REQ_CAPTURE = 4201;
    private String mode = MODE_PNG;
    private boolean receiverRegistered = false;

    private final BroadcastReceiver captureReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!ACTION_CAPTURE_RESULT.equals(intent.getAction())) return;
            boolean success = intent.getBooleanExtra(EXTRA_SUCCESS, false);
            String message = intent.getStringExtra(EXTRA_MESSAGE);
            String filePath = intent.getStringExtra(EXTRA_FILE_PATH);

            if (!success) {
                Toast.makeText(CaptureRequestActivity.this,
                        message == null ? "캡처에 실패했습니다." : message,
                        Toast.LENGTH_LONG).show();
                finish();
                return;
            }

            if (MODE_PRINT.equals(mode) && filePath != null) {
                printPdf(new File(filePath));
            } else {
                Toast.makeText(CaptureRequestActivity.this,
                        message == null ? "저장 완료" : message,
                        Toast.LENGTH_LONG).show();
                finish();
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.setDimAmount(0f);
        w.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);

        String requested = getIntent().getStringExtra(EXTRA_MODE);
        if (requested != null) mode = requested;

        registerCaptureReceiver();

        if (savedInstanceState == null) {
            MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(mgr.createScreenCaptureIntent(), REQ_CAPTURE);
        }
    }

    private void registerCaptureReceiver() {
        IntentFilter filter = new IntentFilter(ACTION_CAPTURE_RESULT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(captureReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(captureReceiver, filter);
        }
        receiverRegistered = true;
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;

        if (resultCode != RESULT_OK || data == null) {
            restoreOverlay();
            Toast.makeText(this, "화면 캡처가 취소되었습니다.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        Intent service = new Intent(this, ScreenCaptureService.class)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                .putExtra(EXTRA_MODE, mode);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
        else startService(service);
    }

    private void restoreOverlay() {
        try {
            Intent restore = new Intent(this, OverlayDrawingService.class)
                    .setAction(OverlayDrawingService.ACTION_CAPTURE_RESTORE);
            startService(restore);
        } catch (Exception ignored) {}
    }

    private void printPdf(File file) {
        if (!file.exists()) {
            Toast.makeText(this, "인쇄용 PDF 파일을 찾지 못했습니다.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        PrintManager printManager = (PrintManager) getSystemService(PRINT_SERVICE);
        printManager.print("Screen Sketch", new PdfFilePrintAdapter(file), null);
    }

    private final class PdfFilePrintAdapter extends PrintDocumentAdapter {
        private final File file;

        PdfFilePrintAdapter(File file) { this.file = file; }

        @Override public void onLayout(PrintAttributes oldAttributes,
                                       PrintAttributes newAttributes,
                                       CancellationSignal cancellationSignal,
                                       LayoutResultCallback callback,
                                       Bundle extras) {
            if (cancellationSignal.isCanceled()) {
                callback.onLayoutCancelled();
                return;
            }
            PrintDocumentInfo info = new PrintDocumentInfo.Builder("ScreenSketch.pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(1)
                    .build();
            callback.onLayoutFinished(info, true);
        }

        @Override public void onWrite(PageRange[] pages,
                                      ParcelFileDescriptor destination,
                                      CancellationSignal cancellationSignal,
                                      WriteResultCallback callback) {
            try (FileInputStream in = new FileInputStream(file);
                 FileOutputStream out = new FileOutputStream(destination.getFileDescriptor())) {
                byte[] buffer = new byte[32 * 1024];
                int len;
                while ((len = in.read(buffer)) > 0) {
                    if (cancellationSignal.isCanceled()) {
                        callback.onWriteCancelled();
                        return;
                    }
                    out.write(buffer, 0, len);
                }
                callback.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
            } catch (Exception e) {
                callback.onWriteFailed(e.getMessage());
            }
        }

        @Override public void onFinish() {
            super.onFinish();
            CaptureRequestActivity.this.finish();
        }
    }

    @Override protected void onDestroy() {
        if (receiverRegistered) {
            try { unregisterReceiver(captureReceiver); } catch (Exception ignored) {}
            receiverRegistered = false;
        }
        super.onDestroy();
    }
}
