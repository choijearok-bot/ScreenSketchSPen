package com.example.screensketch;

import android.app.*;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
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
    private static final int TOOLBAR_EXPANDED_WIDTH_DP = 116;
    private static final int TOOLBAR_COLLAPSED_WIDTH_DP = 54;

    private WindowManager wm;
    private DrawingDisplayView displayView;
    private DrawingInputView inputView;
    private LinearLayout toolbar;
    private LinearLayout paletteView;
    private LinearLayout presetView;

    private WindowManager.LayoutParams displayParams;
    private WindowManager.LayoutParams inputParams;
    private WindowManager.LayoutParams toolbarParams;
    private WindowManager.LayoutParams paletteParams;
    private WindowManager.LayoutParams presetParams;

    private final StrokeStore store = StrokeStore.get();
    private SharedPreferences prefs;

    private boolean drawingEnabled = true;
    private boolean collapsed = false;
    private boolean paletteVisible = false;
    private boolean eraserActive = false;
    private boolean captureHidden = false;
    private boolean toolbarLocked = false;
    private boolean presetVisible = false;

    private int selectedColor = Color.rgb(244,67,54);
    private int widthDp = 5;
    private int alphaPct = 100;
    private int smoothingPct = 45;
    private DrawingInputView.Tool tool = DrawingInputView.Tool.PEN;

    private TextView collapseBtn, penBtn, highBtn, eraseBtn, shapeBtn, presetBtn,
            colorBtn, sizeBtn, undoBtn, redoBtn, eyeBtn, clearBtn,
            lassoBtn, lockBtn, workBtn, recoverBtn,
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
        initializePresetDefaults();
        restorePreferences();
        store.setCanvasSize(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);

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

    private void initializePresetDefaults() {
        if (prefs == null || prefs.getBoolean("presetInit", false)) return;
        int[] pc = {Color.rgb(244,67,54), Color.rgb(255,235,59), Color.rgb(33,150,243), Color.BLACK, Color.rgb(76,175,80)};
        int[] pw = {4,18,7,5,5};
        int[] pa = {100,30,100,100,100};
        DrawingInputView.Tool[] pt = {DrawingInputView.Tool.PEN, DrawingInputView.Tool.HIGHLIGHTER, DrawingInputView.Tool.PEN, DrawingInputView.Tool.PEN, DrawingInputView.Tool.PEN};
        SharedPreferences.Editor e = prefs.edit().putBoolean("presetInit", true);
        for (int i=0;i<5;i++) {
            e.putInt("p"+i+"_color",pc[i]).putInt("p"+i+"_width",pw[i]).putInt("p"+i+"_alpha",pa[i]).putInt("p"+i+"_tool",pt[i].ordinal());
        }
        e.apply();
    }

    private void applyPreset(int index) {
        selectedColor = prefs.getInt("p"+index+"_color", selectedColor);
        widthDp = prefs.getInt("p"+index+"_width", widthDp);
        alphaPct = prefs.getInt("p"+index+"_alpha", alphaPct);
        int ordinal = prefs.getInt("p"+index+"_tool", DrawingInputView.Tool.PEN.ordinal());
        DrawingInputView.Tool[] values = DrawingInputView.Tool.values();
        tool = ordinal >= 0 && ordinal < values.length ? values[ordinal] : DrawingInputView.Tool.PEN;
        if (tool == DrawingInputView.Tool.LASSO || tool == DrawingInputView.Tool.ERASER) tool = DrawingInputView.Tool.PEN;
        rememberRecentColor(selectedColor);
        applyToolSettings(); savePreferences(); updateToolbarState(); hidePresetPanel();
        Toast.makeText(this, "P"+(index+1)+" 펜 적용", Toast.LENGTH_SHORT).show();
    }

    private void savePreset(int index) {
        DrawingInputView.Tool saveTool = tool == DrawingInputView.Tool.HIGHLIGHTER ? DrawingInputView.Tool.HIGHLIGHTER : DrawingInputView.Tool.PEN;
        prefs.edit().putInt("p"+index+"_color",selectedColor).putInt("p"+index+"_width",widthDp)
                .putInt("p"+index+"_alpha",alphaPct).putInt("p"+index+"_tool",saveTool.ordinal()).apply();
        Toast.makeText(this, "현재 펜을 P"+(index+1)+"에 저장", Toast.LENGTH_SHORT).show();
    }

    private void rememberRecentColor(int color) {
        if (prefs == null) return;
        int[] recent = new int[4];
        for (int i=0;i<4;i++) recent[i]=prefs.getInt("recentColor"+i, i==0?selectedColor:Color.TRANSPARENT);
        SharedPreferences.Editor e=prefs.edit(); e.putInt("recentColor0",color);
        int out=1;
        for (int c:recent) { if (c==Color.TRANSPARENT || c==color || out>=4) continue; e.putInt("recentColor"+out,c); out++; }
        while(out<4){e.putInt("recentColor"+out,Color.TRANSPARENT);out++;}
        e.apply();
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
        toolbarLocked = prefs.getBoolean("toolbarLocked", false);

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
                .putBoolean("toolbarLocked", toolbarLocked)
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
                dp(TOOLBAR_EXPANDED_WIDTH_DP),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        toolbarParams.gravity = Gravity.TOP | Gravity.START;
        toolbarParams.x = toolbarX;
        toolbarParams.y = toolbarY;
        wm.addView(toolbar, toolbarParams);
        toolbar.post(this::clampToolbarToScreen);
    }

    private void setDrawingEnabled(boolean enabled) {
        if (captureHidden) return;
        drawingEnabled = enabled;
        eraserActive = false;
        hidePalette();
        hidePresetPanel();
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
        lp.setMargins(dp(2),dp(2),dp(2),dp(2));
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
        lassoBtn = b("LASSO");
        presetBtn = b("★ PENS");
        colorBtn = b("COLOR");
        sizeBtn = b(widthDp + "px\n" + alphaPct + "%");
        lockBtn = b("LOCK");
        undoBtn = b("UNDO");
        redoBtn = b("REDO");
        recoverBtn = b("RECOVER");
        workBtn = b("WORK");
        eyeBtn = b("HIDE");
        clearBtn = b("CLEAR");
        pngBtn = b("SAVE\nPNG");
        pdfBtn = b("SAVE\nPDF");
        printBtn = b("PRINT");
        toggleBtn = b("PEN\nON");
        exitBtn = b("EXIT\nAPP");

        // Keep the toolbar compact enough for Galaxy Tab landscape mode.
        // The collapse button stays on top; all other tools are arranged in two columns.
        LinearLayout.LayoutParams collapseLp = new LinearLayout.LayoutParams(dp(104), dp(38));
        collapseLp.setMargins(dp(2),dp(2),dp(2),dp(3));
        collapseBtn.setLayoutParams(collapseLp);
        toolbar.addView(collapseBtn);

        addToolbarPair(toggleBtn, exitBtn);
        addToolbarPair(penBtn, highBtn);
        addToolbarPair(eraseBtn, shapeBtn);
        addToolbarPair(lassoBtn, presetBtn);
        addToolbarPair(colorBtn, sizeBtn);
        addToolbarPair(lockBtn, eyeBtn);
        addToolbarPair(undoBtn, redoBtn);
        addToolbarPair(recoverBtn, workBtn);
        addToolbarPair(clearBtn, pngBtn);
        addToolbarPair(pdfBtn, printBtn);

        collapseBtn.setOnClickListener(v -> {
            collapsed = !collapsed;
            applyCollapsed();
            savePreferences();
        });
        penBtn.setOnClickListener(v -> selectTool(DrawingInputView.Tool.PEN));
        highBtn.setOnClickListener(v -> selectTool(DrawingInputView.Tool.HIGHLIGHTER));
        eraseBtn.setOnClickListener(v -> selectTool(DrawingInputView.Tool.ERASER));
        lassoBtn.setOnClickListener(v -> selectTool(DrawingInputView.Tool.LASSO));
        shapeBtn.setOnClickListener(v -> {
            shapeIndex = (shapeIndex + 1) % shapes.length;
            tool = shapes[shapeIndex];
            shapeBtn.setText(shapeNames[shapeIndex]);
            applyToolSettings();
            savePreferences();
            updateToolbarState();
        });
        presetBtn.setOnClickListener(v -> togglePresetPanel());
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
        lockBtn.setOnClickListener(v -> {
            toolbarLocked = !toolbarLocked;
            savePreferences();
            updateToolbarState();
            Toast.makeText(this, toolbarLocked ? "툴바 위치 잠금" : "툴바 위치 잠금 해제", Toast.LENGTH_SHORT).show();
        });
        workBtn.setOnClickListener(v -> {
            String name = store.exportWork(getApplicationContext());
            Toast.makeText(this, name == null ? "작업파일 저장 실패" : "작업파일 저장: " + name, Toast.LENGTH_LONG).show();
        });
        workBtn.setOnLongClickListener(v -> {
            String name = store.importLatestWork(getApplicationContext());
            if (name == null) Toast.makeText(this, "불러올 작업파일이 없습니다.", Toast.LENGTH_LONG).show();
            else { displayView.invalidate(); Toast.makeText(this, "최근 작업 불러오기: " + name, Toast.LENGTH_LONG).show(); }
            return true;
        });
        recoverBtn.setOnClickListener(v -> {
            if (store.restorePreviousHistory()) {
                displayView.invalidate();
                Toast.makeText(this, "이전 자동저장 상태를 복구했습니다.", Toast.LENGTH_SHORT).show();
            } else Toast.makeText(this, "복구할 이전 상태가 없습니다.", Toast.LENGTH_SHORT).show();
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
                        if (toolbarLocked) return false;
                        downX = e.getRawX(); downY = e.getRawY();
                        startX = toolbarX; startY = toolbarY; moved = false;
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        if (toolbarLocked) return false;
                        float mx = e.getRawX() - downX;
                        float my = e.getRawY() - downY;
                        if (Math.hypot(mx, my) > dp(10)) {
                            moved = true;
                            int sw = getResources().getDisplayMetrics().widthPixels;
                            int sh = getResources().getDisplayMetrics().heightPixels;
                            int tw = currentToolbarWidthPx();
                            int th = currentToolbarHeightPx();
                            toolbarX = Math.max(0, Math.min(Math.max(0, sw - tw), startX + (int)mx));
                            toolbarY = Math.max(0, Math.min(Math.max(0, sh - th), startY + (int)my));
                            updateToolbarPosition();
                            positionPalette();
                            positionPresetPanel();
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

    private void addToolbarPair(View left, View right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.addView(left);
        row.addView(right);
        toolbar.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private int currentToolbarWidthPx() {
        return dp(collapsed ? TOOLBAR_COLLAPSED_WIDTH_DP : TOOLBAR_EXPANDED_WIDTH_DP);
    }

    private int currentToolbarHeightPx() {
        if (toolbar != null && toolbar.getMeasuredHeight() > 0) return toolbar.getMeasuredHeight();
        return dp(collapsed ? 54 : 520);
    }

    private void clampToolbarToScreen() {
        if (toolbarParams == null) return;
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        int tw = currentToolbarWidthPx();
        int th = currentToolbarHeightPx();
        toolbarX = Math.max(0, Math.min(Math.max(0, sw - tw), toolbarX));
        toolbarY = Math.max(0, Math.min(Math.max(0, sh - th), toolbarY));
        toolbarParams.x = toolbarX;
        toolbarParams.y = toolbarY;
        try { wm.updateViewLayout(toolbar, toolbarParams); } catch (Exception ignored) {}
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
        int tw = currentToolbarWidthPx();
        toolbarX = toolbarX + tw / 2 < sw / 2 ? dp(8) : Math.max(dp(8), sw - tw - dp(8));
        clampToolbarToScreen();
        updateToolbarPosition();
        positionPalette();
        positionPresetPanel();
    }

    private void applyCollapsed() {
        if (toolbar == null) return;
        int count = toolbar.getChildCount();
        for (int i = 1; i < count; i++) toolbar.getChildAt(i).setVisibility(collapsed ? View.GONE : View.VISIBLE);
        collapseBtn.setVisibility(View.VISIBLE);
        collapseBtn.setText(collapsed ? "✎" : "◀\nHIDE");
        hidePalette();
        hidePresetPanel();

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(246,25,31,41));
        bg.setStroke(dp(1),Color.argb(80,255,255,255));
        if (collapsed) {
            bg.setShape(GradientDrawable.OVAL);
            toolbar.setPadding(dp(4),dp(4),dp(4),dp(4));
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) collapseBtn.getLayoutParams();
            lp.width = dp(46); lp.height = dp(46); lp.setMargins(0,0,0,0); collapseBtn.setLayoutParams(lp);
            GradientDrawable cg = new GradientDrawable(); cg.setShape(GradientDrawable.OVAL); cg.setColor(Color.rgb(58,68,82)); cg.setStroke(dp(1),Color.WHITE); collapseBtn.setBackground(cg);
        } else {
            bg.setCornerRadius(dp(18));
            toolbar.setPadding(dp(6),dp(7),dp(6),dp(7));
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) collapseBtn.getLayoutParams();
            lp.width = dp(104); lp.height = dp(38); lp.setMargins(dp(2),dp(2),dp(2),dp(3)); collapseBtn.setLayoutParams(lp);
            setBox(collapseBtn, Color.rgb(58,68,82), false);
        }
        toolbar.setBackground(bg);
        if (toolbarParams != null) {
            toolbarParams.width = dp(collapsed ? TOOLBAR_COLLAPSED_WIDTH_DP : TOOLBAR_EXPANDED_WIDTH_DP);
            toolbarParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
            try { wm.updateViewLayout(toolbar, toolbarParams); } catch (Exception ignored) {}
            toolbar.post(this::clampToolbarToScreen);
        }
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
        TextView[] tools = {penBtn,highBtn,eraseBtn,shapeBtn,lassoBtn};
        for (TextView v : tools) if (v != null) setBox(v, Color.rgb(58,68,82), false);

        if (tool == DrawingInputView.Tool.PEN) setBox(penBtn, Color.rgb(90,104,122), true);
        else if (tool == DrawingInputView.Tool.HIGHLIGHTER) setBox(highBtn, Color.rgb(90,104,122), true);
        else if (tool == DrawingInputView.Tool.ERASER || eraserActive) setBox(eraseBtn, Color.rgb(90,104,122), true);
        else if (tool == DrawingInputView.Tool.LINE || tool == DrawingInputView.Tool.ARROW
                || tool == DrawingInputView.Tool.RECT || tool == DrawingInputView.Tool.ELLIPSE) {
            setBox(shapeBtn, Color.rgb(90,104,122), true);
        } else if (tool == DrawingInputView.Tool.LASSO) {
            setBox(lassoBtn, Color.rgb(90,104,122), true);
        }

        setBox(toggleBtn, drawingEnabled ? Color.rgb(38,124,86) : Color.rgb(133,83,39), false);
        toggleBtn.setText(drawingEnabled ? "PEN\nON" : "PEN\nOFF");
        setBox(colorBtn, selectedColor, false);
        colorBtn.setText("COLOR\n●");
        colorBtn.setTextColor(contrast(selectedColor));
        sizeBtn.setText(widthDp + "px\n" + alphaPct + "%");
        lockBtn.setText(toolbarLocked ? "UNLOCK" : "LOCK");
        setBox(lockBtn, toolbarLocked ? Color.rgb(95,72,150) : Color.rgb(58,68,82), toolbarLocked);
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
        hidePresetPanel();
        if (paletteVisible) hidePalette(); else showPalette();
    }

    private void showPalette() {
        hidePresetPanel();
        hidePalette();
        paletteView = new LinearLayout(this);
        paletteView.setOrientation(LinearLayout.VERTICAL);
        paletteView.setPadding(dp(8),dp(8),dp(8),dp(8));
        paletteView.setElevation(dp(20));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(248,35,38,45));
        bg.setCornerRadius(dp(16));
        paletteView.setBackground(bg);

        LinearLayout recentRow = new LinearLayout(this);
        for (int i=0;i<4;i++) {
            final int rc=prefs.getInt("recentColor"+i, i==0?selectedColor:Color.TRANSPARENT);
            if (rc==Color.TRANSPARENT) continue;
            TextView dot=new TextView(this); LinearLayout.LayoutParams rlp=new LinearLayout.LayoutParams(dp(28),dp(28)); rlp.setMargins(dp(3),dp(3),dp(3),dp(6)); dot.setLayoutParams(rlp);
            GradientDrawable rg=new GradientDrawable(); rg.setShape(GradientDrawable.OVAL); rg.setColor(rc); rg.setStroke(dp(2),Color.WHITE); dot.setBackground(rg);
            dot.setOnClickListener(v->{selectedColor=rc;rememberRecentColor(rc);applyToolSettings();savePreferences();hidePalette();updateToolbarState();});
            recentRow.addView(dot);
        }
        if (recentRow.getChildCount()>0) paletteView.addView(recentRow);

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
                    rememberRecentColor(color);
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
        int right = toolbarX + currentToolbarWidthPx() + gap;
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

    private void togglePresetPanel() {
        hidePalette();
        if (presetVisible) hidePresetPanel(); else showPresetPanel();
    }

    private void showPresetPanel() {
        hidePresetPanel();
        presetView = new LinearLayout(this);
        presetView.setOrientation(LinearLayout.VERTICAL);
        presetView.setPadding(dp(8),dp(8),dp(8),dp(8));
        presetView.setElevation(dp(20));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(248,35,38,45)); bg.setCornerRadius(dp(16));
        presetView.setBackground(bg);
        TextView help = new TextView(this);
        help.setText("탭=적용\n길게=현재펜 저장"); help.setTextColor(Color.LTGRAY); help.setTextSize(9); help.setGravity(Gravity.CENTER);
        presetView.addView(help, new LinearLayout.LayoutParams(dp(126),dp(38)));
        for (int i=0;i<5;i++) {
            final int idx=i; TextView item=b("P"+(i+1));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(126),dp(38)); lp.setMargins(0,dp(2),0,dp(2)); item.setLayoutParams(lp);
            int c=prefs.getInt("p"+i+"_color",Color.WHITE); setBox(item,c,false); item.setTextColor(contrast(c));
            item.setOnClickListener(v -> applyPreset(idx));
            item.setOnLongClickListener(v -> { savePreset(idx); hidePresetPanel(); return true; });
            presetView.addView(item);
        }
        presetParams = new WindowManager.LayoutParams(dp(142),WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT);
        presetParams.gravity=Gravity.TOP|Gravity.START; presetVisible=true; positionPresetParams(); wm.addView(presetView,presetParams);
    }

    private void positionPresetPanel() {
        if (!presetVisible || presetView==null || presetParams==null) return;
        positionPresetParams(); try { wm.updateViewLayout(presetView,presetParams); } catch(Exception ignored) {}
    }

    private void positionPresetParams() {
        if (presetParams==null) return;
        int sw=getResources().getDisplayMetrics().widthPixels, pw=dp(142), gap=dp(8);
        int left=toolbarX-pw-gap, right=toolbarX+currentToolbarWidthPx()+gap;
        presetParams.x=left>=0?left:Math.min(sw-pw,right); presetParams.y=Math.max(dp(8),toolbarY+dp(60));
    }

    private void hidePresetPanel() {
        if (presetView!=null && wm!=null) try { wm.removeView(presetView); } catch(Exception ignored) {}
        presetView=null; presetParams=null; presetVisible=false;
    }

    private void requestCapture(String mode) {
        if (captureHidden) return;
        hidePalette();
        hidePresetPanel();
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

    private void refreshNotification() {
        try { ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(1001, buildNotification()); } catch (Exception ignored) {}
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        store.setCanvasSize(sw, sh);
        clampToolbarToScreen();
        snapToolbarToEdge();
        toolbar.post(this::clampToolbarToScreen);
        if (displayView != null) displayView.invalidate();
        savePreferences();
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
                .setContentTitle("Screen Sketch S Pen v1.5.1")
                .setContentText((drawingEnabled ? "PEN ON" : "PEN OFF") + " · 자동저장/복구 · WORK · LASSO")
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
        hidePresetPanel();
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
