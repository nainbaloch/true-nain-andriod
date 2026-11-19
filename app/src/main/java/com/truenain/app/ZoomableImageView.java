package com.truenain.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

/**
 * Frame Mode view.  v2.4.6 PHASE 1: zoom/pan now runs on the shared ZoomController
 * (one engine for Media Player AND Frame mode — pack requirement).  This view keeps:
 *  - display modes (FIT / FILL / 1:1) via the ZC "content region" trick
 *  - rotation (90-steps + fine angle) composed into the draw matrix
 *  - approved gestures: single tap = controls, double-tap = ±1 frame, hold = ±5 frames
 *    (ZoomController's own double-tap zoom is disabled — these were owner-ordered features)
 *  - settled sharp crop: ZC.onSettled → MainActivity decodes the visible bitmap region
 *    and scales it BICUBIC (SharpScale), shown via setSharpCrop until the next move.
 */
final class ZoomableImageView extends View {
    static final int MODE_FIT = 0, MODE_FILL = 1, MODE_ONE_TO_ONE = 2;

    interface GestureActions { void singleTap(); void doubleTap(float x); void hold(boolean active, float x); void interaction(boolean active); }
    interface SettledListener { void onFrameSettled(); }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix drawMatrix = new Matrix();
    private final RectF tmpRect = new RectF();
    private Bitmap bitmap;
    private int displayMode = MODE_FIT;
    private float rotation = 0f;           // total: 90-steps + fine angle

    // v2.4.6: single zoom engine
    private final ZoomController zc;
    private SettledListener settledListener;
    private Bitmap sharpCrop;              // settled bicubic sharp view (view-sized), dropped on move
    private GestureDetector fallbackGestures;
    private float lastX, lastY;
    private GestureActions actions;

    // displayed region (content handed to ZC), in rotated-image pixel coords
    private int regW, regH, regOX, regOY;
    private float bboxW, bboxH;            // rotated bounding box of the bitmap

