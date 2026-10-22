package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads the public TV provider only. Viewing history stays on the device and is never written back. */
final class ContinueWatchingRepository {
    static final String PERMISSION = "android.permission.READ_TV_LISTINGS";
    static final Uri WATCH_NEXT = Uri.parse("content://android.media.tv/watch_next_program");
    static final Uri PREVIEW = Uri.parse("content://android.media.tv/preview_program");
    static final String[] COLUMNS = {"_id", "package_name", "title", "episode_title", "season_display_number",
            "episode_display_number", "content_id", "internal_provider_id", "intent_uri", "poster_art_uri",
            "watch_next_type", "last_playback_position_millis", "duration_millis", "last_engagement_time_utc_millis", "browsable"};

    static final class Item {
        final String key, pkg, title, episode, appName, artwork;
        final Intent intent;
        final long position, duration, engagement;
        final boolean watchNext;
        Item(String key, String pkg, String title, String episode, String appName, String artwork, Intent intent,
                long position, long duration, long engagement, boolean watchNext) {
            this.key = key; this.pkg = pkg; this.title = title; this.episode = episode; this.appName = appName;
            this.artwork = artwork; this.intent = intent; this.position = position; this.duration = duration;
            this.engagement = engagement; this.watchNext = watchNext;
        }
        int progress() { return ContinueWatchingPolicy.progress(position, duration); }
    }
    static final class Result {
        final List<Item> items;
        final boolean permitted;
        final boolean failed;
        Result(List<Item> items, boolean permitted, boolean failed) {
            this.items = Collections.unmodifiableList(items); this.permitted = permitted; this.failed = failed;
        }
    }
    static boolean permitted(Context context) { return context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED; }

    static Result load(Context context, List<String> hidden, CancellationSignal cancel) {
        return load(context, hidden, cancel, true);
    }

    /** Home caches each app's small list so D-pad changes never wait on a provider query. */
    static Result loadForHome(Context context, List<String> hidden, CancellationSignal cancel) {
        return load(context, hidden, cancel, false);
    }

    private static Result load(Context context, List<String> hidden, CancellationSignal cancel, boolean totalLimit) {
        if (!permitted(context)) return new Result(Collections.emptyList(), false, false);
        List<Item> found = new ArrayList<>(); Set<String> excluded = new HashSet<>(hidden);
        Map<String, String> names = new HashMap<>(); Map<String, Boolean> available = new HashMap<>();
        boolean success = read(context, WATCH_NEXT, true, excluded, names, available, cancel, found);
        if (cancel == null || !cancel.isCanceled()) success |= read(context, PREVIEW, false, excluded, names, available, cancel, found);
        return new Result(select(found, totalLimit), true, !success);
    }

    static List<Item> select(List<Item> found) {
        return select(found, true);
    }

    static List<Item> forPackage(List<Item> candidates, String pkg) {
        if (pkg == null) return Collections.emptyList();
        List<Item> result = new ArrayList<>();
        for (Item item : candidates) if (pkg.equals(item.pkg)) result.add(item);
        return result;
    }

    private static List<Item> select(List<Item> found, boolean totalLimit) {
        // The proper Watch Next item wins over a duplicate recommendation, keeping its engagement time.
        found.sort(Comparator.comparing((Item i) -> !i.watchNext).thenComparing(Comparator.comparingLong((Item i) -> i.engagement).reversed()));
        Map<String, Item> unique = new LinkedHashMap<>(); Set<String> launches = new HashSet<>();
        for (Item item : found) {
            String launch = item.pkg + '\n' + item.intent.toUri(Intent.URI_INTENT_SCHEME);
            if (unique.containsKey(item.key) || launches.contains(launch)) continue;
            unique.put(item.key, item); launches.add(launch);
        }
        List<Item> result = new ArrayList<>(unique.values());
        result.sort(Comparator.comparingLong((Item i) -> i.engagement).reversed().thenComparing(i -> i.key));
        List<Item> limited = new ArrayList<>(); Map<String, Integer> perApp = new HashMap<>();
        for (Item item : result) {
            int count = perApp.getOrDefault(item.pkg, 0);
            if (count >= ContinueWatchingPolicy.MAX_PER_APP) continue;
            limited.add(item); perApp.put(item.pkg, count + 1);
            if (totalLimit && limited.size() == ContinueWatchingPolicy.MAX_ITEMS) break;
        }
        return limited;
    }

