package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;
import android.view.View;

/**
 * The library's liquid-glass lens (SDF refraction, dispersion, rim light) is an AGSL shader
 * and only runs on API 33+. Below that the same properties are accepted and ignored, and the
 * view falls back to a blur. This is that shader, run once on a bitmap, for the flip cards.
 * The numbers match GlassLensRenderer: inward sample, falloff 2, 48px bevel, 160px refraction.
 */
final class SoftwareLens {
    private static int[] cachedPixels;
    private static int cachedId;
    private static int cachedW;
    private static int cachedH;

    private SoftwareLens() {}

    /** One copy of the wallpaper, shared by the cards and the two colon beads. */
    static int[] pixelsOf(Bitmap wall) {
        synchronized (SoftwareLens.class) {
            int id = System.identityHashCode(wall);
            if (cachedPixels != null && cachedId == id
                    && cachedW == wall.getWidth() && cachedH == wall.getHeight()) {
                return cachedPixels;
            }
            int[] pixels = new int[wall.getWidth() * wall.getHeight()];
            wall.getPixels(pixels, 0, wall.getWidth(), 0, 0, wall.getWidth(), wall.getHeight());
            cachedPixels = pixels;
            cachedId = id;
            cachedW = wall.getWidth();
            cachedH = wall.getHeight();
            return pixels;
        }
    }

    static Bitmap wallpaper(View backdrop) {
        if (!(backdrop instanceof ImageView)) return null;
        Drawable drawable = ((ImageView) backdrop).getDrawable();
        if (!(drawable instanceof BitmapDrawable)) return null;
        Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
        if (bitmap == null || bitmap.isRecycled()) return null;
        if (bitmap.getConfig() == Bitmap.Config.HARDWARE) return null;
        return bitmap;
    }

    static final class Job {
        int[] pixels;
        int bitmapW;
        int bitmapH;
        int viewW;
        int viewH;
        float cardX;
        float cardY;
        float cardW;
        float cardH;
        float radius;
        float density;
        /** View pixels. 0 uses the library defaults, 48 and 160. */
        float bevel;
        float refract;
    }

    /** A rounded-rect lens of the card. Null when the picture is not ready. */
    static Bitmap render(Job job) {
        if (job.pixels == null || job.cardW < 2f || job.cardH < 2f || job.viewW < 2 || job.viewH < 2) {
            return null;
        }
        float fit = (float) Math.sqrt(140000.0 / (job.cardW * job.cardH));
        float scale = Math.min(1f, fit);
        int outW = Math.max(8, Math.round(job.cardW * scale));
        int outH = Math.max(8, Math.round(job.cardH * scale));
        scale = outW / job.cardW;

        float minDim = Math.min(job.cardW, job.cardH);
        float refPx = 110f * Math.max(job.density, 1f);
        float wantBevel = job.bevel > 0f ? job.bevel : 48f;
        float wantRefract = job.refract > 0f ? job.refract : 160f;
        float bevel = Math.max(2f, Math.min(wantBevel, minDim * 0.3f)) * scale;
        float refractCap = 0.7f * minDim * Math.min(1f, minDim / refPx);
        float refract = Math.min(wantRefract, refractCap) * scale;
        float radius = Math.min(job.radius, minDim * 0.5f) * scale;
        float blur = 6f * scale;
        int margin = Math.max(2, (int) Math.ceil(blur * 3f + 2f));

        int plateW = outW + margin * 2;
        int plateH = outH + margin * 2;
        int[] plate = new int[plateW * plateH];
        fillPlate(job, plate, plateW, plateH, margin, scale);
        boxBlur(plate, plateW, plateH, Math.max(1, Math.round(blur)));

        float lum = meanLuma(plate, plateW, plateH, margin, outW, outH);
        int tint = lum >= 0.60f ? 0x33000000 : 0x24FFFFFF;

        int[] out = new int[outW * outH];
        lens(plate, plateW, plateH, margin, out, outW, outH, radius, bevel, refract, scale, tint);

        Bitmap bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(out, 0, outW, 0, 0, outW, outH);
        return bitmap;
    }

    private static void fillPlate(Job job, int[] plate, int plateW, int plateH,
                                  int margin, float scale) {
        float crop = Math.max(job.viewW / (float) job.bitmapW, job.viewH / (float) job.bitmapH);
        float drawnW = job.bitmapW * crop;
        float drawnH = job.bitmapH * crop;
        float originX = (job.viewW - drawnW) / 2f;
        float originY = (job.viewH - drawnH) / 2f;
        for (int y = 0; y < plateH; y++) {
            float viewY = job.cardY + (y - margin) / scale;
            float by = (viewY - originY) / crop;
            for (int x = 0; x < plateW; x++) {
                float viewX = job.cardX + (x - margin) / scale;
                float bx = (viewX - originX) / crop;
                plate[y * plateW + x] = sample(job.pixels, job.bitmapW, job.bitmapH, bx, by);
            }
        }
    }

