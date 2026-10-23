package com.gathin.tvhome;

/**
 * How big a theme's wallpaper may be and what size it is decoded at (#166). A 1 GB TV cannot afford a
 * picture decoded at full size: a 65535x2159 image is about 283 MB even in RGB_565.
 */
final class WallpaperSize {
    private WallpaperSize() { }

    /** 4K UHD (3840x2160, 8.3 M pixels) and a little more; anything bigger is refused. */
    static final long MAX_PIXELS = 8_400_000L;

    static boolean accepted(int width, int height) {
        return width > 0 && height > 0 && (long) width * height <= MAX_PIXELS;
    }

    /** The largest power-of-two sample size that still covers the screen in both directions. */
    static int sampleSize(int width, int height, int screenW, int screenH) {
        int sample = 1;
        while (width / (sample * 2) >= screenW && height / (sample * 2) >= screenH) sample *= 2;
        return sample;
    }

    /**
     * Sample size used to draw a picture. Starts at {@link #sampleSize} so a normal photo still covers the
     * screen, then steps down again when that decode would pass {@link #MAX_PIXELS}. A square 6K image on a
     * 1080p TV is the case: half size still has more pixels than the cap, so it used to be dropped and Home
     * stayed plain. One more step is a small upscale and stays on screen.
     */
    static int displaySample(int width, int height, int screenW, int screenH) {
        int sample = sampleSize(width, height, Math.max(screenW, 1), Math.max(screenH, 1));
        while (sample < 64 && !accepted(width / sample, height / sample)) sample *= 2;
        return sample;
    }

    /**
     * Centered source rectangle, in original pixels, with the screen's aspect. A square picture on a
     * 16:9 TV keeps the full width and loses the extra height, so the decode can stay near the
     * framebuffer size instead of shrinking the whole square until it is softer than the screen.
     * Returns {left, top, right, bottom}.
     */
    static int[] coverCrop(int width, int height, int screenW, int screenH) {
        int left = 0, top = 0, cropW = width, cropH = height;
        if (screenW > 0 && screenH > 0 && (long) width * screenH < (long) screenW * height) {
            cropH = (int) ((long) width * screenH / screenW);
            top = Math.max(0, (height - cropH) / 2);
        } else if (screenW > 0 && screenH > 0 && (long) width * screenH > (long) screenW * height) {
            cropW = (int) ((long) height * screenW / screenH);
            left = Math.max(0, (width - cropW) / 2);
        }
        if (cropW < 1) cropW = width;
        if (cropH < 1) cropH = height;
        return new int[]{left, top, left + cropW, top + cropH};
    }

    /**
     * {inDensity, inTargetDensity} that scale a (sampled) picture down to what CENTER_CROP shows: the
     * side that limits the cover lands on the screen size. Null when it is already that small.
     */
    static int[] coverScale(int width, int height, int screenW, int screenH) {
        if ((long) screenW * height >= (long) screenH * width) {   // width limits the cover
            return width > screenW ? new int[]{width, screenW} : null;
        }
        return height > screenH ? new int[]{height, screenH} : null;
    }
}
