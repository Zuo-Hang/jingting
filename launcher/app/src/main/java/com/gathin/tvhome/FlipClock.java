package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.example.liquidglass.LiquidGlassView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Two flip cards, hours and minutes, for the cleared screen. Each card is a
 * {@link LiquidGlassLayout} using the v2.0.11 control-center material. A changing card
 * turns over the hinge; the date stays outside, under the pair.
 */
final class FlipClock extends LinearLayout {
    private static final long HALF_FLIP_MS = 340L;
    /** Glyph width follows the card: the pair was narrowed from 2.2 to 1.55, the digits were not. */
    private static final float DIGIT_WIDTH_SCALE = 1.55f / 2.2f;
    /** Wallpaper visible between the two cards. The glass rim is wide enough to hide a thinner slot. */
    private static final float GAP_FRACTION = 0.10f;

    private final LiquidGlassLayout hourCard;
    private final LiquidGlassLayout minuteCard;
    private final DigitFace hourText;
    private final DigitFace minuteText;
    private final View hourSeam;
    private final View minuteSeam;
    private final Colon colon;
    private final float density;
    private SimpleDateFormat format;
    private boolean hour24;
    private String shown = "";

    private final Runnable tick = this::onTick;
    /** Colon beads: one real second lit, the next second dark. */
    private final Runnable blink = this::blinkDots;

