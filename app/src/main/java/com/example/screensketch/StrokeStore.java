package com.example.screensketch;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

public final class StrokeStore {
    private static final StrokeStore INSTANCE = new StrokeStore();
    private static final String SAVE_FILE = "drawing_session_v15.json";
    private static final String HISTORY_DIR = "drawing_history_v15";
    private static final int MAX_HISTORY = 10;

    public static StrokeStore get() { return INSTANCE; }

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

        public void transform(Matrix matrix, float widthScale) {
            path.transform(matrix);
            float[] xy = new float[2];
            for (PointF p : points) {
                xy[0] = p.x; xy[1] = p.y;
                matrix.mapPoints(xy);
                p.set(xy[0], xy[1]);
            }
            xy[0] = startX; xy[1] = startY; matrix.mapPoints(xy); startX = xy[0]; startY = xy[1];
            xy[0] = endX; xy[1] = endY; matrix.mapPoints(xy); endX = xy[0]; endY = xy[1];
            width = Math.max(1f, width * widthScale);
            path.computeBounds(bounds, true);
        }
    }

    private final List<Stroke> strokes = new ArrayList<>();
    private final List<Stroke> redo = new ArrayList<>();
    private final List<Stroke> selected = new ArrayList<>();
    private final RectF selectionBounds = new RectF();
    private RectF selectionPreview;

    private Context appContext;
    private File saveFile;
    private File historyDir;
    private boolean initialized;
    private int canvasWidth;
    private int canvasHeight;

    private StrokeStore() {}

    public synchronized void init(Context context) {
        if (initialized) return;
        appContext = context.getApplicationContext();
        saveFile = new File(appContext.getFilesDir(), SAVE_FILE);
        historyDir = new File(appContext.getFilesDir(), HISTORY_DIR);
        if (!historyDir.exists()) historyDir.mkdirs();
        if (saveFile.exists()) {
            loadLocked(saveFile);
        } else {
            File legacy = new File(appContext.getFilesDir(), "drawing_session_v14.json");
            if (legacy.exists()) {
                loadLocked(legacy);
                initialized = true;
                saveLocked(false);
                return;
            }
        }
        initialized = true;
    }

    public synchronized void setCanvasSize(int width, int height) {
        if (width <= 0 || height <= 0) return;
        if (canvasWidth > 0 && canvasHeight > 0 && (canvasWidth != width || canvasHeight != height)) {
            float sx = width / (float) canvasWidth;
            float sy = height / (float) canvasHeight;
            Matrix m = new Matrix();
            m.setScale(sx, sy);
            float ws = (float)Math.sqrt(Math.max(0.01f, sx * sy));
            for (Stroke s : strokes) s.transform(m, ws);
            for (Stroke s : redo) s.transform(m, ws);
            canvasWidth = width;
            canvasHeight = height;
            clearSelectionLocked();
            saveLocked(false);
        } else {
            canvasWidth = width;
            canvasHeight = height;
        }
    }

    public synchronized Stroke addStroke(float width, int color, int alpha, String type) {
        clearSelectionLocked();
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
            if (hit) { selected.remove(s); it.remove(); changed = true; }
        }
        if (changed) {
            redo.clear();
            recomputeSelectionBoundsLocked();
        }
        return changed;
    }

    public synchronized void undo() {
        clearSelectionLocked();
        if (!strokes.isEmpty()) {
            redo.add(strokes.remove(strokes.size() - 1));
            saveLocked(true);
        }
    }

    public synchronized void redo() {
        clearSelectionLocked();
        if (!redo.isEmpty()) {
            strokes.add(redo.remove(redo.size() - 1));
            saveLocked(true);
        }
    }

    public synchronized void clear() {
        clearSelectionLocked();
        if (!strokes.isEmpty()) {
            redo.clear();
            redo.addAll(strokes);
            strokes.clear();
            saveLocked(true);
        }
    }

    public synchronized void save() { saveLocked(true); }
    public synchronized boolean isEmpty() { return strokes.isEmpty(); }

    public synchronized boolean selectIn(RectF area) {
        selected.clear();
        if (area == null || area.width() < 4f || area.height() < 4f) {
            selectionBounds.setEmpty();
            return false;
        }
        RectF normalized = normalized(area);
        for (Stroke s : strokes) {
            if (RectF.intersects(normalized, s.bounds) || normalized.contains(s.bounds.centerX(), s.bounds.centerY())) {
                selected.add(s);
            }
        }
        recomputeSelectionBoundsLocked();
        return !selected.isEmpty();
    }

    public synchronized void clearSelection() { clearSelectionLocked(); }
    public synchronized boolean hasSelection() { return !selected.isEmpty(); }
    public synchronized RectF getSelectionBounds() { return new RectF(selectionBounds); }
    public synchronized RectF getSelectionPreview() { return selectionPreview == null ? null : new RectF(selectionPreview); }
    public synchronized void setSelectionPreview(RectF r) { selectionPreview = r == null ? null : normalized(r); }

    public synchronized boolean isPointInSelection(float x, float y, float padding) {
        if (selected.isEmpty()) return false;
        RectF r = new RectF(selectionBounds);
        r.inset(-padding, -padding);
        return r.contains(x, y);
    }

    public synchronized boolean isNearScaleHandle(float x, float y, float radius) {
        if (selected.isEmpty()) return false;
        return Math.hypot(x - selectionBounds.right, y - selectionBounds.bottom) <= radius;
    }

    public synchronized void moveSelection(float dx, float dy) {
        if (selected.isEmpty()) return;
        Matrix m = new Matrix();
        m.setTranslate(dx, dy);
        for (Stroke s : selected) s.transform(m, 1f);
        recomputeSelectionBoundsLocked();
    }

    public synchronized void scaleSelection(float factor) {
        if (selected.isEmpty()) return;
        factor = Math.max(0.35f, Math.min(2.5f, factor));
        float cx = selectionBounds.centerX();
        float cy = selectionBounds.centerY();
        Matrix m = new Matrix();
        m.setScale(factor, factor, cx, cy);
        for (Stroke s : selected) s.transform(m, factor);
        recomputeSelectionBoundsLocked();
    }

    public synchronized void commitSelectionEdit() { saveLocked(true); }

    public synchronized void drawTo(Canvas canvas, float scaleX, float scaleY) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        canvas.save();
        canvas.scale(scaleX, scaleY);
        for (Stroke s : strokes) {
            paint.setColor(s.color);
            paint.setAlpha(s.alpha);
            paint.setStrokeWidth(s.width);
            canvas.drawPath(s.path, paint);
        }
        canvas.restore();
    }

    public synchronized String exportWork(Context context) {
        if (!initialized) init(context);
        try {
            String name = "ScreenSketch_Work_" + timestamp() + ".ssk";
            byte[] bytes = serializeLocked().getBytes(StandardCharsets.UTF_8);
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver cr = context.getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ScreenSketch/");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IllegalStateException("저장 위치 생성 실패");
                try (OutputStream out = cr.openOutputStream(uri)) {
                    if (out == null) throw new IllegalStateException("파일 출력 실패");
                    out.write(bytes);
                }
                values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0); cr.update(uri, values, null, null);
            } else {
                File dir = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "ScreenSketch");
                if (!dir.exists()) dir.mkdirs();
                try (FileOutputStream out = new FileOutputStream(new File(dir, name))) { out.write(bytes); }
            }
            return name;
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized String importLatestWork(Context context) {
        if (!initialized) init(context);
        try {
            int targetW = canvasWidth, targetH = canvasHeight;
            String json = null;
            String displayName = null;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver cr = context.getContentResolver();
                String[] proj = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED};
                String selection = MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?";
                String[] args = {Environment.DIRECTORY_DOWNLOADS + "/ScreenSketch/", "ScreenSketch_Work_%"};
                try (Cursor c = cr.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj, selection, args,
                        MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
                    if (c != null && c.moveToFirst()) {
                        long id = c.getLong(0); displayName = c.getString(1);
                        Uri uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id);
                        try (InputStream in = cr.openInputStream(uri)) { json = readAll(in); }
                    }
                }
            } else {
                File dir = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "ScreenSketch");
                File[] files = dir.listFiles((d, n) -> n.startsWith("ScreenSketch_Work_") && n.endsWith(".ssk"));
                if (files != null && files.length > 0) {
                    List<File> list = new ArrayList<>(); Collections.addAll(list, files);
                    list.sort((a,b) -> Long.compare(b.lastModified(), a.lastModified()));
                    File f = list.get(0); displayName = f.getName();
                    try (InputStream in = new FileInputStream(f)) { json = readAll(in); }
                }
            }
            if (json == null) return null;
            archiveCurrentLocked();
            if (!loadJsonLocked(json)) return null;
            if (targetW > 0 && targetH > 0) setCanvasSize(targetW, targetH);
            saveLocked(false);
            return displayName;
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized boolean restorePreviousHistory() {
        if (historyDir == null || !historyDir.exists()) return false;
        int targetW = canvasWidth, targetH = canvasHeight;
        File[] arr = historyDir.listFiles((d,n) -> n.endsWith(".json"));
        if (arr == null || arr.length == 0) return false;
        List<File> list = new ArrayList<>(); Collections.addAll(list, arr);
        list.sort((a,b) -> Long.compare(b.lastModified(), a.lastModified()));
        File candidate = list.get(0);
        try {
            String json;
            try (InputStream in = new FileInputStream(candidate)) { json = readAll(in); }
            if (!loadJsonLocked(json)) return false;
            if (targetW > 0 && targetH > 0) setCanvasSize(targetW, targetH);
            // Consume the snapshot so repeated RECOVER walks backward through history.
            candidate.delete();
            saveLocked(false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void clearSelectionLocked() {
        selected.clear();
        selectionBounds.setEmpty();
        selectionPreview = null;
    }

    private void recomputeSelectionBoundsLocked() {
        selectionBounds.setEmpty();
        for (Stroke s : selected) {
            if (selectionBounds.isEmpty()) selectionBounds.set(s.bounds);
            else selectionBounds.union(s.bounds);
        }
    }

    private static RectF normalized(RectF r) {
        return new RectF(Math.min(r.left,r.right), Math.min(r.top,r.bottom), Math.max(r.left,r.right), Math.max(r.top,r.bottom));
    }

    private void saveLocked(boolean createHistory) {
        if (!initialized || saveFile == null) return;
        try {
            if (createHistory) archiveCurrentLocked();
            writeAtomic(saveFile, serializeLocked());
        } catch (Exception ignored) {}
    }

    private void archiveCurrentLocked() {
        try {
            if (saveFile == null || !saveFile.exists() || historyDir == null) return;
            if (!historyDir.exists()) historyDir.mkdirs();
            File hist = new File(historyDir, "history_" + System.currentTimeMillis() + ".json");
            copyFile(saveFile, hist);
            pruneHistoryLocked();
        } catch (Exception ignored) {}
    }

    private void pruneHistoryLocked() {
        File[] arr = historyDir.listFiles((d,n) -> n.endsWith(".json"));
        if (arr == null || arr.length <= MAX_HISTORY) return;
        List<File> list = new ArrayList<>(); Collections.addAll(list, arr);
        list.sort(Comparator.comparingLong(File::lastModified));
        while (list.size() > MAX_HISTORY) {
            File f = list.remove(0); f.delete();
        }
    }

    private String serializeLocked() throws Exception {
        JSONObject root = new JSONObject();
        root.put("version", 2);
        root.put("canvasWidth", canvasWidth);
        root.put("canvasHeight", canvasHeight);
        JSONArray list = new JSONArray();
        for (Stroke s : strokes) {
            JSONObject o = new JSONObject();
            o.put("width", s.width); o.put("color", s.color); o.put("alpha", s.alpha); o.put("type", s.type);
            o.put("sx", s.startX); o.put("sy", s.startY); o.put("ex", s.endX); o.put("ey", s.endY);
            JSONArray pts = new JSONArray();
            for (PointF p : s.points) { JSONArray pt = new JSONArray(); pt.put(p.x); pt.put(p.y); pts.put(pt); }
            o.put("points", pts); list.put(o);
        }
        root.put("strokes", list);
        return root.toString();
    }

    private void loadLocked(File file) {
        strokes.clear(); redo.clear(); clearSelectionLocked();
        if (file == null || !file.exists()) return;
        try (InputStream in = new FileInputStream(file)) { loadJsonLocked(readAll(in)); }
        catch (Exception ignored) { strokes.clear(); redo.clear(); }
    }

    private boolean loadJsonLocked(String json) {
        try {
            JSONObject root = new JSONObject(json);
            List<Stroke> loaded = new ArrayList<>();
            JSONArray list = root.optJSONArray("strokes");
            if (list == null) return false;
            for (int i=0;i<list.length();i++) {
                JSONObject o = list.getJSONObject(i);
                Stroke s = new Stroke((float)o.optDouble("width",5f), o.optInt("color",0xffff0000),
                        o.optInt("alpha",255), o.optString("type","FREE"));
                if ("FREE".equals(s.type)) {
                    JSONArray pts = o.optJSONArray("points");
                    if (pts != null) for (int p=0;p<pts.length();p++) {
                        JSONArray pt = pts.getJSONArray(p);
                        s.addPoint((float)pt.getDouble(0),(float)pt.getDouble(1),p==0);
                    }
                    if (s.points.isEmpty()) continue;
                } else {
                    float sx=(float)o.optDouble("sx",0), sy=(float)o.optDouble("sy",0);
                    float ex=(float)o.optDouble("ex",sx), ey=(float)o.optDouble("ey",sy);
                    s.rebuildShape(sx,sy,ex,ey);
                }
                loaded.add(s);
            }
            strokes.clear(); strokes.addAll(loaded); redo.clear(); clearSelectionLocked();
            canvasWidth = root.optInt("canvasWidth", canvasWidth);
            canvasHeight = root.optInt("canvasHeight", canvasHeight);
            return true;
        } catch (Exception e) { return false; }
    }

    private static void writeAtomic(File file, String text) throws Exception {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp,false)) {
            out.write(text.getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
        }
        if (file.exists()) file.delete();
        if (!tmp.renameTo(file)) {
            try (FileOutputStream out = new FileOutputStream(file,false)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
            tmp.delete();
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        try (FileInputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[32768]; int n; while ((n=in.read(buf))>0) out.write(buf,0,n);
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return null;
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line; while ((line=r.readLine())!=null) sb.append(line);
        }
        return sb.toString();
    }

    private static String timestamp() { return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()); }
    private static float dist2(float x1,float y1,float x2,float y2){float dx=x1-x2,dy=y1-y2;return dx*dx+dy*dy;}
    private static float segDist2(float px,float py,float x1,float y1,float x2,float y2){
        float dx=x2-x1,dy=y2-y1;if(dx==0&&dy==0)return dist2(px,py,x1,y1);
        float t=((px-x1)*dx+(py-y1)*dy)/(dx*dx+dy*dy);t=Math.max(0f,Math.min(1f,t));
        float cx=x1+t*dx,cy=y1+t*dy;return dist2(px,py,cx,cy);
    }
}
