package com.example.screensketch;

import android.content.Context;
import android.graphics.Color;
import android.graphics.RectF;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

public class DrawingInputView extends View {
    public interface EraserStateListener { void onEraserStateChanged(boolean active); }
    public enum Tool { PEN, HIGHLIGHTER, ERASER, LINE, ARROW, RECT, ELLIPSE, LASSO }

    private enum SelectAction { NONE, BOX, MOVE, SCALE }

    private final StrokeStore store;
    private final DrawingDisplayView displayView;
    private StrokeStore.Stroke current;
    private int currentColor = Color.RED;
    private float currentWidth;
    private int currentAlpha = 255;
    private Tool tool = Tool.PEN;
    private float eraserRadius;
    private boolean eraserActive = false;
    private EraserStateListener eraserStateListener;
    private float startX,startY,lastX,lastY;
    private float smoothing = .45f;
    private SelectAction selectAction = SelectAction.NONE;
    private RectF selectBox;
    private float lastScaleDistance;

    public DrawingInputView(Context context, StrokeStore store, DrawingDisplayView displayView) {
        super(context);
        this.store=store; this.displayView=displayView;
        float d=getResources().getDisplayMetrics().density;
        currentWidth=5.5f*d; eraserRadius=18f*d;
        setBackgroundColor(Color.TRANSPARENT);
        // Explicitly clickable so this full-screen overlay consistently becomes
        // the touch target when it is attached in PEN ON mode.
        setClickable(true);
        setFocusable(false);
    }

    public void setPenColor(int color){currentColor=color;}
    public void setWidthDp(float dp){currentWidth=dp*getResources().getDisplayMetrics().density;}
    public void setAlphaPercent(int p){currentAlpha=Math.max(26,Math.min(255,Math.round(255f*p/100f)));}
    public void setSmoothingPercent(int p){smoothing=Math.max(0f,Math.min(.9f,p/100f));}
    public void setTool(Tool t){tool=t;if(t!=Tool.LASSO){store.clearSelection();store.setSelectionPreview(null);displayView.invalidate();}}
    public Tool getTool(){return tool;}
    public void setEraserStateListener(EraserStateListener l){eraserStateListener=l;}

