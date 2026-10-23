package com.gathin.tvhome;

import android.content.Context;
import android.util.AttributeSet;

import com.example.liquidglass.LiquidGlassView;

/**
 * Reusable glass panel. It is the v2.0.11 {@link LiquidGlassView} with {@link GlassStyle} applied,
 * so later content can be dropped in as a child and get the same material as the flip clock.
 */
public final class LiquidGlassLayout extends LiquidGlassView {
    public LiquidGlassLayout(Context context) {
        super(context);
        GlassStyle.apply(this);
    }

    public LiquidGlassLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        GlassStyle.apply(this);
    }
}
