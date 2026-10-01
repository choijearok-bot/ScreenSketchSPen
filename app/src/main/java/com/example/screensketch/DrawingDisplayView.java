package com.example.screensketch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

public class DrawingDisplayView extends View {
    private final StrokeStore store;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean annotationsVisible = true;

    public DrawingDisplayView(Context context, StrokeStore store) {
        super(context);
        this.store = store;
        setBackgroundColor(Color.TRANSPARENT);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setAnnotationsVisible(boolean visible) {
        annotationsVisible = visible;
        invalidate();
    }

    public boolean isAnnotationsVisible() { return annotationsVisible; }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!annotationsVisible) return;
        for (StrokeStore.Stroke s : store.snapshot()) {
            paint.setColor(s.color);
            paint.setAlpha(s.alpha);
            paint.setStrokeWidth(s.width);
            canvas.drawPath(s.path, paint);
        }
        paint.setAlpha(255);
    }
}
