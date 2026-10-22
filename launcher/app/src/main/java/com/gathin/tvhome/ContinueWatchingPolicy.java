package com.gathin.tvhome;

/** Pure rules shared by Watch Next and unfinished items published in recommendation channels. */
final class ContinueWatchingPolicy {
    static final int MAX_ITEMS = 24;
    static final int MAX_PER_APP = 6;
    static boolean unfinished(int kind, boolean watchNext, long position, long duration) {
        return (!watchNext || kind == 0) && duration > 0 && position > 0 && position < duration;
    }
    static long engagement(long published, long now) { return Math.max(0, Math.min(published, now)); }
    static int progress(long position, long duration) {
        if (duration <= 0) return 0;
        return (int) Math.min(1000, Math.max(0, (double) position / duration * 1000));
    }
    static String identity(String pkg, String content, String provider, String intent, String title) {
        String id = !content.isEmpty() ? content : !provider.isEmpty() ? provider : !intent.isEmpty() ? intent : title;
        return pkg + '\n' + id;
    }
}