    private static void lens(int[] plate, int plateW, int plateH, int margin,
                             int[] out, int outW, int outH,
                             float radius, float bevel, float refract, float scale, int tint) {
        float cx = outW / 2f;
        float cy = outH / 2f;
        float hw = outW / 2f;
        float hh = outH / 2f;
        float falloff = 2f;
        float dispersion = 0.10f;
        float gB = (float) Math.pow(5.0, -falloff);
        float gDen = 1f - gB;
        float aa = 1.5f * scale;
        float one = scale;
        float two = 2f * scale;
        float three = 3f * scale;
        float bandW = Math.max(2f * scale, Math.min(bevel * 0.3f, 6f * scale));
        float tintA = ((tint >>> 24) & 0xff) / 255f;
        float tintR = ((tint >> 16) & 0xff) / 255f;
        float tintG = ((tint >> 8) & 0xff) / 255f;
        float tintB = (tint & 0xff) / 255f;
        final float lightX = 0.866f;
        final float lightY = 0.5f;

        for (int y = 0; y < outH; y++) {
            float py = y + 0.5f;
            for (int x = 0; x < outW; x++) {
                float px = x + 0.5f;
                float d = sdRound(px, py, cx, cy, hw, hh, radius);
                float cov = clamp(0.5f - d / aa, 0f, 1f);
                if (cov <= 0.004f) {
                    out[y * outW + x] = 0;
                    continue;
                }
                float nx = sdRound(px + 1f, py, cx, cy, hw, hh, radius)
                        - sdRound(px - 1f, py, cx, cy, hw, hh, radius);
                float ny = sdRound(px, py + 1f, cx, cy, hw, hh, radius)
                        - sdRound(px, py - 1f, cx, cy, hw, hh, radius);
                float nLen = (float) Math.sqrt(nx * nx + ny * ny);
                if (nLen > 0.0001f) {
                    nx /= nLen;
                    ny /= nLen;
                } else {
                    nx = 0f;
                    ny = -1f;
                }
                float t = clamp(-d / Math.max(bevel, 1f), 0f, 1f);
                float slope = ((float) Math.pow(1.0 + 4.0 * t, -falloff) - gB) / gDen;
                float shift = -slope * refract;
                float ox = x + margin + nx * shift;
                float oy = y + margin + ny * shift;
                float spread = dispersion * slope;
                int redPx = sample(plate, plateW, plateH, ox + nx * shift * -spread, oy + ny * shift * -spread);
                int greenPx = sample(plate, plateW, plateH, ox, oy);
                int bluePx = sample(plate, plateW, plateH, ox + nx * shift * spread, oy + ny * shift * spread);
                // shift is already the full inward offset. Dispersion scales that offset,
                // so the extra is (scale - 1) * offset, and offset = n * shift.
                float r = ((redPx >> 16) & 0xff) / 255f;
                float g = ((greenPx >> 8) & 0xff) / 255f;
                float b = (bluePx & 0xff) / 255f;

                float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                float satNow = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
                float room = 1f - smoothstep(0.2f, 0.85f, satNow);
                float hl = 1f - smoothstep(0.75f, 0.98f, lum);
                float amount = 1f + 0.4f * mix(0.3f, 1f, room * hl);
                r = clamp(mix(lum, r, amount), 0f, 1f);
                g = clamp(mix(lum, g, amount), 0f, 1f);
                b = clamp(mix(lum, b, amount), 0f, 1f);

                r = mix(r, tintR, tintA);
                g = mix(g, tintG, tintA);
                b = mix(b, tintB, tintA);

                float facing = nx * -lightX + ny * -lightY;
                float lobeF = (float) Math.pow(Math.max(facing, 0f), 4.5);
                float lobeB = (float) Math.pow(Math.max(-facing, 0f), 4.5);
                float inside = -d;
                float glowIn = clamp((inside - one) / two, 0f, 1f);
                float glow = glowIn * (float) Math.pow(clamp(1f - (inside - three) / bandW, 0f, 1f), 1.5) * cov;
                float hair = clamp(1f - Math.abs(d + one) / two, 0f, 1f) * cov;
                float spec = (hair * 0.70f * (lobeF + lobeB) + glow * 0.10f * lobeF);
                r = clamp(r + spec, 0f, 1f);
                g = clamp(g + spec, 0f, 1f);
                b = clamp(b + spec, 0f, 1f);

                int a = Math.round(cov * 255f);
                int ir = Math.round(r * cov * 255f);
                int ig = Math.round(g * cov * 255f);
                int ib = Math.round(b * cov * 255f);
                out[y * outW + x] = (a << 24) | (ir << 16) | (ig << 8) | ib;
            }
        }
    }

    private static float sdRound(float px, float py, float cx, float cy, float hw, float hh, float radius) {
        float dx = Math.abs(px - cx) - hw + radius;
        float dy = Math.abs(py - cy) - hh + radius;
        float ax = Math.max(dx, 0f);
        float ay = Math.max(dy, 0f);
        return (float) Math.sqrt(ax * ax + ay * ay) + Math.min(Math.max(dx, dy), 0f) - radius;
    }

