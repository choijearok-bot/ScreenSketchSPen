package com.example.screensketch;

import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class StrokeStore {
    public static final class Stroke {
        public final Path path = new Path();
        public float width;
        public int color;
        public int alpha;
        public String type;
        public final List<PointF> points = new ArrayList<>();
        public final RectF bounds = new RectF();
        public float startX, startY, endX, endY;

        public Stroke(float width, int color, int alpha, String type) {
            this.width = width;
            this.color = color;
            this.alpha = alpha;
            this.type = type;
        }

        public void addPoint(float x, float y, boolean first) {
            if (first) path.moveTo(x, y);
            else path.lineTo(x, y);
            points.add(new PointF(x, y));
            path.computeBounds(bounds, true);
        }

        public void rebuildShape(float sx, float sy, float ex, float ey) {
            startX = sx; startY = sy; endX = ex; endY = ey;
            points.clear();
            points.add(new PointF(sx, sy));
            points.add(new PointF(ex, ey));
            path.reset();
            switch (type) {
                case "LINE":
                    path.moveTo(sx, sy); path.lineTo(ex, ey); break;
                case "ARROW":
                    path.moveTo(sx, sy); path.lineTo(ex, ey);
                    double a = Math.atan2(ey - sy, ex - sx);
                    float len = Math.max(18f, width * 4f);
                    float ax1 = ex - (float)(len * Math.cos(a - 0.55));
                    float ay1 = ey - (float)(len * Math.sin(a - 0.55));
                    float ax2 = ex - (float)(len * Math.cos(a + 0.55));
                    float ay2 = ey - (float)(len * Math.sin(a + 0.55));
                    path.moveTo(ex, ey); path.lineTo(ax1, ay1);
                    path.moveTo(ex, ey); path.lineTo(ax2, ay2);
                    break;
                case "RECT":
                    path.addRect(Math.min(sx, ex), Math.min(sy, ey), Math.max(sx, ex), Math.max(sy, ey), Path.Direction.CW);
                    break;
                case "ELLIPSE":
                    path.addOval(Math.min(sx, ex), Math.min(sy, ey), Math.max(sx, ex), Math.max(sy, ey), Path.Direction.CW);
                    break;
                default:
                    path.moveTo(sx, sy); path.lineTo(ex, ey);
            }
            path.computeBounds(bounds, true);
        }
    }

    private final List<Stroke> strokes = new ArrayList<>();
    private final List<Stroke> redo = new ArrayList<>();

    public synchronized Stroke addStroke(float width, int color, int alpha, String type) {
        Stroke s = new Stroke(width, color, alpha, type);
        strokes.add(s);
        redo.clear();
        return s;
    }

    public synchronized List<Stroke> snapshot() { return new ArrayList<>(strokes); }

    public synchronized boolean eraseAt(float x, float y, float radius) {
        boolean changed = false;
        float r2 = radius * radius;
        Iterator<Stroke> it = strokes.iterator();
        while (it.hasNext()) {
            Stroke s = it.next();
            RectF expanded = new RectF(s.bounds);
            expanded.inset(-radius, -radius);
            if (!expanded.contains(x, y)) continue;
            boolean hit = false;
            PointF prev = null;
            for (PointF p : s.points) {
                if (dist2(x, y, p.x, p.y) <= r2) { hit = true; break; }
                if (prev != null && segDist2(x, y, prev.x, prev.y, p.x, p.y) <= r2) { hit = true; break; }
                prev = p;
            }
            if (!hit && !s.path.isEmpty()) hit = true;
            if (hit) { it.remove(); changed = true; }
        }
        if (changed) redo.clear();
        return changed;
    }

    public synchronized void undo() {
        if (!strokes.isEmpty()) redo.add(strokes.remove(strokes.size() - 1));
    }

    public synchronized void redo() {
        if (!redo.isEmpty()) strokes.add(redo.remove(redo.size() - 1));
    }

    public synchronized void clear() {
        if (!strokes.isEmpty()) {
            redo.clear();
            redo.addAll(strokes);
            strokes.clear();
        }
    }

    private static float dist2(float x1,float y1,float x2,float y2){float dx=x1-x2,dy=y1-y2;return dx*dx+dy*dy;}
    private static float segDist2(float px,float py,float x1,float y1,float x2,float y2){
        float dx=x2-x1,dy=y2-y1;if(dx==0&&dy==0)return dist2(px,py,x1,y1);
        float t=((px-x1)*dx+(py-y1)*dy)/(dx*dx+dy*dy);t=Math.max(0f,Math.min(1f,t));
        float cx=x1+t*dx,cy=y1+t*dy;return dist2(px,py,cx,cy);
    }
}
