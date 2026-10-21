package com.gathin.tvhome;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;

/** Bundled, offline Scenes. Extract once so the existing cropped wallpaper decoder handles every source. */
final class SceneFiles {
    static synchronized List<File> images(Context context) {
        File dir = new File(context.getFilesDir(), "scenes-v1");
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return java.util.Collections.emptyList();
            String[] names = context.getAssets().list("scenes");
            if (names != null) for (String name : names) {
                if (!name.matches("[a-zA-Z0-9-]+\\.jpg")) continue;
                File output = new File(dir, name);
                if (output.isFile() && output.length() > 0) continue;
                File temporary = new File(dir, name + ".tmp");
                try (InputStream in = context.getAssets().open("scenes/" + name);
                     FileOutputStream out = new FileOutputStream(temporary)) {
                    byte[] buffer = new byte[8192]; int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                }
                if (!temporary.renameTo(output)) temporary.delete();
            }
        } catch (Exception ignored) { }
        return WallpaperRotation.images(dir);
    }

    static String label(String filename) {
        String name = filename.replaceFirst("^[0-9]+-", "").replaceFirst("\\.[^.]+$", "").replace('-', ' ');
        return name.isEmpty() ? filename : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
