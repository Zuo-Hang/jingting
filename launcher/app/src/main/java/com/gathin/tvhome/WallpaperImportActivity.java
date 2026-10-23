package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.AtomicFile;
import android.util.Log;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.security.MessageDigest;

/** Accepts an image read grant, never arbitrary paths. External callers require our signature. */
public final class WallpaperImportActivity extends Activity {
    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        status = new TextView(this);
        status.setTextSize(24);
        status.setPadding(48, 48, 48, 48);
        status.setText("正在应用背景图片……");
        setContentView(status);
        new Thread(() -> {
            try {
                apply(this, getIntent().getData());
                runOnUiThread(() -> {
                    startActivity(new Intent(this, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                    finish();
                });
            } catch (Exception e) {
                Log.w("LocalWallpaper", "Import failed: " + e.getClass().getSimpleName());
                runOnUiThread(() -> status.setText("背景未应用：" + e.getMessage()));
            }
        }, "local-wallpaper-import").start();
    }

    private static void atomicCopy(InputStream input, File target) throws IOException {
        AtomicFile atomic = new AtomicFile(target);
        FileOutputStream output = atomic.startWrite();
        try {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            atomic.finishWrite(output);
        } catch (IOException | RuntimeException e) {
            atomic.failWrite(output);
            throw e;
        }
    }

    private static synchronized void apply(Context context, Uri uri) throws Exception {
        if (uri == null || !"content".equals(uri.getScheme())) throw new IOException("需要授权的图片内容链接");
        File staged = new File(context.getCacheDir(), "wallpaper-import.tmp");
        File target = new File(context.getFilesDir(), "imported-wallpaper.jpg");
        SharedPreferences prefs = context.getSharedPreferences("local_wallpaper", MODE_PRIVATE);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream input = context.getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(staged)) {
                if (input == null) throw new IOException("无法读取所选图片");
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_BYTES) throw new IOException("图片大于20MB");
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                }
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(staged.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                    || (long) bounds.outWidth * bounds.outHeight > 64_000_000L)
                throw new IOException("无法识别图片，或图片尺寸过大");
            try (InputStream input = new FileInputStream(staged)) { atomicCopy(input, target); }
            if (!prefs.edit().putBoolean("enabled", true).commit()) throw new IOException("无法保存背景配置");
            // Slideshow otherwise keeps the picture it was already on. Point it at this file and
            // restart the interval, so confirming a picture shows that picture immediately.
            String picked = mediaFileName(context, uri);
            if (picked != null) {
                long now = System.currentTimeMillis();
                if (!prefs.edit().putString("picked_name", picked).putLong("picked_at", now).commit())
                    throw new IOException("无法保存当前背景");
                context.getSharedPreferences("wallpaper_rotation", MODE_PRIVATE).edit()
                        .putString("image", picked).putLong("changed", now).commit();
            }
            StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            Log.i("LocalWallpaper", "Applied SHA256=" + hex + " dimensions=" + bounds.outWidth + "x" + bounds.outHeight);
        } finally {
            staged.delete();
        }
    }

    /** Filename of a media-library picture, matching the file the slideshow list uses. */
    private static String mediaFileName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{
                MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            String data = cursor.getString(0);
            if (data != null && !data.isEmpty()) {
                String name = new File(data).getName();
                if (!name.isEmpty()) return name;
            }
            String display = cursor.getString(1);
            return display == null || display.isEmpty() ? null : display;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

}
