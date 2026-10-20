package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The picture behind Home: the theme's wallpaper, a theme's rotating collection (wallpaper_rotation_minutes), or
 * the bundled Aurora picture chosen in Launcher settings. The first picture decodes while the tiles load and is on
 * screen for Home's first frame; later changes of a rotation decode in the background while Home is visible.
 */
final class WallpaperPlayer {
    private static final String PREFERENCES = "wallpaper_rotation";

    private final FrameLayout root;
    private final ImageView image;
    private final Resources resources;
    private final List<File> files;
    /** Launcher settings chose Aurora: one bundled picture, no rotation. */
    private final boolean aurora;
    private final long interval;
    private final SharedPreferences preferences;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final int width;
    private final int height;
    /** The opening picture while it decodes, from the constructor until the first start() shows it. */
    private CompletableFuture<Bitmap> first;
    private File firstFile;
    private boolean active;
    private boolean closed;
    private boolean loading;
    private String displayed = "";

    WallpaperPlayer(Context context, FrameLayout root, Config config) {
        this.root = root;
        resources = context.getResources();
        aurora = Config.WALLPAPER_AURORA.equals(config.wallpaperChoice);
        files = aurora ? new ArrayList<>() : new ArrayList<>(config.wallpapers);
        interval = config.wallpaperRotationMinutes * 60_000L;
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        DisplayMetrics metrics = resources.getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (!aurora && (!config.wallpaperEnabled || files.isEmpty())) return;
        root.addView(image, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // No shade layer at all when the theme doesn't dim: a full-screen view is overdraw on 1 GB TVs.
        if (!aurora && config.wallpaperDim > 0) {
            View shade = new View(context);
            shade.setBackgroundColor((config.wallpaperDim * 255 / 100) << 24);
            shade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            root.addView(shade, 1, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        if (aurora) {
            first = CompletableFuture.supplyAsync(() -> decode(null), decoder);
        } else {
            String previous = preferences.getString("image", "");
            firstFile = WallpaperRotation.find(files, previous);
            if (firstFile == null) firstFile = WallpaperRotation.pick(files, previous, random);
            File opening = firstFile;
            first = CompletableFuture.supplyAsync(() -> decode(opening), decoder);
        }
    }

    /** The theme picture on screen now (the rotation's current one), for Launcher settings' preview. */
    static File current(Context context, Config config) {
        if (config.wallpapers.isEmpty()) return null;
        String name = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString("image", "");
        File shown = WallpaperRotation.find(config.wallpapers, name);
        return shown != null ? shown : config.wallpapers.get(0);
    }

    /** Home is visible: the opening picture goes up (waiting for its decode), then a due rotation runs. */
    void start() {
        active = true;
        if (first != null) {
            Bitmap bitmap = null;
            try {
                bitmap = first.join();
            } catch (RuntimeException ignored) {
                // Never thrown by decode itself; the plain colour stays.
            }
            first = null;
            if (bitmap != null) {
                show(bitmap);
                if (firstFile != null) remember(firstFile, preferences.getString("image", ""), false);
            } else if (firstFile != null) {
                files.remove(firstFile);   // unreadable or over 4K: update() tries another
            }
            firstFile = null;
        }
        update();
    }

    void stop() {
        active = false;
    }

    void close() {
        active = false;
        closed = true;
        decoder.shutdownNow();
    }

    void update() {
        if (aurora || !active || closed || loading || first != null || files.isEmpty()) return;
        String previous = preferences.getString("image", "");
        File selected = WallpaperRotation.find(files, previous);
        long changed = preferences.getLong("changed", 0);
        if (selected == null || WallpaperRotation.due(changed, System.currentTimeMillis(), interval)) {
            selected = WallpaperRotation.pick(files, previous, random);
        }
        if (selected == null || selected.getName().equals(displayed)) return;
        File next = selected;
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
                show(bitmap);
                remember(next, previous, changed == 0);
            });
        });
    }

    private void remember(File shown, String previous, boolean force) {
        displayed = shown.getName();
        if (force || !displayed.equals(previous)) {
            preferences.edit().putString("image", displayed)
                    .putLong("changed", System.currentTimeMillis()).apply();
        }
    }

    private void show(Bitmap bitmap) {
        // The previous picture is left to the garbage collector: the render thread may still draw it this frame.
        image.setImageBitmap(bitmap);
        // CENTER_CROP covers the whole screen, so the colour under an opaque picture would be one more
        // full-screen pass every frame. A picture with transparency keeps the colour behind it.
        if (!bitmap.hasAlpha()) root.setBackground(null);
    }

    /** A null file selects the bundled Aurora picture. */
    private InputStream open(File file) throws IOException {
        return file != null ? new FileInputStream(file) : resources.openRawResource(R.raw.aurora_wallpaper);
    }

    /**
     * Only the centre that CENTER_CROP shows is decoded, halved while it still covers the screen and then scaled
     * to it, so any picture, however wide or tall, costs a screen-sized bitmap (about 4 MB at 1080p), which is the
     * price of a wallpaper on a 1 GB TV. A picture whose decoded part would still be over 4K is refused (#166).
     */
    private Bitmap decode(File file) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            try (InputStream in = open(file)) {
                BitmapFactory.decodeStream(in, null, opts);
            }
            int w = opts.outWidth, h = opts.outHeight;
            if (w <= 0 || h <= 0 || width <= 0 || height <= 0) return null;
            // The centred part with the screen's aspect ratio: all that CENTER_CROP would show.
            Rect crop;
            if ((long) w * height > (long) h * width) {
                int cw = (int) Math.max(1, (long) h * width / height);
                crop = new Rect((w - cw) / 2, 0, (w - cw) / 2 + cw, h);
            } else {
                int ch = (int) Math.max(1, (long) w * height / width);
                crop = new Rect(0, (h - ch) / 2, w, (h - ch) / 2 + ch);
            }
            int sample = WallpaperSize.sampleSize(crop.width(), crop.height(), width, height);
            if (!WallpaperSize.accepted(crop.width() / sample, crop.height() / sample)) return null;
            opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            // The bundled 720p gradient needs full colour to avoid bands; it still uses less memory
            // than a 1080p RGB_565 theme picture. External pictures retain the low-memory decode.
            opts.inPreferredConfig = file == null ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
            Bitmap bmp;
            try (InputStream in = open(file)) {
                @SuppressWarnings("deprecation")   // newInstance(InputStream) is API 31
                BitmapRegionDecoder region = BitmapRegionDecoder.newInstance(in, false);
                try {
                    bmp = region.decodeRegion(crop, opts);
                } finally {
                    region.recycle();
                }
            } catch (IOException unsupported) {
                // A format the region decoder cannot read (GIF, BMP): whole picture, but never a huge one.
                if (!WallpaperSize.accepted(w / sample, h / sample)) return null;
                try (InputStream in = open(file)) {
                    bmp = BitmapFactory.decodeStream(in, null, opts);
                }
            }
            if (bmp == null) return null;
            if (bmp.getWidth() > width && bmp.getHeight() > height) {
                // Down to the smallest size that still covers the screen; CENTER_CROP does the rest.
                float scale = Math.max(width / (float) bmp.getWidth(), height / (float) bmp.getHeight());
                Bitmap fitted = Bitmap.createScaledBitmap(bmp,
                        Math.max(1, Math.round(bmp.getWidth() * scale)), Math.max(1, Math.round(bmp.getHeight() * scale)), true);
                if (fitted != bmp) bmp.recycle();
                bmp = fitted;
            }
            return bmp;
        } catch (Exception | OutOfMemoryError e) {
            // A bad or huge picture must never take the home screen down; the plain colour stays.
            return null;
        }
    }
}
