package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Bounded, private copies of user-picked app banners; no lasting document-provider permission is needed. */
final class AppArtwork {
    static final String APP_ICON_PREFIX = "app_icon:";
    static boolean isAppIcon(String artwork) { return artwork != null && artwork.startsWith(APP_ICON_PREFIX); }
    static Drawable otherAppIcon(Context context, String artwork) {
        if (!isAppIcon(artwork)) return null;
        String pkg = artwork.substring(APP_ICON_PREFIX.length());
        if (!pkg.matches("[A-Za-z0-9_.]+")) return null;
        try { return context.getPackageManager().getApplicationIcon(pkg); }
        catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException ignored) { return null; }
    }
    static File file(Context context, String pkg) {
        if (!pkg.matches("[A-Za-z0-9_.]+")) throw new IllegalArgumentException("Invalid package");
        return new File(new File(context.getFilesDir(), "app-banners"), pkg + ".jpg");
    }
    static void importImage(Context context, String pkg, Uri uri) throws Exception {
        byte[] bytes;
        try (InputStream in = context.getContentResolver().openInputStream(uri); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new java.io.IOException("Image is unavailable");
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > 8 * 1024 * 1024) throw new java.io.IOException("Choose an image under 8 MB");
                out.write(buffer, 0, n);
            }
            bytes = out.toByteArray();
        }
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) throw new java.io.IOException("This file is not an image");
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 512) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        Bitmap image = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (image == null) throw new java.io.IOException("Image could not be read");
        try {
            File output = file(context, pkg); File parent = output.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) throw new java.io.IOException("Image could not be saved");
            android.util.AtomicFile atomic = new android.util.AtomicFile(output); FileOutputStream stream = atomic.startWrite();
            try {
                if (!image.compress(Bitmap.CompressFormat.JPEG, 90, stream)) throw new java.io.IOException("Image could not be saved");
                atomic.finishWrite(stream);
            } catch (Exception e) { atomic.failWrite(stream); throw e; }
        } finally { image.recycle(); }
    }
    static Drawable load(Context context, String pkg) {
        try {
            Bitmap bitmap = BitmapFactory.decodeFile(file(context, pkg).getPath());
            return bitmap == null ? null : new BitmapDrawable(context.getResources(), bitmap);
        } catch (Exception | OutOfMemoryError ignored) { return null; }
    }
}
