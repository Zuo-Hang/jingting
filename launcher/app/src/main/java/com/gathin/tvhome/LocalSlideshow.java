package com.gathin.tvhome;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.provider.MediaStore;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Home wallpaper taken from pictures the TV's media library has already scanned. */
final class LocalSlideshow {
    static final String PREFS = "local_wallpaper";
    static final String KEY_MINUTES = "slideshow_minutes";
    static final String KEY_ORDER = "slideshow_order";
    /** Off, then the intervals the menu offers, all inside 2 to 30 minutes. */
    static final int[] MINUTES = {0, 2, 5, 10, 15, 20, 30};
    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final int MAX_PICTURES = 120;

    private LocalSlideshow() {}

    static int minutes(SharedPreferences prefs) {
        int minutes = prefs.getInt(KEY_MINUTES, 0);
        for (int allowed : MINUTES) if (allowed == minutes) return minutes;
        return 0;
    }

    static String order(SharedPreferences prefs) {
        String order = prefs.getString(KEY_ORDER, WallpaperRotation.ORDER_SEQUENTIAL);
        return WallpaperRotation.validOrder(order) ? order : WallpaperRotation.ORDER_SEQUENTIAL;
    }

    static void save(Context context, int minutes, String order) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_MINUTES, minutes(minutes))
                .putString(KEY_ORDER, WallpaperRotation.validOrder(order) ? order : WallpaperRotation.ORDER_SEQUENTIAL)
                .apply();
        SharedPreferences settings = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        settings.edit().putLong(Config.SETTINGS_REVISION_KEY,
                settings.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    private static int minutes(int minutes) {
        for (int allowed : MINUTES) if (allowed == minutes) return minutes;
        return 0;
    }

    /** Pictures the media library can already open, in filename order, skipping anything over 20 MB. */
    static List<File> pictures(Context context) {
        List<File> found = new ArrayList<>();
        if (context.checkSelfPermission(storagePermission()) != PackageManager.PERMISSION_GRANTED) return found;
        String[] projection = {MediaStore.Images.Media.DATA, MediaStore.Images.Media.SIZE};
        String selection = MediaStore.Images.Media.SIZE + " > 0 AND " + MediaStore.Images.Media.SIZE + " <= " + MAX_BYTES;
        try (Cursor cursor = context.getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, null,
                MediaStore.Images.Media.DISPLAY_NAME + " ASC")) {
            if (cursor == null) return found;
            int dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
            while (cursor.moveToNext() && found.size() < MAX_PICTURES) {
                String path = cursor.getString(dataColumn);
                if (path == null || path.isEmpty()) continue;
                File file = new File(path);
                if (!file.isFile() || file.length() <= 0 || file.length() > MAX_BYTES) continue;
                found.add(file);
            }
        } catch (RuntimeException ignored) {
            found.clear();
        }
        return found;
    }

    private static String storagePermission() {
        return Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }
}
