package com.gathin.tvhome;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.provider.MediaStore;
import android.util.Size;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lists pictures the TV's media library already has, then hands the chosen one to WallpaperImportActivity. */
public final class WallpaperPickerActivity extends Activity {
    private static final int PERMISSION_REQUEST = 44;
    private static final int MAX_PICTURES = 120;
    private static final long MAX_BYTES = 20L * 1024 * 1024;

    private final ExecutorService images = Executors.newFixedThreadPool(2);
    private RecyclerView grid;
    private TextView message;
    private TextView allow;
    private PictureAdapter adapter;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_wallpaper_picker);
        grid = findViewById(R.id.grid);
        message = findViewById(R.id.message);
        allow = findViewById(R.id.allow);
        grid.setLayoutManager(new GridLayoutManager(this, 4));
        adapter = new PictureAdapter();
        grid.setAdapter(adapter);
        allow.setOnClickListener(v -> requestPermissions(new String[]{storagePermission()}, PERMISSION_REQUEST));
        if (canRead()) loadPictures();
        else {
            showMessage(getString(R.string.wallpaper_picker_permission), true);
            requestPermissions(new String[]{storagePermission()}, PERMISSION_REQUEST);
        }
    }

    @Override protected void onDestroy() {
        images.shutdownNow();
        super.onDestroy();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != PERMISSION_REQUEST) return;
        if (canRead()) loadPictures();
        else showMessage(getString(R.string.wallpaper_picker_denied), true);
    }

    private boolean canRead() {
        return checkSelfPermission(storagePermission()) == PackageManager.PERMISSION_GRANTED;
    }

    /** Android 13 renamed the picture permission. This TV is Android 11, so it uses the older one. */
    private static String storagePermission() {
        return Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private void showMessage(String text, boolean showAllow) {
        grid.setVisibility(View.GONE);
        message.setVisibility(View.VISIBLE);
        message.setText(text);
        allow.setVisibility(showAllow ? View.VISIBLE : View.GONE);
        if (showAllow) allow.requestFocus();
    }

    private void loadPictures() {
        allow.setVisibility(View.GONE);
        message.setVisibility(View.GONE);
        grid.setVisibility(View.VISIBLE);
        images.execute(() -> {
            List<Picture> found = queryPictures();
            if (isDestroyed()) return;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (found.isEmpty()) {
                    showMessage(getString(R.string.wallpaper_picker_empty), false);
                    return;
                }
                adapter.setPictures(found);
                grid.post(() -> {
                    View first = grid.getLayoutManager() == null ? null
                            : grid.getLayoutManager().findViewByPosition(0);
                    if (first != null) first.requestFocus();
                    else grid.requestFocus();
                });
            });
        });
    }

    private List<Picture> queryPictures() {
        List<Picture> found = new ArrayList<>();
        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE
        };
        Bundle args = new Bundle();
        args.putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                MediaStore.Images.Media.DATE_ADDED + " DESC");
        args.putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, MAX_PICTURES);
        try (Cursor cursor = getContentResolver().query(collection, projection, args, null)) {
            if (cursor == null) return found;
            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
            int sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE);
            while (cursor.moveToNext()) {
                long size = cursor.isNull(sizeColumn) ? 0 : cursor.getLong(sizeColumn);
                if (size > MAX_BYTES) continue;
                long id = cursor.getLong(idColumn);
                String name = cursor.getString(nameColumn);
                found.add(new Picture(ContentUris.withAppendedId(collection, id),
                        name == null || name.isEmpty() ? "图片" : name));
            }
        } catch (RuntimeException ignored) {
            return found;
        }
        return found;
    }

    private void choose(Picture picture) {
        Intent apply = new Intent(this, WallpaperImportActivity.class).setData(picture.uri);
        apply.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        apply.setClipData(android.content.ClipData.newRawUri("Selected wallpaper", picture.uri));
        startActivity(apply);
        finish();
    }

    private Bitmap thumbnail(Uri uri) {
        if (Build.VERSION.SDK_INT < 29) return null;
        try {
            return getContentResolver().loadThumbnail(uri, new Size(360, 202), new CancellationSignal());
        } catch (Exception e) {
            return null;
        }
    }

    private static final class Picture {
        final Uri uri;
        final String name;
        Picture(Uri uri, String name) { this.uri = uri; this.name = name; }
    }

    private final class PictureAdapter extends RecyclerView.Adapter<PictureAdapter.Holder> {
        private final List<Picture> pictures = new ArrayList<>();

        void setPictures(List<Picture> next) {
            pictures.clear();
            pictures.addAll(next);
            notifyDataSetChanged();
        }

        @Override public Holder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            View view = getLayoutInflater().inflate(R.layout.item_wallpaper, parent, false);
            return new Holder(view);
        }

        @Override public void onBindViewHolder(Holder holder, int position) {
            Picture picture = pictures.get(position);
            holder.name.setText(picture.name);
            holder.image.setImageDrawable(null);
            holder.image.setTag(picture.uri);
            holder.itemView.setOnClickListener(v -> choose(picture));
            images.execute(() -> {
                Bitmap bitmap = thumbnail(picture.uri);
                holder.image.post(() -> {
                    if (picture.uri.equals(holder.image.getTag()) && !isDestroyed()) {
                        holder.image.setImageBitmap(bitmap);
                    }
                });
            });
        }

        @Override public int getItemCount() { return pictures.size(); }

        final class Holder extends RecyclerView.ViewHolder {
            final ImageView image;
            final TextView name;
            Holder(View view) {
                super(view);
                image = view.findViewById(R.id.image);
                name = view.findViewById(R.id.name);
            }
        }
    }
}
