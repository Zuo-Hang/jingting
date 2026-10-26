package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Build;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Displays and rotates a theme's local wallpaper images while Home is visible. */
final class WallpaperPlayer {
    /** Two 1080p pictures overlap only for this fade; the extra bitmap is released afterwards. */
    private static final int FADE_MS = 1000;

    private final FrameLayout root;
    private final ImageView image;
    /** On top of {@link #image} while a change fades in; gone the rest of the time. */
    private ImageView incoming;
    /** The picture still on {@link #image} during a fade, recycled when the new one is fully up. */
    private Bitmap outgoing;
    private final boolean fadeChanges;
    private final List<File> files;
    private final long interval;
    private final String order;
    private final SharedPreferences preferences;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final int width;
    /** config wallpaper_true_color: ARGB_8888 instead of RGB_565. */
    private final Bitmap.Config bitmapConfig;
    private final int height;
    /** wallpaper_dim as an ARGB black, painted into each picture once instead of a full-screen shade view. */
    private final int dimColor;
    /** The picture on screen, released once the next one has been drawn. */
    private Bitmap current;
    private boolean active;
    private boolean closed;
    private boolean loading;
    private String displayed = "";
    /** wallpaper_captions: the shown picture's name from wallpapers/captions.txt; null when off or there are none. */
    private final TextView caption;
    private final Map<String, String> names;

