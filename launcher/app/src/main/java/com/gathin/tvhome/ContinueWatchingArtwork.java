package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Small poster thumbnails only; no video, credentials, full-size images or persistent history cache. */
final class ContinueWatchingArtwork {
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(2, 2, 15, TimeUnit.SECONDS, new LinkedBlockingQueue<>(32), new ThreadPoolExecutor.DiscardPolicy());
    static { WORKER.allowCoreThreadTimeOut(true); }
    private static String key(ContinueWatchingRepository.Item item) { return item.pkg + '\n' + item.artwork; }
    static Bitmap cached(ContinueWatchingRepository.Item item) { return CACHE.get(key(item)); }
    static void load(Context context, ContinueWatchingRepository.Item item, Consumer<Bitmap> ready) {
        Bitmap saved = cached(item);
        if (saved != null) { ready.accept(saved); return; }
        if (item.artwork.isEmpty()) { ready.accept(null); return; }
        Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            Bitmap bitmap = cached(item);
            if (bitmap == null) bitmap = read(app, item);
            if (bitmap != null) CACHE.put(key(item), bitmap);
            Bitmap result = bitmap; new Handler(Looper.getMainLooper()).post(() -> ready.accept(result));
        });
    }
    private static Bitmap read(Context context, ContinueWatchingRepository.Item item) {
        HttpURLConnection connection = null;
        try {
            Uri uri = Uri.parse(item.artwork); String scheme = uri.getScheme();
            InputStream input;
            if ("content".equals(scheme) || "android.resource".equals(scheme)) {
                if ("content".equals(scheme)) {
                    android.content.pm.ProviderInfo provider = context.getPackageManager().resolveContentProvider(uri.getAuthority(), 0);
                    if (provider == null || !item.pkg.equals(provider.packageName)) return null;
                } else if (!item.pkg.equals(uri.getAuthority())) return null;
                input = context.getContentResolver().openInputStream(uri);
            } else if ("https".equals(scheme)) {
                URL url = new URL(item.artwork);
                for (int redirect = 0; ; redirect++) {
                    if (!url.getProtocol().equals("https") || url.getUserInfo() != null) return null;
                    connection = (HttpURLConnection) url.openConnection(); connection.setInstanceFollowRedirects(false);
                    connection.setConnectTimeout(4000); connection.setReadTimeout(4000);
                    connection.setRequestProperty("User-Agent", "CleanYourTV");
                    int response = connection.getResponseCode();
                    if (response == 200) break;
                    if (response < 300 || response > 399 || redirect >= 2) return null;
                    String location = connection.getHeaderField("Location"); if (location == null) return null;
                    URL next = new URL(url, location); connection.disconnect(); connection = null; url = next;
                }
                if (connection.getContentLengthLong() > 3 * 1024 * 1024) return null;
                input = connection.getInputStream();
            } else return null;
            byte[] bytes;
            try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                if (in == null) return null;
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > 3 * 1024 * 1024) return null;
                    out.write(buffer, 0, n);
                }
                bytes = out.toByteArray();
            }
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (options.outWidth <= 0 || options.outHeight <= 0 || (long) options.outWidth * options.outHeight > 40_000_000) return null;
            options.inSampleSize = 1;
            while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 512) options.inSampleSize *= 2;
            options.inJustDecodeBounds = false; options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (Exception | OutOfMemoryError ignored) { return null; }
        finally { if (connection != null) connection.disconnect(); }
    }
}
