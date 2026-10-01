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
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

public class OverlayDrawingService extends Service {
    public static final String ACTION_CLEAR="com.example.screensketch.CLEAR";
    public static final String ACTION_STOP="com.example.screensketch.STOP";

    private WindowManager wm;
    private DrawingDisplayView displayView;
    private DrawingInputView inputView;
    private LinearLayout toolbar;
    private LinearLayout paletteView;
    private WindowManager.LayoutParams displayParams,inputParams,toolbarParams,paletteParams;
    private final StrokeStore store=new StrokeStore();
    private boolean drawingEnabled=true, collapsed=false, paletteVisible=false, eraserActive=false;
    private int selectedColor=Color.rgb(244,67,54), widthDp=5, alphaPct=100, smoothingPct=45;
    private DrawingInputView.Tool tool=DrawingInputView.Tool.PEN;
    private TextView collapseBtn, penBtn, highBtn, eraseBtn, shapeBtn, presetBtn, colorBtn, sizeBtn, undoBtn, redoBtn, eyeBtn, clearBtn, toggleBtn;
    private int shapeIndex=0;
    private final DrawingInputView.Tool[] shapes={DrawingInputView.Tool.LINE,DrawingInputView.Tool.ARROW,DrawingInputView.Tool.RECT,DrawingInputView.Tool.ELLIPSE};
    private final String[] shapeNames={"LINE","ARROW","RECT","OVAL"};
    private final int[] colors={Color.BLACK,Color.WHITE,Color.rgb(244,67,54),Color.rgb(255,152,0),Color.rgb(255,235,59),Color.rgb(76,175,80),Color.rgb(0,200,170),Color.rgb(3,169,244),Color.rgb(33,150,243),Color.rgb(103,58,183),Color.rgb(233,30,99),Color.rgb(117,117,117)};

    @Override public void onCreate(){
        super.onCreate();createChannel();Notification n=buildNotification();
        if(Build.VERSION.SDK_INT>=34)startForeground(1001,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(1001,n);
        if(!Settings.canDrawOverlays(this)){stopSelf();return;}
        wm=(WindowManager)getSystemService(WINDOW_SERVICE);addDisplay();addInput();addToolbar();
    }

    @Override public int onStartCommand(Intent i,int flags,int id){
        if(i!=null&&ACTION_CLEAR.equals(i.getAction())){store.clear();if(displayView!=null)displayView.invalidate();}
        else if(i!=null&&ACTION_STOP.equals(i.getAction()))stopSelf();
        return START_STICKY;
    }

    private void addDisplay(){
        displayView=new DrawingDisplayView(this,store);
        displayParams=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        displayParams.alpha=.79f;displayParams.gravity=Gravity.TOP|Gravity.START;wm.addView(displayView,displayParams);
    }
    private void addInput(){
        if(inputView!=null)return;inputView=new DrawingInputView(this,store,displayView);applyToolSettings();
        inputView.setEraserStateListener(a->{eraserActive=a;updateToolbarState();});
        inputParams=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        inputParams.gravity=Gravity.TOP|Gravity.START;wm.addView(inputView,inputParams);drawingEnabled=true;updateToolbarState();
    }
    private void removeInput(){if(inputView!=null){try{wm.removeView(inputView);}catch(Exception ignored){}inputView=null;}drawingEnabled=false;eraserActive=false;hidePalette();updateToolbarState();}

    private TextView b(String text){
        TextView v=new TextView(this);v.setText(text);v.setTextColor(Color.WHITE);v.setTextSize(10);v.setGravity(Gravity.CENTER);v.setPadding(dp(2),dp(2),dp(2),dp(2));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(48),dp(44));lp.setMargins(0,dp(2),0,dp(2));v.setLayoutParams(lp);setBox(v,Color.rgb(58,68,82),false);return v;
    }
    private void setBox(TextView v,int fill,boolean active){GradientDrawable g=new GradientDrawable();g.setCornerRadius(dp(12));g.setColor(fill);if(active)g.setStroke(dp(2),Color.WHITE);v.setBackground(g);}

