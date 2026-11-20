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
 * Frame Mode view.  v2.4.6 FINAL: ZoomController = gesture/state engine ONLY
 * (pinch/drag/fling/wheel/settled); the DRAWING uses the PROVEN v2.4.5 matrix
 * structure (translate-center → rotate → scale → translate-view+pan) — the
 * fragile bbox/region composition that caused black/zoomed-frame bugs is GONE.
 *
 * Kept owner-ordered features: single tap = controls, double-tap = ±1 frame,
 * hold = ±5 frames (ZoomController double-tap zoom disabled), FIT/FILL/1:1,
 * rotation (90-steps + fine, normalized), settled bicubic sharp crop.
 */
final class ZoomableImageView extends View {
    static final int MODE_FIT = 0, MODE_FILL = 1, MODE_ONE_TO_ONE = 2;

    interface GestureActions { void singleTap(); void doubleTap(float x); void hold(boolean active, float x); void interaction(boolean active); }
    interface SettledListener { void onFrameSettled(); }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private Bitmap bitmap;
    private int displayMode = MODE_FIT;
    private float rotation = 0f;           // total, normalized 0..360 (90-steps + fine)

    // v2.4.6: single zoom engine — gestures/state; zoom/pan fed into the proven draw
    private final ZoomController zc;
    private SettledListener settledListener;
    private Bitmap sharpCrop;              // settled bicubic sharp view (view-sized)
    private GestureDetector fallbackGestures;
    private float lastX, lastY;
    private float zoom = 1f, panX, panY;   // fed from ZoomController onTransform
    private GestureActions actions;

    ZoomableImageView(Context c) {
        super(c);
        setFocusable(true);
        zc = new ZoomController(c, this, new ZoomController.Listener() {
            @Override public void onTransform(Matrix m, float z, boolean interacting) {
                float[] vals = new float[9];
                m.getValues(vals);
                zoom = z;
                // ZC matrix: MTRANS = vw/2 + pan − (bitmap size)·s/2  → pan wapas nikalo
                panX = vals[Matrix.MTRANS_X] + (bitmap != null ? bitmap.getWidth() * vals[Matrix.MSCALE_X] / 2f : 0f)
                        - getWidth() / 2f;
                panY = vals[Matrix.MTRANS_Y] + (bitmap != null ? bitmap.getHeight() * vals[Matrix.MSCALE_X] / 2f : 0f)
                        - getHeight() / 2f;
                sharpCrop = null;              // fast path while moving — sharp overlay is stale
                invalidate();
            }
            @Override public void onSettled(RectF visible, float z, int generation) {
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
        updateZcContent();
        if (!keepTransform) zc.reset(false);
        invalidate();
    }

    void setDisplayMode(int m) {
        displayMode = m;
        sharpCrop = null;
        invalidate();
    }

    /** v2.4.7 BUG 1: rotation ke baad layout settle hone par hi sharp crop refresh. */
    private final Runnable settledRefresh = new Runnable() {
        @Override public void run() {
            if (zc.isZoomed() && settledListener != null) settledListener.onFrameSettled();
        }
    };

    void setDisplayRotation(int r) {
        rotation = ((r % 360) + 360) % 360;
        sharpCrop = null;              // purana overlay foran clear — normal frame dikhe
        updateZcContent();
        removeCallbacks(settledRefresh);
        postDelayed(settledRefresh, 350);   // layout settle hone par hi refresh
        invalidate();
    }

    /** v2.4.5 fine angle (absolute total) — NORMALIZED 0..360 (no unbounded accumulation). */
    void setFineRotation(float deg) {
        rotation = ((deg % 360) + 360) % 360;
        sharpCrop = null;              // purana overlay foran clear — normal frame dikhe
        updateZcContent();
        removeCallbacks(settledRefresh);
        postDelayed(settledRefresh, 350);   // layout settle hone par hi refresh
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
     *  v2.4.6 FINAL: ZoomController content = bitmap size → visible IS the bitmap rect. */
    RectF getBitmapVisibleRect() {
        return new RectF(zc.getVisible());
    }

    /** Keep ZoomController's content = ROTATED bitmap dims (its maxZoom/pan/visible math needs it). */
    private void updateZcContent() {
        if (bitmap == null || bitmap.isRecycled()) return;
        boolean swap = ((int) Math.round(rotation) % 180) == 90;
        int bw = bitmap.getWidth(), bh = bitmap.getHeight();
        zc.setContent(swap ? bh : bw, swap ? bw : bh);
    }

    /** Display base for the current mode (v2.4.5 proven logic). */
    private float baseScale() {
        if (bitmap == null || bitmap.isRecycled()) return 1f;
        int bw = bitmap.getWidth(), bh = bitmap.getHeight();
        boolean swap = ((int) Math.round(rotation) % 180) == 90;
        float rw = swap ? bh : bw, rh = swap ? bw : bh;
        int vw = getWidth(), vh = getHeight();
        if (vw <= 0 || vh <= 0) return 1f;
        switch (displayMode) {
            case MODE_FILL: return Math.max(vw / rw, vh / rh);
            case MODE_ONE_TO_ONE: return 1f;
            default: return Math.min(vw / rw, vh / rh);   // FIT
        }
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (bitmap == null || bitmap.isRecycled()) return;
        int bw = bitmap.getWidth(), bh = bitmap.getHeight();
        int vw = getWidth(), vh = getHeight();
        // v2.4.6 FINAL: PROVEN v2.4.5 matrix chain + ZoomController zoom/pan:
        //   p → T(-bitmapC) → R(rotation) → S(base·zoom) → T(viewCenter + pan)
        float s = baseScale() * zoom;
        matrix.reset();
        matrix.postTranslate(-bw / 2f, -bh / 2f);
        matrix.postRotate(rotation);
        matrix.postScale(s, s);
        matrix.postTranslate(vw / 2f + panX, vh / 2f + panY);
        c.drawBitmap(bitmap, matrix, paint);
        if (sharpCrop != null && !sharpCrop.isRecycled()) {
            paint.setFilterBitmap(true);
            c.drawBitmap(sharpCrop, null, new RectF(0, 0, vw, vh), paint);   // settled sharp overlay
        }
    }
}