    WallpaperPlayer(Context context, FrameLayout root, Config config) {
        this.root = root;
        files = new ArrayList<>(config.wallpapers);
        interval = config.wallpaperRotationMinutes * 60_000L;
        bitmapConfig = config.wallpaperTrueColor ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        // The dim is painted into the picture (decode), so no full-screen shade layer is drawn on every frame.
        dimColor = config.wallpaperDim > 0 ? (config.wallpaperDim * 255 / 100) << 24 : 0;
        order = config.wallpaperOrder;
        fadeChanges = !config.reducedMotion;
        preferences = context.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE);
        // Playback state belongs to one collection. Changing the collection starts from its first
        // picture instead of resuming a filename that belonged to the previous one.
        String collection = collectionKey(files);
        if (!collection.equals(preferences.getString("collection", ""))) {
            preferences.edit().putString("collection", collection)
                    .remove("image").remove("changed").apply();
        }
        // A picture just confirmed in the picker. A new collection clears the name above, so put it
        // back when it is still the newest choice and it belongs to this list.
        SharedPreferences chosen = context.getSharedPreferences("local_wallpaper", Context.MODE_PRIVATE);
        String picked = chosen.getString("picked_name", "");
        long pickedAt = chosen.getLong("picked_at", 0);
        if (!picked.isEmpty() && pickedAt > 0 && pickedAt >= preferences.getLong("changed", 0)
                && WallpaperRotation.find(files, picked) != null) {
            preferences.edit().putString("image", picked).putLong("changed", pickedAt).apply();
        }
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        names = config.wallpaperCaptions && config.wallpaperEnabled && !files.isEmpty()
                ? WallpaperCaptions.read(files.get(0).getParentFile()) : Collections.emptyMap();
        caption = names.isEmpty() ? null : makeCaption(context, root);
        if (config.wallpaperEnabled && !files.isEmpty()) {
            root.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            showFirst();
        }
    }

    /** The full-screen picture. Glass panels sample this instead of their own parent. */
    View picture() {
        return image;
    }

    /**
     * The picture Home opens with is decoded right here, on the main thread, as the single wallpaper always was:
     * Home's first frame already has it instead of drawing the plain colour and the picture popping in later.
     * Only the later changes of a rotation decode in the background.
     */
    private void showFirst() {
        String previous = preferences.getString("image", "");
        File first = WallpaperRotation.find(files, previous);
        if (first == null) first = WallpaperRotation.pick(files, previous, order, random);
        while (first != null) {
            Bitmap bitmap = decode(first);
            if (bitmap != null) {
                show(bitmap, false);
                // The picture covers the whole screen: the root's plain colour under it is overdraw.
                root.setBackground(null);
                displayed = first.getName();
                showCaption(false);
                if (!displayed.equals(previous)) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
                return;
            }
            files.remove(first);   // unreadable or over 4K: try another, or keep the plain colour
            first = WallpaperRotation.pick(files, previous, order, random);
        }
    }

    void start() {
        active = true;
        update();
    }

    void stop() {
        active = false;
    }

    void close() {
        active = false;
        closed = true;
        finishIncoming(false);
        decoder.shutdownNow();
    }

    void update() {
        if (!active || closed || loading || files.isEmpty()) return;
        String previous = preferences.getString("image", "");
        File selected = WallpaperRotation.find(files, previous);
        long changed = preferences.getLong("changed", 0);
        if (selected == null || WallpaperRotation.due(changed, System.currentTimeMillis(), interval)) {
            selected = WallpaperRotation.pick(files, previous, order, random);
        }
        if (selected == null || selected.getName().equals(displayed)) return;
        beginShow(selected, previous);
    }

    /**
     * Rotation while the screen is cleared. The configured slideshow interval wins. When that
     * setting is off, the cleared screen still advances through {@code pool} on
     * {@code fallbackInterval}, instead of staying on the one picture Home had open.
     */
    void updateCleared(List<File> pool, long fallbackInterval, String poolOrder) {
        if (!active || closed || loading) return;
        List<File> images = new ArrayList<>();
        if (pool != null) {
            for (File file : pool) {
                if (file != null && file.isFile() && file.length() > 0 && !images.contains(file)) images.add(file);
            }
        }
        if (images.size() < 2) {
            update();
            return;
        }
        long every = interval > 0 ? interval : fallbackInterval;
        String previous = displayed.isEmpty() ? preferences.getString("image", "") : displayed;
        long changed = preferences.getLong("changed", 0);
        if (!WallpaperRotation.due(changed, System.currentTimeMillis(), every)) return;
        String useOrder = WallpaperRotation.validOrder(poolOrder) ? poolOrder : order;
        File next = WallpaperRotation.pick(images, previous, useOrder, random);
        if (next == null || next.getName().equals(previous)) return;
        if (WallpaperRotation.find(files, next.getName()) == null) files.add(next);
        beginShow(next, previous);
    }

    private void beginShow(File next, String previous) {
        loading = true;
        decoder.execute(() -> {
            Bitmap bitmap = decode(next);
            root.post(() -> {
                loading = false;
                if (closed || !active) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) {
                    files.remove(next);
                    Log.w("TvHome.Wallpaper", "Skipping unreadable image: " + next.getName());
                    update();
                    return;
                }
                show(bitmap, fadeChanges);
                displayed = next.getName();
                showCaption(true);
                if (!displayed.equals(previous) || preferences.getLong("changed", 0) == 0) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
            });
        });
    }

    /**
     * Puts a different picture on screen immediately. The choice is random and skips the one already showing,
     * whatever playback order the automatic rotation is using. The interval starts again from this picture.
     */
    void showAnother(List<File> pool) {
        if (closed || loading) return;
        List<File> images = new ArrayList<>();
        if (pool != null) images.addAll(pool);
        for (File file : files) {
            if (WallpaperRotation.find(images, file.getName()) == null) images.add(file);
        }
        String previous = displayed.isEmpty() ? preferences.getString("image", "") : displayed;
        File next = WallpaperRotation.pick(images, previous, random);
        if (next == null || next.getName().equals(previous)) return;
        if (WallpaperRotation.find(files, next.getName()) == null) files.add(next);
        if (image.getParent() == null) {
            root.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        loading = true;
        decoder.execute(() -> {
            Bitmap bitmap = decode(next);
            root.post(() -> {
                loading = false;
                if (closed || !active) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) {
                    files.remove(next);
                    Log.w("TvHome.Wallpaper", "Skipping unreadable image: " + next.getName());
                    return;
                }
                show(bitmap, fadeChanges);
                root.setBackground(null);
                displayed = next.getName();
                showCaption(true);
                long now = System.currentTimeMillis();
                preferences.edit().putString("image", displayed).putLong("changed", now).commit();
                root.getContext().getSharedPreferences("local_wallpaper", Context.MODE_PRIVATE).edit()
                        .putString("picked_name", displayed).putLong("picked_at", now).commit();
            });
        });
    }

    /**
     * Small light text under the top-right buttons, above everything else and never focusable. It sits where the
     * top bar ends in every layout, so it does not cover the clock, the stats line or the tiles.
     */
    private static TextView makeCaption(Context context, FrameLayout root) {
        float dp = context.getResources().getDisplayMetrics().density;
        TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setTextColor(0xD9FFFFFF);
        text.setShadowLayer(6 * dp, 0, dp, 0xCC000000);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setMaxWidth(Math.round(520 * dp));
        text.setFocusable(false);
        text.setAlpha(0f);
        try {
            text.setTypeface(UiFont.get(context, R.string.font_regular));
        } catch (Exception ignored) {
            // the system font is fine
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        params.topMargin = Math.round(110 * dp);
        params.setMarginEnd(Math.round(62 * dp));
        root.addView(text, params);
        return text;
    }

    /** The displayed picture's name, faded across a change; a picture without a name hides the caption. */
    private void showCaption(boolean fade) {
        if (caption == null) return;
        String name = names.get(displayed);
        caption.animate().cancel();
        if (name == null) {
            caption.setAlpha(0f);
            return;
        }
        caption.setContentDescription(name);
        if (!fade || caption.getAlpha() == 0f) {
            caption.setText(name);
            caption.setAlpha(1f);
            return;
        }
        caption.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            caption.setText(name);
            caption.animate().alpha(1f).setDuration(350).start();
        }).start();
    }

    /** Names plus sizes: enough to notice a different collection without hashing every image. */
    private static String collectionKey(List<File> images) {
        StringBuilder key = new StringBuilder();
        for (File file : images) key.append(file.getName()).append(':').append(file.length()).append(';');
        return key.toString();
    }

    /**
     * Puts a picture on screen. A rotation or a tap fades the new 1080p bitmap over the old one for
     * about a second, then drops the outgoing picture so the extra memory is gone.
     * The first frame of Home stays instant: no empty screen waiting on a fade.
     */
    private void show(Bitmap bitmap, boolean fade) {
        if (incoming != null) finishIncoming(true);
        if (!fade || current == null) {
            Bitmap old = current;
            current = bitmap;
            image.setImageBitmap(bitmap);
            refreshGlass();
            if (old != null && old != bitmap) old.recycle();
            return;
        }
        outgoing = current;
        current = bitmap;
        incoming = new ImageView(image.getContext());
        incoming.setScaleType(ImageView.ScaleType.CENTER_CROP);
        incoming.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        incoming.setAlpha(0f);
        incoming.setImageBitmap(bitmap);
        int index = root.indexOfChild(image);
        root.addView(incoming, index < 0 ? 0 : index + 1, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        incoming.animate()
                .alpha(1f)
                .setDuration(FADE_MS)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(() -> finishIncoming(true))
                .start();
    }

    /** Commits the incoming picture onto {@link #image} and frees the layer plus the old bitmap. */
    private void finishIncoming(boolean keepNew) {
        if (incoming != null) {
            incoming.animate().cancel();
            if (keepNew && current != null) {
                image.setImageBitmap(current);
                refreshGlass();
            }
            incoming.setImageBitmap(null);
            if (incoming.getParent() == root) root.removeView(incoming);
            incoming = null;
        }
        if (outgoing != null && outgoing != current) {
            outgoing.recycle();
            outgoing = null;
        }
    }

    /** Glass on Android 11 samples on demand. Tell every panel the picture changed. */
    private void refreshGlass() {
        if (root == null) return;
        root.post(() -> invalidateTree(root));
    }

    private static void invalidateTree(View view) {
        view.invalidate();
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) invalidateTree(group.getChildAt(i));
    }

    private Bitmap decode(File file) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            // Crop to the screen's shape first. A square 6K picture halved is about 9 M pixels, just over
            // the cap, so the next power-of-two sample fell to 1504 px wide and had to be scaled up on a
            // 1920-wide screen. The 16:9 window of that same picture halves to about 3008x1692 and lands
            // on 1920x1080.
            int[] crop = WallpaperSize.coverCrop(width, height, this.width, this.height);
            int cropW = crop[2] - crop[0], cropH = crop[3] - crop[1];
            int sample = WallpaperSize.sampleSize(cropW, cropH, this.width, this.height);
            // The screen-shaped window is what gets decoded. Only step down further when that window
            // itself would pass the memory cap.
            if (!WallpaperSize.accepted(cropW / sample, cropH / sample)) {
                sample = WallpaperSize.displaySample(cropW, cropH, this.width, this.height);
            }
            if (!WallpaperSize.accepted(Math.max(1, cropW / sample), Math.max(1, cropH / sample))) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            options.inPreferredConfig = bitmapConfig;
            options.inMutable = dimColor != 0;
            Rect window = new Rect(crop[0], crop[1], crop[2], crop[3]);
            Bitmap decoded = decodeCrop(file, window, options);
            if (decoded == null) decoded = decodeFullThenCrop(file, window, sample, options);
            if (decoded == null) {
                Log.w("TvHome.Wallpaper", "Could not decode " + file.getName()
                        + " " + width + "x" + height + " sample=" + sample);
                return null;
            }
            if (decoded.getWidth() != this.width || decoded.getHeight() != this.height) {
                Bitmap fitted = Bitmap.createScaledBitmap(decoded, this.width, this.height, true);
                if (fitted != decoded) {
                    decoded.recycle();
                    decoded = fitted;
                }
            }
            if (dimColor != 0) {
                if (!decoded.isMutable()) {
                    Bitmap copy = decoded.copy(bitmapConfig, true);
                    if (copy != null) {
                        decoded.recycle();
                        decoded = copy;
                    }
                }
                if (decoded.isMutable()) new Canvas(decoded).drawColor(dimColor);
            }
            // API 31+ can sample a GPU bitmap. Android 11 draws the glass on a software canvas, and a
            // HARDWARE bitmap throws there; the library then fills the card with milky white.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Bitmap gpu = decoded.copy(Bitmap.Config.HARDWARE, false);
                if (gpu != null) {
                    decoded.recycle();
                    return gpu;
                }
            }
            return decoded;
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }

    /** Decodes only the screen-shaped window. The rest of a square or very wide picture is never expanded. */
    private static Bitmap decodeCrop(File file, Rect crop, BitmapFactory.Options options) {
        BitmapRegionDecoder decoder = null;
        try {
            decoder = BitmapRegionDecoder.newInstance(file.getPath(), false);
            Bitmap bitmap = decoder.decodeRegion(crop, options);
            if (bitmap != null) {
                Log.i("TvHome.Wallpaper", "Region " + bitmap.getWidth() + "x" + bitmap.getHeight());
            }
            return bitmap;
        } catch (Exception failure) {
            Log.w("TvHome.Wallpaper", "Region decode failed: " + failure.getClass().getSimpleName());
            return null;
        } finally {
            if (decoder != null) decoder.recycle();
        }
    }

    /** Some boards refuse a region decode. Sample the whole file, then cut the same window. */
    private static Bitmap decodeFullThenCrop(File file, Rect crop, int sample, BitmapFactory.Options options) {
        BitmapFactory.Options full = new BitmapFactory.Options();
        full.inSampleSize = sample;
        full.inPreferredConfig = options.inPreferredConfig;
        full.inMutable = options.inMutable;
        Bitmap entire = BitmapFactory.decodeFile(file.getPath(), full);
        if (entire == null) return null;
        int left = crop.left / sample;
        int top = crop.top / sample;
        int right = Math.min(entire.getWidth(), crop.right / sample);
        int bottom = Math.min(entire.getHeight(), crop.bottom / sample);
        if (right <= left || bottom <= top) {
            entire.recycle();
            return null;
        }
        Bitmap cut = Bitmap.createBitmap(entire, left, top, right - left, bottom - top);
        if (cut != entire) entire.recycle();
        Log.i("TvHome.Wallpaper", "Full-crop " + cut.getWidth() + "x" + cut.getHeight());
        return cut;
    }
}
