package com.truenain.app;

import android.graphics.Bitmap;

/**
 * Quality Pack Phase 1: editor-style color grading — pure Java math (no
 * third-party libs, no internet).
 *
 * Preset params order: bright(-0.5..0.5) contrast(0.5..2) sat(0..2)
 * warm(-1..1) shadowLift(0..1) highlightTame(0..1) vignette(0..1) sharpen(0..1.5)
 *
 * Used by: Frame Mode SAVE (Bitmap grading) and the trim re-encode shader
 * (ExactTrimTranscoder reads the same table via params()).
 */
final class ColorGrade {
    private ColorGrade() {}

    static final int NONE = 0, HDR = 1, CINEMATIC = 2, VIVID = 3, WARM = 4, COOL = 5;

    private static final float[][] PRESETS = {
            { 0f,     1f,    1f,    0f,     0f,    0f,    0f,    0f    },  // NONE
            { 0.015f, 1.15f, 1.18f, 0.06f,  0.30f, 0.22f, 0.10f, 0.60f },  // HDR+
            { 0f,     1.22f, 0.95f, -0.25f, 0.35f, 0.28f, 0.20f, 0.35f },  // CINEMATIC
            { 0f,     1.18f, 1.35f, 0.05f,  0.15f, 0.12f, 0f,    0.50f },  // VIVID
            { 0.03f,  1f,    1.08f, 0.40f,  0f,    0f,    0f,    0f    },  // WARM
            { 0f,     1f,    1.05f, -0.40f, 0f,    0f,    0f,    0f    },  // COOL
    };

    static String name(int preset) {
        switch (preset) {
            case HDR: return "HDR+";
            case CINEMATIC: return "Cinematic";
            case VIVID: return "Vivid";
            case WARM: return "Warm";
            case COOL: return "Cool";
            default: return "Original";
        }
    }

    /** Returns the 8-param table for a preset id (clamped). */
    static float[] params(int preset) {
        return PRESETS[Math.max(0, Math.min(PRESETS.length - 1, preset))];
    }

    /**
     * Grades a bitmap (brightness → contrast → saturation → warmth → shadows/
     * highlights → vignette → unsharp sharpen).  Returns a NEW bitmap (or the
     * original reference when preset ≤ 0).  Caller owns the result.
     */
    static Bitmap grade(Bitmap src, int preset) {
        if (src == null || src.isRecycled() || preset <= 0) return src;
        float[] p = params(preset);
        float bright = p[0], contrast = p[1], sat = p[2], warm = p[3];
        float shadow = p[4], high = p[5], vig = p[6], sharp = p[7];
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        int cx = w / 2, cy = h / 2;
        float maxR = (float) Math.sqrt((float) cx * cx + (float) cy * cy);
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int c = px[row + x];
                int r = (c >>> 16) & 255, g = (c >>> 8) & 255, b = c & 255;
                r += bright * 255f; g += bright * 255f; b += bright * 255f;
                r = Math.round((r - 128) * contrast + 128);
                g = Math.round((g - 128) * contrast + 128);
                b = Math.round((b - 128) * contrast + 128);
                float lum = 0.299f * r + 0.587f * g + 0.114f * b;
                r = Math.round(lum + (r - lum) * sat);
                g = Math.round(lum + (g - lum) * sat);
                b = Math.round(lum + (b - lum) * sat);
                r += warm * 20f; b -= warm * 20f;
                float l01 = lum / 255f;
                float sMask = 1f - smooth(0f, 0.45f, l01);
                float hMask = smooth(0.55f, 1f, l01);
                r += shadow * sMask * 26f; g += shadow * sMask * 26f; b += shadow * sMask * 26f;
                r -= high * hMask * 26f; g -= high * hMask * 26f; b -= high * hMask * 26f;
                float dx = x - cx, dy = y - cy;
                float v = vig * smooth(0.20f, 0.80f, (float) Math.sqrt(dx * dx + dy * dy) / maxR);
                r -= v * 120f; g -= v * 120f; b -= v * 120f;
                px[row + x] = 0xff000000 | (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
            }
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        if (sharp > 0f) sharpen(out, px, w, h, sharp);
        return out;
    }

    /** 3×3 unsharp-mask sharpen (re-reads the graded pixels). */
    private static void sharpen(Bitmap bmp, int[] px, int w, int h, float amt) {
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        int[] src = new int[px.length];
        System.arraycopy(px, 0, src, 0, px.length);
        for (int y = 1; y < h - 1; y++) {
            int row = y * w;
            for (int x = 1; x < w - 1; x++) {
                int c = src[row + x];
                int cr = (c >>> 16) & 255, cg = (c >>> 8) & 255, cb = c & 255;
                int n1 = src[row + x - 1], n2 = src[row + x + 1];
                int n3 = src[row - w + x], n4 = src[row + w + x];
                int sr = ((n1 >>> 16 & 255) + (n2 >>> 16 & 255) + (n3 >>> 16 & 255) + (n4 >>> 16 & 255)) >> 2;
                int sg = ((n1 >>> 8 & 255) + (n2 >>> 8 & 255) + (n3 >>> 8 & 255) + (n4 >>> 8 & 255)) >> 2;
                int sb = ((n1 & 255) + (n2 & 255) + (n3 & 255) + (n4 & 255)) >> 2;
                int r = cr + Math.round(amt * (cr - sr));
                int g = cg + Math.round(amt * (cg - sg));
                int b = cb + Math.round(amt * (cb - sb));
                px[row + x] = 0xff000000 | (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static float smooth(float a, float b, float v) {
        float t = Math.max(0f, Math.min(1f, (v - a) / (b - a)));
        return t * t * (3f - 2f * t);
    }

    private static int clamp255(int v) { return v < 0 ? 0 : Math.min(255, v); }
}
