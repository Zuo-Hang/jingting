package com.gathin.tvhome;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.TextClock;

/** Clock and date text. Glyph glass was dropped; these stay solid like the rest of the header. */
public final class GlassClock extends TextClock {
    public GlassClock(Context context) { this(context, null); }
    public GlassClock(Context context, AttributeSet attrs) { this(context, attrs, 0); }
    public GlassClock(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }
}
