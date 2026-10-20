package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.animation.Animator;
import android.animation.ArgbEvaluator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.widget.EditText;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * tvOS-inspired home: a glass dock, an expanding app grid, folders, and app-colour focus lighting.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    static final String ACTION_QUICK_SETTINGS = "com.gathin.tvhome.action.QUICK_SETTINGS";
    private static final int LAUNCHER_SETTINGS_REQUEST = 42;

    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private static final long MINUTE_MS = 60_000L;
    private String liveTvPkg;
    private String liveTvAction;
    private String inputAction;
    private String sourcePanelService;
    private String sourcePanelAction;

    private AppAdapter adapter;
    private RecyclerView grid;
    private AppAdapter homeAdapter;
    private AppAdapter preparedFolderAdapter;
    private RecyclerView homeGrid, folderGrid;
    private View homeGridHost, folderGridHost;
    private FrameLayout root;
    private GlassView glass;
    private ImageView ambient;
    private TextView heroName;
    /** Glow colour on screen, and the one a running cross-fade is heading to. */
    private int ambientColor = -1, ambientTarget = -1;
    /** Advanced effects blend the glow from one app's colour to the next instead of dipping it. */
    private ValueAnimator ambientAnimator;
    /** Top-shelf title a running cross-fade is heading to; the old one stays on screen until it ends. */
    private String heroLabel;
    private boolean heroShown;
    private TextView greeting;
    private View btnInputs, btnSettings;
    private TextView btnWifi;
    private QuickSettingsPanel quickSettings;
    private boolean resumeQuickSettings;
    private int quickSettingsFocus = R.id.quickAllSettings;
    private boolean quickSettingsAwaitingLayout;
    private TextView shamsiDate;
    private boolean shamsi;
    /** Optional time-of-day backdrop (config.xml, sky_intensity); 0 keeps it hidden. */
    private SkyView sky;
    private String vpnApp;
    private float glassTargetX, glassTargetY;
    private boolean glassShown;
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    private float density;
    private PopupWindow tileMenu;
    private boolean restoreMenuFocus = true;
    private boolean menuReleaseGuard;
    private String movingKey;
    private List<String> moveSnapshot;
    private HomeLayout savingLayout;
    private MoveIndicator moveIndicator;
    private HomeLayoutStore layoutStore;
    private DockDecoration dockDecoration;
    private View topBar, gridHost, folderPanel;
    private TextView folderTitle;
    private FolderTransition folderMotion;
    private View folderBackdrop;
    private boolean expanded;
    private boolean gridTransition;
    private int gridTransitionRequest;
    private ValueAnimator gridSlide;
    private String folderReturnKey;
    private AlertDialog editDialog;
    private int rowsBelowDock;
    private boolean savingOrderNow, paused = true, refreshPending;
    private int focusRequest;
    private String requestedFocusKey;
    private boolean confirmDown;
    private int moveAnimationRequest;
    /** Scroll during a reorder is viewport movement, not part of the neighbouring tiles' animation. */
    private int moveScrollX, moveScrollY;
    /** Module cards (res/values/config.xml, module_cards): discovered on resume, shown only after the owner's Show. */
    private boolean moduleCards;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): stiff spring, short fades, no replay. */
    private boolean reducedMotion;
    /** Launcher settings: Animations off jumps every transition to its end; advanced effects add lift and colour blends. */
    private boolean animations, advancedEffects;
    /** Hebrew mirrors the app grid and the header buttons (values-iw/bools.xml). */
    private boolean rtlAppGrid, rtlMenuBar;
    private boolean navigationSounds;
    /** hidden_apps as the adapter sees them (config.xml or a theme's config.json): a hidden app gets no card either. */
    private Set<String> hiddenApps = Collections.emptySet();
    private List<Cards.Card> cards = Collections.emptyList();
    /** The grid has been laid out and focused since the last resume; the card prompt waits for this. */
    private boolean tilesReady;
    private Dialog cardPrompt;
    /** Set when a decision could not be saved: no more prompts until the next resume, instead of asking in a loop. */
    private boolean promptsPaused;
    /** The Inputs or Module cards dropdown while one is open, so onPause can close it. */
    private PopupWindow openPopup;
    /**
     * Home was only paused since it was last shown: Android TV opens Wi-Fi and its other settings pages as a
     * side panel over Home. Closing the panel then keeps Home as it was, a focused header button included.
     */
    private boolean onlyPaused;
    /** The tiles have been built at least once by this activity; see tilesOutdated(). */
    private boolean tilesLoaded;
    /** PackageManager's change counter when the tiles were last built. */
    private int packageSequence;
    /** The theme's wallpaper while it decodes, from onCreate until the first onResume shows it. */
    private java.util.concurrent.CompletableFuture<Bitmap> wallpaperLoad;
    /** The glow mask is painted once and tinted as focus moves, matching main's backdrop. */
    private BitmapDrawable ambientGlow;
    private final BroadcastReceiver packageChanges = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                    && Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())) return;
            if (savingOrderNow) { refreshPending = true; return; }
            dismissTileMenu(false);
            if (movingKey != null) finishMove(false, false);
            String key = lastFocusedKey;
            int slot = lastFocused;
            reloadTiles();
            syncFolderPresentation();
            if (!paused) focusKey(key, slot);
            // A newly installed app may have brought a card with it.
            showCardPrompt();
        }
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    /** Shows the Inputs pill once the TV registers an input: at boot HDMI inputs can appear after Home is shown. */
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputAdded(String inputId) {
            updateInputsButton();
        }

        @Override
        public void onInputRemoved(String inputId) {
            updateInputsButton();
        }
    };
    /** Listens for any VPN coming up or going down, even one this app's traffic is excluded from. */
    private final ConnectivityManager.NetworkCallback vpnCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateWifiLabel();
            sky.update();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        resumeQuickSettings = savedInstanceState != null
                ? savedInstanceState.getBoolean("quick_settings_open", false)
                : ACTION_QUICK_SETTINGS.equals(getIntent().getAction());
        if (savedInstanceState != null)
            quickSettingsFocus = savedInstanceState.getInt("quick_settings_focus", R.id.quickAllSettings);
        // config.xml, overlaid by config.json from a pushed theme (see Config and tools/theme.sh).
        Config cfg = Config.load(this);
        COLUMNS = cfg.columns;
        reducedMotion = cfg.reducedMotion;
        animations = cfg.animations;
        advancedEffects = cfg.animations && cfg.advancedEffects;
        rtlAppGrid = getResources().getBoolean(R.bool.rtl_app_grid);
        rtlMenuBar = getResources().getBoolean(R.bool.rtl_menu_bar);
        navigationSounds = cfg.navigationSounds;
        liveTvPkg = cfg.liveTvPackage;
        liveTvAction = cfg.liveTvAction;
        inputAction = cfg.inputAction;
        sourcePanelService = cfg.sourcePanelService;
        sourcePanelAction = cfg.sourcePanelAction;

        root = findViewById(R.id.root);
        root.setFocusableInTouchMode(true);
        topBar = findViewById(R.id.topBar);
        gridHost = findViewById(R.id.gridHost);
        folderPanel = findViewById(R.id.folderPanel);
        folderTitle = findViewById(R.id.folderTitle);
        homeGridHost = gridHost;
        folderGridHost = findViewById(R.id.folderGridHost);
        folderBackdrop = findViewById(R.id.folderBackdrop);
        folderMotion = new FolderTransition(root, folderBackdrop,
                homeGridHost, reducedMotion, animations, folderPanel, folderTitle, folderGridHost);
        folderTitle.setSoundEffectsEnabled(navigationSounds);
        folderTitle.setTextColor(getResources().getColorStateList(R.color.app_action_text, getTheme()));
        folderPanel.setBackground(new GlassPanelDrawable(density, 36));
        folderTitle.setOnClickListener(v -> renameFolder(adapter.activeFolder()));
        folderTitle.setOnFocusChangeListener((v, focused) -> updateFolderTitle());
        folderTitle.setOnKeyListener((v, code, event) -> {
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    focusTile(Math.min(lastFocused, adapter.columns() - 1));
                    if (adapter.getItemCount() > 0) playNavigationSound(code);
                }
                return true;
            }
            return code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_LEFT
                    || code == KeyEvent.KEYCODE_DPAD_RIGHT;
        });
        root.setBackgroundColor(cfg.backgroundColor);
        // The root colour is always opaque (Config), so the window's own black fill under it would be
        // one more full-screen pass every frame for nothing.
        getWindow().setBackgroundDrawable(null);
        if (cfg.wallpaper != null || Config.WALLPAPER_AURORA.equals(cfg.wallpaperChoice)) {
            // Decoded while the tiles load; onResume waits for it, so the first frame still has it.
            File file = cfg.wallpaper;
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int screenW = dm.widthPixels, screenH = dm.heightPixels;
            android.content.res.Resources resources = getResources();
            wallpaperLoad = java.util.concurrent.CompletableFuture.supplyAsync(() -> decodeWallpaper(file, resources, screenW, screenH));
        }
        sky = findViewById(R.id.sky);
        sky.setIntensity(cfg.skyIntensity);
        glass = findViewById(R.id.glass);
        ambient = findViewById(R.id.ambient);
        heroName = findViewById(R.id.heroName);
        greeting = findViewById(R.id.greeting);
        btnWifi = findViewById(R.id.btnWifi);
        shamsiDate = findViewById(R.id.shamsiDate);
        shamsi = cfg.shamsiDate;
        if (shamsi) {
            shamsiDate.setVisibility(View.VISIBLE);
            TextView date = findViewById(R.id.date);
            date.setTextSize(11f);
            date.setPadding(0, (int) (4 * density), 0, 0);
        }
        vpnApp = cfg.vpnApp;
        moduleCards = cfg.moduleCards;
        hiddenApps = new HashSet<>(cfg.hiddenApps);

        grid = homeGrid = findViewById(R.id.grid);
        folderGrid = findViewById(R.id.folderGrid);
        configureGrid(homeGrid, COLUMNS);
        configureGrid(folderGrid, AppAdapter.FOLDER_COLUMNS);
        dockDecoration = new DockDecoration(COLUMNS, density);
        grid.addItemDecoration(dockDecoration);
        folderGrid.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override public void getItemOffsets(@NonNull Rect out, @NonNull View view,
                    @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                if (!(parent.getAdapter() instanceof AppAdapter)) return;
                AppAdapter contents = (AppAdapter) parent.getAdapter();
                int count = contents.getItemCount(), columns = contents.columns(), remainder = count % columns;
                int position = parent.getChildAdapterPosition(view);
                if (remainder == 0 || position < count - remainder || position < 0) return;
                // Shift the final partial row as a unit, keeping real tile/focus bounds together.
                // A right-to-left grid fills that row from the right, so it moves the other way.
                int shift = Math.round((columns - remainder) * parent.getWidth() / (2f * columns));
                out.left = rtlAppGrid ? -shift : shift;
                out.right = -out.left;
            }
        });
        adapter = homeAdapter = new AppAdapter(this, this, COLUMNS, cfg);
        grid.setAdapter(adapter);
        layoutStore = new HomeLayoutStore(this);
        moveIndicator = new MoveIndicator(this, rtlAppGrid);
        moveIndicator.setVisibility(View.GONE);
        root.addView(moveIndicator, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addDataScheme("package");
        registerReceiver(packageChanges, packages);
        // Apps on adopted (USB) storage come and go with the drive, announced without a package: URI.
        IntentFilter storage = new IntentFilter();
        storage.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
        storage.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        registerReceiver(packageChanges, storage);

        // tvOS layout: push the dock row towards the bottom of the screen, leaving room for
        // rows_below_dock grid rows under it; the rest of the grid stays below until you press Down.
        rowsBelowDock = cfg.rowsBelowDock;

        btnInputs = findViewById(R.id.btnInputs);
        btnSettings = findViewById(R.id.btnSettings);
        btnInputs.setSoundEffectsEnabled(navigationSounds);
        btnWifi.setSoundEffectsEnabled(navigationSounds);
        btnSettings.setSoundEffectsEnabled(navigationSounds);
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> openNetworkPill());
        btnWifi.setOnLongClickListener(v -> {
            openSettings(Settings.ACTION_WIFI_SETTINGS);
            return true;
        });
        btnSettings.setOnClickListener(v -> openQuickSettings());
        btnSettings.setOnLongClickListener(v -> {
            showSettingsMenu();
            return true;
        });
        if (advancedEffects) {
            for (View button : new View[]{btnInputs, btnWifi, btnSettings}) {
                button.setOnFocusChangeListener((view, focused) -> view.animate()
                        .scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f)
                        .setDuration(160).start());
            }
        }

        // Header and grid directions share the activity key router. Do not send Down through
        // TextView/Android focus search to the RecyclerView container instead of an actual card.

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) hideGlass();
        });

        // Folder/span/padding changes move children without emitting a scroll delta. Realign the
        // floating highlight after layout as well as on focus and scroll events.
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            alignFocusedTile();
        });

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.registerCallback(inputCallback, handler);
        if (cm != null) {
            cm.registerDefaultNetworkCallback(networkCallback);
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(), vpnCallback);
        }
    }

    private void configureGrid(RecyclerView target, int columns) {
        target.setLayoutManager(new GridLayoutManager(this, columns) {
            // Mirror only the app grids; the header and other locales keep their own layouts.
            @Override protected boolean isLayoutRTL() {
                return rtlAppGrid;
            }

            @Override public boolean onRequestChildFocus(@NonNull RecyclerView parent,
                    @NonNull RecyclerView.State state, @NonNull View child, View focused) {
                return (target == grid && gridTransition) || (target == homeGrid && !expanded)
                        || super.onRequestChildFocus(parent, state, child, focused);
            }
        });
        target.setHasFixedSize(true);
        target.setPreserveFocusAfterLayout(false);
        target.setItemAnimator(null);
        target.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (target == grid) updateGridPadding();
            if (r - l != or - ol && target.getAdapter() instanceof AppAdapter) {
                ((AppAdapter) target.getAdapter()).resizeAttachedTiles(target);
                target.invalidateItemDecorations();
            }
        });
        target.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (rv == grid && movingKey != null) {
                    moveScrollX += dx;
                    moveScrollY += dy;
                }
                if (rv == grid) alignFocusedTile();
            }
        });
    }

    /** A null theme file selects the bundled Aurora picture. */
    private static java.io.InputStream openWallpaper(File file, android.content.res.Resources resources)
            throws java.io.IOException {
        return file != null ? new java.io.FileInputStream(file)
                : resources.openRawResource(R.raw.aurora_wallpaper);
    }

    /**
     * A full-screen picture behind everything. Only the centre that CENTER_CROP shows is decoded,
     * halved while it still covers the screen and then scaled to it, so any picture, however wide or tall,
     * costs a screen-sized bitmap (about 4 MB at 1080p), which is the price of a wallpaper on a 1 GB TV (#166).
     */
    private static Bitmap decodeWallpaper(File file, android.content.res.Resources resources, int screenW, int screenH) {
        try {
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            try (java.io.InputStream in = openWallpaper(file, resources)) {
                android.graphics.BitmapFactory.decodeStream(in, null, opts);
            }
            int w = opts.outWidth, h = opts.outHeight;
            if (w <= 0 || h <= 0 || screenW <= 0 || screenH <= 0) return null;
            // The centred part with the screen's aspect ratio: all that CENTER_CROP would show.
            Rect crop;
            if ((long) w * screenH > (long) h * screenW) {
                int cw = (int) Math.max(1, (long) h * screenW / screenH);
                crop = new Rect((w - cw) / 2, 0, (w - cw) / 2 + cw, h);
            } else {
                int ch = (int) Math.max(1, (long) w * screenH / screenW);
                crop = new Rect(0, (h - ch) / 2, w, (h - ch) / 2 + ch);
            }
            int sample = 1;
            while (crop.width() / (sample * 2) >= screenW && crop.height() / (sample * 2) >= screenH) sample *= 2;
            opts = new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = sample;
            // The bundled 720p gradient needs full colour to avoid bands; it still uses less memory
            // than a 1080p RGB_565 theme picture. External pictures retain the low-memory decode.
            opts.inPreferredConfig = file == null ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
            android.graphics.Bitmap bmp;
            try (java.io.InputStream in = openWallpaper(file, resources)) {
                @SuppressWarnings("deprecation")   // newInstance(InputStream) is API 31
                android.graphics.BitmapRegionDecoder decoder =
                        android.graphics.BitmapRegionDecoder.newInstance(in, false);
                try {
                    bmp = decoder.decodeRegion(crop, opts);
                } finally {
                    decoder.recycle();
                }
            } catch (java.io.IOException unsupported) {
                // A format the region decoder cannot read (GIF, BMP): whole picture, but never a huge one.
                if ((long) (w / sample) * (h / sample) > 4L * screenW * screenH) return null;
                try (java.io.InputStream in = openWallpaper(file, resources)) {
                    bmp = android.graphics.BitmapFactory.decodeStream(in, null, opts);
                }
            }
            if (bmp == null) return null;
            if (bmp.getWidth() > screenW && bmp.getHeight() > screenH) {
                // Down to the smallest size that still covers the screen; CENTER_CROP does the rest.
                float scale = Math.max(screenW / (float) bmp.getWidth(), screenH / (float) bmp.getHeight());
                android.graphics.Bitmap fitted = android.graphics.Bitmap.createScaledBitmap(bmp,
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

    private void showWallpaper(Bitmap bmp) {
        android.widget.ImageView wall = new android.widget.ImageView(this);
        wall.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        wall.setImageBitmap(bmp);
        root.addView(wall, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // CENTER_CROP covers the whole screen, so the colour under an opaque picture would be one more
        // full-screen pass every frame. A picture with transparency keeps the colour behind it.
        if (!bmp.hasAlpha()) root.setBackground(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Only paused, by a side panel such as Wi-Fi settings: a header button keeps its focus instead of
        // handing it to a tile. After an app, or Home pressed, focus returns to the tile as before.
        View focused = getCurrentFocus();
        boolean keepHeader = onlyPaused && (focused == btnInputs || focused == btnWifi || focused == btnSettings);
        onlyPaused = false;
        paused = false;
        tilesReady = false;
        promptsPaused = false;
        glassShown = false;
        glass.setAlpha(0f);
        heroShown = false;
        heroName.setAlpha(0f);
        ambient.setAlpha(0f);
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        if (tilesOutdated()) reloadTiles();
        if (wallpaperLoad != null) {
            Bitmap wall = null;
            try {
                wall = wallpaperLoad.join();
            } catch (RuntimeException ignored) {
                // Never thrown by decodeWallpaper itself; the plain colour stays.
            }
            wallpaperLoad = null;
            if (wall != null) showWallpaper(wall);
        }
        if (savingOrderNow && savingLayout != null) adapter.restoreLayout(savingLayout);
        syncFolderPresentation();
        updateGreeting();
        updateWifiLabel();
        updateInputsButton();
        sky.update();
        handler.postDelayed(minuteTick, MINUTE_MS);

        // Return to the app we left from, or the same slot if that app is gone. Always refocus: after
        // reload() the view that holds focus may no longer show the app it had.
        if (!keepHeader) {
            int pos = adapter.positionOf(returnKey);
            focusTile(pos >= 0 ? pos : returnSlot);
        }
        // The card prompt waits for a laid out grid.
        grid.post(() -> {
            tilesReady = true;
            if (adapter == homeAdapter) for (String key : homeAdapter.keys()) {
                if (homeAdapter.folder(key) != null) { prepareFolder(key); break; }
            }
            if (resumeQuickSettings && !paused) showQuickSettings();
            else showCardPrompt();
        });
    }

    /**
     * Rebuilds the tiles; the module cards the owner chose to show stand in for their app's tile.
     * Every step into the card code runs under CardFeature: if it fails, the tiles are the ordinary
     * ones and cards stay off for this process.
     */
    private void reloadTiles() {
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        // Taken before the reload, so a change that lands while it runs still counts next time.
        try {
            android.content.pm.ChangedPackages changed = getPackageManager().getChangedPackages(packageSequence);
            if (changed != null) packageSequence = changed.getSequenceNumber();
        } catch (RuntimeException ignored) {
            // tilesLoaded alone then decides; the package receiver still reloads on every change.
        }
        tilesLoaded = true;
        final List<Cards.Card> none = Collections.emptyList();
        cards = moduleCards ? CardFeature.run(() -> Cards.discover(this, hiddenApps, Cards.load(Cards.storeFile(this))), none) : none;
        String folder = adapter.activeFolder();
        preparedFolderAdapter = null;
        homeAdapter.reload(cards.isEmpty() ? none : CardFeature.run(() -> Cards.shown(cards), none));
        if (folder != null && homeAdapter.folder(folder) != null) {
            adapter = homeAdapter.folderAdapter(folder);
            preparedFolderAdapter = adapter;
            folderGrid.setAdapter(adapter);
        } else {
            adapter = homeAdapter;
            grid = homeGrid;
            gridHost = homeGridHost;
        }
        lastFocusedKey = returnKey;
        lastFocused = returnSlot;
    }

    /**
     * Whether an app may have been installed, updated, removed or switched on or off since the tiles were
     * built. The package receiver reloads on each such change; this covers a broadcast held back while Home
     * was in the background, without re-reading every app's label and banner on every Home press.
     */
    private boolean tilesOutdated() {
        if (!tilesLoaded) return true;
        try {
            return getPackageManager().getChangedPackages(packageSequence) != null;
        } catch (RuntimeException e) {
            return true;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        paused = true;
        cancelGridTransition();
        folderMotion.cancel();
        updateFolderTitle();
        focusRequest++;
        requestedFocusKey = null;
        dismissTileMenu(false);
        if (editDialog != null) editDialog.dismiss();
        if (movingKey != null && !savingOrderNow) finishMove(false, false);
        handler.removeCallbacks(minuteTick);
        if (quickSettings != null) {
            resumeQuickSettings = true;
            quickSettingsFocus = quickSettings.selectedControl();
            quickSettings.closeWithoutFocus();
        }
        // Nothing of ours stays on screen over another app; an undecided prompt simply returns next time.
        if (cardPrompt != null) cardPrompt.dismiss();
        if (openPopup != null) openPopup.dismiss();
        stopAmbientAnimation();
        onlyPaused = true;
    }

    @Override
    protected void onStop() {
        super.onStop();
        onlyPaused = false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // Launcher preferences are read once, in onCreate; settings reports when any choice changed.
        if (requestCode == LAUNCHER_SETTINGS_REQUEST && resultCode == RESULT_OK) recreate();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) showCardPrompt();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(packageChanges);
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.unregisterCallback(inputCallback);
        if (cm != null) {
            cm.unregisterNetworkCallback(networkCallback);
            cm.unregisterNetworkCallback(vpnCallback);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("quick_settings_open", resumeQuickSettings || quickSettings != null);
        state.putInt("quick_settings_focus", quickSettings != null
                ? quickSettings.selectedControl() : quickSettingsFocus);
        super.onSaveInstanceState(state);
    }

    @Override
    public void onBackPressed() {
        if (folderMotion.isRunning() && adapter.activeFolder() == null) return;
        if (quickSettings != null) quickSettings.dismiss();
        else if (tileMenu != null) dismissTileMenu(true);
        else if (movingKey != null && !savingOrderNow) finishMove(false, true);
        else if (!savingOrderNow && adapter.activeFolder() != null) closeFolder();
        else if (!savingOrderNow && expanded) returnHome();
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onTileFocused(int position, View card) {
        if (requestedFocusKey != null && !requestedFocusKey.equals(adapter.keyAt(position))) return;
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        if (adapter == homeAdapter && !folderMotion.isRunning()) prepareFolder(lastFocusedKey);
        if (folderMotion.isRunning()) {
            // Let the panel own motion while entering or leaving a folder. A second tile zoom
            // would redraw the panel's hardware layer on every frame.
            card.animate().cancel();
            card.setScaleX(AppAdapter.FOCUS_SCALE);
            card.setScaleY(AppAdapter.FOCUS_SCALE);
            TextView caption = ((View) card.getParent()).findViewById(R.id.name);
            caption.animate().cancel();
            caption.setAlpha(1f);
            caption.setTranslationY(dp(4));
        }
        glass.setTint(adapter.ambientColorAt(position));
        alignTilePresentation(position, card);
        if (gridTransition) return;
        ensureTileVisible(position, card);
    }

    /** Presentation callbacks never request scrolling or layout for the RecyclerView. */
    private void alignFocusedTile() {
        if (paused || tileMenu != null || editDialog != null) return;
        View card = getCurrentFocus();
        if (card == null || card.getId() != R.id.card) return;
        RecyclerView.ViewHolder holder = grid.findContainingViewHolder(card);
        if (holder == null) return;
        int position = holder.getBindingAdapterPosition();
        if (position == RecyclerView.NO_POSITION
                || (requestedFocusKey != null && !requestedFocusKey.equals(adapter.keyAt(position)))) return;
        alignTilePresentation(position, card);
    }

    private void alignTilePresentation(int position, View card) {
        if (folderMotion.isRunning()) return;
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        // offsetDescendantRectToMyCoords uses layout bounds, not the host's GPU translation.
        r.offset(0, Math.round(gridHost.getTranslationY()));
        float pad = GlassView.HALO_INSET_DP * density;
        float w = card.getWidth() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float h = card.getHeight() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            glass.setRadius(AppAdapter.cornerRadius(card.getWidth(), density) * AppAdapter.FOCUS_SCALE);
        }
        glassTargetX = x;
        glassTargetY = y;
        if (!glassShown) {
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            glass.animate().alpha(1f).setDuration(motion(reducedMotion ? 100 : 180)).start();
            glassShown = true;
        } else {
            // Coloured light belongs to the selected tile, so it never slides across empty space.
            glass.setTranslationX(x);
            glass.setTranslationY(y);
        }
        onTileFocusFrame(card);
        if (adapter.activeFolder() == null && !expanded && !gridTransition && position < COLUMNS)
            showHero(position, r.top);
        else hideHero();
    }

    private void ensureTileVisible(int position, View card) {
        View item = (View) card.getParent();
        if (adapter.activeFolder() == null && !expanded && position < COLUMNS) {
            // The collapsed dock shares the expanded layout. Only the host translates; do not
            // start another smooth scroll from focus/global-layout callbacks.
            GridLayoutManager layout = (GridLayoutManager) grid.getLayoutManager();
            View first = layout.findViewByPosition(0);
            if (first == null || Math.abs(layout.getDecoratedTop(first) - grid.getPaddingTop()) > 1)
                layout.scrollToPositionWithOffset(0, 0);
        } else {
            if ((expanded || adapter.activeFolder() != null) && position < adapter.columns() * 2) {
                // With a long app list, focus-search can carry the old collapsed offset into the
                // expanded layout. Keep both opening rows, including the entire dock, on screen.
                // Coming back up from the rows below, glide there like every other row does: a
                // jump to the top would snap the dock into view.
                GridLayoutManager layout = (GridLayoutManager) grid.getLayoutManager();
                int dy = distanceToFirstRow(layout, item);
                if (Math.abs(dy) > 1) {
                    if (reducedMotion || !animations) layout.scrollToPositionWithOffset(0, 0);
                    else grid.smoothScrollBy(0, dy);
                }
            } else {
                float pad = GlassView.HALO_INSET_DP * density;
                Rect want = new Rect(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
                grid.requestChildRectangleOnScreen(item, want, reducedMotion || !animations);
            }
        }
    }

    /**
     * How far the grid must scroll for its first row, the dock on Home, to sit at the top again.
     * The item is the focused one in the second row, or null: then 0 once the first row is gone.
     */
    private int distanceToFirstRow(GridLayoutManager layout, View item) {
        View first = layout.findViewByPosition(0);
        if (first != null) return layout.getDecoratedTop(first) - grid.getPaddingTop();
        if (item == null) return 0;
        // Already scrolled out and recycled, so the focused item is in the second row: the first
        // sits right above it, one item tall plus the inset the dock band adds under its row.
        return layout.getDecoratedTop(item) - grid.getPaddingTop()
                - layout.getDecoratedMeasuredHeight(item) - dockDecoration.rowInset();
    }

    @Override public void onTileFocusFrame(View card) {
        if (!card.isFocused() || !glassShown) return;
        // Scale the cached light with the icon's existing GPU animation; no bitmap rebuild per frame.
        glass.setScaleX(card.getScaleX() / AppAdapter.FOCUS_SCALE);
        glass.setScaleY(card.getScaleY() / AppAdapter.FOCUS_SCALE);
        // Advanced effects lift the tile; the light rises with it.
        glass.setTranslationY(glassTargetY + card.getTranslationY());
        if (movingKey != null) {
            int position = adapter.positionOf(movingKey);
            moveIndicator.follow(glassTargetX + glass.getLayoutParams().width / 2f,
                    glassTargetY + glass.getLayoutParams().height / 2f,
                    card.getWidth() * card.getScaleX(), card.getHeight() * card.getScaleY(),
                    position, adapter.getItemCount(), adapter.columns());
        }
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        String label = adapter.labelAt(position);
        if (label.trim().isEmpty()) { hideHero(); return; }
        int color = adapter.ambientColorAt(position);
        float y = dockTopInRoot - 14 * density - 26 * density - heroName.getHeight();
        // Compare with where the shelf is heading, not with what it shows: during a cross-fade the old
        // title is still on screen, and a quick Right-Left would otherwise end on the neighbour's.
        boolean changed = !label.equals(heroLabel);
        heroLabel = label;

        if (reducedMotion || !animations) {
            // Snap the shelf instead of sliding and fading it: each of those frames repaints the
            // full-screen panel, and this switch exists for the GPUs that cannot afford it.
            heroName.animate().cancel();
            stopAmbientAnimation();
            heroName.setText(label);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.setAlpha(1f);
            setAmbient(color);
            if (!heroShown) {
                ambient.animate().cancel();
                ambient.setAlpha(animations ? 0f : 1f);
                if (animations) ambient.animate().alpha(1f).setDuration(160).start();
                heroShown = true;
            }
            return;
        }

        if (!heroShown) {
            stopAmbientAnimation();
            heroName.setText(label);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.animate().alpha(1f).setDuration(260).start();
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(600).start();
            heroShown = true;
        } else if (changed) {
            if (label.contentEquals(heroName.getText())) {
                // Back on the title still showing before its fade-out ended: replacing the animation
                // drops the pending swap to the other title.
                heroName.setY(y);
                heroName.animate().alpha(1f).translationX(0f).setDuration(120).start();
            } else {
                heroName.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                    heroName.setText(label);
                    heroName.setY(y);
                    heroName.setTranslationX(12 * density);
                    heroName.animate().alpha(1f).translationX(0f).setDuration(200).start();
                }).start();
            }
            if (advancedEffects) blendAmbientTo(color);
            else fadeAmbientTo(color);
        } else {
            heroName.setY(y);
        }
    }

    /** Cross-fades the glow to this colour; a newer target replaces a fade still on its way. */
    private void fadeAmbientTo(int color) {
        if (color == ambientTarget) return;
        stopAmbientAnimation();
        ambientTarget = color;
        if (color == ambientColor) {
            // Still showing this colour: replacing the alpha animation drops the pending swap.
            ambient.animate().alpha(1f).setDuration(300).start();
            return;
        }
        ambient.animate().alpha(0.35f).setDuration(200).withEndAction(() -> {
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(500).start();
        }).start();
    }

    /**
     * The glow used to be a full-screen radial GradientDrawable. On Android 10 that drawable is
     * drawn on the CPU, so fading it for half a second redrew 1920×1080 every frame and the
     * highlight looked like it was running at a few frames per second. A small bitmap stretched
     * over the screen is one texture. Tinting the same mask avoids repainting or uploading it
     * when the focused app changes.
     */
    private void setAmbient(int color) {
        ambientTarget = color;
        if (color == ambientColor && ambientGlow != null) return;
        ambientColor = color;
        if (ambientGlow == null) {
            int w = 480;
            int h = 270;
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setShader(new RadialGradient(
                    w * 0.32f, h * 0.42f, w * 0.62f,
                    new int[]{0x5CFFFFFF, 0x26FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.45f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            ambientGlow = new BitmapDrawable(getResources(), bitmap);
            ambient.setImageDrawable(ambientGlow);
        }
        ambientGlow.setTint(0xFF000000 | color);
    }

    /**
     * Advanced effects: the glow stays lit and its tint blends from the colour on screen to the next app's.
     * Each frame only changes the tint of the same mask, so nothing is repainted or uploaded.
     */
    private void blendAmbientTo(int color) {
        if (color == ambientTarget) return;
        stopAmbientAnimation();
        ambient.animate().cancel();
        ambient.animate().alpha(1f).setDuration(180).start();
        ambientTarget = color;
        if (color == ambientColor) return;
        // A quick Right-Left continues from the colour actually on screen.
        ValueAnimator blend = ValueAnimator.ofObject(new ArgbEvaluator(), ambientColor, color);
        blend.setDuration(360);
        blend.addUpdateListener(animation -> {
            setAmbient((int) animation.getAnimatedValue());
            ambientTarget = color;
        });
        ambientAnimator = blend;
        blend.start();
    }

    private void stopAmbientAnimation() {
        if (ambientAnimator == null) return;
        ambientAnimator.removeAllUpdateListeners();
        ambientAnimator.cancel();
        ambientAnimator = null;
    }

    private void hideHero() {
        if (!heroShown) return;
        heroShown = false;
        // Cancel label/colour end actions before fading away so they cannot revive the shelf
        // halfway through expansion.
        stopAmbientAnimation();
        heroName.animate().cancel();
        ambient.animate().cancel();
        heroName.animate().alpha(0f).setDuration(motion(reducedMotion ? 100 : 160)).start();
        ambient.animate().alpha(0f).setDuration(motion(reducedMotion ? 140 : 300)).start();
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().alpha(0f).setDuration(motion(160)).start();
    }

    /** A transition's length under launcher settings' Animations switch: off, it ends on the next frame. */
    private long motion(long millis) {
        return animations ? millis : 0;
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int columns = adapter.columns(), count = adapter.getItemCount();
        AppOrder.Direction direction = GridNavigation.direction(keyCode, rtlAppGrid);
        if (direction == null) return false;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && position < columns) {
            if (adapter.activeFolder() != null) {
                if (folderTitle.getVisibility() != View.VISIBLE) return true;
                focusRequest++;
                requestedFocusKey = null;
                if (folderTitle.requestFocusFromTouch()) playNavigationSound(keyCode);
                return true;
            }
            if (expanded) {
                setExpanded(false);
                focusTile(position);
                playNavigationSound(keyCode);
                return true;
            }
            focusRequest++;
            requestedFocusKey = null;
            if ((btnInputs.getVisibility() == View.VISIBLE ? btnInputs : btnWifi).requestFocusFromTouch())
                playNavigationSound(keyCode);
            return true;
        }
        int target = adapter.activeFolder() == null ? AppOrder.target(position, count, columns, direction)
                : AppOrder.centeredTarget(position, count, columns, direction);
        if (target != position && adapter.activeFolder() == null && !expanded && target >= columns)
            setExpanded(true);
        focusTile(target);
        if (target != position) playNavigationSound(keyCode);
        return true;
    }

    private void playNavigationSound(int keyCode) {
        if (!navigationSounds) return;
        // Our D-pad handlers consume the event before Android's focus search can play its sound.
        // Use the View API so navigation still respects the TV's system sound setting.
        int effect;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT: effect = SoundEffectConstants.NAVIGATION_LEFT; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: effect = SoundEffectConstants.NAVIGATION_RIGHT; break;
            case KeyEvent.KEYCODE_DPAD_UP: effect = SoundEffectConstants.NAVIGATION_UP; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: effect = SoundEffectConstants.NAVIGATION_DOWN; break;
            default: return;
        }
        root.playSoundEffect(effect);
    }

    private void updateGridPadding() {
        int want = dp(adapter.activeFolder() != null ? 18 : 24);
        if (grid.getPaddingTop() != want) grid.setPadding(0, want, 0, grid.getPaddingBottom());
        if (!gridTransition) gridHost.setTranslationY(adapter.activeFolder() == null && !expanded
                ? collapsedGridOffset() : 0f);
    }

    private float collapsedGridOffset() {
        int inner = grid.getWidth() - grid.getPaddingLeft() - grid.getPaddingRight();
        int itemH = AppAdapter.itemHeightPx(AppAdapter.tileHeightPx(
                AppAdapter.tileWidthPx(inner, COLUMNS, density)), density);
        // Leave just 12dp of the next row's artwork below any fully visible rows. Its top
        // starts after the dock decoration's inset and the item's own 6dp top padding.
        int nextRowSpace = dockDecoration.rowInset() + dp(6 + 12);
        return Math.max(0, grid.getHeight() - grid.getPaddingTop()
                - itemH * (1 + rowsBelowDock) - nextRowSpace);
    }

    private void cancelGridTransition() {
        gridTransitionRequest++;
        if (gridSlide != null) {
            gridSlide.removeAllListeners();
            gridSlide.removeAllUpdateListeners();
            gridSlide.cancel();
            gridSlide = null;
        }
        gridTransition = false;
    }

    private void setExpanded(boolean value) {
        if (expanded == value) return;
        // Home and the expanded grid use identical bounds, padding, and row positions. One GPU
        // translation owns the slide; reversals start at its current value without a relayout snap.
        cancelGridTransition();
        expanded = value;
        gridTransition = true;
        int transitionRequest = gridTransitionRequest;
        grid.stopScroll();
        hideHero();
        topBar.animate().cancel();
        topBar.setVisibility(View.VISIBLE);
        topBar.animate().alpha(value ? 0f : 1f).setDuration(motion(160)).withEndAction(() -> {
            if (transitionRequest == gridTransitionRequest && expanded) topBar.setVisibility(View.INVISIBLE);
        }).start();
        float target = value ? 0f : collapsedGridOffset();
        // Up on the dock can come before the glide back to the first row has finished. The slide
        // carries the rest of that scroll, so the collapsed dock lands at the top without a jump.
        int scroll = value ? 0 : distanceToFirstRow((GridLayoutManager) grid.getLayoutManager(), null);
        int[] unscrolled = {scroll};
        gridSlide = ValueAnimator.ofFloat(gridHost.getTranslationY(), target);
        gridSlide.setDuration(motion(320));
        gridSlide.setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f));
        gridSlide.addUpdateListener(animation -> {
            if (transitionRequest != gridTransitionRequest || gridSlide != animation) return;
            gridHost.setTranslationY((Float) animation.getAnimatedValue());
            if (unscrolled[0] != 0) {
                int left = Math.round(scroll * (1f - animation.getAnimatedFraction()));
                grid.scrollBy(0, unscrolled[0] - left);
                unscrolled[0] = left;
            }
            alignFocusedTile();
        });
        gridSlide.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (transitionRequest != gridTransitionRequest) return;
                gridSlide = null;
                gridTransition = false;
                View card = getCurrentFocus();
                RecyclerView.ViewHolder holder = card == null ? null : grid.findContainingViewHolder(card);
                if (holder != null && holder.getBindingAdapterPosition() != RecyclerView.NO_POSITION
                        && requestedFocusKey == null) onTileFocused(holder.getBindingAdapterPosition(), card);
                else alignFocusedTile();
            }
        });
        gridSlide.start();
    }

    @Override public void onFolderOpened(String key) {
        if (savingOrderNow || adapter.folder(key) == null) return;
        prepareFolder(key);
        folderReturnKey = key;
        folderBackdrop.setVisibility(View.VISIBLE);
        cancelGridTransition();
        homeGridHost.setTranslationY(expanded ? 0f : collapsedGridOffset());
        root.requestFocusFromTouch();
        homeGrid.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        adapter = preparedFolderAdapter;
        grid = folderGrid;
        gridHost = folderGridHost;
        folderGrid.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        syncFolderPresentation();
        homeGridHost.setVisibility(View.VISIBLE);
        animateFolder(true);
        focusTile(0);
    }

    private void syncFolderPresentation() {
        folderMotion.cancel();
        cancelGridTransition();
        topBar.animate().cancel();
        boolean inFolder = adapter.activeFolder() != null;
        homeGrid.setDescendantFocusability(inFolder ? ViewGroup.FOCUS_BLOCK_DESCENDANTS : ViewGroup.FOCUS_AFTER_DESCENDANTS);
        folderGrid.setDescendantFocusability(inFolder ? ViewGroup.FOCUS_AFTER_DESCENDANTS : ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        folderGridHost.setVisibility(inFolder ? View.VISIBLE : View.INVISIBLE);
        homeGridHost.setVisibility(inFolder ? View.INVISIBLE : View.VISIBLE);
        HomeLayout.Folder folder = adapter.folder(adapter.activeFolder());
        folderPanel.setVisibility(inFolder ? View.VISIBLE : View.GONE);
        folderBackdrop.setAlpha(inFolder ? 1f : 0f);
        folderBackdrop.setVisibility(inFolder ? View.VISIBLE : View.GONE);
        folderTitle.setVisibility(folder != null ? View.VISIBLE : View.GONE);
        updateFolderTitle();
        topBar.setVisibility(inFolder || expanded ? View.INVISIBLE : View.VISIBLE);
        topBar.setAlpha(inFolder || expanded ? 0f : 1f);
        ((GridLayoutManager) grid.getLayoutManager()).setSpanCount(adapter.columns());
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) gridHost.getLayoutParams();
        lp.setMarginStart(dp(inFolder ? 180 : 24));
        lp.setMarginEnd(dp(inFolder ? 180 : 24));
        lp.topMargin = dp(inFolder ? 94 : 12);
        lp.bottomMargin = dp(inFolder ? 40 : 0);
        gridHost.setLayoutParams(lp);
        if (inFolder) layoutFolderContents();
        else {
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            gridHost.setLayoutParams(lp);
            grid.setPadding(0, dp(24), 0, dp(40));
        }
        hideHero();
        heroName.animate().cancel();
        heroName.setAlpha(0f);
        ambient.animate().cancel();
        ambient.setAlpha(0f);
        hideGlass();
        grid.invalidateItemDecorations();
        ((GridLayoutManager) grid.getLayoutManager()).scrollToPositionWithOffset(0, 0);
        grid.post(() -> {
            updateGridPadding();
        });
    }

    private void closeFolder() {
        closeFolder(folderReturnKey);
    }

    private void closeFolder(String focus) {
        root.requestFocusFromTouch();
        for (int i = 0; i < folderGrid.getChildCount(); i++) {
            View child = folderGrid.getChildAt(i);
            child.findViewById(R.id.card).animate().cancel();
            child.findViewById(R.id.name).animate().cancel();
        }
        focusRequest++;
        requestedFocusKey = null;
        adapter = homeAdapter;
        grid = homeGrid;
        gridHost = homeGridHost;
        homeGridHost.setVisibility(View.VISIBLE);
        homeGrid.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        folderGrid.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        if (adapter.positionOf(focus) >= COLUMNS && !expanded) {
            expanded = true;
            updateGridPadding();
        }
        topBar.setVisibility(expanded ? View.INVISIBLE : View.VISIBLE);
        topBar.setAlpha(expanded ? 0f : 1f);
        animateFolder(false);
        focusKey(focus, 0);
    }

    private void animateFolder(boolean opening) {
        glass.animate().cancel();
        glass.setAlpha(0f);
        glassShown = false;
        folderMotion.start(opening, () -> {
            if (!opening) {
                folderGridHost.setVisibility(View.INVISIBLE);
                folderPanel.setVisibility(View.GONE);
                folderTitle.setVisibility(View.GONE);
                folderBackdrop.setVisibility(View.GONE);
            } else homeGridHost.setVisibility(View.INVISIBLE);
            updateFolderTitle();
            alignFocusedTile();
        });
    }

    private void prepareFolder(String key) {
        if (homeAdapter.folder(key) == null || adapter != homeAdapter) return;
        if (preparedFolderAdapter != null && key.equals(preparedFolderAdapter.activeFolder())) return;
        preparedFolderAdapter = homeAdapter.folderAdapter(key);
        folderGrid.setAdapter(preparedFolderAdapter);
        folderGridHost.setVisibility(View.INVISIBLE);
        layoutFolderContents();
    }

    private void layoutFolderContents() {
        AppAdapter contents = (AppAdapter) folderGrid.getAdapter();
        if (contents == null || contents.activeFolder() == null || root.getWidth() <= 0 || root.getHeight() <= 0) return;
        int innerWidth = root.getWidth() - dp(360);
        int tileHeight = AppAdapter.tileHeightPx(AppAdapter.tileWidthPx(innerWidth, contents.columns(), density));
        int rows = (contents.getItemCount() + contents.columns() - 1) / contents.columns();
        int maxPanelHeight = root.getHeight() - dp(106);
        int gridHeight = Math.min(maxPanelHeight - dp(32),
                rows * AppAdapter.itemHeightPx(tileHeight, density) + dp(36));
        int panelHeight = gridHeight + dp(32);
        int panelTop = dp(78) + (maxPanelHeight - panelHeight) / 2;
        FrameLayout.LayoutParams panel = (FrameLayout.LayoutParams) folderPanel.getLayoutParams();
        panel.height = panelHeight;
        panel.topMargin = panelTop;
        panel.bottomMargin = 0;
        folderPanel.setLayoutParams(panel);
        FrameLayout.LayoutParams host = (FrameLayout.LayoutParams) folderGridHost.getLayoutParams();
        host.height = gridHeight;
        host.topMargin = panelTop + dp(16);
        host.bottomMargin = 0;
        folderGridHost.setLayoutParams(host);
        folderGrid.setPadding(0, dp(18), 0, dp(18));
        FrameLayout.LayoutParams title = (FrameLayout.LayoutParams) folderTitle.getLayoutParams();
        title.topMargin = panelTop - dp(64);
        folderTitle.setLayoutParams(title);
        folderGrid.invalidateItemDecorations();
    }

    private void updateFolderTitle() {
        if (adapter == null) return;
        HomeLayout.Folder folder = adapter.folder(adapter.activeFolder());
        boolean unnamed = folder == null || folder.name.isEmpty();
        boolean revealed = !unnamed || folderTitle.isFocused();
        folderTitle.setText(unnamed ? folderTitle.isFocused() ? getString(R.string.untitled_folder) : "" : folder.name);
        folderTitle.setContentDescription(unnamed ? getString(R.string.untitled_folder) : folder.name);
        folderTitle.setBackgroundResource(revealed ? R.drawable.pill_bg : 0);
        // Keep the empty title in the D-pad path, but draw nothing until the user goes Up to it.
        if (!folderMotion.isRunning()) folderTitle.setAlpha(revealed ? 1f : 0f);
    }

    private void returnHome() {
        // Older RecyclerView/Android TV focus recovery can pull the viewport back to a recycled
        // bottom-row child while Home is laying out. Release that child before resetting the grid.
        root.requestFocusFromTouch();
        adapter = homeAdapter;
        grid = homeGrid;
        gridHost = homeGridHost;
        homeGrid.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        // Android 9 delivers a new Home intent while paused, then calls onResume. Persist the
        // reset now so onResume cannot restore the app from the old, scrolled row.
        lastFocused = 0;
        lastFocusedKey = adapter.keyAt(0);
        expanded = false;
        syncFolderPresentation();
        ((GridLayoutManager) grid.getLayoutManager()).scrollToPositionWithOffset(0, 0);
        focusTile(0);
    }

    private void focusTile(int position) {
        if (paused) return;
        if (adapter.getItemCount() == 0) {
            btnWifi.requestFocus();
            return;
        }
        int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        String key = adapter.keyAt(pos);
        lastFocused = pos;
        lastFocusedKey = key;
        requestedFocusKey = key;
        int request = ++focusRequest;
        grid.stopScroll();
        RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(pos);
        if (holder == null) grid.scrollToPosition(pos);
        requestTileFocus(key, request, 0);
    }

    private void requestTileFocus(String key, int request, int attempt) {
        if (paused || request != focusRequest || tileMenu != null || editDialog != null) return;
        if (attempt > 24) { requestedFocusKey = null; return; }
        int pos = adapter.positionOf(key);
        if (pos < 0) { requestedFocusKey = null; return; }
        RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(pos);
        if (holder != null && !grid.isComputingLayout() && !grid.hasPendingAdapterUpdates()) {
            View card = holder.itemView.findViewById(R.id.card);
            // A remote/mouse click on a dialog can leave the window in touch mode. Explicitly
            // return focus to the requested tile before OK can activate a different app.
            if (card.requestFocusFromTouch()) {
                // A successful request is not proof that focus survived layout. Older TV builds
                // can restore the previous header button later in the same frame. Keep the intent
                // until the next traversal finishes, then retry if the card lost focus.
                grid.postOnAnimation(() -> grid.post(() -> {
                    if (paused || request != focusRequest || tileMenu != null || editDialog != null) return;
                    int currentPosition = adapter.positionOf(key);
                    RecyclerView.ViewHolder current = currentPosition < 0 ? null
                            : grid.findViewHolderForAdapterPosition(currentPosition);
                    if (current == holder && holder.getBindingAdapterPosition() == currentPosition
                            && card.isFocused() && !grid.hasPendingAdapterUpdates()) {
                        requestedFocusKey = null;
                        onTileFocused(currentPosition, card);
                    } else {
                        requestTileFocus(key, request, attempt + 1);
                    }
                }));
                return;
            }
        }
        // Attached views can briefly refuse focus during a rebuild as well. Do not leave the
        // pending key stuck forever just because a holder happened to exist on the first attempt.
        if (attempt < 24) {
            grid.postOnAnimation(() -> requestTileFocus(key, request, attempt + 1));
        } else {
            requestedFocusKey = null;
        }
    }

    private void focusKey(String key, int fallback) {
        if (paused || tileMenu != null) return;
        int pos = adapter.positionOf(key);
        focusTile(pos >= 0 ? pos : fallback);
    }

    // ---- app actions and remote reordering -------------------------------------------------------

    private boolean handleTopBarDirection(View focused, KeyEvent event) {
        if (focused != btnInputs && focused != btnWifi && focused != btnSettings) return false;
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
            focusTile(Math.min(lastFocused, COLUMNS - 1));
            if (adapter.getItemCount() > 0) playNavigationSound(code);
        } else {
            // Explicit header navigation also cancels any deferred tile restoration from resume.
            focusRequest++;
            requestedFocusKey = null;
            View next = focused;
            // Hebrew lays the buttons out right to left: Settings, network, Inputs from the left edge.
            if (rtlMenuBar && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT))
                code = code == KeyEvent.KEYCODE_DPAD_LEFT ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (focused == btnSettings) next = btnWifi;
                else if (focused == btnWifi && btnInputs.getVisibility() == View.VISIBLE) next = btnInputs;
            } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (focused == btnInputs) next = btnWifi;
                else if (focused == btnWifi) next = btnSettings;
            }
            if (next.requestFocusFromTouch() && next != focused) playNavigationSound(event.getKeyCode());
        }
        return true;
    }

    private int dp(float value) { return Math.round(value * density); }

    private static boolean isConfirm(int key) {
        return key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                || key == KeyEvent.KEYCODE_NUMPAD_ENTER || key == KeyEvent.KEYCODE_BUTTON_A;
    }

    @Override
    public boolean onTileClicked() {
        if (folderMotion.isRunning()) return true;
        if (movingKey != null) { saveMove(); return true; }
        return tileMenu != null || savingOrderNow;
    }

    @android.annotation.SuppressLint("RtlHardcoded")
    @Override
    public void onTileLongPressed(int position, View card) {
        if (movingKey != null || tileMenu != null || savingOrderNow || paused || folderMotion.isRunning()) return;
        String key = adapter.keyAt(position);
        if (key == null) return;
        final int fallback = position;
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(8), dp(8), dp(8));
        TextView title = new TextView(this);
        title.setText(adapter.labelAt(position));
        title.setTextSize(13);
        title.setTextColor(0xCFFFFFFF);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(dp(14), dp(8), dp(14), dp(12));
        panel.addView(title);
        PopupWindow popup = new PopupWindow(panel, dp(264), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(dp(16));
        popup.setOutsideTouchable(true);
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        popup.setAnimationStyle(0);
        tileMenu = popup;
        restoreMenuFocus = true;
        menuReleaseGuard = true;
        TextView move = addAppAction(panel, R.string.move_app, R.drawable.ic_move, () -> {
            dismissTileMenu(false);
            beginMove(key);
        });
        if (adapter.isFolder(position)) {
            addAppAction(panel, R.string.rename_folder, R.drawable.ic_folder, () -> {
                dismissTileMenu(false);
                renameFolder(key);
            });
        } else if (adapter.isApp(position)) {
            boolean inFolder = adapter.activeFolder() != null;
            addAppAction(panel, inFolder ? R.string.remove_from_folder : R.string.move_to, R.drawable.ic_folder, () -> {
                dismissTileMenu(false);
                if (inFolder) removeFolderItem(key);
                else showMoveTo(key);
            });
            addAppAction(panel, R.string.app_settings, R.drawable.ic_settings, () -> {
                dismissTileMenu(false);
                adapter.openAppSettings(key);
            });
        }
        popup.setOnDismissListener(() -> {
            if (tileMenu == popup) tileMenu = null;
            adapter.setMenuKey(null);
            if (restoreMenuFocus && !paused) focusKey(key, fallback);
        });
        // Use transformed screen bounds so the gap starts at the focused tile's visible edge.
        Rect tile = new Rect();
        card.getGlobalVisibleRect(tile);
        int[] screen = new int[2], window = new int[2];
        root.getLocationOnScreen(screen);
        root.getLocationInWindow(window);
        int inset = dp(16), gap = dp(12);
        int leftSpace = tile.left - screen[0] - inset - gap;
        int rightSpace = screen[0] + root.getWidth() - inset - tile.right - gap;
        boolean right = rightSpace >= dp(264) || (leftSpace < dp(264) && rightSpace >= leftSpace);
        int width = Math.min(dp(264), Math.max(1, right ? rightSpace : leftSpace));
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(root.getHeight() - inset * 2, View.MeasureSpec.AT_MOST));
        int height = panel.getMeasuredHeight();
        popup.setWidth(width);
        popup.setHeight(height);
        int x = right ? tile.right + gap : tile.left - gap - width;
        int y = Math.max(screen[1] + inset, Math.min(tile.centerY() - height / 2,
                screen[1] + root.getHeight() - inset - height));
        panel.setBackground(new FrostedGlassDrawable(root,
                new Rect(x - screen[0], y - screen[1], x - screen[0] + width, y - screen[1] + height),
                density, 26));
        adapter.setMenuKey(key);
        popup.showAtLocation(root, Gravity.TOP | Gravity.LEFT,
                x - screen[0] + window[0], y - screen[1] + window[1]);
        panel.setAlpha(0f);
        panel.setPivotX(right ? 0 : width);
        panel.setPivotY(height / 2f);
        panel.setScaleX(.94f); panel.setScaleY(.94f);
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(motion(180))
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        move.requestFocusFromTouch();
    }

    private void showMoveTo(String key) {
        if (savingOrderNow) return;
        HomeLayout layout = adapter.snapshotLayout();
        List<String> labels = new ArrayList<>(), destinations = new ArrayList<>();
        labels.add(getString(R.string.new_folder));
        destinations.add("new");
        if (adapter.activeFolder() != null) {
            labels.add(getString(R.string.move_to_home));
            destinations.add(null);
        }
        for (HomeLayout.Folder folder : layout.folders.values()) {
            if (folder.id.equals(adapter.activeFolder())) continue;
            labels.add(folder.name.isEmpty() ? getString(R.string.unnamed_folder) : folder.name);
            destinations.add(folder.id);
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.move_to)
                .setAdapter(new android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
                    @Override public View getView(int position, View convertView, ViewGroup parent) {
                        TextView row = (TextView) super.getView(position, convertView, parent);
                        row.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
                        row.setTextSize(17);
                        row.setTextColor(getResources().getColorStateList(R.color.glass_action_text, getTheme()));
                        row.setBackgroundResource(R.drawable.glass_menu_row);
                        row.setMinHeight(dp(52));
                        row.setPadding(dp(18), dp(12), dp(18), dp(12));
                        android.graphics.drawable.Drawable icon = getDrawable(R.drawable.ic_folder).mutate();
                        icon.setBounds(0, 0, dp(22), dp(22));
                        row.setCompoundDrawablesRelative(icon, null, null, null);
                        row.setCompoundDrawablePadding(dp(14));
                        row.setCompoundDrawableTintList(getResources().getColorStateList(R.color.glass_action_text, getTheme()));
                        return row;
                    }
                }, (d, which) -> {
                    editDialog = null;
                    String destination = destinations.get(which);
                    if ("new".equals(destination)) editFolderName(key, null);
                    else {
                        HomeLayout draft = adapter.snapshotLayout();
                        draft.moveTo(key, destination);
                        saveFolderChange(draft, destination == null ? key : destination);
                    }
                }).setNegativeButton(R.string.cancel, null).create();
        showEditDialog(dialog, key, false);
    }

    private void renameFolder(String key) {
        if (!savingOrderNow && adapter.folder(key) != null) editFolderName(null, key);
    }

    private void editFolderName(String appKey, String folderKey) {
        HomeLayout.Folder folder = adapter.folder(folderKey);
        if (folderKey != null && folder == null) return;
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN);
        input.setTextColor(Color.WHITE);
        input.setTextSize(22);
        input.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
        input.setPadding(dp(24), dp(16), dp(24), dp(16));
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(40)});
        input.setHint(R.string.folder_name);
        input.setText(folder != null ? folder.name : "");
        input.selectAll();
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(folder != null ? R.string.rename_folder : R.string.new_folder)
                .setView(input).setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.done, null).create();
        showEditDialog(dialog, appKey != null ? appKey : folderKey, true);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            HomeLayout draft = adapter.snapshotLayout();
            String focus;
            if (folderKey == null) focus = draft.createFolder(appKey, name);
            else { draft.folders.get(folderKey).name = name; focus = folderKey; }
            editDialog = null;
            dialog.dismiss();
            saveFolderChange(draft, focus);
        });
        input.setOnEditorActionListener((v, action, event) -> {
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_DONE) return false;
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            return true;
        });
        input.requestFocus();
    }

    private void showEditDialog(AlertDialog dialog, String focus, boolean keyboard) {
        editDialog = dialog;
        boolean returnToTitle = folderTitle.isFocused();
        hideGlass();
        dialog.setOnDismissListener(d -> {
            if (editDialog == dialog) editDialog = null;
            if (!paused && !savingOrderNow && editDialog == null) {
                if (returnToTitle && adapter.activeFolder() != null) folderTitle.requestFocusFromTouch();
                else focusKey(focus, lastFocused);
            }
        });
        if (keyboard && dialog.getWindow() != null) {
            // Anchor the editor above the TV keyboard; neither window may pan or resize Home.
            Window window = dialog.getWindow();
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                    | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            window.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            android.view.WindowManager.LayoutParams params = window.getAttributes();
            params.y = dp(24);
            window.setAttributes(params);
        }
        dialog.show();
        if (!keyboard && dialog.getListView() != null) {
            dialog.getListView().setSelector(new ColorDrawable(Color.TRANSPARENT));
            dialog.getListView().setPadding(dp(12), 0, dp(12), dp(4));
            dialog.getListView().setDivider(null);
            dialog.getListView().setSelection(0);
            dialog.getListView().requestFocusFromTouch();
        }
        if (dialog.getWindow() != null) {
            // Keep the first measured frame opaque, then capture only the dialog's footprint.
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(0xFF343840));
            dialog.getWindow().setLayout(dp(440), ViewGroup.LayoutParams.WRAP_CONTENT);
            dialog.getWindow().setDimAmount(.32f);
            View decor = dialog.getWindow().getDecorView();
            int[] previous = {Integer.MIN_VALUE, 0, 0, 0};
            Runnable material = () -> {
                if (!dialog.isShowing() || decor.getWidth() <= 0 || decor.getHeight() <= 0) return;
                int[] location = new int[2], home = new int[2];
                decor.getLocationOnScreen(location);
                root.getLocationOnScreen(home);
                int x = location[0] - home[0], y = location[1] - home[1];
                int width = decor.getWidth(), height = decor.getHeight();
                if (previous[0] == x && previous[1] == y && previous[2] == width && previous[3] == height) return;
                previous[0] = x; previous[1] = y; previous[2] = width; previous[3] = height;
                dialog.getWindow().setBackgroundDrawable(new FrostedGlassDrawable(root,
                        new Rect(x, y, x + width, y + height), density, 28));
            };
            decor.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> material.run());
            decor.post(material);
            for (int which : new int[]{AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_POSITIVE}) {
                android.widget.Button button = dialog.getButton(which);
                if (button == null) continue;
                button.setAllCaps(false);
                button.setTextSize(16);
                button.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
                button.setTextColor(getResources().getColorStateList(R.color.glass_action_text, getTheme()));
                button.setBackgroundResource(R.drawable.glass_menu_row);
                button.setPadding(dp(18), dp(10), dp(18), dp(10));
            }
        }
    }

    private void removeFolderItem(String key) {
        if (savingOrderNow || key == null || adapter.activeFolder() == null) return;
        int position = adapter.positionOf(key);
        if (position < 0) return;
        String label = adapter.labelAt(position);
        List<String> remaining = adapter.keys();
        remaining.remove(key);
        String next = remaining.isEmpty() ? key : remaining.get(Math.min(position, remaining.size() - 1));
        HomeLayout draft = adapter.snapshotLayout();
        draft.moveTo(key, null);
        saveFolderChange(draft, next, !remaining.isEmpty(), label);
    }

    private void saveFolderChange(HomeLayout draft, String focus) {
        saveFolderChange(draft, focus, false, null);
    }

    private void saveFolderChange(HomeLayout draft, String focus, boolean keepFolder, String removedLabel) {
        if (savingOrderNow) return;
        savingOrderNow = true;
        savingLayout = draft.copy();
        final String originalFolder = adapter.activeFolder();
        AppOrderStore.WRITER.execute(() -> {
            boolean saved = layoutStore.write(draft);
            handler.post(() -> {
                if (isDestroyed()) return;
                savingOrderNow = false;
                savingLayout = null;
                if (saved) {
                    preparedFolderAdapter = null;
                    boolean staying = originalFolder != null && draft.folders.containsKey(originalFolder)
                            && (keepFolder || originalFolder.equals(focus));
                    if (adapter != homeAdapter) homeAdapter.restoreLayout(draft);
                    if (originalFolder != null && !staying) {
                        closeFolder(focus);
                    } else if (staying) {
                        adapter.restoreLayout(draft);
                        preparedFolderAdapter = adapter;
                        layoutFolderContents();
                        updateFolderTitle();
                    } else {
                        adapter.restoreLayout(draft);
                        syncFolderPresentation();
                    }
                    if (!paused) {
                        if (staying && originalFolder.equals(focus)) folderTitle.requestFocusFromTouch();
                        else focusKey(focus, lastFocused);
                        if (removedLabel != null) root.announceForAccessibility(
                                getString(R.string.folder_item_removed, removedLabel));
                    }
                } else if (!paused) {
                    Toast.makeText(this, R.string.folder_save_failed, Toast.LENGTH_LONG).show();
                    focusKey(lastFocusedKey, lastFocused);
                }
                if (refreshPending) {
                    refreshPending = false;
                    reloadTiles();
                    syncFolderPresentation();
                    if (!paused) focusKey(focus, 0);
                }
            });
        });
    }

    private TextView addAppAction(LinearLayout panel, int title, int icon, Runnable action) {
        TextView row = new TextView(this);
        row.setText(title);
        row.setTextSize(16);
        row.setTextColor(getResources().getColorStateList(R.color.app_action_text, getTheme()));
        row.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinHeight(dp(50));
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        android.graphics.drawable.Drawable glyph = getDrawable(icon).mutate();
        glyph.setBounds(0, 0, dp(20), dp(20));
        row.setCompoundDrawablesRelative(glyph, null, null, null);
        row.setCompoundDrawablePadding(dp(12));
        row.setCompoundDrawableTintList(getResources().getColorStateList(R.color.app_action_text, getTheme()));
        row.setBackgroundResource(R.drawable.glass_menu_row);
        row.setFocusable(true);
        row.setClickable(true);
        row.setOnClickListener(v -> action.run());
        row.setOnKeyListener((v, code, event) -> {
            if (isConfirm(code)) {
                // The release of the long press must not activate the newly focused menu action.
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                    menuReleaseGuard = false;
                if (menuReleaseGuard) {
                    if (event.getAction() == KeyEvent.ACTION_UP) menuReleaseGuard = false;
                    return true;
                }
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) action.run();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) return true;
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    int index = panel.indexOfChild(row);
                    int next = code == KeyEvent.KEYCODE_DPAD_UP ? index - 1 : index + 1;
                    if (next >= 1 && next < panel.getChildCount()) panel.getChildAt(next).requestFocus();
                }
                return true;
            }
            return false;
        });
        panel.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private void dismissTileMenu(boolean restoreFocus) {
        restoreMenuFocus = restoreFocus;
        if (tileMenu != null) tileMenu.dismiss();
    }

    private void beginMove(String key) {
        if (adapter.positionOf(key) < 0) return;
        moveSnapshot = adapter.keys();
        movingKey = key;
        moveScrollX = moveScrollY = 0;
        confirmDown = false;
        adapter.setMovingKey(key);
        moveIndicator.setVisibility(View.VISIBLE);
        focusKey(key, 0);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (quickSettings != null)
            return quickSettings.dispatchKeyEvent(event) || super.dispatchKeyEvent(event);
        int code = event.getKeyCode();
        if (folderMotion.isRunning() && code != KeyEvent.KEYCODE_BACK
                && (isConfirm(code) || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) return true;
        if (code == KeyEvent.KEYCODE_SETTINGS) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) openQuickSettings();
            return true;
        }
        if (android.util.Log.isLoggable("TvHomeMotion", android.util.Log.DEBUG)
                && event.getAction() == KeyEvent.ACTION_DOWN)
            android.util.Log.d("TvHomeMotion", "key=" + code + " expanded=" + expanded
                    + " transition=" + gridTransition + " offset=" + gridHost.getTranslationY()
                    + " requested=" + requestedFocusKey + " last=" + lastFocused
                    + " paused=" + paused + " focus=" + getCurrentFocus());
        if (movingKey == null) {
            boolean arrow = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                    || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
            if (arrow && !paused && tileMenu == null && editDialog == null && !savingOrderNow) {
                // Visible header focus takes priority over a stale pending tile request.
                if (handleTopBarDirection(getCurrentFocus(), event)) return true;
                // Continue from the latest requested key even when RecyclerView still holds focus
                // on an old/recycled child. Android's geometric focus search cannot do that.
                int position = adapter.positionOf(requestedFocusKey);
                if (position < 0) {
                    View focused = getCurrentFocus();
                    RecyclerView.ViewHolder holder = focused == null ? null
                            : grid.findContainingViewHolder(focused);
                    if (holder != null) position = holder.getBindingAdapterPosition();
                    else if (focused == root || focused == grid || focused == null)
                        position = adapter.positionOf(lastFocusedKey);
                }
                if (position >= 0) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) onTileVerticalKey(position, code);
                    return true;
                }
            }
            return super.dispatchKeyEvent(event);
        }
        if (isConfirm(code)) {
            if (!savingOrderNow) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) confirmDown = true;
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    boolean confirm = confirmDown && !event.isCanceled();
                    confirmDown = false;
                    if (confirm) saveMove();
                }
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (!savingOrderNow && event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) finishMove(false, true);
            return true;
        }
        AppOrder.Direction direction = GridNavigation.direction(code, rtlAppGrid);
        if (direction == null) return super.dispatchKeyEvent(event);
        if (event.getAction() == KeyEvent.ACTION_DOWN && !savingOrderNow) {
            int from = adapter.positionOf(movingKey);
            int to = adapter.activeFolder() == null
                    ? AppOrder.target(from, adapter.getItemCount(), adapter.columns(), direction)
                    : AppOrder.centeredTarget(from, adapter.getItemCount(), adapter.columns(), direction);
            if (adapter.activeFolder() == null && !expanded && to >= COLUMNS) setExpanded(true);
            if (to != from) {
                animateMove(from, to);
                focusKey(movingKey, to);
                playNavigationSound(code);
            }
        }
        return true;
    }

    private void animateMove(int from, int to) {
        int scrollX = moveScrollX, scrollY = moveScrollY;
        java.util.Map<String, android.graphics.Point> before = new java.util.HashMap<>();
        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            int pos = grid.getChildAdapterPosition(child);
            if (pos < 0) continue;
            before.put(adapter.keyAt(pos), new android.graphics.Point(
                    child.getLeft() + Math.round(child.getTranslationX()),
                    child.getTop() + Math.round(child.getTranslationY())));
            child.animate().cancel();
            child.setTranslationX(0f);
            child.setTranslationY(0f);
        }
        adapter.move(from, to);
        grid.invalidateItemDecorations();
        int animationRequest = ++moveAnimationRequest;
        grid.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                grid.getViewTreeObserver().removeOnPreDrawListener(this);
                if (paused || movingKey == null || animationRequest != moveAnimationRequest) return true;
                for (int i = 0; i < grid.getChildCount(); i++) {
                    View child = grid.getChildAt(i);
                    int pos = grid.getChildAdapterPosition(child);
                    String key = adapter.keyAt(pos);
                    android.graphics.Point old = before.get(key);
                    if (old == null || key.equals(movingKey)) continue;
                    // Focus may scroll before this layout is drawn. Subtract that shared movement
                    // so unchanged rows do not animate back to their old viewport coordinates.
                    child.setTranslationX(old.x - (moveScrollX - scrollX) - child.getLeft());
                    child.setTranslationY(old.y - (moveScrollY - scrollY) - child.getTop());
                    child.animate().translationX(0).translationY(0).setDuration(motion(140)).start();
                }
                return true;
            }
        });
    }

    private void saveMove() {
        if (movingKey == null || savingOrderNow) return;
        savingOrderNow = true;
        savingLayout = adapter.snapshotLayout();
        final HomeLayout confirmed = savingLayout.copy();
        AppOrderStore.WRITER.execute(() -> {
            boolean saved = layoutStore.write(confirmed);
            handler.post(() -> {
                if (isDestroyed()) return;
                savingOrderNow = false;
                savingLayout = null;
                if (saved) {
                    if (adapter != homeAdapter) homeAdapter.restoreLayout(confirmed);
                    finishMove(true, !paused);
                } else if (paused) {
                    finishMove(false, false);
                } else {
                    Toast.makeText(this, R.string.app_order_failed, Toast.LENGTH_LONG).show();
                }
                if (refreshPending) {
                    refreshPending = false;
                    if (movingKey != null) finishMove(false, false);
                    String key = lastFocusedKey;
                    int slot = lastFocused;
                    reloadTiles();
                    syncFolderPresentation();
                    if (!paused) focusKey(key, slot);
                }
            });
        });
    }

    private void finishMove(boolean saved, boolean restoreFocus) {
        String key = movingKey;
        int fallback = adapter.positionOf(key);
        if (!saved && moveSnapshot != null) adapter.applyOrder(moveSnapshot);
        movingKey = null;
        moveAnimationRequest++;
        moveSnapshot = null;
        confirmDown = false;
        adapter.setMovingKey(null);
        moveIndicator.setVisibility(View.GONE);
        // Never leave a translation on a recycled item after cancelling mid-animation.
        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            child.animate().cancel();
            child.setTranslationX(0f);
            child.setTranslationY(0f);
        }
        if (restoreFocus) {
            focusKey(key, Math.max(0, fallback));
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (ACTION_QUICK_SETTINGS.equals(intent.getAction())) {
            if (paused) {
                quickSettingsFocus = R.id.quickAllSettings;
                resumeQuickSettings = true;
            } else if (quickSettings != null) quickSettings.dismiss();
            else openQuickSettings();
            return;
        }
        resumeQuickSettings = false;
        onlyPaused = false;
        if (quickSettings != null) quickSettings.closeWithoutFocus();
        dismissTileMenu(false);
        if (editDialog != null) editDialog.dismiss();
        if (movingKey != null && !savingOrderNow) finishMove(false, false);
        if (!savingOrderNow) returnHome();
    }

    // ---- top bar ---------------------------------------------------------------------------------

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        greeting.setText(res);
        if (shamsi) shamsiDate.setText(Shamsi.today());
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        boolean vpn = false, online = false;
        int icon = R.drawable.ic_wifi;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean wifi = false, ethernet = false;
            // All networks, not just the active one: with a VPN up the active network is the VPN itself.
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpn = true;
                    continue;
                }
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                online = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
            if (!online) {
                text = getString(R.string.offline);
                vpn = false;
            } else if (ethernet) {
                text = getString(R.string.ethernet);
                icon = R.drawable.ic_ethernet;
            } else if (wifi) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        // With a VPN app configured, a VPN that is down matters too: show a red "VPN off" capsule then.
        boolean vpnDown = !vpn && online && !vpnApp.isEmpty();
        if (vpn || vpnDown) {
            // Network name, then the green lock-and-VPN (or red "VPN off") capsule drawn in place of the word.
            String badge = getString(vpn ? R.string.vpn : R.string.vpn_off);
            VpnBadge span = new VpnBadge(vpn ? getDrawable(R.drawable.ic_lock) : null, density);
            // The pill has a maximum width and ellipsizes at the end, which would cut the badge off after
            // a long network name, so shorten the name to whatever room the badge leaves. The total paddings
            // include the Wi-Fi icon.
            TextPaint paint = btnWifi.getPaint();
            float room = btnWifi.getMaxWidth() - btnWifi.getTotalPaddingStart() - btnWifi.getTotalPaddingEnd()
                    - span.getSize(paint, badge, 0, badge.length(), null) - paint.measureText(" ") - 2 * density;
            text = TextUtils.ellipsize(text, paint, Math.max(0, room), TextUtils.TruncateAt.END).toString();
            SpannableString label = new SpannableString(text + " " + badge);
            label.setSpan(span, text.length() + 1, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            btnWifi.setText(label);
        } else {
            btnWifi.setText(text);
        }
        btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
    }

    /** The network pill opens the VPN app when one is configured and installed, otherwise Wi-Fi settings. */
    private void openNetworkPill() {
        Intent i = vpnApp.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(vpnApp);
        if (i == null && !vpnApp.isEmpty()) i = getPackageManager().getLaunchIntentForPackage(vpnApp);
        if (i != null) open(i);
        else openSettings(Settings.ACTION_WIFI_SETTINGS);
    }

    private void openSettings(String action) {
        // Settings shares one task across its panels. NEW_TASK alone can bring its previous
        // submenu back to the front instead of opening the screen the user requested.
        open(systemIntent(this, action).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }

    private void openQuickSettings() {
        quickSettingsFocus = R.id.quickAllSettings;
        showQuickSettings();
    }

    private void showQuickSettings() {
        if (paused || isFinishing() || quickSettings != null) return;
        if (root.getWidth() == 0 || !root.isLaidOut()) {
            resumeQuickSettings = true;
            if (!quickSettingsAwaitingLayout) {
                quickSettingsAwaitingLayout = true;
                root.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override public void onGlobalLayout() {
                        if (root.getWidth() == 0) return;
                        root.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        quickSettingsAwaitingLayout = false;
                        if (resumeQuickSettings && !paused) handler.post(MainActivity.this::showQuickSettings);
                    }
                });
            }
            return;
        }
        cancelGridTransition();
        focusRequest++;
        requestedFocusKey = null;
        resumeQuickSettings = false;
        // The panel crossfades Home's shortcuts along with its dimming and glass controls.
        QuickSettingsPanel panel = new QuickSettingsPanel(this, root, reducedMotion, animations, navigationSounds,
                new QuickSettingsPanel.Listener() {
                    @Override public void onAction(int control) { openQuickControl(control); }
                    @Override public void onClosed(boolean restoreFocus) {
                        quickSettings = null;
                        if (restoreFocus && !paused) {
                            resumeQuickSettings = false;
                            quickSettingsFocus = R.id.quickAllSettings;
                            int request = ++focusRequest;
                            requestedFocusKey = null;
                            btnSettings.requestFocusFromTouch();
                            btnSettings.postOnAnimation(() -> {
                                if (!paused && request == focusRequest && quickSettings == null)
                                    btnSettings.requestFocusFromTouch();
                            });
                        }
                    }
                });
        quickSettings = panel;
        panel.open(quickSettingsFocus);
    }

    private void openQuickControl(int control) {
        // Launcher settings is ours: it stays in Home's task and reports a change back (onActivityResult).
        boolean launcherSettings = control == R.id.quickLauncherSettings;
        Intent intent = launcherSettings ? new Intent(this, LauncherSettingsActivity.class)
                : QuickSettingsActions.intent(this, control)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        quickSettingsFocus = control;
        resumeQuickSettings = true;
        Runnable launch = () -> {
            try {
                if (launcherSettings) startActivityForResult(intent, LAUNCHER_SETTINGS_REQUEST);
                else startActivity(intent);
            } catch (ActivityNotFoundException | SecurityException unavailable) {
                resumeQuickSettings = false;
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
                handler.post(this::showQuickSettings);
            }
        };
        if (quickSettings != null) quickSettings.closeForAction(launch);
        else launch.run();
    }

    /** Standard settings first, with optional firmware destinations supplied by config.xml or a theme. */
    static Intent systemIntent(Context context, String standardAction) {
        return QuickSettingsActions.systemIntent(context, standardAction, Config.load(context).settingsRoutes);
    }

    /** Hides the Inputs pill when it would have nothing to list: streaming boxes have no HDMI-in, AV or tuner. */
    private void updateInputsButton() {
        boolean any = liveTvIntent() != null || !sourcePanelService.isEmpty();
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (!any && tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.isPassthroughInput()) {
                    any = true;
                    break;
                }
            }
        }
        btnInputs.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    /** The Live TV app's launch intent: its leanback entry point, or the configured action if it has none. */
    private Intent liveTvIntent() {
        if (liveTvPkg.isEmpty()) return null;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (i != null || liveTvAction.isEmpty()) return i;
        Intent byAction = new Intent(liveTvAction).setPackage(liveTvPkg);
        return getPackageManager().resolveActivity(byAction, 0) != null ? byAction : null;
    }

    /** The TV's own source-selection panel, for TVs that have no TvInputManager (TCL Android TV 9). */
    private void openSourcePanel() {
        Intent i = new Intent(sourcePanelAction);
        ComponentName c = ComponentName.unflattenFromString(sourcePanelService);
        if (c != null) i.setComponent(c);
        try {
            startService(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Switches to a passthrough input (HDMI, AV). The standard way is ACTION_VIEW on its channel URI,
     *  which the Live TV app handles; where nothing takes that, the configured `input_action` is sent
     *  with the same URI instead (XGIMI's HDMI player only answers com.xgimi.action.hdmiPlayer). */
    private void openInput(String inputId) {
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(view);
            return;
        } catch (ActivityNotFoundException e) {
            if (inputAction.isEmpty()) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        open(new Intent(inputAction).setDataAndType(uri, "vnd.android.cursor.item/channel"));
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = glassPopup(panel, width);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        String tunerId = null;
        boolean tunerIsHw = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    // Prefer a hardware tuner: the framework names those ".../HW<n>". System apps such as
                    // AirPlay also register software inputs of type TUNER and may sort first (seen on TCL G05).
                    boolean hw = info.getId().matches(".*/HW\\d+$");
                    if (tunerId == null || (hw && !tunerIsHw) || (hw == tunerIsHw && info.getId().compareTo(tunerId) < 0)) {
                        tunerId = info.getId();
                        tunerIsHw = hw;
                    }
                }
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                int state = hdmi && hdmiStateBogus ? -1 : tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> openInput(id));
            }
        }
        // Nothing to list where there is no TvInputManager: the TV's own source panel switches inputs.
        if (rows.getChildCount() == 0 && !sourcePanelService.isEmpty()) {
            addInputRow(rows, popup, R.drawable.ic_input, getString(R.string.inputs), -1, this::openSourcePanel);
        }
        Intent liveTv = liveTvIntent();
        if (liveTv != null && tunerId != null) {
            liveTv = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tunerId), "vnd.android.cursor.item/channel")
                    .setPackage(liveTvPkg);
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Fit the widest row: TVs that report a status on every port (Sony) would otherwise squeeze the names out.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(width, panel.getMeasuredWidth()));

        // Inputs is the first button in reading order: the left end, or the right end in Hebrew.
        dropIn(popup, panel, anchor, rtlMenuBar ? Gravity.END : Gravity.START);
        rows.getChildAt(0).requestFocus();
    }

    private PopupWindow glassPopup(View panel, int width) {
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        popup.setOnDismissListener(() -> {
            if (openPopup == popup) openPopup = null;
        });
        openPopup = popup;
        return popup;
    }

    /** Drop in: fade and unfold from the top edge, hanging under the anchor pill. */
    private void dropIn(PopupWindow popup, View panel, View anchor, int gravity) {
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, 0, (int) (10 * density), gravity);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(motion(220))
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }


    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }

    // ---- launcher settings and module cards -------------------------------------------------------

    /** The Settings button sits at the right end, or the left end in Hebrew: its menus hang inwards. */
    private int settingsMenuGravity() {
        return rtlMenuBar ? Gravity.START : Gravity.END;
    }

    private void openLauncherSettings() {
        try {
            startActivityForResult(new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Hold OK on Settings: the launcher's own settings, and Module cards when cards are on. */
    private void showSettingsMenu() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, (int) (200 * density));
        addSettingsMenuRow(rows, popup, R.drawable.ic_quick_launcher_settings, R.string.launcher_settings,
                this::openLauncherSettings);
        if (moduleCards) addSettingsMenuRow(rows, popup, R.drawable.ic_quick_apps, R.string.module_cards,
                this::showModuleCards);
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max((int) (200 * density), panel.getMeasuredWidth()));
        dropIn(popup, panel, anchor, settingsMenuGravity());
        rows.getChildAt(0).requestFocus();
    }

    private void addSettingsMenuRow(LinearLayout rows, PopupWindow popup, int iconRes, int title, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        // The OK release that ends the long press opening this menu must not activate its first row.
        final boolean[] pressed = {false};
        row.setOnKeyListener((v, code, event) -> {
            if (!isConfirm(code)) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) pressed[0] = true;
                return true;
            }
            boolean activate = pressed[0] && !event.isCanceled();
            pressed[0] = false;
            if (activate) {
                popup.dismiss();
                action.run();
            }
            return true;
        });
        rows.addView(row);
    }

    // ---- module cards ----------------------------------------------------------------------------

    /**
     * Asks about the next card that has no decision for its current signing key. One prompt at a
     * time, only over a laid out grid with window focus (not under a popup or another app), and
     * the next one follows as soon as this one is answered. Nothing is shown until the owner says Show.
     */
    private void showCardPrompt() {
        if (!moduleCards || !tilesReady || promptsPaused || cardPrompt != null || !hasWindowFocus() || isFinishing()) return;
        // Not over the tile menu, a folder dialog or a move in progress; the prompt follows once they close.
        if (paused || quickSettings != null || tileMenu != null || editDialog != null || movingKey != null || savingOrderNow) return;
        if (cards.isEmpty() || !CardFeature.working()) return;
        CardFeature.run(() -> {
            showCardPromptNow();
            return null;
        }, null);
    }

    private void showCardPromptNow() {
        final Cards.Card card = Cards.firstPending(cards);
        if (card == null) return;

        View panel = getLayoutInflater().inflate(R.layout.dialog_card_prompt, root, false);
        ((TextView) panel.findViewById(R.id.title)).setText(getString(R.string.card_prompt_title, card.appLabel));
        ((TextView) panel.findViewById(R.id.body)).setText(card.state == Cards.State.RESIGNED
                ? getString(R.string.card_prompt_resigned, card.appLabel)
                : card.state == Cards.State.RELABELLED
                ? getString(R.string.card_prompt_relabelled, card.appLabel)
                : getString(R.string.card_prompt_body, card.appLabel));
        TextView names = panel.findViewById(R.id.names);
        if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) names.setVisibility(View.GONE);
        else names.setText(getString(R.string.card_prompt_names, card.label, card.appLabel));
        ((TextView) panel.findViewById(R.id.identity)).setText(
                getString(R.string.card_prompt_identity, card.pkg, Cards.shortDigest(card.digest)));
        TextView keep = panel.findViewById(R.id.btnKeep);
        TextView show = panel.findViewById(R.id.btnShow);

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(false);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (440 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.6f);
        }
        // One decision per prompt: the buttons and Back (cancel) all end here.
        final boolean[] decided = {false};
        View.OnClickListener choose = v -> {
            if (decided[0]) return;
            decided[0] = true;
            cardPrompt = null;
            dialog.dismiss();
            decideCard(card, v.getId() == R.id.btnShow);   // guards its own card calls; the tile reload stays outside
            // The tiles changed under the prompt; back to the same app. Window focus returning chains the next prompt.
            focusKey(lastFocusedKey, lastFocused);
        };
        keep.setOnClickListener(choose);
        show.setOnClickListener(choose);
        dialog.setOnCancelListener(d -> choose.onClick(keep));
        dialog.setOnDismissListener(d -> {
            if (cardPrompt == dialog) cardPrompt = null;
        });
        // Left and Right move between the two buttons; Up and Down stay put; the ends don't wrap.
        View.OnKeyListener keys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == R.id.btnKeep;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnShow;
        };
        keep.setOnKeyListener(keys);
        show.setOnKeyListener(keys);
        cardPrompt = dialog;
        dialog.show();
        keep.requestFocus();
    }

    /** Writes the owner's decision and rebuilds the tiles; focus is left where it is. */
    private void decideCard(Cards.Card card, boolean show) {
        boolean saved = CardFeature.run(() -> Cards.decide(Cards.storeFile(this), card, show), false);
        if (!saved) {
            Toast.makeText(this, R.string.card_not_saved, Toast.LENGTH_SHORT).show();
            promptsPaused = true;
        }
        reloadTiles();
    }

    /** Dropdown under the Settings pill (hold OK): every discovered card, its package and key; OK toggles Shown and Hidden. */
    private void showModuleCards() {
        if (cards.isEmpty() || !CardFeature.working()) {
            Toast.makeText(this, R.string.no_module_cards, Toast.LENGTH_SHORT).show();
            return;
        }
        CardFeature.run(() -> {
            showModuleCardsNow();
            return null;
        }, null);
    }

    private void showModuleCardsNow() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_cards, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = glassPopup(panel, (int) (400 * density));
        List<Cards.Card> listed = new ArrayList<>(cards);
        Collections.sort(listed, Cards::byLabel);
        for (Cards.Card card : listed) {
            View row = getLayoutInflater().inflate(R.layout.item_card_row, rows, false);
            row.setTag(card.pkg);
            ((TextView) row.findViewById(R.id.title)).setText(card.label);
            TextView app = row.findViewById(R.id.app);
            if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) app.setVisibility(View.GONE);
            else app.setText(getString(R.string.card_app_name, card.appLabel));
            ((TextView) row.findViewById(R.id.pkg)).setText(card.pkg);
            ((TextView) row.findViewById(R.id.digest)).setText(Cards.longDigest(card.digest));
            final String pkg = card.pkg;
            row.setOnClickListener(v -> CardFeature.run(() -> {
                // The list stays open; the decision is for the card as it is now, not as it was listed.
                Cards.Card now = cardFor(pkg);
                if (now == null) return null;
                decideCard(now, now.state != Cards.State.SHOWN);
                // Every row, not only this one: a toggle can move another card across the limit of 8.
                updateCardStates(rows);
                return null;
            }, null));
            rows.addView(row);
        }
        updateCardStates(rows);
        // Scroll inside the panel instead of running off the bottom of a 720p screen.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        int room = root.getHeight() - (at[1] + anchor.getHeight()) - (int) (34 * density);
        popup.setHeight(Math.min(panel.getMeasuredHeight(), Math.max(room, (int) (120 * density))));
        dropIn(popup, panel, anchor, settingsMenuGravity());
        rows.getChildAt(0).requestFocus();
    }

    private Cards.Card cardFor(String pkg) {
        for (Cards.Card c : cards) if (c.pkg.equals(pkg)) return c;
        return null;
    }

    /** Writes the state of every row in the Module cards list from the current card list. */
    private void updateCardStates(LinearLayout rows) {
        List<Cards.Card> drawn = Cards.shown(cards);
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            Cards.Card card = row.getTag() instanceof String ? cardFor((String) row.getTag()) : null;
            ((TextView) row.findViewById(R.id.state)).setText(card != null ? stateText(card, drawn.contains(card)) : "");
        }
    }

    /** The row's state: a card the owner set to Shown but which is beyond the eight drawn says so. */
    private String stateText(Cards.Card card, boolean drawn) {
        switch (card.state) {
            case SHOWN:
                return getString(drawn ? R.string.card_state_shown : R.string.card_state_over_limit);
            case HIDDEN:
                return getString(R.string.card_state_hidden);
            case RESIGNED:
                return getString(R.string.card_state_new_key);
            case RELABELLED:
                return getString(R.string.card_state_new_label);
            default:
                return getString(R.string.card_state_new);
        }
    }
}
