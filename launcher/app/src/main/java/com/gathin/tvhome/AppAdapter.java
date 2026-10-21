package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewPropertyAnimator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * App tiles. The first {@link MainActivity#COLUMNS} positions are the dock. Configured favourites, then the
 * module cards the owner chose to show (by label), then alphabetical order are defaults until customized.
 * With app_library on, Home ends with one more full-width row: the App Library pill.
 */
public class AppAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        void onTileLongPressed(int position, View card);

        boolean onTileClicked();

        void onFolderOpened(String key);

        void onTileFocusFrame(View card);

        /** Called for a D-pad direction key on a tile. Return true when handled (focus stays put). */
        boolean onTileVerticalKey(int position, int keyCode);

        /** OK on the App Library pill after the last row. */
        void onLibraryRequested();

        void onLibraryFocused();
    }

    /** View type of the App Library row; tiles are 0. */
    static final int LIBRARY_ROW = 1;

    static final class Entry {
        static final int APP = 0, HOME_SETTINGS = 1, CARD = 2, FOLDER = 3;

        final int kind;
        final String label;
        final String key;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        /** Text under the tile when it is focused in the grid: the label, plus the caption for a card. */
        final String name;
        /** Ambient glow colour; 0 until sampled from the banner, or the card's accent from the start. */
        int ambient;
        Drawable glyph;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this(APP, label, component, banner, icon, null, 0);
        }

        Entry(int kind, String label, ComponentName component, Drawable banner, Drawable icon, String caption, int accent) {
            this.kind = kind;
            this.label = label;
            this.component = component;
            this.key = component.flattenToShortString();
            this.pkg = component.getPackageName();
            this.banner = banner;
            this.icon = icon;
            this.name = caption == null ? label : label + " \u00B7 " + caption;
            this.ambient = accent;
        }
        Entry(String key, String label, Drawable preview) {
            this.kind = FOLDER; this.name = label;
            this.key = key; this.label = label; this.component = null;
            this.pkg = null; this.banner = preview; this.icon = null;
        }
    }

    static final float FOCUS_SCALE = 1.10f;
    /** Advanced effects (launcher settings): the focused tile lifts this far and its artwork zooms inside it. */
    static final float LIFT_DP = 4f, ARTWORK_ZOOM = 1.035f;
    static final int FOLDER_COLUMNS = 4;
    /** Artwork proportions; the size calculation leaves room for focus zoom in every column count. */
    static final float DESIGN_W_DP = 180f, DESIGN_H_DP = 108f;

    /**
     * Tile width for a grid of this inner width: the cell minus room for the focus zoom and the glass
     * highlight. A tighter gutter makes dense grids a little larger; sparse grids can grow 10%
     * beyond the original design size.
     */
    static int tileWidthPx(int innerWidthPx, int columns, float density) {
        int design = Math.round(DESIGN_W_DP * 1.10f * density);
        if (innerWidthPx <= 0 || columns <= 0) return design;
        int cell = innerWidthPx / columns;
        int fit = (int) ((cell - 4 * density) / FOCUS_SCALE);
        return Math.max(Math.round(80 * density), Math.min(design, fit));
    }

    static int tileHeightPx(int tileWidthPx) {
        return Math.round(tileWidthPx * DESIGN_H_DP / DESIGN_W_DP);
    }

    /** Height of one grid item: the tile, the name label under it and the item's own padding. */
    static int itemHeightPx(int tileHeightPx, float density) {
        return tileHeightPx + Math.round(38 * density);
    }

    private final Context ctx;
    private final TileListener listener;
    private final int dockSize;
    private final float restNameAlpha;
    private final boolean cinematic;
    private final Map<String, String> launchActions, appLabels, appBanners;
    /** Dock order (config.xml dock_apps, or a theme's config.json). */
    private final List<String> favourites;
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    private final boolean showHomeSettings;
    /** Launcher settings: motion at all, and the lifted tile, artwork zoom and app-open transition on top. */
    private final boolean animations, advancedEffects;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): no overshoot, shorter fades. */
    private final boolean reducedMotion;
    private final boolean navigationSounds;
    /** config.xml app_library: Home's last row is the App Library pill (never inside a folder). */
    private final boolean showLibrary;
    /** Packages with a leanback entry at the last reload(): their tiles launch with the leanback category. */
    private Set<String> leanbackPackages = new HashSet<>();
    private final List<Entry> items = new ArrayList<>();
    private final HomeLayoutStore layoutStore;
    private HomeLayout layout = new HomeLayout();
    private final java.util.Map<String, Entry> inventory = new java.util.LinkedHashMap<>();
    /**
     * Banners of the last reload, by component, install path and banner resource. An update installs to a
     * new path, so only a changed app's banner is decoded again (the density-pinned load skips the system's
     * drawable cache on any TV that is not exactly xhdpi).
     */
    private Map<String, Drawable> banners = new HashMap<>();
    /** RecyclerView ids per key: hashCode() could give two apps the same id. */
    private final Map<String, Long> ids = new HashMap<>();
    private String activeFolder;
    private int columns;

    HomeLayout snapshotLayout() { return layout.copy(); }
    String activeFolder() { return activeFolder; }
    int columns() { return columns; }
    boolean isFolder(int position) { return layout.folders.containsKey(keyAt(position)); }
    HomeLayout.Folder folder(String key) { return layout.folders.get(key); }
    void showFolder(String key) { activeFolder = key; columns = key == null ? dockSize : FOLDER_COLUMNS; rebuild(); }
    void restoreLayout(HomeLayout saved) { layout = saved.copy(); rebuild(); }

    AppAdapter folderAdapter(String key) {
        AppAdapter copy = new AppAdapter(this);
        copy.inventory.putAll(inventory);
        copy.layout = layout.copy();
        copy.showFolder(key);
        return copy;
    }

    private AppAdapter(AppAdapter source) {
        ctx = source.ctx;
        listener = source.listener;
        layoutStore = source.layoutStore;
        dockSize = source.dockSize;
        restNameAlpha = source.restNameAlpha;
        cinematic = source.cinematic;
        launchActions = source.launchActions;
        appLabels = source.appLabels;
        appBanners = source.appBanners;
        favourites = source.favourites;
        hidden = source.hidden;
        showHomeSettings = source.showHomeSettings;
        animations = source.animations;
        advancedEffects = source.advancedEffects;
        reducedMotion = source.reducedMotion;
        navigationSounds = source.navigationSounds;
        showLibrary = false;
        leanbackPackages = source.leanbackPackages;
        columns = FOLDER_COLUMNS;
        setHasStableIds(true);
    }

    private void rebuild() {
        if (activeFolder != null && !layout.folders.containsKey(activeFolder)) activeFolder = null;
        columns = activeFolder == null ? dockSize : FOLDER_COLUMNS;
        items.clear();
        List<String> available = new ArrayList<>(inventory.keySet());
        for (String key : layout.visible(activeFolder, available)) {
            HomeLayout.Folder folder = layout.folders.get(key);
            if (folder == null) items.add(inventory.get(key));
            else {
                List<Drawable> art = new ArrayList<>();
                for (String app : folder.apps) {
                    Entry e = inventory.get(app);
                    if (e != null) {
                        Drawable d = e.banner != null ? e.banner : e.icon;
                        art.add(d != null && d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d);
                    }
                }
                items.add(new Entry(key, folder.name, new FolderPreviewDrawable(art)));
            }
        }
        notifyDataSetChanged();
    }
    private String movingKey;
    private String menuKey;

    AppAdapter(Context ctx, TileListener listener, int dockSize, Config cfg) {
        this.ctx = ctx;
        this.layoutStore = new HomeLayoutStore(ctx);
        this.columns = dockSize;
        this.listener = listener;
        this.dockSize = dockSize;
        this.restNameAlpha = cfg.alwaysShowNames ? .75f : 0f;
        this.cinematic = cfg.cinematicHome;
        this.launchActions = cfg.launchActions;
        this.appLabels = cfg.appLabels;
        this.appBanners = cfg.appBanners;
        this.favourites = cfg.dockApps;
        this.hidden = new HashSet<>(cfg.hiddenApps);
        this.showHomeSettings = cfg.showHomeSettingsShortcut;
        this.animations = cfg.animations;
        this.advancedEffects = cfg.animations && cfg.advancedEffects;
        this.reducedMotion = cfg.reducedMotion;
        this.navigationSounds = cfg.navigationSounds;
        this.showLibrary = cfg.appLibrary;
        // Tiles keep their identity across reload(), so a focused tile stays on its app instead of
        // its view being rebound to whatever app now sits in that slot.
        setHasStableIds(true);
    }

    /**
     * Rebuilds the tiles. A shown module card stands in for every tile of its package (same component,
     * so focus follows it); hidden_apps wins over a card, and a card's package in dock_apps takes that slot.
     * Apps added from the App Library join as ordinary tiles, phone apps included; one taken off Home there
     * (or with Remove from Home) stays off until it is added again.
     */
    void reload(List<Cards.Card> cards, List<ResolveInfo> leanback) {
        PackageManager pm = ctx.getPackageManager();
        List<ResolveInfo> found = new ArrayList<>(leanback);
        Set<String> tvPackages = new HashSet<>();
        for (ResolveInfo ri : found) if (ri.activityInfo != null) tvPackages.add(ri.activityInfo.packageName);
        leanbackPackages = tvPackages;
        Set<String> added = LibraryApps.load(ctx);
        Set<String> removed = LibraryApps.loadRemoved(ctx);
        if (!added.isEmpty()) {
            Set<String> listed = new HashSet<>(tvPackages);
            for (ResolveInfo ri : pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
                if (ri.activityInfo != null && added.contains(ri.activityInfo.packageName)
                        && listed.add(ri.activityInfo.packageName)) found.add(ri);
            }
        }
        Map<String, Cards.Card> cardByPkg = new HashMap<>();
        for (Cards.Card c : cards) cardByPkg.put(c.pkg, c);
        List<ResolveInfo> shown = new ArrayList<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null || !ri.activityInfo.exported) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || !LibraryApps.visible(tvPackages.contains(pkg),
                    hidden.contains(pkg), added.contains(pkg), removed.contains(pkg))) continue;
            Cards.Card card = cardByPkg.get(pkg);
            if (card != null && !card.component.equals(new ComponentName(pkg, ri.activityInfo.name))) continue;   // the card stands in for this tile
            shown.add(ri);
        }
        Map<String, Drawable> previousBanners = banners;
        // Labels, icons and banners come from each app's own resources, and on a cold start nothing is cached
        // yet: load several apps at once. The tiles still appear together and complete, only sooner.
        List<Entry> loaded = shown.parallelStream()
                .map(ri -> entry(pm, ri, cardByPkg.get(ri.activityInfo.packageName), previousBanners))
                .collect(Collectors.toCollection(ArrayList::new));
        banners = new HashMap<>();
        List<Entry> fresh = new ArrayList<>();
        // Some system apps declare two leanback entry points under one name (Sony's Timers & Clock on the
        // BRAVIA VU31), which would show as two identical tiles: keep the first per package and label.
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < shown.size(); i++) {
            Entry e = loaded.get(i);
            banners.put(artKey(shown.get(i)), e.banner);
            if (seen.add(e.pkg + '\n' + e.label)) fresh.add(e);
        }
        Collections.sort(fresh, (a, b) -> {
            int fa = favourites.indexOf(a.pkg), fb = favourites.indexOf(b.pkg);
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            // Cards come right after the configured dock apps, before the alphabetical grid.
            boolean ca = a.kind == Entry.CARD, cb = b.kind == Entry.CARD;
            if (ca != cb) return ca ? -1 : 1;
            return a.label.compareToIgnoreCase(b.label);
        });
        // Optional "Default home" tile (config.xml), only when this TV actually has a home-screen picker.
        if (showHomeSettings
                && pm.resolveActivity(new Intent(Settings.ACTION_HOME_SETTINGS), 0) != null) {
            fresh.add(new Entry(Entry.HOME_SETTINGS, ctx.getString(R.string.home_settings),
                    new ComponentName(ctx, HomeSettingsActivity.class), null,
                    ctx.getDrawable(R.drawable.ic_home_settings), null, 0));
        }
        inventory.clear();
        for (Entry entry : fresh) inventory.put(entry.key, entry);
        layout = layoutStore.read();
        layout.reconcile(new ArrayList<>(inventory.keySet()));
        rebuild();
    }

    /** One tile, built on a loader thread; the banner is decoded only when its key is new since the last reload. */
    private Entry entry(PackageManager pm, ResolveInfo ri, Cards.Card card, Map<String, Drawable> previousBanners) {
        String pkg = ri.activityInfo.packageName;
        ComponentName component = new ComponentName(pkg, ri.activityInfo.name);
        String art = artKey(ri);
        String artwork = appBanners.get(pkg);
        boolean linkedIcon = AppArtwork.isAppIcon(artwork);
        Drawable banner = "custom".equals(artwork) ? AppArtwork.load(ctx, pkg) : bundledBanner(artwork);
        if (banner == null && !"icon".equals(artwork) && !linkedIcon) banner = previousBanners.containsKey(art) ? previousBanners.get(art) : loadBanner(pm, ri);
        // Spotify's custom glyph still needs its icon even if the app supplies a banner.
        Drawable icon = linkedIcon ? AppArtwork.otherAppIcon(ctx, artwork) : null;
        if (icon == null && (banner == null || "com.spotify.tv.android".equals(pkg) && !appBanners.containsKey(pkg))) icon = ri.loadIcon(pm);
        if (card != null) return new Entry(Entry.CARD, card.label, component, banner, icon, card.caption, card.accent);
        // Same cleaner as the card prompt: an app label with a bidi override or line separator must not
        // redraw the tile name as something else (#117).
        return new Entry(Cards.cleanLabel(appLabels.containsKey(pkg) ? appLabels.get(pkg) : String.valueOf(ri.loadLabel(pm)), pkg), component, banner, icon);
    }

    /** The banners map's key: an update installs to a new path, and an app can name a different banner. */
    private static String artKey(ResolveInfo ri) {
        return new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name).flattenToShortString()
                + '|' + ri.activityInfo.applicationInfo.sourceDir
                + '|' + ri.activityInfo.banner + '|' + ri.activityInfo.applicationInfo.banner;
    }

    private Drawable loadBanner(PackageManager pm, ResolveInfo ri) {
        int id = ri.activityInfo.banner;
        if (id == 0) id = ri.activityInfo.applicationInfo.banner;
        Drawable banner = null;
        if (id != 0) {
            try {
                Resources resources = pm.getResourcesForApplication(ri.activityInfo.applicationInfo);
                // Keep at least xhdpi detail rather than downsampling artwork before the focus zoom.
                int density = Math.max(DisplayMetrics.DENSITY_XHIGH, ctx.getResources().getDisplayMetrics().densityDpi);
                banner = resources.getDrawableForDensity(id, density, null);
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) {
            }
        }
        if (banner == null) banner = ri.activityInfo.loadBanner(pm);
        // A square "banner" counts as none: the tile shows the app's icon and name instead of a blur.
        return banner != null && isBannerShaped(banner.getIntrinsicWidth(), banner.getIntrinsicHeight()) ? banner : null;
    }

    /**
     * Some apps declare a small square image as their banner (Rockchip's Media Center ships a 64 px one). Cropped
     * over a 16:9 tile it is a blur. A drawable with no intrinsic size (a colour, a shape) still fills the tile.
     */
    static boolean isBannerShaped(int width, int height) {
        return width <= 0 || height <= 0 || width >= height * 1.4f;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == LIBRARY_ROW) {
            View row = LayoutInflater.from(parent.getContext()).inflate(R.layout.row_app_library, parent, false);
            View button = row.findViewById(R.id.btnLibrary);
            button.setSoundEffectsEnabled(navigationSounds);
            button.setOnClickListener(v -> { if (listener != null) listener.onLibraryRequested(); });
            button.setOnFocusChangeListener((v, focused) -> {
                if (focused && listener != null) listener.onLibraryFocused();
            });
            return new RecyclerView.ViewHolder(row) { };
        }
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        float density = parent.getResources().getDisplayMetrics().density;
        int width = parent.getWidth() > 0 ? parent.getWidth() : parent.getMeasuredWidth();
        int inner = width - parent.getPaddingLeft() - parent.getPaddingRight();
        int w = tileWidthPx(inner, columns, density), h = tileHeightPx(w);
        View card = v.findViewById(R.id.card);
        card.setSoundEffectsEnabled(navigationSounds);
        ViewGroup.LayoutParams cardLp = card.getLayoutParams();
        cardLp.width = w;
        cardLp.height = h;
        card.setLayoutParams(cardLp);
        View name = v.findViewById(R.id.name);
        ViewGroup.MarginLayoutParams nameLp = (ViewGroup.MarginLayoutParams) name.getLayoutParams();
        nameLp.width = w;
        nameLp.topMargin = h + Math.round(6 * density);
        name.setLayoutParams(nameLp);
        return new Holder(v, listener, animations, advancedEffects, reducedMotion, restNameAlpha);
    }

    @Override public void onViewAttachedToWindow(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewAttachedToWindow(holder);
        if (holder instanceof Holder && holder.itemView.getParent() instanceof RecyclerView)
            resizeTile((Holder) holder, (RecyclerView) holder.itemView.getParent());
    }

    void resizeAttachedTiles(RecyclerView parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            RecyclerView.ViewHolder holder = parent.getChildViewHolder(parent.getChildAt(i));
            if (holder instanceof Holder) resizeTile((Holder) holder, parent);
        }
    }

    private void resizeTile(Holder holder, RecyclerView parent) {
        float density = ctx.getResources().getDisplayMetrics().density;
        int width = tileWidthPx(parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight(), columns, density);
        int height = tileHeightPx(width), nameTop = height + Math.round(6 * density);
        ViewGroup.LayoutParams card = holder.card.getLayoutParams();
        if (card.width != width || card.height != height) {
            card.width = width; card.height = height; holder.card.setLayoutParams(card);
        }
        ViewGroup.MarginLayoutParams name = (ViewGroup.MarginLayoutParams) holder.name.getLayoutParams();
        if (name.width != width || name.topMargin != nameTop) {
            name.width = width; name.topMargin = nameTop; holder.name.setLayoutParams(name);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (!(holder instanceof Holder)) return;   // the App Library row has nothing to bind
        Holder h = (Holder) holder;
        Entry e = items.get(position);
        float d = ctx.getResources().getDisplayMetrics().density;
        RecyclerView parent = (RecyclerView) h.itemView.getParent();
        if (parent != null) {
            resizeTile(h, parent);
        }
        boolean glyphTile = "com.spotify.tv.android".equals(e.pkg) && e.icon != null && !appBanners.containsKey(e.pkg);
        int backdrop = glyphTile ? Holder.BACKDROP_GLYPH : isFolder(position) ? Holder.BACKDROP_GLASS
                : e.banner != null && e.banner.getOpacity() == PixelFormat.OPAQUE ? Holder.BACKDROP_NONE : Holder.BACKDROP_TILE;
        if (h.backdrop != backdrop) {
            // A rebind keeps the tile's background unless the kind of tile changed.
            h.backdrop = backdrop;
            h.card.setBackground(backdrop == Holder.BACKDROP_GLASS ? new GlassPanelDrawable(d, 16)
                    : backdrop == Holder.BACKDROP_GLYPH ? new android.graphics.drawable.GradientDrawable(
                            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                            new int[]{0xFF242624, 0xFF101311})
                    : backdrop == Holder.BACKDROP_NONE ? null : ctx.getDrawable(R.drawable.tile_bg));
        }
        h.name.setText(e.name);
        h.name.setVisibility(e.label.isEmpty() || (restNameAlpha == 0f && activeFolder == null && position < dockSize) || movingKey != null ? View.INVISIBLE : View.VISIBLE);
        // Keep captions quiet during rearrangement; the lifted app and direction handles show selection.
        h.card.setActivated(e.key.equals(movingKey)
                || e.key.equals(menuKey));
        boolean focused = h.card.isFocused() || h.card.isActivated();   // a rebind after reload() can hit the focused tile
        h.name.animate().cancel();
        h.name.setAlpha(focused ? 1f : restNameAlpha);
        h.card.animate().cancel();
        h.banner.animate().cancel();
        h.card.setScaleX(focused ? FOCUS_SCALE : 1f);
        h.card.setScaleY(focused ? FOCUS_SCALE : 1f);
        h.card.setTranslationY(focused && advancedEffects ? -LIFT_DP * d : 0f);
        h.card.setTranslationZ(focused ? (advancedEffects ? 8 : 4) * d : 0f);
        h.banner.setScaleX(focused && advancedEffects ? ARTWORK_ZOOM : 1f);
        h.banner.setScaleY(focused && advancedEffects ? ARTWORK_ZOOM : 1f);
        h.banner.setPadding(0, 0, 0, 0);
        h.banner.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (glyphTile) {
            // The reference uses Spotify's large glyph, without duplicating its wordmark in the tile.
            int inset = Math.round(h.card.getLayoutParams().height * .06f);
            h.banner.setPadding(inset, inset, inset, inset);
            h.banner.setScaleType(ImageView.ScaleType.FIT_CENTER);
            if (e.glyph == null) e.glyph = spotifyGlyph(e.icon);
            h.banner.setImageDrawable(e.glyph);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            h.label.setVisibility(View.VISIBLE);
        }
        h.card.setContentDescription(isFolder(position) ? e.label.isEmpty()
                ? ctx.getString(R.string.unnamed_folder) : ctx.getString(R.string.folder_description, e.label) : e.label);
        boolean moving = e.key.equals(movingKey);
        h.card.setElevation((moving ? 9 : 3) * d);
        h.card.setTranslationZ(focused ? 4 * d : 0f);
        h.card.setForeground(null);
        h.card.setOnClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            if (listener == null || !listener.onTileClicked()) {
                if (e.component == null) { if (listener != null) listener.onFolderOpened(e.key); }
                else launch(e);
            }
        });
        h.card.setOnLongClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (listener != null && pos != RecyclerView.NO_POSITION) listener.onTileLongPressed(pos, v);
            return true;
        });
    }

    List<String> keys() {
        List<String> result = new ArrayList<>();
        for (Entry entry : items) result.add(entry.key);
        return result;
    }

    /** The settings preview reads the same layout and artwork as Home without saving any changes. */
    List<Entry> previewEntries() { return new ArrayList<>(items); }

    Entry previewApp(String pkg) {
        for (Entry entry : inventory.values()) if (pkg.equals(entry.pkg)) return entry;
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER})
            for (ResolveInfo ri : ctx.getPackageManager().queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(pkg), 0))
                if (ri.activityInfo != null && ri.activityInfo.exported)
                    return entry(ctx.getPackageManager(), ri, null, Collections.emptyMap());
        return null;
    }

    void applyOrder(List<String> order) {
        List<String> keys = AppOrder.reconcile(keys(), order);
        java.util.Map<String, Entry> entries = new java.util.HashMap<>();
        for (Entry entry : items) entries.put(entry.key, entry);
        items.clear();
        for (String key : keys) items.add(entries.get(key));
        layout.reorder(activeFolder, keys());
        notifyDataSetChanged();
    }

    void move(int from, int to) {
        if (from == to) return;
        items.add(to, items.remove(from));
        layout.reorder(activeFolder, keys());
        notifyItemMoved(from, to);
        // Positions determine dock labels as well as decoration, even for displaced neighbours.
        notifyItemRangeChanged(Math.min(from, to), Math.abs(from - to) + 1);
    }

    void setMenuKey(String key) {
        menuKey = key;
        notifyItemRangeChanged(0, items.size());
    }

    void setMovingKey(String key) {
        movingKey = key;
        notifyItemRangeChanged(0, items.size());
    }

    boolean isApp(int position) {
        return position >= 0 && position < items.size() && items.get(position).component != null
                && !items.get(position).component.equals(new ComponentName(ctx, HomeSettingsActivity.class));
    }

    void openAppSettings(String key) {
        int position = positionOf(key);
        if (position < 0 || !isApp(position)) return;
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + items.get(position).pkg));
        try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException ignored) {
            Toast.makeText(ctx, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Opens the tile's activity by explicit component, with no extras: a card can name nothing else. */
    private void launch(Entry e) {
        if (e.kind == Entry.HOME_SETTINGS) {
            ctx.startActivity(new Intent(ctx, HomeSettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        Intent i = new Intent(Intent.ACTION_MAIN)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        // TV apps launch with the same leanback intent as from the stock launcher or the remote, so an app
        // already running resumes instead of restarting; only phone apps added from the library use plain MAIN.
        if (leanbackPackages.contains(e.pkg)) i.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        String action = launchActions.get(e.pkg);
        if (action != null) {
            Intent byAction = new Intent(action).setPackage(e.pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            if (ctx.getPackageManager().resolveActivity(byAction, 0) != null) i = byAction;
        }
        try {
            ctx.startActivity(i);
            if (cinematic) LastOpened.record(ctx, e.pkg);
            if (advancedEffects && ctx instanceof Activity)
                ((Activity) ctx).overridePendingTransition(R.anim.app_open_enter, R.anim.app_open_exit);
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size() + (hasLibrary() ? 1 : 0);
    }

    /** Tiles only: every position below this is an app or a folder; the App Library row may follow. */
    int appCount() {
        return items.size();
    }

    private boolean hasLibrary() {
        return showLibrary && activeFolder == null;
    }

    boolean isLibraryAt(int position) {
        return hasLibrary() && position == items.size();
    }

    @Override public int getItemViewType(int position) {
        return isLibraryAt(position) ? LIBRARY_ROW : 0;
    }

    /** Whether the package has a tile on Home, on its own or inside a folder: the App Library's "On Home". */
    boolean containsPackage(String pkg) {
        for (Entry entry : inventory.values()) if (pkg.equals(entry.pkg)) return true;
        return false;
    }

    /** The app's package at this position, or null for a folder or the App Library row. */
    String packageAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).pkg : null;
    }

    @Override
    public long getItemId(int position) {
        if (isLibraryAt(position)) return Long.MIN_VALUE;
        return ids.computeIfAbsent(items.get(position).key, key -> (long) ids.size());
    }

    /** Stable key for the app at a position (its component), or null. */
    String keyAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).key : null;
    }

    /** Position of the app with this key, or -1 when it is gone. */
    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).key.equals(key)) return i;
        }
        return -1;
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    /** Remove the Android icon's square black matte, preserving the green glyph's antialiasing. */
    private Drawable spotifyGlyph(Drawable original) {
        Drawable source = original.getConstantState() != null
                ? original.getConstantState().newDrawable().mutate() : original;
        Rect bounds = new Rect(source.getBounds());
        int size = 192;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        source.setBounds(0, 0, size, size);
        source.draw(new Canvas(bitmap));
        source.setBounds(bounds);
        int[] pixels = new int[size * size];
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size);
        int peak = 0, colour = 0;
        for (int pixel : pixels) {
            int green = Color.green(pixel) - Math.max(Color.red(pixel), Color.blue(pixel));
            if (green > peak) { peak = green; colour = pixel & 0xFFFFFF; }
        }
        if (peak < 12) { bitmap.recycle(); return original; }
        int left = size, top = size, right = -1, bottom = -1;
        for (int i = 0; i < pixels.length; i++) {
            int green = Math.max(0, Color.green(pixels[i]) - Math.max(Color.red(pixels[i]), Color.blue(pixels[i])));
            int alpha = Color.alpha(pixels[i]) * green / peak;
            pixels[i] = (alpha << 24) | colour;
            if (alpha > 4) {
                int x = i % size, y = i / size;
                left = Math.min(left, x); top = Math.min(top, y);
                right = Math.max(right, x); bottom = Math.max(bottom, y);
            }
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size);
        Bitmap glyph = Bitmap.createBitmap(bitmap, left, top, right - left + 1, bottom - top + 1);
        if (glyph != bitmap) bitmap.recycle();
        return new android.graphics.drawable.BitmapDrawable(ctx.getResources(), glyph);
    }

    /** A vivid colour sampled from the app's banner (or icon), cached per entry; a card's accent when it set one. */
    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return 0xFF444444;
        Entry e = items.get(position);
        if (e.ambient == 0) e.ambient = vividColor(e.banner != null ? e.banner : e.icon);
        return e.ambient;
    }

    static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        // Draw a private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 12;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        int[] px = new int[n * n];
        bmp.getPixels(px, 0, n, 0, 0, n, n);
        bmp.recycle();
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int p : px) {
            int pr = (p >> 16) & 0xFF, pg = (p >> 8) & 0xFF, pb = p & 0xFF;
            r += pr;
            g += pg;
            b += pb;
            int max = Math.max(pr, Math.max(pg, pb));
            int min = Math.min(pr, Math.min(pg, pb));
            float sat = max == 0 ? 0f : (max - min) / (float) max;
            float val = max / 255f;
            float score = sat * Math.min(1f, val * 1.4f);   // saturated and not too dark
            if (score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        if (bestScore < 0.25f) {   // nearly monochrome banner (e.g. white): use its average, lifted
            int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    /** Corner radius for a tile of this width: 16dp at the design width, proportionally less when smaller. */
    static float cornerRadius(int tileWidthPx, float density) {
        return 22f * density * Math.min(1f, tileWidthPx / (DESIGN_W_DP * density));
    }

    static final class Holder extends RecyclerView.ViewHolder {
        static final int BACKDROP_TILE = 0, BACKDROP_GLASS = 1, BACKDROP_GLYPH = 2, BACKDROP_NONE = 3;
        /** Which background the card has now; -1 until the first bind. */
        int backdrop = -1;
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;
        final boolean animations, advancedEffects, reducedMotion;
        final float restNameAlpha;
        boolean suppressConfirmRelease;

        Holder(View v, TileListener listener, boolean animations, boolean advancedEffects, boolean reducedMotion, float restNameAlpha) {
            super(v);
            card = v.findViewById(R.id.card);
            // The launcher draws its own app-colour focus light; avoid a platform fill behind glass.
            card.setDefaultFocusHighlightEnabled(false);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);
            this.animations = animations;
            this.advancedEffects = advancedEffects;
            this.reducedMotion = reducedMotion;
            this.restNameAlpha = restNameAlpha;

            final float density = v.getResources().getDisplayMetrics().density;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    // 16dp at the design width, scaled down with the tile so small tiles keep the same shape.
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius(view.getWidth(), density));
                    // A filled native elevation shadow shows through the translucent folder as an
                    // inset grey panel on TV hardware. Keep clipping, and use only our outer glow.
                    outline.setAlpha(view.getBackground() instanceof GlassPanelDrawable ? 0f : 1f);
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                boolean confirm = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                        || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A;
                if (confirm) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                        suppressConfirmRelease = false;
                    // Some remotes report an explicit long-press repeat instead of waiting for
                    // View's timer. Handle both without allowing the release to launch the app.
                    if (event.getAction() == KeyEvent.ACTION_DOWN && event.isLongPress()) {
                        view.cancelLongPress();
                        view.setPressed(false);
                        suppressConfirmRelease = true;
                        int pos = getBindingAdapterPosition();
                        if (listener != null && pos != RecyclerView.NO_POSITION)
                            listener.onTileLongPressed(pos, view);
                        return true;
                    }
                    if (event.getAction() == KeyEvent.ACTION_UP && suppressConfirmRelease) {
                        suppressConfirmRelease = false;
                        view.setPressed(false);
                        return true;
                    }
                }
                // Direction keys are handled by the activity, including while the target row is
                // being recycled. The card keeps only click/long-press key handling.
                return false;
            });

            card.setOnFocusChangeListener((view, focused) -> {
                boolean highlighted = focused || view.isActivated();
                float s = highlighted ? FOCUS_SCALE : 1.0f;
                float lift = highlighted && advancedEffects ? -LIFT_DP * density : 0f;
                float depth = highlighted ? (advancedEffects ? 8 : 4) * density : 0f;
                float zoomArt = highlighted && advancedEffects ? ARTWORK_ZOOM : 1f;
                view.animate().cancel();
                banner.animate().cancel();
                name.animate().cancel();
                if (!animations) {
                    // Animations off (launcher settings): the focused look without the motion.
                    view.setScaleX(s);
                    view.setScaleY(s);
                    view.setTranslationY(lift);
                    view.setTranslationZ(depth);
                    banner.setScaleX(zoomArt);
                    banner.setScaleY(zoomArt);
                    name.setAlpha(focused ? 1f : restNameAlpha);
                    name.setTranslationY(focused ? density * 4f : -density * 6f);
                    if (listener != null && view.isFocused()) listener.onTileFocusFrame(view);
                } else {
                    ViewPropertyAnimator zoom = view.animate().scaleX(s).scaleY(s)
                            .translationY(lift).translationZ(depth)
                            .setUpdateListener(animation -> {
                                if (listener != null && view.isFocused()) listener.onTileFocusFrame(view);
                            })
                            .withLayer();
                    if (reducedMotion) {
                        // No overshoot: the extra frames redraw the banner and the dock band on a weak GPU.
                        zoom.setDuration(focused ? 120 : 80)
                                .setInterpolator(new DecelerateInterpolator())
                                .start();
                        name.animate().alpha(focused ? 1f : restNameAlpha).setDuration(100).start();
                        // Same place as the full motion's slide ends: the layout's -6dp is only its starting point,
                        // and left there the name overlaps the zoomed tile. Only on focus: snapping it back to -6dp
                        // while it fades out would jump it up over the tile.
                        if (focused) name.setTranslationY(density * 4f);
                    } else {
                        // Advanced effects settle with a softer overshoot: the lift already carries the motion.
                        zoom.setDuration(advancedEffects ? (focused ? 220 : 170) : (focused ? 260 : 180))
                                .setInterpolator(new OvershootInterpolator(focused ? (advancedEffects ? 0.7f : 1.6f) : 0f))
                                .start();
                        name.animate().alpha(focused ? 1f : restNameAlpha)
                                .translationY(focused ? density * 4f : -density * 6f)
                                .setDuration(220).start();
                    }
                    if (advancedEffects) banner.animate().scaleX(zoomArt).scaleY(zoomArt)
                            .setDuration(reducedMotion ? 120 : 280).start();
                }
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }

    private Drawable bundledBanner(String name) {
        if (name == null || !name.startsWith("banner_")) return null;
        int id = ctx.getResources().getIdentifier(name, "drawable", ctx.getPackageName());
        return id != 0 ? ctx.getDrawable(id) : null;
    }



    Drawable artworkAt(int position) {
        if (position < 0 || position >= items.size()) return null;
        Entry e = items.get(position);
        return e.banner != null ? e.banner : e.icon;
    }

    /** Release artwork while hidden without changing persisted folder or app order. */
    void clear() {
        items.clear();
        inventory.clear();
        banners.clear();
        notifyDataSetChanged();
    }
}
