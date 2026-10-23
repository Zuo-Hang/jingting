package com.gathin.tvhome;

import com.example.liquidglass.LiquidGlassView;

/**
 * The values the v2.0.11 demo opens on. Its playground glass only turns on a live backdrop;
 * every other slider starts at the library default: a light blur, a 48px bevel, 160px of
 * refraction, chromatic aberration, and no extra colour wash. Corner radius is left to the
 * caller, because that demo's 999px default is a pill button.
 */
final class GlassStyle {
    private GlassStyle() {}

    static void apply(LiquidGlassView glass) {
        // API 31+ keeps a live sample. Android 11's software glass redraws the whole picture
        // every frame if this stays on, so it samples when the wallpaper actually changes.
        glass.setEnableDynamicBackground(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S);
        glass.setEnablePressEffect(false);
        glass.setBlurAmount(0.0625f);
        glass.setSaturation(140f);
        glass.setBevelWidth(48f);
        glass.setRefractionHeight(160f);
        glass.setDispersionStrength(0.10f);
        glass.setEnableChromaticAberration(true);
        glass.setEnableChromaticDispersion(false);
        glass.setAberrationIntensity(2f);
        glass.setEnableEdgeHighlight(true);
        glass.setEdgeHighlightOpacity(100f);
        glass.setEnableSensorHighlight(false);
        glass.setEnableAdaptiveTint(false);
        glass.setGlassTint(android.graphics.Color.TRANSPARENT);
    }
}
