package com.example.screensketch;

import android.content.Context;
import android.graphics.Color;
import android.view.MotionEvent;
import android.view.View;

public class DrawingInputView extends View {
    public interface EraserStateListener { void onEraserStateChanged(boolean active); }
    public enum Tool { PEN, HIGHLIGHTER, ERASER, LINE, ARROW, RECT, ELLIPSE }

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
    private float startX, startY;
    private float smoothing = 0.45f;

    public DrawingInputView(Context context, StrokeStore store, DrawingDisplayView displayView) {
        super(context);
        this.store = store; this.displayView = displayView;
        float d = getResources().getDisplayMetrics().density;
        currentWidth = 5.5f * d; eraserRadius = 18f * d;
        setBackgroundColor(Color.TRANSPARENT);
    }

    public void setPenColor(int color) { currentColor = color; }
    public void setWidthDp(float dp) { currentWidth = dp * getResources().getDisplayMetrics().density; }
    public void setAlphaPercent(int p) { currentAlpha = Math.max(26, Math.min(255, Math.round(255f * p / 100f))); }
    public void setSmoothingPercent(int p) { smoothing = Math.max(0f, Math.min(.9f, p / 100f)); }
    public void setTool(Tool t) { tool = t; }
    public Tool getTool() { return tool; }
    public void setEraserStateListener(EraserStateListener l) { eraserStateListener = l; }

    private void setEraserActive(boolean active) {
        if (eraserActive == active) return;
        eraserActive = active;
        if (eraserStateListener != null) eraserStateListener.onEraserStateChanged(active);
    }
    private boolean buttonPressed(MotionEvent e){int b=e.getButtonState();return (b&MotionEvent.BUTTON_STYLUS_PRIMARY)!=0||(b&MotionEvent.BUTTON_STYLUS_SECONDARY)!=0;}
    private boolean isStylus(MotionEvent e,int i){int t=e.getToolType(i);return t==MotionEvent.TOOL_TYPE_STYLUS||t==MotionEvent.TOOL_TYPE_ERASER;}
    private boolean isErase(MotionEvent e,int i){return tool==Tool.ERASER||e.getToolType(i)==MotionEvent.TOOL_TYPE_ERASER||buttonPressed(e);}
    private boolean isShape(){return tool==Tool.LINE||tool==Tool.ARROW||tool==Tool.RECT||tool==Tool.ELLIPSE;}
    private String shapeType(){switch(tool){case ARROW:return "ARROW";case RECT:return "RECT";case ELLIPSE:return "ELLIPSE";default:return "LINE";}}

    @Override public boolean onTouchEvent(MotionEvent e) {
        int i=Math.max(0,Math.min(e.getActionIndex(),e.getPointerCount()-1));
        if(!isStylus(e,i)) return true;
        boolean erasing=isErase(e,i);setEraserActive(erasing);
        float x=e.getX(i),y=e.getY(i);
        if(erasing){current=null;if(e.getActionMasked()==MotionEvent.ACTION_DOWN||e.getActionMasked()==MotionEvent.ACTION_MOVE){if(store.eraseAt(x,y,eraserRadius))displayView.invalidate();}if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL)setEraserActive(false);return true;}
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                startX=x;startY=y;
                if(isShape()){
                    current=store.addStroke(currentWidth,currentColor,currentAlpha,shapeType());
                    current.rebuildShape(startX,startY,x,y);
                }else{
                    int alpha=(tool==Tool.HIGHLIGHTER)?Math.min(currentAlpha,90):currentAlpha;
                    float w=(tool==Tool.HIGHLIGHTER)?Math.max(currentWidth,currentWidth*2.8f):currentWidth;
                    current=store.addStroke(w,currentColor,alpha,"FREE");
                    current.addPoint(x,y,true);
                }
                displayView.invalidate();return true;
            case MotionEvent.ACTION_MOVE:
                if(current!=null){
                    if(isShape()) current.rebuildShape(startX,startY,x,y);
                    else {
                        float lx=current.points.get(current.points.size()-1).x;
                        float ly=current.points.get(current.points.size()-1).y;
                        float f=.15f+(1f-smoothing)*.7f;
                        current.addPoint(lx+(x-lx)*f,ly+(y-ly)*f,false);
                    }
                    displayView.invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if(current!=null&&isShape())current.rebuildShape(startX,startY,x,y);
                current=null;setEraserActive(false);displayView.invalidate();return true;
            default:return true;
        }
    }

    @Override public boolean onGenericMotionEvent(MotionEvent e){
        if(e.getPointerCount()>0&&e.getToolType(0)==MotionEvent.TOOL_TYPE_STYLUS){
            if(e.getActionMasked()==MotionEvent.ACTION_BUTTON_PRESS)setEraserActive(true);
            else if(e.getActionMasked()==MotionEvent.ACTION_BUTTON_RELEASE)setEraserActive(false);
        }
        return super.onGenericMotionEvent(e);
    }
}
