package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A read-only miniature Home, fed by Home's real layout, app artwork and current wallpaper. */
public final class HomePreviewView extends View {
    static final String DOCK_PACKAGE = "preview_dock_package";
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path clip = new Path();
    private final RectF bounds = new RectF();
    private final SkyView sky;
    private Config config;
    private Bitmap wallpaper;
    private List<Tile> tiles = Collections.emptyList();
    private Tile app;
    private String appPackage, artworkKey, wallpaperKey;
    private int artworkRequest, wallpaperRequest;
    private int continueRequest;
    private long continueLoadedAt;
    private List<ContinueWatchingRepository.Item> continueItems = Collections.emptyList();
    private List<ContinueWatchingRepository.Item> continueCandidates = Collections.emptyList();
    private String dockPackage;
    private final Runnable tick = () -> { if (isAttachedToWindow()) refresh(Config.load(getContext()), appPackage); };

    private static final class Tile {
        final String label;
        final Bitmap image;
        final boolean banner;
        Tile(AppAdapter.Entry entry, int size) {
            label = entry.label; banner = entry.banner != null;
            Drawable source = banner ? entry.banner : entry.icon;
            if (source == null) { image = null; return; }
            image = Bitmap.createBitmap(size, banner ? size * 3 / 5 : size, Bitmap.Config.ARGB_8888);
            Drawable drawable = source.getConstantState() == null ? source : source.getConstantState().newDrawable().mutate();
            android.graphics.Rect previous = new android.graphics.Rect(drawable.getBounds());
            drawable.setBounds(0, 0, image.getWidth(), image.getHeight()); drawable.draw(new Canvas(image)); drawable.setBounds(previous);
        }
    }

    public HomePreviewView(Context context, AttributeSet attrs) {
        super(context, attrs); sky = new SkyView(context, null); setWillNotDraw(false);
        paint.setTypeface(getResources().getFont(R.font.outfit_regular));
    }

    void setDockPackage(String pkg) {
        dockPackage = pkg;
        continueItems = ContinueWatchingRepository.forPackage(continueCandidates, dockPackage); invalidate();
    }

    void refresh(Config current, String selectedApp) {
        if (!java.util.Objects.equals(appPackage, selectedApp)) app = null;
        config = current; appPackage = selectedApp; sky.setIntensity(current.skyIntensity);
        if (!current.continueWatching || !ContinueWatchingRepository.permitted(getContext())) {
            continueRequest++; continueCandidates = continueItems = Collections.emptyList(); continueLoadedAt = 0;
        } else if (System.currentTimeMillis() - continueLoadedAt > 15_000) {
            continueLoadedAt = System.currentTimeMillis(); int token = ++continueRequest;
            Context appContext = getContext().getApplicationContext();
            LOADER.execute(() -> {
                ContinueWatchingRepository.Result result = ContinueWatchingRepository.loadForHome(appContext, current.hiddenApps, null);
                post(() -> {
                    if (token != continueRequest) return;
                    continueCandidates = result.items;
                    continueItems = ContinueWatchingRepository.forPackage(continueCandidates, dockPackage); invalidate();
                    for (int i = 0; i < Math.min(4, continueItems.size()); i++)
                        ContinueWatchingArtwork.load(appContext, continueItems.get(i), bitmap -> invalidate());
                });
            });
        }
        File picture = Config.WALLPAPER_OFF.equals(current.wallpaperChoice) || !current.wallpaperEnabled
                ? null : WallpaperPlayer.current(getContext(), current);
        boolean aurora = Config.WALLPAPER_AURORA.equals(current.wallpaperChoice);
        String nextWallpaper = aurora ? Config.WALLPAPER_AURORA : picture == null ? "off"
                : picture.getPath() + ':' + picture.lastModified();
        nextWallpaper += ':' + String.valueOf(current.wallpaperTrueColor);
        if (!nextWallpaper.equals(wallpaperKey)) {
            wallpaperKey = nextWallpaper; int token = ++wallpaperRequest;
            if (picture == null && !aurora) wallpaper = null;
            else LOADER.execute(() -> {
                Bitmap bitmap = loadWallpaper(picture, aurora, current.wallpaperTrueColor);
                post(() -> { if (token == wallpaperRequest) { wallpaper = bitmap; invalidate(); } else if (bitmap != null) bitmap.recycle(); });
            });
        }
        String nextArtwork = current.appBanners + "|" + current.appLabels + "|" + current.dockApps + "|" + current.hiddenApps
                + "|" + selectedApp + "|" + current.moduleCards + "|" + new File(getContext().getFilesDir(), "home-layout-v2.json").lastModified();
        // Replacing an imported image leaves its preference value ("custom") unchanged.
        for (String pkg : current.appBanners.keySet()) if (pkg.matches("[A-Za-z0-9_.]+") && "custom".equals(current.appBanners.get(pkg)))
            nextArtwork += '|' + pkg + ':' + AppArtwork.file(getContext(), pkg).lastModified();
        if (!nextArtwork.equals(artworkKey)) {
            artworkKey = nextArtwork; int token = ++artworkRequest;
            Context context = getContext().getApplicationContext();
            LOADER.execute(() -> {
                List<Tile> loaded = new ArrayList<>(); Tile selected = null;
                try {
                    AppAdapter adapter = new AppAdapter(context, null, current.columns, current);
                    List<android.content.pm.ResolveInfo> leanback = context.getPackageManager().queryIntentActivities(
                            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
                    List<Cards.Card> none = Collections.emptyList();
                    List<Cards.Card> cards = current.moduleCards ? CardFeature.run(() -> Cards.discover(context, new java.util.HashSet<>(current.hiddenApps),
                            () -> Cards.load(Cards.storeFile(context)), leanback), none) : none;
                    adapter.reload(CardFeature.run(() -> Cards.shown(cards), none), leanback);
                    List<AppAdapter.Entry> entries = adapter.previewEntries();
                    for (int i = 0; i < Math.min(35, entries.size()); i++) loaded.add(new Tile(entries.get(i), 128));
                    AppAdapter.Entry target = selectedApp == null ? null : adapter.previewApp(selectedApp);
                    if (target != null) selected = new Tile(target, 256);
                } catch (RuntimeException | OutOfMemoryError ignored) { /* A package may disappear during loading. */ }
                Tile result = selected;
                post(() -> { if (token == artworkRequest) { tiles = loaded; app = result; invalidate(); } });
            });
        }
        invalidate(); removeCallbacks(tick);
        if (isAttachedToWindow()) postDelayed(tick, 60_000);
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); if (config != null) refresh(config, appPackage); }
    @Override protected void onDetachedFromWindow() {
        removeCallbacks(tick); artworkRequest++; wallpaperRequest++; artworkKey = wallpaperKey = null;
        continueRequest++; continueLoadedAt = 0;
        super.onDetachedFromWindow();
    }