    private void addToolbar(){
        toolbar=new LinearLayout(this);toolbar.setOrientation(LinearLayout.VERTICAL);toolbar.setGravity(Gravity.CENTER_HORIZONTAL);toolbar.setPadding(dp(6),dp(7),dp(6),dp(7));toolbar.setElevation(dp(12));
        GradientDrawable bg=new GradientDrawable();bg.setColor(Color.argb(244,25,31,41));bg.setCornerRadius(dp(18));bg.setStroke(dp(1),Color.argb(80,255,255,255));toolbar.setBackground(bg);
        collapseBtn=b("◀\nHIDE");penBtn=b("PEN");highBtn=b("MARK");eraseBtn=b("ERASE");shapeBtn=b("LINE");presetBtn=b("★ PRESET");colorBtn=b("COLOR");sizeBtn=b("5px\n100%");undoBtn=b("UNDO");redoBtn=b("REDO");eyeBtn=b("SHOW");clearBtn=b("CLEAR");toggleBtn=b("DRAW\nON");
        TextView[] arr={collapseBtn,penBtn,highBtn,eraseBtn,shapeBtn,presetBtn,colorBtn,sizeBtn,undoBtn,redoBtn,eyeBtn,clearBtn,toggleBtn};for(TextView v:arr)toolbar.addView(v);
        collapseBtn.setOnClickListener(v->{collapsed=!collapsed;applyCollapsed();});
        penBtn.setOnClickListener(v->{tool=DrawingInputView.Tool.PEN;applyToolSettings();updateToolbarState();});
        highBtn.setOnClickListener(v->{tool=DrawingInputView.Tool.HIGHLIGHTER;applyToolSettings();updateToolbarState();});
        eraseBtn.setOnClickListener(v->{tool=DrawingInputView.Tool.ERASER;applyToolSettings();updateToolbarState();});
        shapeBtn.setOnClickListener(v->{shapeIndex=(shapeIndex+1)%shapes.length;tool=shapes[shapeIndex];shapeBtn.setText(shapeNames[shapeIndex]);applyToolSettings();updateToolbarState();});
        presetBtn.setOnClickListener(new View.OnClickListener(){int p=-1;@Override public void onClick(View v){p=(p+1)%3;if(p==0){tool=DrawingInputView.Tool.PEN;selectedColor=Color.rgb(244,67,54);widthDp=4;alphaPct=100;}else if(p==1){tool=DrawingInputView.Tool.HIGHLIGHTER;selectedColor=Color.rgb(255,235,59);widthDp=18;alphaPct=30;}else{tool=DrawingInputView.Tool.PEN;selectedColor=Color.rgb(33,150,243);widthDp=7;alphaPct=100;}applyToolSettings();updateToolbarState();}});
        colorBtn.setOnClickListener(v->togglePalette());
        sizeBtn.setOnClickListener(new View.OnClickListener(){int w=0,a=0;int[] ws={3,5,8,12};int[] as={100,70,35};@Override public void onClick(View v){w=(w+1)%ws.length;if(w==0)a=(a+1)%as.length;widthDp=ws[w];alphaPct=as[a];applyToolSettings();updateToolbarState();}});
        undoBtn.setOnClickListener(v->{store.undo();displayView.invalidate();});redoBtn.setOnClickListener(v->{store.redo();displayView.invalidate();});
        eyeBtn.setOnClickListener(v->{displayView.setAnnotationsVisible(!displayView.isAnnotationsVisible());updateToolbarState();});
        clearBtn.setOnClickListener(v->{store.clear();displayView.invalidate();});
        toggleBtn.setOnClickListener(v->{if(drawingEnabled)removeInput();else addInput();});

        toolbarParams=new WindowManager.LayoutParams(dp(62),WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);
        toolbarParams.gravity=Gravity.TOP|Gravity.START;toolbarParams.x=getResources().getDisplayMetrics().widthPixels-dp(76);toolbarParams.y=Math.max(dp(20),getResources().getDisplayMetrics().heightPixels/2-dp(250));
        toolbar.setOnTouchListener(new View.OnTouchListener(){float dx,dy;int sx,sy;boolean moved;@Override public boolean onTouch(View v, MotionEvent e){switch(e.getActionMasked()){case MotionEvent.ACTION_DOWN:dx=e.getRawX();dy=e.getRawY();sx=toolbarParams.x;sy=toolbarParams.y;moved=false;return false;case MotionEvent.ACTION_MOVE:float mx=e.getRawX()-dx,my=e.getRawY()-dy;if(Math.hypot(mx,my)>dp(10)){moved=true;toolbarParams.x=sx+(int)mx;toolbarParams.y=sy+(int)my;try{wm.updateViewLayout(toolbar,toolbarParams);}catch(Exception ignored){}if(paletteVisible)positionPalette();return true;}default:return moved;}}});
        wm.addView(toolbar,toolbarParams);updateToolbarState();
    }

