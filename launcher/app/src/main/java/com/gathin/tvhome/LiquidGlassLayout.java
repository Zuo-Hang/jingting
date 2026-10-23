package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Glass panel for the top icons, menus and the dock. The library lens only runs on API 33,
 * so on this TV the panel paints {@link SoftwareLens} and keeps its children on top.
 */
public final class LiquidGlassLayout extends FrameLayout {
    private View backdrop;
    private float radius;
    private float bevel = 48f;
    private float refract = 160f;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF dest = new RectF();
    private Bitmap lens;
    private int token;
    private int drawnKey;
    private boolean rendering;

    public LiquidGlassLayout(Context context) {
        super(context);
        setWillNotDraw(false);
    }

    public LiquidGlassLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
    }

    public void setBackdropSource(View backdrop) {
        this.backdrop = backdrop;
        drawnKey = 0;
        invalidate();
    }

    public void setCornerRadius(float radius) {
        if (this.radius == radius) return;
        this.radius = radius;
        invalidateOutline();
        drawnKey = 0;
        invalidate();
    }

    public float getCornerRadius() {
        return radius;
    }

    public void setBevelWidth(float bevel) {
        if (this.bevel == bevel) return;
        this.bevel = bevel;
        drawnKey = 0;
        invalidate();
    }

    public void setRefractionHeight(float refract) {
        if (this.refract == refract) return;
        this.refract = refract;
        drawnKey = 0;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Bitmap frame = lens;
        if (frame != null && !frame.isRecycled()) {
            dest.set(0f, 0f, getWidth(), getHeight());
            canvas.drawBitmap(frame, null, dest, paint);
        }
        requestLens();
    }

    private void requestLens() {
        if (rendering || backdrop == null || getWidth() < 2 || getHeight() < 2) return;
        if (backdrop.getWidth() < 2 || backdrop.getHeight() < 2) return;
        Bitmap wall = SoftwareLens.wallpaper(backdrop);
        if (wall == null) return;
        int[] here = new int[2];
        int[] there = new int[2];
        getLocationOnScreen(here);
        backdrop.getLocationOnScreen(there);
        float useRadius = radius;
        if (getWidth() == getHeight()) useRadius = Math.max(useRadius, getWidth() / 2f);
        int key = (here[0] >> 3) * 31 + (here[1] >> 3)
                + (there[0] >> 3) * 17 + (there[1] >> 3)
                + getWidth() * 13 + getHeight()
                + backdrop.getWidth() * 7 + backdrop.getHeight()
                + System.identityHashCode(wall)
                + Float.floatToIntBits(useRadius)
                + Float.floatToIntBits(bevel)
                + Float.floatToIntBits(refract);
        if (key == drawnKey) return;
        drawnKey = key;
        final int mine = ++token;
        final int[] pixels;
        try {
            pixels = SoftwareLens.pixelsOf(wall);
        } catch (RuntimeException ignored) {
            drawnKey = 0;
            return;
        }
        rendering = true;
        final SoftwareLens.Job job = new SoftwareLens.Job();
        job.pixels = pixels;
        job.bitmapW = wall.getWidth();
        job.bitmapH = wall.getHeight();
        job.viewW = backdrop.getWidth();
        job.viewH = backdrop.getHeight();
        job.cardX = here[0] - there[0];
        job.cardY = here[1] - there[1];
        job.cardW = getWidth();
        job.cardH = getHeight();
        job.radius = useRadius;
        job.bevel = bevel;
        job.refract = refract;
        job.density = getResources().getDisplayMetrics().density;
        new Thread(() -> {
            Bitmap next = SoftwareLens.render(job);
            post(() -> {
                rendering = false;
                if (mine != token) {
                    if (next != null) next.recycle();
                    invalidate();
                    return;
                }
                if (next == null) {
                    drawnKey = 0;
                    invalidate();
                    return;
                }
                Bitmap previous = lens;
                lens = next;
                if (previous != null && previous != next) previous.recycle();
                invalidate();
            });
        }, "lens").start();
    }
}
