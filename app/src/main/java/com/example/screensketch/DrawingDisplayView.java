package com.example.screensketch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;

public class DrawingDisplayView extends View {
    private final StrokeStore store;
    private boolean annotationsVisible = true;

    public DrawingDisplayView(Context context, StrokeStore store) {
        super(context);
        this.store = store;
        setBackgroundColor(Color.TRANSPARENT);
    }

    public void setAnnotationsVisible(boolean visible) {
        annotationsVisible = visible;
        invalidate();
    }

    public boolean isAnnotationsVisible() { return annotationsVisible; }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!annotationsVisible) return;
        store.drawTo(canvas, 1f, 1f);
    }
}