    private void applyCollapsed(){
        int count=toolbar.getChildCount();for(int i=1;i<count-1;i++)toolbar.getChildAt(i).setVisibility(collapsed?View.GONE:View.VISIBLE);
        collapseBtn.setText(collapsed?"▶\nTOOLS":"◀\nHIDE");hidePalette();
    }
    private void applyToolSettings(){if(inputView==null)return;inputView.setPenColor(selectedColor);inputView.setWidthDp(widthDp);inputView.setAlphaPercent(alphaPct);inputView.setSmoothingPercent(smoothingPct);inputView.setTool(tool);}
    private void updateToolbarState(){
        if(toolbar==null)return;TextView[] tools={penBtn,highBtn,eraseBtn,shapeBtn};for(TextView v:tools)if(v!=null)setBox(v,Color.rgb(58,68,82),false);
        if(tool==DrawingInputView.Tool.PEN)setBox(penBtn,Color.rgb(90,104,122),true);else if(tool==DrawingInputView.Tool.HIGHLIGHTER)setBox(highBtn,Color.rgb(90,104,122),true);else if(tool==DrawingInputView.Tool.ERASER||eraserActive)setBox(eraseBtn,Color.rgb(90,104,122),true);else if(tool==DrawingInputView.Tool.LINE||tool==DrawingInputView.Tool.ARROW||tool==DrawingInputView.Tool.RECT||tool==DrawingInputView.Tool.ELLIPSE)setBox(shapeBtn,Color.rgb(90,104,122),true);
        setBox(toggleBtn,drawingEnabled?Color.rgb(38,124,86):Color.rgb(133,83,39),false);toggleBtn.setText(drawingEnabled?"DRAW\nON":"TOUCH\nON");
        setBox(colorBtn,selectedColor,false);colorBtn.setText(contrast(selectedColor)==Color.BLACK?"COLOR\n●":"COLOR\n●");colorBtn.setTextColor(contrast(selectedColor));
        sizeBtn.setText(widthDp+"px\n"+alphaPct+"%");eyeBtn.setText(displayView!=null&&displayView.isAnnotationsVisible()?"SHOW":"HIDE");
    }

    private void togglePalette(){if(paletteVisible)hidePalette();else showPalette();}
    private void showPalette(){
        paletteView=new LinearLayout(this);paletteView.setOrientation(LinearLayout.VERTICAL);paletteView.setPadding(dp(8),dp(8),dp(8),dp(8));GradientDrawable bg=new GradientDrawable();bg.setColor(Color.argb(245,35,38,45));bg.setCornerRadius(dp(16));paletteView.setBackground(bg);
        for(int r=0;r<4;r++){LinearLayout row=new LinearLayout(this);for(int col=0;col<3;col++){final int color=colors[r*3+col];TextView dot=new TextView(this);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(36),dp(36));lp.setMargins(dp(3),dp(3),dp(3),dp(3));dot.setLayoutParams(lp);GradientDrawable g=new GradientDrawable();g.setShape(GradientDrawable.OVAL);g.setColor(color);g.setStroke(dp(2),Color.WHITE);dot.setBackground(g);dot.setOnClickListener(v->{selectedColor=color;applyToolSettings();hidePalette();updateToolbarState();});row.addView(dot);}paletteView.addView(row);}
        paletteParams=new WindowManager.LayoutParams(dp(138),WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);paletteParams.gravity=Gravity.TOP|Gravity.START;wm.addView(paletteView,paletteParams);paletteVisible=true;positionPalette();
    }
    private void positionPalette(){if(!paletteVisible)return;int sw=getResources().getDisplayMetrics().widthPixels;int pw=dp(138),gap=dp(8);int left=toolbarParams.x-pw-gap,right=toolbarParams.x+dp(62)+gap;paletteParams.x=left>=0?left:Math.min(sw-pw,right);paletteParams.y=Math.max(dp(8),toolbarParams.y+dp(80));try{wm.updateViewLayout(paletteView,paletteParams);}catch(Exception ignored){}}
    private void hidePalette(){if(paletteView!=null)try{wm.removeView(paletteView);}catch(Exception ignored){}paletteView=null;paletteVisible=false;}
    private int contrast(int color){int r=Color.red(color),g=Color.green(color),b=Color.blue(color);return .299*r+.587*g+.114*b>170?Color.BLACK:Color.WHITE;}

    private Notification buildNotification(){Intent open=new Intent(this,MainActivity.class);PendingIntent op=PendingIntent.getActivity(this,1,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);Intent cl=new Intent(this,OverlayDrawingService.class).setAction(ACTION_CLEAR);PendingIntent cp=PendingIntent.getService(this,2,cl,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);Intent st=new Intent(this,OverlayDrawingService.class).setAction(ACTION_STOP);PendingIntent sp=PendingIntent.getService(this,3,st,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);return new Notification.Builder(this,"screen_sketch").setContentTitle("Screen Sketch S Pen v1.2").setContentText("펜/형광펜/도형/Undo/Redo · S펜 버튼=지우개").setSmallIcon(R.drawable.ic_pen).setContentIntent(op).setOngoing(true).addAction(R.drawable.ic_pen,"전체 삭제",cp).addAction(R.drawable.ic_pen,"종료",sp).build();}
    private void createChannel(){if(Build.VERSION.SDK_INT>=26){NotificationChannel c=new NotificationChannel("screen_sketch","Screen Sketch",NotificationManager.IMPORTANCE_LOW);c.setDescription("화면 위 S펜 드로잉 서비스");getSystemService(NotificationManager.class).createNotificationChannel(c);}}
    @Override public void onDestroy(){if(wm!=null){if(inputView!=null)try{wm.removeView(inputView);}catch(Exception ignored){}if(displayView!=null)try{wm.removeView(displayView);}catch(Exception ignored){}if(toolbar!=null)try{wm.removeView(toolbar);}catch(Exception ignored){}if(paletteView!=null)try{wm.removeView(paletteView);}catch(Exception ignored){}}super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
}