    private static int sample(int[] pixels, int w, int h, float x, float y) {
        if (x < 0f) x = 0f;
        if (y < 0f) y = 0f;
        float maxX = w - 1f;
        float maxY = h - 1f;
        if (x > maxX) x = maxX;
        if (y > maxY) y = maxY;
        int x0 = (int) x;
        int y0 = (int) y;
        int x1 = Math.min(x0 + 1, w - 1);
        int y1 = Math.min(y0 + 1, h - 1);
        float fx = x - x0;
        float fy = y - y0;
        int c00 = pixels[y0 * w + x0];
        int c10 = pixels[y0 * w + x1];
        int c01 = pixels[y1 * w + x0];
        int c11 = pixels[y1 * w + x1];
        int a = lerp4(c00 >>> 24, c10 >>> 24, c01 >>> 24, c11 >>> 24, fx, fy);
        int r = lerp4((c00 >> 16) & 0xff, (c10 >> 16) & 0xff, (c01 >> 16) & 0xff, (c11 >> 16) & 0xff, fx, fy);
        int g = lerp4((c00 >> 8) & 0xff, (c10 >> 8) & 0xff, (c01 >> 8) & 0xff, (c11 >> 8) & 0xff, fx, fy);
        int b = lerp4(c00 & 0xff, c10 & 0xff, c01 & 0xff, c11 & 0xff, fx, fy);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int lerp4(int c00, int c10, int c01, int c11, float fx, float fy) {
        float top = c00 + (c10 - c00) * fx;
        float bot = c01 + (c11 - c01) * fx;
        return Math.round(top + (bot - top) * fy);
    }

    private static void boxBlur(int[] pixels, int w, int h, int radius) {
        int[] tmp = new int[pixels.length];
        blurH(pixels, tmp, w, h, radius);
        blurV(tmp, pixels, w, h, radius);
    }

    private static void blurH(int[] src, int[] dst, int w, int h, int radius) {
        int span = radius * 2 + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int a = 0, r = 0, g = 0, b = 0;
            for (int i = -radius; i <= radius; i++) {
                int p = src[row + clampIndex(i, w)];
                a += p >>> 24;
                r += (p >> 16) & 0xff;
                g += (p >> 8) & 0xff;
                b += p & 0xff;
            }
            for (int x = 0; x < w; x++) {
                dst[row + x] = ((a / span) << 24) | ((r / span) << 16) | ((g / span) << 8) | (b / span);
                int drop = src[row + clampIndex(x - radius, w)];
                int add = src[row + clampIndex(x + radius + 1, w)];
                a += (add >>> 24) - (drop >>> 24);
                r += ((add >> 16) & 0xff) - ((drop >> 16) & 0xff);
                g += ((add >> 8) & 0xff) - ((drop >> 8) & 0xff);
                b += (add & 0xff) - (drop & 0xff);
            }
        }
    }

    private static void blurV(int[] src, int[] dst, int w, int h, int radius) {
        int span = radius * 2 + 1;
        for (int x = 0; x < w; x++) {
            int a = 0, r = 0, g = 0, b = 0;
            for (int i = -radius; i <= radius; i++) {
                int p = src[clampIndex(i, h) * w + x];
                a += p >>> 24;
                r += (p >> 16) & 0xff;
                g += (p >> 8) & 0xff;
                b += p & 0xff;
            }
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = ((a / span) << 24) | ((r / span) << 16) | ((g / span) << 8) | (b / span);
                int drop = src[clampIndex(y - radius, h) * w + x];
                int add = src[clampIndex(y + radius + 1, h) * w + x];
                a += (add >>> 24) - (drop >>> 24);
                r += ((add >> 16) & 0xff) - ((drop >> 16) & 0xff);
                g += ((add >> 8) & 0xff) - ((drop >> 8) & 0xff);
                b += (add & 0xff) - (drop & 0xff);
            }
        }
    }

    private static float meanLuma(int[] plate, int plateW, int plateH, int margin, int outW, int outH) {
        long sum = 0;
        int count = 0;
        int step = Math.max(1, Math.min(outW, outH) / 24);
        for (int y = 0; y < outH; y += step) {
            int row = (y + margin) * plateW;
            for (int x = 0; x < outW; x += step) {
                int p = plate[row + x + margin];
                int r = (p >> 16) & 0xff;
                int g = (p >> 8) & 0xff;
                int b = p & 0xff;
                sum += (54L * r + 183L * g + 19L * b);
                count++;
            }
        }
        if (count == 0) return 0f;
        return (sum / (float) count) / (256f * 255f);
    }

    private static int clampIndex(int i, int size) {
        if (i < 0) return 0;
        if (i >= size) return size - 1;
        return i;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = clamp((x - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }
}