    ZoomableImageView(Context c) {
        super(c);
        setFocusable(true);
        zc = new ZoomController(c, this, new ZoomController.Listener() {
            @Override public void onTransform(Matrix m, float zoom, boolean interacting) {
                drawMatrix.set(m);
                sharpCrop = null;          // fast path while moving — sharp overlay is stale
                invalidate();
            }
            @Override public void onSettled(RectF visible, float zoom, int generation) {
                if (!zc.isCurrent(generation) || !zc.isZoomed()) return;   // only when zoomed
                if (settledListener != null) settledListener.onFrameSettled();
            }
            @Override public void onSingleTap() {
                if (actions != null) actions.singleTap();
            }
        });
        zc.setDoubleTapZoomEnabled(false);     // double-tap = ±1 frame (approved feature)
        zc.setFallback(this::onTouchFallback);
        setOnTouchListener(zc);
        setOnGenericMotionListener(zc);

        // Fallback gestures: only what ZoomController does NOT own (frame-step + hold).
        fallbackGestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (actions != null) actions.doubleTap(e.getX());
                return true;
            }
            @Override public void onLongPress(MotionEvent e) {
                if (actions != null) actions.hold(true, e.getX());
            }
        });
    }

    void setGestureActions(GestureActions a) { actions = a; }
    void setOnSettledListener(SettledListener l) { settledListener = l; }

    /** Old touch logic = ZoomController fallback (runs when NOT zoomed). */
    private boolean onTouchFallback(View v, MotionEvent e) {
        fallbackGestures.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = e.getX(); lastY = e.getY();
                if (actions != null) actions.interaction(true);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (actions != null) { actions.hold(false, lastX); actions.interaction(false); }
                break;
        }
        return true;
    }

    void setFrame(Bitmap b, boolean keepTransform) {
        bitmap = b;
        sharpCrop = null;
        updateContent();
        if (!keepTransform) zc.reset(false);
        invalidate();
    }

    void setDisplayMode(int m) {
        displayMode = m;
        sharpCrop = null;
        updateContent();
        invalidate();
    }

    void setDisplayRotation(int r) {
        rotation = ((r % 360) + 360) % 360;
        sharpCrop = null;
        updateContent();
        invalidate();
    }

    /** v2.4.5 fine angle (absolute total) — content box recomputed for the tilted bbox. */
    void setFineRotation(float deg) {
        rotation = deg;
        sharpCrop = null;
        updateContent();
        invalidate();
    }

    float getRotationDeg() { return rotation; }

    void zoomBy(float f) { zc.zoomBy(f); }
    void resetZoom() { zc.reset(false); sharpCrop = null; invalidate(); }
    /** v2.4.6: stale-guard generation for the async settled crop. */
    int getZcGeneration() { return zc.getGeneration(); }
    /** v2.4.6 PHASE 2: zoom-aware look grading needs the current zoom. */
    float getZoom() { return zc.getZoom(); }

    /** Settled sharp view (view-sized bicubic crop). Dropped automatically on the next move. */
    void setSharpCrop(Bitmap b, int generation) {
        if (b != null && !zc.isCurrent(generation)) { b.recycle(); return; }   // stale — pack rule
        if (sharpCrop != null && sharpCrop != b && !sharpCrop.isRecycled()) sharpCrop.recycle();
        sharpCrop = b;
        invalidate();
    }

    /** Visible area in BITMAP pixel coords (for the settled crop decode). UI-thread only.
     *  v2.4.6 FIX: correct chain — region → rotated-image → bitmap (post-concat order). */
    RectF getBitmapVisibleRect() {
        tmpRect.set(0, 0, 0, 0);
        if (bitmap == null || bitmap.isRecycled() || bitmap.getWidth() <= 0) return tmpRect;
        int bw = bitmap.getWidth(), bh = bitmap.getHeight();
        Matrix m = new Matrix();
        // region coords → rotated-image coords: q = p_region + regO
        m.postTranslate(regOX, regOY);
        // rotated-image → bitmap: p = R(-θ)·(q - bboxC) + bitmapC
        m.postTranslate(-bboxW / 2f, -bboxH / 2f);
        m.postRotate(-rotation);
        m.postTranslate(bw / 2f, bh / 2f);
        // map the region-visible rect corners and take the bounding box
        RectF vis = new RectF(zc.getVisible());
        float[] pts = {vis.left, vis.top, vis.right, vis.top, vis.right, vis.bottom, vis.left, vis.bottom};
        for (int i = 0; i < 8; i += 2) {
            float[] pt = {pts[i], pts[i + 1]};
            m.mapPoints(pt);
            float mx = pt[0], my = pt[1];
            if (i == 0) tmpRect.set(mx, my, mx, my);
            else {
                tmpRect.left = Math.min(tmpRect.left, mx); tmpRect.right = Math.max(tmpRect.right, mx);
                tmpRect.top = Math.min(tmpRect.top, my); tmpRect.bottom = Math.max(tmpRect.bottom, my);
            }
        }
        tmpRect.intersect(0, 0, bw, bh);
        return tmpRect;
    }

    /** Recompute the ZC content region for the current display mode / rotation / view size. */
    private void updateContent() {
        int vw = getWidth(), vh = getHeight();
        if (bitmap == null || bitmap.isRecycled()) return;
        int bw = bitmap.getWidth(), bh = bitmap.getHeight();
        float rad = (float) Math.toRadians(rotation);
        float cos = Math.abs((float) Math.cos(rad)), sin = Math.abs((float) Math.sin(rad));
        bboxW = bw * cos + bh * sin;
        bboxH = bw * sin + bh * cos;
        int rotW = Math.max(1, Math.round(bboxW)), rotH = Math.max(1, Math.round(bboxH));
        if (vw <= 0 || vh <= 0) { regW = rotW; regH = rotH; regOX = 0; regOY = 0; zc.setContent(regW, regH); return; }
        float rA = (float) rotW / rotH, vA = (float) vw / vh;
        if (displayMode == MODE_ONE_TO_ONE) {
            // pixel-perfect window (view-sized, centered — may be larger or smaller than the image)
            regW = vw; regH = vh;
        } else if (displayMode == MODE_FILL) {
            if (rA > vA) { regH = rotH; regW = Math.max(1, Math.round(rotH * vA)); }
            else { regW = rotW; regH = Math.max(1, Math.round(rotW / vA)); }
        } else { // FIT
            regW = rotW; regH = rotH;
        }
        regOX = (rotW - regW) / 2;
        regOY = (rotH - regH) / 2;
        zc.setContent(regW, regH);
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        updateContent();   // FILL/1:1 regions depend on the view size
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (bitmap == null || bitmap.isRecycled()) return;
        int bw = bitmap.getWidth(), bh = bitmap.getHeight();
        int vw = getWidth(), vh = getHeight();
        c.save();
        c.clipRect(0, 0, vw, vh);
        // v2.4.6 FIX: correct chain — bitmap point p:
        //   view = zcMatrix( T(-regO)( T(bboxC)( R(θ)( T(-bitmapC)(p) ) ) ) )
        // i.e. bitmap → rotate about bitmap center → place in rotated bbox → offset by region origin → ZC zoom/pan.
        drawMatrix.set(zc.getMatrix());
        drawMatrix.postTranslate(-regOX, -regOY);          // region → rotated-image coords
        drawMatrix.postTranslate(bboxW / 2f, bboxH / 2f);  // rotated-image origin → bbox center
        drawMatrix.postRotate(rotation);                   // rotate bitmap about its center
        drawMatrix.postTranslate(-bw / 2f, -bh / 2f);      // bitmap center → bitmap origin
        c.drawBitmap(bitmap, drawMatrix, paint);
        if (sharpCrop != null && !sharpCrop.isRecycled()) {
            paint.setFilterBitmap(true);
            c.drawBitmap(sharpCrop, null, new RectF(0, 0, vw, vh), paint);   // settled sharp overlay
        }
        c.restore();
    }
}
