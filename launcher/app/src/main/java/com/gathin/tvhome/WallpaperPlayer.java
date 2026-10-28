package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
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

    private final Config config;
    private final String latitude, longitude;
    private final java.util.Map<String,String> captions;
    private final android.widget.TextView caption;
    private final FrameLayout root;
    private final ImageView image;
    private final Resources resources;
    private final List<File> files;
    /** Launcher settings chose Aurora: one bundled picture, no rotation. */
    private final boolean aurora;
    private final long interval;
    private final String order;
    private final Bitmap.Config bitmapConfig;
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
    private final int backgroundColor, dimColor;
    private View mirror;
    private Runnable onChanged;

    WallpaperPlayer(Context context, FrameLayout root, Config config) {
        this.root = root;
        this.config=config;
        android.content.SharedPreferences features=FeaturePreferences.prefs(context);
        latitude=features.getString("latitude",context.getString(R.string.latitude));
        longitude=features.getString("longitude",context.getString(R.string.longitude));
        resources = context.getResources();
        aurora = Config.WALLPAPER_AURORA.equals(config.wallpaperChoice);
        backgroundColor = config.backgroundColor;
        dimColor = !aurora && config.wallpaperEnabled ? (config.wallpaperDim * 255 / 100) << 24 : 0;
        files = aurora ? new ArrayList<>() : new ArrayList<>(config.wallpapers);
        captions=config.wallpaperCaptions && !files.isEmpty()
                ? WallpaperCaptions.read(files.get(0).getParentFile()) : java.util.Collections.emptyMap();
        if(!captions.isEmpty()) {
            caption=new android.widget.TextView(context);caption.setTextColor(0x99FFFFFF);caption.setTextSize(12);
            caption.setTypeface(UiFont.get(context,R.string.font_regular));caption.setSingleLine(true);
            caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
            FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-2,-2,android.view.Gravity.TOP|android.view.Gravity.END);
            float density=context.getResources().getDisplayMetrics().density;
            lp.topMargin=Math.round(90*density);lp.setMarginEnd(Math.round(64*density));root.addView(caption,lp);
        } else caption=null;
        interval = config.wallpaperRotationMinutes * 60_000L;
        order = config.wallpaperOrder;
        bitmapConfig = config.wallpaperTrueColor ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        // Aurora and Off must not discard the rotating theme's saved position.
        if (!files.isEmpty()) {
            String collection = collectionKey(files);
            if (!collection.equals(preferences.getString("collection", "")))
                preferences.edit().putString("collection", collection).remove("image").remove("changed").apply();
        }
        DisplayMetrics metrics = resources.getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (!aurora && (!config.wallpaperEnabled || files.isEmpty())) return;
        root.addView(image, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (aurora) {
            first = CompletableFuture.supplyAsync(() -> decode(null), decoder);
        } else {
            String previous = preferences.getString("image", "");
            firstFile = WallpaperRotation.find(activeFiles(), previous);
            if (firstFile == null) firstFile = WallpaperRotation.pick(activeFiles(), previous, order, random);
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
        if(aurora && first==null && image.getDrawable()==null)
            first=CompletableFuture.supplyAsync(() -> decode(null),decoder);
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
        File selected = WallpaperRotation.find(activeFiles(), previous);
        long changed = preferences.getLong("changed", 0);
        if (selected == null || WallpaperRotation.due(changed, System.currentTimeMillis(), interval)) {
            selected = WallpaperRotation.pick(activeFiles(), previous, order, random);
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
        if(caption!=null) {String name=captions.get(displayed);caption.setText(name);caption.setVisibility(name==null?View.GONE:View.VISIBLE);}
        if (force || !displayed.equals(previous) || preferences.getLong("changed",0)==0) {
            preferences.edit().putString("image", displayed)
                    .putLong("changed", System.currentTimeMillis()).apply();
        }
    }

    private void show(Bitmap bitmap) {
        // The previous picture is left to the garbage collector: the render thread may still draw it this frame.
        image.setImageBitmap(bitmap);
        if (mirror != null) mirror.invalidate();
        if (onChanged != null) onChanged.run();
        // CENTER_CROP covers the whole screen, so the colour under an opaque picture would be one more
        // full-screen pass every frame. A picture with transparency keeps the colour behind it.
        if (!bitmap.hasAlpha()) root.setBackground(null);
    }

    private List<File> activeFiles() {
        if(config.wallpaperMoods.isEmpty()) return files;
        java.time.LocalDateTime now=java.time.LocalDateTime.now();
        int[] sun=WallpaperMood.sun(now.toLocalDate(),latitude,longitude,java.time.ZoneId.systemDefault());
        return WallpaperMood.select(files,config.wallpaperMoods,
                WallpaperMood.timeOfDay(now.getHour()*60+now.getMinute(),sun[0],sun[1]));
    }

    void releaseMemory() {
        if(active) return;
        image.setImageDrawable(null);displayed="";root.setBackgroundColor(backgroundColor);
        if(mirror!=null)mirror.invalidate();
    }

    void setMirror(View view) { mirror = view; }
    void setOnChanged(Runnable callback) { onChanged = callback; }

    /** Draw the same image matrix and shade, without another decode, bitmap or Home tile snapshot. */
    void drawBackground(Canvas canvas) {
        canvas.drawColor(backgroundColor);
        if (image.getParent() == root) {
            image.draw(canvas);

        }
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
            opts.inPreferredConfig = file == null ? Bitmap.Config.ARGB_8888 : bitmapConfig;
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
            if(dimColor!=0){
                if(!bmp.isMutable()){Bitmap mutable=bmp.copy(bitmapConfig,true);if(mutable==null)return bmp;bmp.recycle();bmp=mutable;}
                new Canvas(bmp).drawColor(dimColor);
            }
            bmp.prepareToDraw();
            return bmp;
        } catch (Exception | OutOfMemoryError e) {
            // A bad or huge picture must never take the home screen down; the plain colour stays.
            return null;
        }
    }

    private static String collectionKey(List<File> images) {
        StringBuilder key = new StringBuilder();
        for (File file : images) key.append(file.getName()).append(':').append(file.length()).append(';');
        return key.toString();
    }
}