    FlipClock(Context context, View backdrop) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);
        setClipChildren(false);
        setClipToPadding(false);
        hourText = digits();
        minuteText = digits();
        hourCard = card(hourText, backdrop);
        minuteCard = card(minuteText, backdrop);
        hourSeam = seam(hourCard);
        minuteSeam = seam(minuteCard);
        colon = new Colon(context, backdrop);
        LayoutParams cardLp = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
        addView(hourCard, cardLp);
        addView(colon, new LayoutParams(0, LayoutParams.MATCH_PARENT));
        addView(minuteCard, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    private void onTick() {
        String next = current();
        if (shown.isEmpty()) {
            shown = next;
            show(hourText, next.substring(0, 2));
            show(minuteText, next.substring(2));
            setContentDescription(spoken(next));
        } else if (!next.equals(shown)) {
            play(shown, next);
        }
        schedule();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        shown = "";
        tick.run();
        blink.run();
    }

    private void blinkDots() {
        long now = System.currentTimeMillis();
        colon.setLit((now / 1000L) % 2L == 0L);
        postDelayed(blink, Math.max(16L, 1000L - (now % 1000L)));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = View.MeasureSpec.getSize(widthMeasureSpec);
        if (w > 0) ((LayoutParams) colon.getLayoutParams()).width = gapPx(w);
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float cardW = (w - gapPx(w)) / 2f;
        // The demo's large modules use a 40pt corner on a 160pt tile.
        float radius = cardW * 0.25f;
        restyle(hourCard, hourText, hourSeam, radius, cardW, h);
        restyle(minuteCard, minuteText, minuteSeam, radius, cardW, h);
    }

    /** The slot between the cards, with two glass beads laid out as a colon. */
    private final class Colon extends FrameLayout {
        private final LiquidGlassLayout upper;
        private final LiquidGlassLayout lower;

        Colon(Context context, View backdrop) {
            super(context);
            setClipChildren(true);
            upper = bead(backdrop);
            lower = bead(backdrop);
            addView(upper);
            addView(lower);
        }

        void setLit(boolean on) {
            int visibility = on ? VISIBLE : INVISIBLE;
            if (upper.getVisibility() == visibility) return;
            upper.setVisibility(visibility);
            lower.setVisibility(visibility);
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int slot = right - left;
            int height = bottom - top;
            int dot = Math.round(Math.min(slot * 0.55f, height * 0.07f));
            dot = Math.max(dot, Math.round(6f * density));
            int between = Math.round(dot * 1.35f);
            int x = Math.max(0, (slot - dot) / 2);
            int y = Math.max(0, (height - (dot * 2 + between)) / 2);
            int spec = View.MeasureSpec.makeMeasureSpec(dot, View.MeasureSpec.EXACTLY);
            place(upper, spec, x, y, dot);
            place(lower, spec, x, y + dot + between, dot);
        }

        private void place(LiquidGlassLayout dot, int spec, int x, int y, int size) {
            dot.measure(spec, spec);
            dot.layout(x, y, x + size, y + size);
            dot.setCornerRadius(size / 2f);
            float bevel = size * 0.22f;
            dot.setBevelWidth(bevel);
            dot.setRefractionHeight(bevel * 0.45f);
        }

        private LiquidGlassLayout bead(View backdrop) {
            LiquidGlassLayout dot = new LiquidGlassLayout(getContext());
            dot.setBackdropSource(backdrop);
            dot.setClipToOutline(true);
            dot.setBlurAmount(0.18f);
            dot.setEnableChromaticAberration(false);
            dot.setDispersionStrength(0f);
            dot.setGlassTint(0x73FFFFFF);
            dot.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            dot.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            return dot;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        removeCallbacks(blink);
        hourCard.animate().cancel();
        minuteCard.animate().cancel();
        super.onDetachedFromWindow();
    }

    private LiquidGlassLayout card(DigitFace text, View backdrop) {
        LiquidGlassLayout glass = new LiquidGlassLayout(getContext());
        glass.setBackdropSource(backdrop);
        glass.setClipToOutline(true);
        glass.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        ((LiquidGlassView) view).getCornerRadius());
            }
        });
        glass.setCameraDistance(8000f * density);
        glass.addView(text, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        return glass;
    }

    private DigitFace digits() {
        return new DigitFace(getContext());
    }

    /**
     * Draws both digits already condensed. Scaling the view instead clips the text to the card
     * first, so a wide pair such as 25 loses a digit.
     */
    private final class DigitFace extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String digits = "";

        DigitFace(Context context) {
            super(context);
            setWillNotDraw(false);
            paint.setColor(Color.WHITE);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setSubpixelText(true);
            Typeface face = UiFont.get(context, R.string.font_body);
            paint.setTypeface(face);
            paint.setFontVariationSettings("'wght' 780");
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setDigits(String value) {
            digits = value == null ? "" : value;
            invalidate();
        }

        void setDigitSize(float size) {
            paint.setTextSize(size);
            invalidate();
        }

        Paint digitPaint() {
            return paint;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (digits.isEmpty() || getWidth() == 0 || getHeight() == 0) return;
            Paint.FontMetrics fm = paint.getFontMetrics();
            float baseline = getHeight() / 2f - (fm.ascent + fm.descent) / 2f;
            canvas.save();
            canvas.translate(getWidth() / 2f, baseline);
            canvas.scale(DIGIT_WIDTH_SCALE, 1f);
            canvas.drawText(digits, 0f, 0f, paint);
            canvas.restore();
        }
    }

    private View seam(LiquidGlassLayout glass) {
        View line = new View(getContext());
        line.setBackgroundColor(Color.BLACK);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(2f * density)));
        lp.gravity = Gravity.CENTER_VERTICAL;
        glass.addView(line, lp);
        return line;
    }

    private void restyle(LiquidGlassLayout glass, DigitFace text, View line,
                          float radius, float cardW, int cardH) {
        glass.setCornerRadius(radius);
        glass.invalidateOutline();
        glass.setCameraDistance(Math.max(cardH, 1) * 8f * density);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) line.getLayoutParams();
        lp.width = FrameLayout.LayoutParams.MATCH_PARENT;
        lp.leftMargin = 0;
        lp.rightMargin = 0;
        line.setLayoutParams(lp);
        sizeDigits(text, cardW, cardH);
    }

    /** Condensed width, and a size whose full pair still fits inside the card. */
    private void sizeDigits(DigitFace text, float cardW, int cardH) {
        float probe = 100f;
        text.setDigitSize(probe);
        Paint paint = text.digitPaint();
        float pair = paint.measureText("00");
        float glyph = -paint.getFontMetrics().ascent;
        float byWidth = pair > 0f ? (cardW * 0.86f) / (pair * DIGIT_WIDTH_SCALE) * probe : probe;
        float byHeight = glyph > 0f ? (cardH * 0.84f) / glyph * probe : probe;
        text.setDigitSize(Math.min(byWidth, byHeight));
    }

    private void show(DigitFace text, String digits) {
        text.setDigits(digits);
    }

    private void play(String oldText, String newText) {
        String oldHour = oldText.substring(0, 2);
        String newHour = newText.substring(0, 2);
        String oldMinute = oldText.substring(2);
        String newMinute = newText.substring(2);
        if (!oldHour.equals(newHour)) turn(hourCard, hourText, newHour);
        else show(hourText, newHour);
        if (!oldMinute.equals(newMinute)) turn(minuteCard, minuteText, newMinute);
        else show(minuteText, newMinute);
        shown = newText;
        setContentDescription(spoken(newText));
    }

    /** Half turn to the hinge, swap the digits, then settle. The glass shader stays on the card. */
    private void turn(LiquidGlassLayout card, DigitFace text, String digits) {
        card.animate().cancel();
        card.setRotationX(0f);
        card.animate().rotationX(90f).setDuration(HALF_FLIP_MS)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        card.animate().setListener(null);
                        show(text, digits);
                        card.setRotationX(-90f);
                        card.animate().rotationX(0f).setDuration(HALF_FLIP_MS).start();
                    }
                }).start();
    }

    private void schedule() {
        long now = System.currentTimeMillis();
        long next = (now / 60_000L + 1) * 60_000L + 30L;
        postDelayed(tick, Math.max(500L, next - now));
    }

    private String current() {
        boolean h24 = DateFormat.is24HourFormat(getContext());
        if (format == null || hour24 != h24) {
            hour24 = h24;
            format = new SimpleDateFormat(h24 ? "HHmm" : "hhmm", Locale.getDefault());
        }
        String text = format.format(new Date());
        return text.length() == 4 ? text : "0000";
    }

    private static int gapPx(int width) {
        return Math.round(width * GAP_FRACTION);
    }

    private static String spoken(String hhmm) {
        return hhmm.substring(0, 2) + ":" + hhmm.substring(2);
    }
}
