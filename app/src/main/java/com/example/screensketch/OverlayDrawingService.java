package com.example.screensketch;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class OverlayDrawingService extends Service {
    public static final String ACTION_CLEAR = "com.example.screensketch.CLEAR";
    public static final String ACTION_STOP = "com.example.screensketch.STOP";
    public static final String ACTION_CAPTURE_RESTORE = "com.example.screensketch.CAPTURE_RESTORE";

    private WindowManager wm;
    private DrawingDisplayView displayView;
    private DrawingInputView inputView;
    private FrameLayout interactionRoot;
    private LinearLayout toolbar;
    private LinearLayout paletteView;

    private WindowManager.LayoutParams displayParams;
    private WindowManager.LayoutParams interactionParams;
    private WindowManager.LayoutParams toolbarWindowParams;
    private WindowManager.LayoutParams paletteWindowParams;

    private final StrokeStore store = StrokeStore.get();
    private boolean drawingEnabled = true;
    private boolean toolbarStandalone = false;
    private boolean collapsed = false;
    private boolean paletteVisible = false;
    private boolean paletteAsWindow = false;
    private boolean eraserActive = false;
    private boolean captureHidden = false;

    private int selectedColor = Color.rgb(244,67,54);
    private int widthDp = 5;
    private int alphaPct = 100;
    private int smoothingPct = 45;
    private DrawingInputView.Tool tool = DrawingInputView.Tool.PEN;

    private TextView collapseBtn, penBtn, highBtn, eraseBtn, shapeBtn, presetBtn,
            colorBtn, sizeBtn, undoBtn, redoBtn, eyeBtn, clearBtn,
            pngBtn, pdfBtn, printBtn, toggleBtn, exitBtn;

    private int toolbarX, toolbarY;
    private int shapeIndex = 0;
    private final DrawingInputView.Tool[] shapes = {
            DrawingInputView.Tool.LINE,
            DrawingInputView.Tool.ARROW,
            DrawingInputView.Tool.RECT,
            DrawingInputView.Tool.ELLIPSE
    };
    private final String[] shapeNames = {"LINE", "ARROW", "RECT", "OVAL"};
    private final int[] colors = {
            Color.BLACK, Color.WHITE, Color.rgb(244,67,54), Color.rgb(255,152,0),
            Color.rgb(255,235,59), Color.rgb(76,175,80), Color.rgb(0,200,170),
            Color.rgb(3,169,244), Color.rgb(33,150,243), Color.rgb(103,58,183),
            Color.rgb(233,30,99), Color.rgb(117,117,117)
    };

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1001, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1001, n);
        }

        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        initToolbarPosition();
        addDisplay();
        buildToolbar();
        showDrawingWindow();
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        if (i != null) {
            String action = i.getAction();
            if (ACTION_CLEAR.equals(action)) {
                store.clear();
                if (displayView != null) displayView.invalidate();
            } else if (ACTION_STOP.equals(action)) {
                stopSelf();
                return START_NOT_STICKY;
            } else if (ACTION_CAPTURE_RESTORE.equals(action)) {
                setCaptureHidden(false);
            }
        }
        return START_NOT_STICKY;
    }

    private void initToolbarPosition() {
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        toolbarX = Math.max(dp(8), sw - dp(76));
        toolbarY = Math.max(dp(20), sh / 2 - dp(330));
    }

    private void addDisplay() {
        displayView = new DrawingDisplayView(this, store);
        displayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        displayParams.gravity = Gravity.TOP | Gravity.START;
        wm.addView(displayView, displayParams);
    }

    /**
     * DRAW ON uses one full-screen overlay window containing BOTH the drawing input
     * and the toolbar. Because the toolbar is a child above the canvas, drawing can
     * never steal toolbar touches.
     */
    private void showDrawingWindow() {
        hidePalette();
        removeToolbarStandalone();
        removeInteractionRoot();

        interactionRoot = new FrameLayout(this);
        interactionRoot.setBackgroundColor(Color.TRANSPARENT);

        inputView = new DrawingInputView(this, store, displayView);
        applyToolSettings();
        inputView.setEraserStateListener(active -> {
            eraserActive = active;
            updateToolbarState();
        });
        interactionRoot.addView(inputView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        detachFromParent(toolbar);
        interactionRoot.addView(toolbar, childToolbarParams());
        toolbar.bringToFront();

        interactionParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        interactionParams.gravity = Gravity.TOP | Gravity.START;
        wm.addView(interactionRoot, interactionParams);

        drawingEnabled = true;
        toolbarStandalone = false;
        captureHidden = false;
        updateToolbarState();
    }

    /**
     * TOUCH ON removes the full-screen touch window completely. Only the compact
     * toolbar remains as a small overlay window, so touches outside the toolbar go
     * directly to TradingView/PDF/Maps/etc.
     */
    private void showTouchWindow() {
        hidePalette();
        if (interactionRoot != null) {
            try { interactionRoot.removeView(toolbar); } catch (Exception ignored) {}
        }
        removeInteractionRoot();

        detachFromParent(toolbar);
        toolbarWindowParams = new WindowManager.LayoutParams(
                dp(64),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        toolbarWindowParams.gravity = Gravity.TOP | Gravity.START;
        toolbarWindowParams.x = toolbarX;
        toolbarWindowParams.y = toolbarY;
        wm.addView(toolbar, toolbarWindowParams);

        drawingEnabled = false;
        toolbarStandalone = true;
        inputView = null;
        eraserActive = false;
        captureHidden = false;
        updateToolbarState();
    }

    private void setDrawingEnabled(boolean enabled) {
        if (captureHidden) return;
        if (enabled == drawingEnabled) return;
        if (enabled) showDrawingWindow(); else showTouchWindow();
    }

    private void removeInteractionRoot() {
        if (interactionRoot != null) {
            try { wm.removeView(interactionRoot); } catch (Exception ignored) {}
            interactionRoot = null;
        }
        inputView = null;
    }

    private void removeToolbarStandalone() {
        if (toolbarStandalone && toolbar != null) {
            try { wm.removeView(toolbar); } catch (Exception ignored) {}
            toolbarStandalone = false;
        }
    }

    private void detachFromParent(View view) {
        if (view == null) return;
        if (view.getParent() instanceof ViewGroup) {
            ((ViewGroup) view.getParent()).removeView(view);
        }
    }

    private FrameLayout.LayoutParams childToolbarParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(64), FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = toolbarX;
        lp.topMargin = toolbarY;
        return lp;
    }

    private TextView b(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Color.WHITE);
        v.setTextSize(10);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(2),dp(2),dp(2),dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(48), dp(42));
        lp.setMargins(0,dp(2),0,dp(2));
        v.setLayoutParams(lp);
        setBox(v, Color.rgb(58,68,82), false);
        return v;
    }

    private void setBox(TextView v, int fill, boolean active) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(12));
        g.setColor(fill);
        if (active) g.setStroke(dp(2), Color.WHITE);
        v.setBackground(g);
    }

    private void buildToolbar() {
        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.VERTICAL);
        toolbar.setGravity(Gravity.CENTER_HORIZONTAL);
        toolbar.setPadding(dp(6),dp(7),dp(6),dp(7));
        toolbar.setElevation(dp(16));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(246,25,31,41));
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1),Color.argb(80,255,255,255));
        toolbar.setBackground(bg);

        collapseBtn = b("◀\nHIDE");
        penBtn = b("PEN");
        highBtn = b("MARK");
        eraseBtn = b("ERASE");
        shapeBtn = b("LINE");
        presetBtn = b("★ PRESET");
        colorBtn = b("COLOR");
        sizeBtn = b("5px\n100%");
        undoBtn = b("UNDO");
        redoBtn = b("REDO");
        eyeBtn = b("SHOW");
        clearBtn = b("CLEAR");
        pngBtn = b("SAVE\nPNG");
        pdfBtn = b("SAVE\nPDF");
        printBtn = b("PRINT");
        toggleBtn = b("DRAW\nON");
        exitBtn = b("EXIT");

        TextView[] arr = {
                collapseBtn, penBtn, highBtn, eraseBtn, shapeBtn, presetBtn,
                colorBtn, sizeBtn, undoBtn, redoBtn, eyeBtn, clearBtn,
                pngBtn, pdfBtn, printBtn, toggleBtn, exitBtn
        };
        for (TextView v : arr) toolbar.addView(v);

        collapseBtn.setOnClickListener(v -> {
            collapsed = !collapsed;
            applyCollapsed();
        });
        penBtn.setOnClickListener(v -> {
            tool = DrawingInputView.Tool.PEN;
            applyToolSettings();
            updateToolbarState();
        });
        highBtn.setOnClickListener(v -> {
            tool = DrawingInputView.Tool.HIGHLIGHTER;
            applyToolSettings();
            updateToolbarState();
        });
        eraseBtn.setOnClickListener(v -> {
            tool = DrawingInputView.Tool.ERASER;
            applyToolSettings();
            updateToolbarState();
        });
        shapeBtn.setOnClickListener(v -> {
            shapeIndex = (shapeIndex + 1) % shapes.length;
            tool = shapes[shapeIndex];
            shapeBtn.setText(shapeNames[shapeIndex]);
            applyToolSettings();
            updateToolbarState();
        });
        presetBtn.setOnClickListener(new View.OnClickListener() {
            int p = -1;
            @Override public void onClick(View v) {
                p = (p + 1) % 3;
                if (p == 0) {
                    tool = DrawingInputView.Tool.PEN;
                    selectedColor = Color.rgb(244,67,54);
                    widthDp = 4; alphaPct = 100;
                } else if (p == 1) {
                    tool = DrawingInputView.Tool.HIGHLIGHTER;
                    selectedColor = Color.rgb(255,235,59);
                    widthDp = 18; alphaPct = 30;
                } else {
                    tool = DrawingInputView.Tool.PEN;
                    selectedColor = Color.rgb(33,150,243);
                    widthDp = 7; alphaPct = 100;
                }
                applyToolSettings();
                updateToolbarState();
            }
        });
        colorBtn.setOnClickListener(v -> togglePalette());
        sizeBtn.setOnClickListener(new View.OnClickListener() {
            int w = 0, a = 0;
            final int[] ws = {3,5,8,12};
            final int[] as = {100,70,35};
            @Override public void onClick(View v) {
                w = (w + 1) % ws.length;
                if (w == 0) a = (a + 1) % as.length;
                widthDp = ws[w];
                alphaPct = as[a];
                applyToolSettings();
                updateToolbarState();
            }
        });
        undoBtn.setOnClickListener(v -> { store.undo(); displayView.invalidate(); });
        redoBtn.setOnClickListener(v -> { store.redo(); displayView.invalidate(); });
        eyeBtn.setOnClickListener(v -> {
            displayView.setAnnotationsVisible(!displayView.isAnnotationsVisible());
            updateToolbarState();
        });
        clearBtn.setOnClickListener(v -> { store.clear(); displayView.invalidate(); });
        pngBtn.setOnClickListener(v -> requestCapture(CaptureRequestActivity.MODE_PNG));
        pdfBtn.setOnClickListener(v -> requestCapture(CaptureRequestActivity.MODE_PDF));
        printBtn.setOnClickListener(v -> requestCapture(CaptureRequestActivity.MODE_PRINT));
        toggleBtn.setOnClickListener(v -> setDrawingEnabled(!drawingEnabled));
        exitBtn.setOnClickListener(v -> stopSelf());
        setBox(exitBtn, Color.rgb(176,45,45), false);

        // Toolbar can be dragged using its background/padding area.
        toolbar.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean moved;

            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = toolbarX;
                        startY = toolbarY;
                        moved = false;
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        float mx = e.getRawX() - downX;
                        float my = e.getRawY() - downY;
                        if (Math.hypot(mx, my) > dp(10)) {
                            moved = true;
                            int sw = getResources().getDisplayMetrics().widthPixels;
                            int sh = getResources().getDisplayMetrics().heightPixels;
                            toolbarX = Math.max(0, Math.min(sw - dp(64), startX + (int)mx));
                            toolbarY = Math.max(0, Math.min(sh - dp(80), startY + (int)my));
                            updateToolbarPosition();
                            positionPalette();
                            return true;
                        }
                        return false;
                    default:
                        return moved;
                }
            }
        });
    }

    private void updateToolbarPosition() {
        if (toolbarStandalone && toolbarWindowParams != null) {
            toolbarWindowParams.x = toolbarX;
            toolbarWindowParams.y = toolbarY;
            try { wm.updateViewLayout(toolbar, toolbarWindowParams); } catch (Exception ignored) {}
        } else if (toolbar != null && toolbar.getParent() == interactionRoot) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) toolbar.getLayoutParams();
            lp.leftMargin = toolbarX;
            lp.topMargin = toolbarY;
            toolbar.setLayoutParams(lp);
        }
    }

    private void applyCollapsed() {
        int count = toolbar.getChildCount();
        for (int i = 1; i < count; i++) {
            View child = toolbar.getChildAt(i);
            boolean safety = child == toggleBtn || child == exitBtn;
            child.setVisibility(collapsed && !safety ? View.GONE : View.VISIBLE);
        }
        collapseBtn.setVisibility(View.VISIBLE);
        collapseBtn.setText(collapsed ? "▶\nTOOLS" : "◀\nHIDE");
        hidePalette();
    }

    private void applyToolSettings() {
        if (inputView == null) return;
        inputView.setPenColor(selectedColor);
        inputView.setWidthDp(widthDp);
        inputView.setAlphaPercent(alphaPct);
        inputView.setSmoothingPercent(smoothingPct);
        inputView.setTool(tool);
    }

    private void updateToolbarState() {
        if (toolbar == null) return;
        TextView[] tools = {penBtn,highBtn,eraseBtn,shapeBtn};
        for (TextView v : tools) if (v != null) setBox(v, Color.rgb(58,68,82), false);

        if (tool == DrawingInputView.Tool.PEN) setBox(penBtn, Color.rgb(90,104,122), true);
        else if (tool == DrawingInputView.Tool.HIGHLIGHTER) setBox(highBtn, Color.rgb(90,104,122), true);
        else if (tool == DrawingInputView.Tool.ERASER || eraserActive) setBox(eraseBtn, Color.rgb(90,104,122), true);
        else if (tool == DrawingInputView.Tool.LINE || tool == DrawingInputView.Tool.ARROW
                || tool == DrawingInputView.Tool.RECT || tool == DrawingInputView.Tool.ELLIPSE) {
            setBox(shapeBtn, Color.rgb(90,104,122), true);
        }

        setBox(toggleBtn, drawingEnabled ? Color.rgb(38,124,86) : Color.rgb(133,83,39), false);
        toggleBtn.setText(drawingEnabled ? "DRAW\nON" : "TOUCH\nON");
        setBox(colorBtn, selectedColor, false);
        colorBtn.setText("COLOR\n●");
        colorBtn.setTextColor(contrast(selectedColor));
        sizeBtn.setText(widthDp + "px\n" + alphaPct + "%");
        eyeBtn.setText(displayView != null && displayView.isAnnotationsVisible() ? "SHOW" : "HIDE");
        setBox(exitBtn, Color.rgb(176,45,45), false);
    }

    private void togglePalette() {
        if (paletteVisible) hidePalette(); else showPalette();
    }

    private void showPalette() {
        hidePalette();
        paletteView = new LinearLayout(this);
        paletteView.setOrientation(LinearLayout.VERTICAL);
        paletteView.setPadding(dp(8),dp(8),dp(8),dp(8));
        paletteView.setElevation(dp(18));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(248,35,38,45));
        bg.setCornerRadius(dp(16));
        paletteView.setBackground(bg);

        for (int r = 0; r < 4; r++) {
            LinearLayout row = new LinearLayout(this);
            for (int col = 0; col < 3; col++) {
                final int color = colors[r * 3 + col];
                TextView dot = new TextView(this);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(36), dp(36));
                dlp.setMargins(dp(3),dp(3),dp(3),dp(3));
                dot.setLayoutParams(dlp);
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(color);
                g.setStroke(dp(2),Color.WHITE);
                dot.setBackground(g);
                dot.setOnClickListener(v -> {
                    selectedColor = color;
                    applyToolSettings();
                    hidePalette();
                    updateToolbarState();
                });
                row.addView(dot);
            }
            paletteView.addView(row);
        }

        paletteVisible = true;
        if (drawingEnabled && interactionRoot != null) {
            paletteAsWindow = false;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(138), FrameLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            paletteView.setLayoutParams(lp);
            interactionRoot.addView(paletteView);
            positionPalette();
            paletteView.bringToFront();
            toolbar.bringToFront();
        } else {
            paletteAsWindow = true;
            paletteWindowParams = new WindowManager.LayoutParams(
                    dp(138),
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            paletteWindowParams.gravity = Gravity.TOP | Gravity.START;
            positionPaletteWindowParams();
            wm.addView(paletteView, paletteWindowParams);
        }
    }

    private void positionPalette() {
        if (!paletteVisible || paletteView == null) return;
        if (paletteAsWindow) {
            positionPaletteWindowParams();
            try { wm.updateViewLayout(paletteView, paletteWindowParams); } catch (Exception ignored) {}
            return;
        }
        if (paletteView.getParent() != interactionRoot) return;
        int sw = getResources().getDisplayMetrics().widthPixels;
        int pw = dp(138), gap = dp(8);
        int left = toolbarX - pw - gap;
        int right = toolbarX + dp(64) + gap;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) paletteView.getLayoutParams();
        lp.leftMargin = left >= 0 ? left : Math.min(sw - pw, right);
        lp.topMargin = Math.max(dp(8), toolbarY + dp(60));
        paletteView.setLayoutParams(lp);
    }

    private void positionPaletteWindowParams() {
        if (paletteWindowParams == null) return;
        int sw = getResources().getDisplayMetrics().widthPixels;
        int pw = dp(138), gap = dp(8);
        int left = toolbarX - pw - gap;
        int right = toolbarX + dp(64) + gap;
        paletteWindowParams.x = left >= 0 ? left : Math.min(sw - pw, right);
        paletteWindowParams.y = Math.max(dp(8), toolbarY + dp(60));
    }

    private void hidePalette() {
        if (paletteView != null) {
            if (paletteAsWindow) {
                try { wm.removeView(paletteView); } catch (Exception ignored) {}
            } else if (paletteView.getParent() instanceof ViewGroup) {
                try { ((ViewGroup)paletteView.getParent()).removeView(paletteView); } catch (Exception ignored) {}
            }
        }
        paletteView = null;
        paletteWindowParams = null;
        paletteVisible = false;
        paletteAsWindow = false;
    }

    private void requestCapture(String mode) {
        if (captureHidden) return;
        hidePalette();
        Toast.makeText(this, "화면 캡처 허용 창에서 '전체 화면'을 선택하세요.", Toast.LENGTH_LONG).show();
        setCaptureHidden(true);
        try {
            Intent intent = new Intent(this, CaptureRequestActivity.class)
                    .putExtra(CaptureRequestActivity.EXTRA_MODE, mode)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(intent);
        } catch (Exception e) {
            setCaptureHidden(false);
            Toast.makeText(this, "캡처 화면을 열지 못했습니다: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void setCaptureHidden(boolean hidden) {
        captureHidden = hidden;
        if (displayView != null) displayView.setVisibility(hidden ? View.INVISIBLE : View.VISIBLE);
        if (drawingEnabled) {
            if (interactionRoot != null) interactionRoot.setVisibility(hidden ? View.INVISIBLE : View.VISIBLE);
        } else {
            if (toolbar != null && toolbarStandalone) toolbar.setVisibility(hidden ? View.INVISIBLE : View.VISIBLE);
        }
        if (!hidden && displayView != null) displayView.invalidate();
    }

    private int contrast(int color) {
        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        return .299 * r + .587 * g + .114 * b > 170 ? Color.BLACK : Color.WHITE;
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent op = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent cl = new Intent(this, OverlayDrawingService.class).setAction(ACTION_CLEAR);
        PendingIntent cp = PendingIntent.getService(this, 2, cl,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent st = new Intent(this, OverlayDrawingService.class).setAction(ACTION_STOP);
        PendingIntent sp = PendingIntent.getService(this, 3, st,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, "screen_sketch")
                .setContentTitle("Screen Sketch S Pen v1.3")
                .setContentText("PNG/PDF/PRINT · 빨간 EXIT 또는 알림에서 언제든 종료")
                .setSmallIcon(R.drawable.ic_pen)
                .setContentIntent(op)
                .setOngoing(true)
                .addAction(R.drawable.ic_pen, "전체 삭제", cp)
                .addAction(R.drawable.ic_pen, "종료", sp)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(
                    "screen_sketch", "Screen Sketch", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("화면 위 S펜 드로잉 서비스");
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    @Override public void onDestroy() {
        hidePalette();
        if (wm != null) {
            if (toolbarStandalone && toolbar != null) {
                try { wm.removeView(toolbar); } catch (Exception ignored) {}
            }
            if (interactionRoot != null) {
                try { wm.removeView(interactionRoot); } catch (Exception ignored) {}
            }
            if (displayView != null) {
                try { wm.removeView(displayView); } catch (Exception ignored) {}
            }
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    private int dp(int v) {
        return (int)(v * getResources().getDisplayMetrics().density + .5f);
    }
}
