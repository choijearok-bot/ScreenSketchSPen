package com.example.screensketch;

import android.app.*;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class OverlayDrawingService extends Service {
    public static final String ACTION_STOP = "com.example.screensketch.STOP";
    public static final String ACTION_TOGGLE = "com.example.screensketch.TOGGLE_DRAW";
    public static final String ACTION_CAPTURE_RESTORE = "com.example.screensketch.CAPTURE_RESTORE";

    private static final String PREFS = "screen_sketch_state_v14";

    private WindowManager wm;
    private DrawingDisplayView displayView;
    private DrawingInputView inputView;
    private LinearLayout toolbar;
    private LinearLayout paletteView;

    private WindowManager.LayoutParams displayParams;
    private WindowManager.LayoutParams inputParams;
    private WindowManager.LayoutParams toolbarParams;
    private WindowManager.LayoutParams paletteParams;

    private final StrokeStore store = StrokeStore.get();
    private SharedPreferences prefs;

    private boolean drawingEnabled = true;
    private boolean collapsed = false;
    private boolean paletteVisible = false;
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
    private long clearConfirmUntil = 0L;
    private long exitConfirmUntil = 0L;

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

        store.init(getApplicationContext());
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        restorePreferences();

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        addDisplayWindow();
        addInputWindow();
        buildToolbar();
        addToolbarWindow(); // Always added LAST so it stays above the drawing input layer.
        applyDrawingMode();
        applyCollapsed();
        updateToolbarState();
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        if (i != null) {
            String action = i.getAction();
            if (ACTION_STOP.equals(action)) {
                requestFullExit();
                return START_NOT_STICKY;
            } else if (ACTION_TOGGLE.equals(action)) {
                setDrawingEnabled(!drawingEnabled);
            } else if (ACTION_CAPTURE_RESTORE.equals(action)) {
                setCaptureHidden(false);
            }
        }
        // Keep the foreground drawing toolbar alive even if the launcher activity is closed.
        // If Android recreates the service, the saved strokes and toolbar state are restored.
        return START_STICKY;
    }

    private void restorePreferences() {
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        int defaultX = Math.max(dp(8), sw - dp(76));
        int defaultY = Math.max(dp(20), sh / 2 - dp(330));

        drawingEnabled = prefs.getBoolean("drawingEnabled", true);
        collapsed = prefs.getBoolean("collapsed", false);
        selectedColor = prefs.getInt("selectedColor", Color.rgb(244,67,54));
        widthDp = prefs.getInt("widthDp", 5);
        alphaPct = prefs.getInt("alphaPct", 100);
        smoothingPct = prefs.getInt("smoothingPct", 45);
        toolbarX = prefs.getInt("toolbarX", defaultX);
        toolbarY = prefs.getInt("toolbarY", defaultY);

        int toolOrdinal = prefs.getInt("tool", DrawingInputView.Tool.PEN.ordinal());
        DrawingInputView.Tool[] values = DrawingInputView.Tool.values();
        tool = toolOrdinal >= 0 && toolOrdinal < values.length ? values[toolOrdinal] : DrawingInputView.Tool.PEN;
        for (int i = 0; i < shapes.length; i++) {
            if (tool == shapes[i]) { shapeIndex = i; break; }
        }
    }

    private void savePreferences() {
        if (prefs == null) return;
        prefs.edit()
                .putBoolean("drawingEnabled", drawingEnabled)
                .putBoolean("collapsed", collapsed)
                .putInt("selectedColor", selectedColor)
                .putInt("widthDp", widthDp)
                .putInt("alphaPct", alphaPct)
                .putInt("smoothingPct", smoothingPct)
                .putInt("toolbarX", toolbarX)
                .putInt("toolbarY", toolbarY)
                .putInt("tool", tool.ordinal())
                .apply();
    }

    private void addDisplayWindow() {
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
        // Android 12+ allows touch-through for untrusted overlays only below 0.8 obscuring opacity.
        displayParams.alpha = 0.78f;
        wm.addView(displayView, displayParams);
    }

    private void addInputWindow() {
        inputView = new DrawingInputView(this, store, displayView);
        inputView.setEraserStateListener(active -> {
            eraserActive = active;
            updateToolbarState();
        });
        applyToolSettings();

        inputParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        inputParams.gravity = Gravity.TOP | Gravity.START;
        // This window draws nothing; low alpha keeps touch-through safe when PEN is OFF.
        inputParams.alpha = 0.01f;
        wm.addView(inputView, inputParams);
    }

    private void addToolbarWindow() {
        toolbarParams = new WindowManager.LayoutParams(
                dp(64),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        toolbarParams.gravity = Gravity.TOP | Gravity.START;
        toolbarParams.x = toolbarX;
        toolbarParams.y = toolbarY;
        wm.addView(toolbar, toolbarParams);
    }

    private void setDrawingEnabled(boolean enabled) {
        if (captureHidden) return;
        drawingEnabled = enabled;
        eraserActive = false;
        hidePalette();
        applyDrawingMode();
        savePreferences();
        updateToolbarState();
        Toast.makeText(this,
                enabled ? "펜 입력 ON · 그리기 가능" : "펜 입력 OFF · 아래 화면 조작 가능",
                Toast.LENGTH_SHORT).show();
    }

    private void applyDrawingMode() {
        if (inputView == null || inputParams == null || wm == null) return;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (!drawingEnabled || captureHidden) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        }
        inputParams.flags = flags;
        inputParams.alpha = 0.01f;
        inputView.setEnabled(drawingEnabled && !captureHidden);
        inputView.setVisibility(captureHidden ? View.INVISIBLE : View.VISIBLE);
        try { wm.updateViewLayout(inputView, inputParams); } catch (Exception ignored) {}
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
        toolbar.setElevation(dp(18));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(246,25,31,41));
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1),Color.argb(80,255,255,255));
        toolbar.setBackground(bg);

        collapseBtn = b("◀\nHIDE");
        penBtn = b("PEN");
        highBtn = b("MARK");
        eraseBtn = b("ERASE");
        shapeBtn = b(shapeNames[shapeIndex]);
        presetBtn = b("★ PRESET");
        colorBtn = b("COLOR");
        sizeBtn = b(widthDp + "px\n" + alphaPct + "%");
        undoBtn = b("UNDO");
        redoBtn = b("REDO");
        eyeBtn = b("HIDE");
        clearBtn = b("CLEAR");
        pngBtn = b("SAVE\nPNG");
        pdfBtn = b("SAVE\nPDF");
        printBtn = b("PRINT");
        toggleBtn = b("PEN\nON");
        exitBtn = b("EXIT\nAPP");

        TextView[] arr = {
                collapseBtn, penBtn, highBtn, eraseBtn, shapeBtn, presetBtn,
                colorBtn, sizeBtn, undoBtn, redoBtn, eyeBtn, clearBtn,
                pngBtn, pdfBtn, printBtn, toggleBtn, exitBtn
        };
        for (TextView v : arr) toolbar.addView(v);

        collapseBtn.setOnClickListener(v -> {
            collapsed = !collapsed;
            applyCollapsed();
            savePreferences();
        });
        penBtn.setOnClickListener(v -> selectTool(DrawingInputView.Tool.PEN));
        highBtn.setOnClickListener(v -> selectTool(DrawingInputView.Tool.HIGHLIGHTER));
        eraseBtn.setOnClickListener(v -> selectTool(DrawingInputView.Tool.ERASER));
        shapeBtn.setOnClickListener(v -> {
            shapeIndex = (shapeIndex + 1) % shapes.length;
            tool = shapes[shapeIndex];
            shapeBtn.setText(shapeNames[shapeIndex]);
            applyToolSettings();
            savePreferences();
            updateToolbarState();
        });
        presetBtn.setOnClickListener(new View.OnClickListener() {
            int p = -1;
            @Override public void onClick(View v) {
                p = (p + 1) % 3;
                if (p == 0) {
                    tool = DrawingInputView.Tool.PEN;
                    selectedColor = Color.rgb(244,67,54); widthDp = 4; alphaPct = 100;
                } else if (p == 1) {
                    tool = DrawingInputView.Tool.HIGHLIGHTER;
                    selectedColor = Color.rgb(255,235,59); widthDp = 18; alphaPct = 30;
                } else {
                    tool = DrawingInputView.Tool.PEN;
                    selectedColor = Color.rgb(33,150,243); widthDp = 7; alphaPct = 100;
                }
                applyToolSettings();
                savePreferences();
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
                widthDp = ws[w]; alphaPct = as[a];
                applyToolSettings(); savePreferences(); updateToolbarState();
            }
        });
        undoBtn.setOnClickListener(v -> { store.undo(); displayView.invalidate(); });
        redoBtn.setOnClickListener(v -> { store.redo(); displayView.invalidate(); });
        eyeBtn.setOnClickListener(v -> {
            displayView.setAnnotationsVisible(!displayView.isAnnotationsVisible());
            updateToolbarState();
        });
        clearBtn.setOnClickListener(v -> confirmClear());
        pngBtn.setOnClickListener(v -> requestCapture(CaptureRequestActivity.MODE_PNG));
        pdfBtn.setOnClickListener(v -> requestCapture(CaptureRequestActivity.MODE_PDF));
        printBtn.setOnClickListener(v -> requestCapture(CaptureRequestActivity.MODE_PRINT));
        toggleBtn.setOnClickListener(v -> setDrawingEnabled(!drawingEnabled));
        exitBtn.setOnClickListener(v -> confirmExit());
        setBox(exitBtn, Color.rgb(176,45,45), false);

        toolbar.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean moved;

            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY();
                        startX = toolbarX; startY = toolbarY; moved = false;
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
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (moved) {
                            snapToolbarToEdge();
                            savePreferences();
                            return true;
                        }
                        return false;
                    default:
                        return moved;
                }
            }
        });
    }

    private void selectTool(DrawingInputView.Tool selected) {
        tool = selected;
        applyToolSettings();
        savePreferences();
        updateToolbarState();
    }

    private void updateToolbarPosition() {
        if (toolbar == null || toolbarParams == null) return;
        toolbarParams.x = toolbarX;
        toolbarParams.y = toolbarY;
        try { wm.updateViewLayout(toolbar, toolbarParams); } catch (Exception ignored) {}
    }

    private void snapToolbarToEdge() {
        int sw = getResources().getDisplayMetrics().widthPixels;
        toolbarX = toolbarX + dp(32) < sw / 2 ? dp(8) : Math.max(dp(8), sw - dp(72));
        updateToolbarPosition();
        positionPalette();
    }

    private void applyCollapsed() {
        if (toolbar == null) return;
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
        toggleBtn.setText(drawingEnabled ? "PEN\nON" : "PEN\nOFF");
        setBox(colorBtn, selectedColor, false);
        colorBtn.setText("COLOR\n●");
        colorBtn.setTextColor(contrast(selectedColor));
        sizeBtn.setText(widthDp + "px\n" + alphaPct + "%");
        eyeBtn.setText(displayView != null && displayView.isAnnotationsVisible() ? "HIDE" : "SHOW");
        if (clearConfirmUntil == 0L) clearBtn.setText("CLEAR");
        if (exitConfirmUntil == 0L) exitBtn.setText("EXIT\nAPP");
        setBox(exitBtn, Color.rgb(176,45,45), false);
    }

    private void confirmClear() {
        long now = SystemClock.elapsedRealtime();
        if (now <= clearConfirmUntil) {
            clearConfirmUntil = 0L;
            store.clear();
            displayView.invalidate();
            clearBtn.setText("CLEAR");
            Toast.makeText(this, "그림을 모두 지웠습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        clearConfirmUntil = now + 2500L;
        clearBtn.setText("CLEAR?");
        Toast.makeText(this, "전체 삭제하려면 CLEAR를 한 번 더 누르세요.", Toast.LENGTH_SHORT).show();
        clearBtn.postDelayed(() -> {
            if (SystemClock.elapsedRealtime() > clearConfirmUntil) {
                clearConfirmUntil = 0L;
                if (clearBtn != null) clearBtn.setText("CLEAR");
            }
        }, 2600L);
    }

    private void confirmExit() {
        long now = SystemClock.elapsedRealtime();
        if (now <= exitConfirmUntil) {
            exitConfirmUntil = 0L;
            requestFullExit();
            return;
        }
        exitConfirmUntil = now + 2500L;
        exitBtn.setText("EXIT?");
        Toast.makeText(this, "앱을 완전히 종료하려면 EXIT를 한 번 더 누르세요.", Toast.LENGTH_SHORT).show();
        exitBtn.postDelayed(() -> {
            if (SystemClock.elapsedRealtime() > exitConfirmUntil) {
                exitConfirmUntil = 0L;
                if (exitBtn != null) exitBtn.setText("EXIT\nAPP");
            }
        }, 2600L);
    }

    private void requestFullExit() {
        store.save();
        savePreferences();
        stopSelf();
    }

    private void togglePalette() {
        if (paletteVisible) hidePalette(); else showPalette();
    }

    private void showPalette() {
        hidePalette();
        paletteView = new LinearLayout(this);
        paletteView.setOrientation(LinearLayout.VERTICAL);
        paletteView.setPadding(dp(8),dp(8),dp(8),dp(8));
        paletteView.setElevation(dp(20));
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
                    savePreferences();
                    hidePalette();
                    updateToolbarState();
                });
                row.addView(dot);
            }
            paletteView.addView(row);
        }

        paletteParams = new WindowManager.LayoutParams(
                dp(138),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        paletteParams.gravity = Gravity.TOP | Gravity.START;
        paletteVisible = true;
        positionPaletteParams();
        wm.addView(paletteView, paletteParams);
    }

    private void positionPalette() {
        if (!paletteVisible || paletteView == null || paletteParams == null) return;
        positionPaletteParams();
        try { wm.updateViewLayout(paletteView, paletteParams); } catch (Exception ignored) {}
    }

    private void positionPaletteParams() {
        if (paletteParams == null) return;
        int sw = getResources().getDisplayMetrics().widthPixels;
        int pw = dp(138), gap = dp(8);
        int left = toolbarX - pw - gap;
        int right = toolbarX + dp(64) + gap;
        paletteParams.x = left >= 0 ? left : Math.min(sw - pw, right);
        paletteParams.y = Math.max(dp(8), toolbarY + dp(60));
    }

    private void hidePalette() {
        if (paletteView != null && wm != null) {
            try { wm.removeView(paletteView); } catch (Exception ignored) {}
        }
        paletteView = null;
        paletteParams = null;
        paletteVisible = false;
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
        if (toolbar != null) toolbar.setVisibility(hidden ? View.INVISIBLE : View.VISIBLE);
        applyDrawingMode();
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

        Intent toggle = new Intent(this, OverlayDrawingService.class).setAction(ACTION_TOGGLE);
        PendingIntent tp = PendingIntent.getService(this, 2, toggle,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, OverlayDrawingService.class).setAction(ACTION_STOP);
        PendingIntent sp = PendingIntent.getService(this, 3, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, "screen_sketch")
                .setContentTitle("Screen Sketch S Pen v1.4")
                .setContentText("툴바 상시 유지 · 그림 자동복구 · PNG/PDF/PRINT")
                .setSmallIcon(R.drawable.ic_pen)
                .setContentIntent(op)
                .setOngoing(true)
                .addAction(R.drawable.ic_pen, "펜 ON/OFF", tp)
                .addAction(R.drawable.ic_pen, "앱 완전 종료", sp)
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
        store.save();
        savePreferences();
        hidePalette();
        if (wm != null) {
            if (toolbar != null) try { wm.removeView(toolbar); } catch (Exception ignored) {}
            if (inputView != null) try { wm.removeView(inputView); } catch (Exception ignored) {}
            if (displayView != null) try { wm.removeView(displayView); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    private int dp(int v) {
        return (int)(v * getResources().getDisplayMetrics().density + .5f);
    }
}
