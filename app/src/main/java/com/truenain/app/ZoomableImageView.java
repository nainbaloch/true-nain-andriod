package com.truenain.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

final class ZoomableImageView extends View {
    static final int MODE_FIT = 0, MODE_FILL = 1, MODE_ONE_TO_ONE = 2;
    // v2.4.4: doubleTap/hold ab touch X bhi dete hain — left/right side ka pata
    // chalta hai (user request: right double-tap = +1 frame, left = -1; hold = ±5).
    interface GestureActions { void singleTap(); void doubleTap(float x); void hold(boolean active, float x); void interaction(boolean active); }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private final ScaleGestureDetector scaler;
    private final GestureDetector gestures;
    private Bitmap bitmap;
    private float scale = 1f, tx = 0f, ty = 0f;
    private int displayMode = MODE_FIT;
    private float rotation = 0f;   // v2.4.4: float — free-angle (fine) rotation bhi yehi field use karta hai
    private float lastX, lastY;
    private boolean dragging;
    private GestureActions actions;
    // Hardening spec S1.3: dynamic max zoom — low-res sources over-magnify into
    // blocky artifacts past 2.5x native pixels, so the cap follows the source size.
    private float maxScale = 8f;

    ZoomableImageView(Context c) {
        super(c);
        setFocusable(true);
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                scale = clamp(scale * d.getScaleFactor(), 1f, maxScale); clampPan(); invalidate(); return true;
            }
            @Override public void onScaleEnd(ScaleGestureDetector d) {
                updateMaxScale();          // re-cap against the current source + view size
            }
        });
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { if (actions != null) actions.singleTap(); return true; }
            @Override public boolean onDoubleTap(MotionEvent e) { if (actions != null) actions.doubleTap(e.getX()); return true; }
            @Override public void onLongPress(MotionEvent e) { if (actions != null) actions.hold(true, e.getX()); }
        });
    }

    void setGestureActions(GestureActions a) { actions = a; }
    void setFrame(Bitmap b, boolean keepTransform) { bitmap = b; updateMaxScale(); if (!keepTransform) resetZoom(); invalidate(); }
    void setDisplayMode(int m) { displayMode = m; resetZoom(); invalidate(); }
    void setDisplayRotation(int r) { rotation = ((r % 360) + 360) % 360; updateMaxScale(); resetZoom(); invalidate(); }
    /** v2.4.4: free-angle rotation — zoom/pan BAKI rehta hai (tirchhi video seedha karne ke liye). */
    void setFineRotation(float deg) { rotation = deg; updateMaxScale(); clampPan(); invalidate(); }
    float getRotationDeg() { return rotation; }
    void zoomBy(float f) { scale = clamp(scale * f, 1f, maxScale); clampPan(); invalidate(); }
    void resetZoom() { scale = 1f; tx = ty = 0f; }

    /** Hardening spec S1.3: cap = max(4x, 2.5x past 1:1 native pixels), hard-clamped to 12x. */
    private void updateMaxScale() {
        float cap = 8f;
        int bw = bitmap == null || bitmap.isRecycled() ? 0 : bitmap.getWidth();
        if (bw > 0 && getWidth() > 0) {
            int srcW = ((int) Math.round(rotation)) % 180 == 90 ? bitmap.getHeight() : bw;
            cap = Math.max(4f, srcW / (float) getWidth() * 2.5f);
        }
        maxScale = Math.min(12f, cap);
        if (scale > maxScale) { scale = maxScale; clampPan(); }
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (bitmap == null || bitmap.isRecycled()) return;
        float bw = bitmap.getWidth(), bh = bitmap.getHeight();
        boolean swap = ((int) Math.round(rotation)) % 180 == 90;
        float rw = swap ? bh : bw, rh = swap ? bw : bh;
        float base;
        if (displayMode == MODE_FILL) base = Math.max(getWidth()/rw, getHeight()/rh);
        else if (displayMode == MODE_ONE_TO_ONE) base = 1f;
        else base = Math.min(getWidth()/rw, getHeight()/rh);
        float s = base * scale;
        // V2.0.4: bilinear smoothing at EVERY scale — user-reported "pixel kharab"
        // on mobile came from filter being disabled at ≥1x (hard upscaled pixels).
        paint.setFilterBitmap(true);
        matrix.reset();
        matrix.postTranslate(-bw/2f, -bh/2f);
        matrix.postRotate(rotation);
        matrix.postScale(s, s);
        matrix.postTranslate(getWidth()/2f + tx, getHeight()/2f + ty);
        c.save();
        c.clipRect(0, 0, getWidth(), getHeight());
        c.drawBitmap(bitmap, matrix, paint);
        c.restore();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        scaler.onTouchEvent(e); gestures.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: lastX=e.getX(); lastY=e.getY(); dragging=true; if (actions != null) actions.interaction(true); break;
            case MotionEvent.ACTION_POINTER_UP:
                int survivor = e.getActionIndex() == 0 ? 1 : 0;
                if (survivor < e.getPointerCount()) {
                    lastX = e.getX(survivor);
                    lastY = e.getY(survivor);
                }
                // Re-anchor gesture tracking when either finger lifts.
                dragging = true;
                break;
            case MotionEvent.ACTION_MOVE:
                if (e.getPointerCount() != 1) break;
                if (dragging && !scaler.isInProgress() && scale > 1f) {
                    tx += e.getX()-lastX; ty += e.getY()-lastY; lastX=e.getX(); lastY=e.getY(); clampPan(); invalidate();
                } break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging=false; if (actions != null) { actions.hold(false, lastX); actions.interaction(false); } break;
        }
        return true;
    }

    private void clampPan() {
        if (bitmap == null || getWidth() <= 0 || getHeight() <= 0) return;
        float w = bitmap.getWidth(), h = bitmap.getHeight();
        if (((int) Math.round(rotation)) % 180 == 90) { float tmp = w; w = h; h = tmp; }
        float base = displayMode == MODE_FILL ? Math.max(getWidth()/w, getHeight()/h)
                : displayMode == MODE_ONE_TO_ONE ? 1f : Math.min(getWidth()/w, getHeight()/h);
        float maxX = Math.max(0f, (w * base * scale - getWidth()) / 2f);
        float maxY = Math.max(0f, (h * base * scale - getHeight()) / 2f);
        tx = clamp(tx, -maxX, maxX); ty = clamp(ty, -maxY, maxY);
    }
    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        updateMaxScale();
        clampPan();
    }
    /** DeX/mouse: wheel scroll = zoom in/out (pinch mouse se possible nahi). */
    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_SCROLL) {
            float scroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (scroll != 0f) { zoomBy(1f + scroll * 0.15f); return true; }
        }
        return super.onGenericMotionEvent(event);
    }

    private static float clamp(float v, float a, float b) { return Math.max(a, Math.min(b, v)); }
}
