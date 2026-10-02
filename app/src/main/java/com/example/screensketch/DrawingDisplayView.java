package com.example.screensketch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

public class DrawingDisplayView extends View {
    private final StrokeStore store;
    private boolean annotationsVisible = true;
    private final Paint selectPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public DrawingDisplayView(Context context, StrokeStore store) {
        super(context);
        this.store = store;
        setBackgroundColor(Color.TRANSPARENT);
        selectPaint.setStyle(Paint.Style.STROKE);
        selectPaint.setStrokeWidth(dp(2));
        selectPaint.setColor(Color.rgb(30,144,255));
        selectPaint.setPathEffect(new DashPathEffect(new float[]{dp(8),dp(5)},0));
        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setColor(Color.WHITE);
    }

    public void setAnnotationsVisible(boolean visible) { annotationsVisible = visible; invalidate(); }
    public boolean isAnnotationsVisible() { return annotationsVisible; }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!annotationsVisible) return;
        store.drawTo(canvas,1f,1f);

        RectF preview = store.getSelectionPreview();
        if (preview != null && !preview.isEmpty()) canvas.drawRect(preview, selectPaint);

        RectF selected = store.getSelectionBounds();
        if (store.hasSelection() && !selected.isEmpty()) {
            canvas.drawRect(selected, selectPaint);
            canvas.drawCircle(selected.right, selected.bottom, dp(7), handlePaint);
            canvas.drawCircle(selected.right, selected.bottom, dp(7), selectPaint);
        }
    }

    private float dp(int v){ return v * getResources().getDisplayMetrics().density; }
}