    private Bitmap loadWallpaper(File picture, boolean aurora, boolean trueColor) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
            if (aurora) try (java.io.InputStream in = getResources().openRawResource(R.raw.aurora_wallpaper)) { BitmapFactory.decodeStream(in, null, options); }
            else BitmapFactory.decodeFile(picture.getPath(), options);
            options.inSampleSize = 1;
            while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 640) options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = trueColor ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
            if (aurora) try (java.io.InputStream in = getResources().openRawResource(R.raw.aurora_wallpaper)) { return BitmapFactory.decodeStream(in, null, options); }
            return BitmapFactory.decodeFile(picture.getPath(), options);
        } catch (Exception | OutOfMemoryError ignored) { return null; }
    }

    @Override protected void onDraw(Canvas canvas) {
        if (config == null) return;
        canvas.drawColor(config.backgroundColor);
        if (wallpaper != null) {
            crop(canvas, wallpaper, new RectF(0, 0, getWidth(), getHeight()));
            if (!Config.WALLPAPER_AURORA.equals(config.wallpaperChoice) && config.wallpaperDim > 0)
                canvas.drawColor((config.wallpaperDim * 255 / 100) << 24);
        }
        sky.drawBackdrop(canvas, getWidth(), getHeight());
        canvas.save(); canvas.scale(getWidth() / 960f, getHeight() / 540f);
        if (appPackage != null) {
            if (app != null) { tile(canvas, app, new RectF(300, 158, 660, 374)); text(canvas, app.label, 480, 420, 26, Paint.Align.CENTER, 0xFFFFFFFF); }
        } else {
            text(canvas, android.text.format.DateFormat.getTimeFormat(getContext()).format(new java.util.Date()), 56, 98, 46, Paint.Align.LEFT, Color.WHITE);
            text(canvas, getContext().getString(R.string.settings_preview_home), 58, 42, 12, Paint.Align.LEFT, 0x99FFFFFF);
            rounded(canvas, new RectF(700, 59, 852, 97), 19, 0x33FFFFFF);
            rounded(canvas, new RectF(868, 59, 906, 97), 19, 0x33FFFFFF);
            if (config.showStats) text(canvas, "CPU   RAM   WIFI", 56, 126, 12, Paint.Align.LEFT, 0xB3FFFFFF);
            int width = AppAdapter.tileWidthPx(912, config.columns, 1), height = AppAdapter.tileHeightPx(width);
            int itemHeight = AppAdapter.itemHeightPx(height, 1);
            boolean continuation = !continueItems.isEmpty();
            int rowCount = 1 + config.rowsBelowDock;
            float top = Math.max(140, 510 - itemHeight * rowCount);
            rounded(canvas, new RectF(12, top - 12, 948, top + height + 14), 24, 0x44FFFFFF);
            for (int row = 0; row < rowCount; row++) for (int col = 0; col < config.columns; col++) {
                int index = row * config.columns + col; if (index >= tiles.size()) break;
                float x = 24 + col * (912f / config.columns) + (912f / config.columns - width) / 2;
                float y = top + row * itemHeight;
                tile(canvas, tiles.get(index), new RectF(x, y, x + width, y + height));
                if (config.alwaysShowNames) text(canvas, tiles.get(index).label, x + width / 2f, y + height + 16, 11, Paint.Align.CENTER, 0xCCFFFFFF);
            }
            float shelfBottom = top - 30, shelfHeight = Math.min(228, shelfBottom - 130);
            if (continuation && shelfHeight >= 142) {
                float shelfTop = shelfBottom - shelfHeight, imageHeight = shelfHeight - 90, cardWidth = imageHeight * 16 / 9;
                text(canvas, getContext().getString(R.string.continue_watching), 56, shelfTop + 22, 22, Paint.Align.LEFT, Color.WHITE);
                for (int i = 0; i < Math.min(5, continueItems.size()); i++) {
                    ContinueWatchingRepository.Item item = continueItems.get(i);
                    RectF box = new RectF(56 + i * (cardWidth + 20), shelfTop + 38, 56 + i * (cardWidth + 20) + cardWidth, shelfTop + 38 + imageHeight);
                    if (box.left > 950) break;
                    rounded(canvas, box, 12, 0xFF202630);
                    Bitmap image = ContinueWatchingArtwork.cached(item);
                    if (image != null) { canvas.save(); clip.reset(); clip.addRoundRect(box, 12, 12, Path.Direction.CW); canvas.clipPath(clip); crop(canvas, image, box); canvas.restore(); }
                    paint.setColor(0xFFFFFFFF); canvas.drawRect(box.left + 11, box.bottom - 8, box.left + 11 + (box.width() - 22) * item.progress() / 1000, box.bottom - 6, paint);
                    paint.setTextSize(16);
                    text(canvas, TextUtils.ellipsize(item.title, new android.text.TextPaint(paint), cardWidth, TextUtils.TruncateAt.END).toString(), box.left, box.bottom + 23, 16, Paint.Align.LEFT, Color.WHITE);
                    text(canvas, item.appName, box.left, box.bottom + 42, 12, Paint.Align.LEFT, 0xB3FFFFFF);
                }
            } else if (config.appLibrary && top + itemHeight * rowCount < 522)
                rounded(canvas, new RectF(420, 512, 540, 529), 8, 0x55FFFFFF);
        }
        canvas.restore();
    }

    private void tile(Canvas canvas, Tile tile, RectF rect) {
        rounded(canvas, rect, 12, 0xFF202630);
        if (tile.image == null) return;
        canvas.save(); clip.reset(); clip.addRoundRect(rect, 12, 12, Path.Direction.CW); canvas.clipPath(clip);
        if (tile.banner) crop(canvas, tile.image, rect);
        else {
            float size = rect.height() * .55f;
            bounds.set(rect.centerX() - size / 2, rect.top + rect.height() * .1f, rect.centerX() + size / 2, rect.top + rect.height() * .1f + size);
            paint.setColor(Color.WHITE); canvas.drawBitmap(tile.image, null, bounds, paint);
            paint.setTextSize(rect.height() * .14f); paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(TextUtils.ellipsize(tile.label, new android.text.TextPaint(paint), rect.width() - 12, TextUtils.TruncateAt.END).toString(), rect.centerX(), rect.bottom - rect.height() * .12f, paint);
        }
        canvas.restore();
    }
    private void crop(Canvas canvas, Bitmap bitmap, RectF target) {
        float scale = Math.max(target.width() / bitmap.getWidth(), target.height() / bitmap.getHeight());
        bounds.set(target.centerX() - bitmap.getWidth() * scale / 2, target.centerY() - bitmap.getHeight() * scale / 2,
                target.centerX() + bitmap.getWidth() * scale / 2, target.centerY() + bitmap.getHeight() * scale / 2);
        int save = canvas.save(); canvas.clipRect(target); paint.setColor(Color.WHITE); canvas.drawBitmap(bitmap, null, bounds, paint); canvas.restoreToCount(save);
    }
    private void rounded(Canvas canvas, RectF rect, float radius, int color) { paint.setColor(color); canvas.drawRoundRect(rect, radius, radius, paint); }
    private void text(Canvas canvas, String value, float x, float y, float size, Paint.Align align, int color) {
        paint.setColor(color); paint.setTextSize(size); paint.setTextAlign(align); canvas.drawText(value, x, y, paint);
    }
}