    private static boolean read(Context context, Uri uri, boolean next, Set<String> hidden, Map<String, String> names,
            Map<String, Boolean> available, CancellationSignal cancel, List<Item> result) {
        try (Cursor cursor = context.getContentResolver().query(uri, COLUMNS, null, null, null, cancel)) {
            if (cursor == null) return false;
            int count = 0; long now = System.currentTimeMillis();
            while (cursor.moveToNext() && count++ < 2000) {
                if (cancel != null) cancel.throwIfCanceled();
                try {
                    long position = number(cursor, "last_playback_position_millis"), duration = number(cursor, "duration_millis");
                    int typeColumn = cursor.getColumnIndex("watch_next_type");
                    if (next && (typeColumn < 0 || cursor.isNull(typeColumn))) continue;
                    if (!ContinueWatchingPolicy.unfinished((int) number(cursor, "watch_next_type"), next, position, duration)) continue;
                    int browsable = cursor.getColumnIndex("browsable");
                    if (browsable >= 0 && !cursor.isNull(browsable) && cursor.getInt(browsable) == 0) continue;
                    String pkg = text(cursor, "package_name"), title = clean(text(cursor, "title"));
                    if (title.isEmpty() || hidden.contains(pkg) || !pkg.matches("[A-Za-z0-9_.]+")) continue;
                    if (!available.containsKey(pkg)) {
                        try {
                            android.content.pm.ApplicationInfo app = context.getPackageManager().getApplicationInfo(pkg, 0);
                            available.put(pkg, app.enabled); names.put(pkg, clean(app.loadLabel(context.getPackageManager()).toString()));
                        } catch (PackageManager.NameNotFoundException e) { available.put(pkg, false); }
                    }
                    if (!available.get(pkg)) continue;
                    String published = text(cursor, "intent_uri"); Intent intent = resumeIntent(context, pkg, published);
                    if (intent == null) continue;
                    String episode = clean(text(cursor, "episode_title"));
                    if (episode.isEmpty()) {
                        String season = clean(text(cursor, "season_display_number")), number = clean(text(cursor, "episode_display_number"));
                        episode = number.isEmpty() ? "" : season.isEmpty() ? "E" + number : "S" + season + " · E" + number;
                    }
                    String key = ContinueWatchingPolicy.identity(pkg, text(cursor, "content_id"), text(cursor, "internal_provider_id"), published, title);
                    result.add(new Item(key, pkg, title, episode, names.get(pkg), text(cursor, "poster_art_uri"), intent, position, duration,
                            ContinueWatchingPolicy.engagement(number(cursor, "last_engagement_time_utc_millis"), now), next));
                } catch (RuntimeException ignored) { /* A malformed record never hides other apps' records. */ }
            }
            return true;
        } catch (RuntimeException ignored) { return false; }
    }

    /** Package-bound links avoid a chooser when two installed Prime Video variants claim the same URI. */
    static Intent resumeIntent(Context context, String pkg, String value) {
        if (value.isEmpty() || value.length() > 16_384 || !pkg.matches("[A-Za-z0-9_.]+")) return null;
        try {
            Intent intent = Intent.parseUri(value, Intent.URI_INTENT_SCHEME);
            intent.setSelector(null); intent.setClipData(null);
            if (intent.getComponent() != null && !pkg.equals(intent.getComponent().getPackageName())) intent.setComponent(null);
            intent.setPackage(pkg); intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if (intent.getAction() == null) intent.setAction(Intent.ACTION_VIEW);
            ResolveInfo target = context.getPackageManager().resolveActivity(intent, 0);
            if (target == null || target.activityInfo == null || !target.activityInfo.exported || !target.activityInfo.enabled
                    || !pkg.equals(target.activityInfo.packageName)) return null;
            String permission = target.activityInfo.permission;
            if (permission != null && context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return null;
            return intent;
        } catch (Exception ignored) { return null; }
    }
    private static String text(Cursor cursor, String column) {
        int i = cursor.getColumnIndex(column); String value = i < 0 || cursor.isNull(i) ? "" : cursor.getString(i);
        return value.length() > 16_384 ? "" : value;
    }
    private static long number(Cursor cursor, String column) { int i = cursor.getColumnIndex(column); return i < 0 || cursor.isNull(i) ? 0 : cursor.getLong(i); }
    private static String clean(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(value.length(), 160); i++) {
            char c = value.charAt(i); if (c >= 32 && c != 127 && !(c >= '\u202a' && c <= '\u202e') && !(c >= '\u2066' && c <= '\u2069')) out.append(c);
        }
        return out.toString().trim();
    }
}