    private void setEraserActive(boolean active){if(eraserActive==active)return;eraserActive=active;if(eraserStateListener!=null)eraserStateListener.onEraserStateChanged(active);}
    private boolean buttonPressed(MotionEvent e){int b=e.getButtonState();return (b&MotionEvent.BUTTON_STYLUS_PRIMARY)!=0||(b&MotionEvent.BUTTON_STYLUS_SECONDARY)!=0;}
    private boolean isStylus(MotionEvent e,int i){
        int t=e.getToolType(i);
        if(t==MotionEvent.TOOL_TYPE_STYLUS||t==MotionEvent.TOOL_TYPE_ERASER)return true;
        // Samsung/One UI can occasionally preserve SOURCE_STYLUS while reporting
        // a less specific tool type through an overlay window. Accept the source
        // as a fallback so genuine S Pen input is not discarded.
        return (e.getSource() & InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS;
    }
    private boolean isErase(MotionEvent e,int i){return tool==Tool.ERASER||e.getToolType(i)==MotionEvent.TOOL_TYPE_ERASER||buttonPressed(e);}
    private boolean isShape(){return tool==Tool.LINE||tool==Tool.ARROW||tool==Tool.RECT||tool==Tool.ELLIPSE;}
    private String shapeType(){switch(tool){case ARROW:return"ARROW";case RECT:return"RECT";case ELLIPSE:return"ELLIPSE";default:return"LINE";}}

    @Override public boolean onTouchEvent(MotionEvent e){
        int i=Math.max(0,Math.min(e.getActionIndex(),e.getPointerCount()-1));
        if(!isStylus(e,i))return true;
        float x=e.getX(i),y=e.getY(i);

        if(tool==Tool.LASSO && !buttonPressed(e)) return handleLasso(e,x,y);

        boolean erasing=isErase(e,i);setEraserActive(erasing);
        if(erasing){
            current=null;
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN||e.getActionMasked()==MotionEvent.ACTION_MOVE){if(store.eraseAt(x,y,eraserRadius))displayView.invalidate();}
            if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){setEraserActive(false);store.save();}
            return true;
        }

        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                startX=x;startY=y;
                if(isShape()){
                    current=store.addStroke(currentWidth,currentColor,currentAlpha,shapeType());current.rebuildShape(startX,startY,x,y);
                }else{
                    int alpha=(tool==Tool.HIGHLIGHTER)?Math.min(currentAlpha,90):currentAlpha;
                    float w=(tool==Tool.HIGHLIGHTER)?Math.max(currentWidth,currentWidth*2.8f):currentWidth;
                    current=store.addStroke(w,currentColor,alpha,"FREE");current.addPoint(x,y,true);
                }
                displayView.invalidate();return true;
            case MotionEvent.ACTION_MOVE:
                if(current!=null){
                    if(isShape())current.rebuildShape(startX,startY,x,y);
                    else{
                        float lx=current.points.get(current.points.size()-1).x,ly=current.points.get(current.points.size()-1).y;
                        float f=.15f+(1f-smoothing)*.7f;current.addPoint(lx+(x-lx)*f,ly+(y-ly)*f,false);
                    }
                    displayView.invalidate();
                }return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if(current!=null&&isShape())current.rebuildShape(startX,startY,x,y);
                current=null;setEraserActive(false);store.save();displayView.invalidate();return true;
            default:return true;
        }
    }

    private boolean handleLasso(MotionEvent e,float x,float y){
        float d=getResources().getDisplayMetrics().density;
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                startX=lastX=x;startY=lastY=y;
                if(store.hasSelection() && store.isNearScaleHandle(x,y,26f*d)){
                    selectAction=SelectAction.SCALE;
                    RectF b=store.getSelectionBounds();
                    lastScaleDistance=(float)Math.hypot(x-b.centerX(),y-b.centerY());
                }else if(store.hasSelection() && store.isPointInSelection(x,y,8f*d)){
                    selectAction=SelectAction.MOVE;
                }else{
                    selectAction=SelectAction.BOX;
                    store.clearSelection();
                    selectBox=new RectF(x,y,x,y);store.setSelectionPreview(selectBox);
                }
                displayView.invalidate();return true;
            case MotionEvent.ACTION_MOVE:
                if(selectAction==SelectAction.MOVE){store.moveSelection(x-lastX,y-lastY);lastX=x;lastY=y;}
                else if(selectAction==SelectAction.SCALE){
                    RectF b=store.getSelectionBounds();float dist=(float)Math.hypot(x-b.centerX(),y-b.centerY());
                    if(lastScaleDistance>4f*d&&dist>4f*d){float factor=dist/lastScaleDistance;factor=Math.max(.82f,Math.min(1.22f,factor));store.scaleSelection(factor);lastScaleDistance=dist;}
                }else if(selectAction==SelectAction.BOX){selectBox.set(startX,startY,x,y);store.setSelectionPreview(selectBox);}
                displayView.invalidate();return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if(selectAction==SelectAction.BOX){store.setSelectionPreview(null);store.selectIn(selectBox);}
                else if(selectAction==SelectAction.MOVE||selectAction==SelectAction.SCALE){store.commitSelectionEdit();}
                selectAction=SelectAction.NONE;selectBox=null;displayView.invalidate();return true;
            default:return true;
        }
    }

    @Override public boolean onGenericMotionEvent(MotionEvent e){
        if(e.getPointerCount()>0&&isStylus(e,0)){
            if(e.getActionMasked()==MotionEvent.ACTION_BUTTON_PRESS)setEraserActive(true);
            else if(e.getActionMasked()==MotionEvent.ACTION_BUTTON_RELEASE)setEraserActive(false);
        }
        return super.onGenericMotionEvent(e);
    }
}
