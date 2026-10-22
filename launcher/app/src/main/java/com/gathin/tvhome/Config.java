package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The launcher's settings: the values in res/values/config.xml, with an optional overlay read from
 * {@code config.json} in the app's external files directory
 * ({@code /sdcard/Android/data/com.gathin.tvhome/files/}). Every key in the JSON is optional and an
 * unknown or badly typed key is ignored, so a theme can change the dock and the look without a rebuild:
 * {@code tools/theme.sh themes/<name>} pushes a theme folder there and restarts Home.
 *
 * Read once in {@link MainActivity#onCreate}; nothing here touches the network.
 */
final class Config {
    private static final String TAG = "TvHome.Config";
    static final String FILE_NAME = "config.json";
    static final String WALLPAPER_NAME = "wallpaper.jpg";
    static final String SETTINGS_NAME = "launcher_settings";
    static final String ROWS_BELOW_DOCK_KEY = "rows_below_dock";
    static final String WALLPAPER_CHOICE_KEY = "wallpaper_choice";
    static final String WALLPAPER_THEME = "theme";
    static final String WALLPAPER_AURORA = "aurora";
    static final String WALLPAPER_OFF = "off";
    private static final String THEME_REVISION_NAME = "theme_revision";
    private static final String THEME_SIGNATURE_KEY = "theme_signature";

    final List<String> dockApps;
    final List<String> hiddenApps;
    final String liveTvPackage;
    final String liveTvAction;
    final String sourcePanelService;
    final String sourcePanelAction;
    final String inputAction;
    /** Optional firmware destinations, with the same flat keys in config.xml and a theme. */
    final Map<String, String> settingsRoutes;
    final int columns;
    final boolean animations;
    final boolean advancedEffects;
    final int rowsBelowDock;
    final boolean shamsiDate;
    /** Name after the greeting ("Good morning, Mr. Trịnh"); empty for the greeting alone. */
    final String greetingName;
    final boolean moduleCards;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): stiff spring, short fades, no replay. */
    final boolean reducedMotion;
    final boolean navigationSounds;
    /** The focused dock app's name in large type on the top shelf (config.xml, dock_title); the glow stays either way. */
    final boolean dockTitle;
    final String vpnApp;
    final boolean showHomeSettingsShortcut;
    /** One line under the clock: CPU, free memory, free storage. Off unless config or a theme turns it on. */
    final boolean showStats;
    /** App Library pill after the last Home row (config.xml app_library, or Launcher settings). */
    final boolean appLibrary;
    final boolean continueWatching;
    /** Background colour behind everything, #RRGGBB; black unless a theme says otherwise. */
    final int backgroundColor;
    /** Time-of-day backdrop, 0 to 100 (config.xml, sky_intensity); 0 keeps the plain background. */
    final int skyIntensity;
    /** Remote keycodes that open Settings through HomeKeyService (config.xml, settings_key_keycodes). */
    final int[] settingsKeycodes;
    /** Extra keycodes that open the input switcher in InputKeyService (config.xml input_key_keycodes). */
    final int[] inputKeycodes;
    /** Remote buttons that open an app, "keycode:package" or "KEYCODE_NAME:package" (config.xml app_keys). */
    final List<String> appKeys;
    /** The remote's Settings button opens the glass quick settings instead of the TV's Settings (settings_key_panel). */
    final boolean settingsKeyPanel;
    /** Whether themes may show a wallpaper (config.json, wallpaper). */
    final boolean wallpaperEnabled;
    /** External theme picture, or null when absent or an on-TV wallpaper choice takes priority. */
    final File wallpaper;
    /** Rotating theme images, or the single theme picture; empty when an on-TV choice replaces the theme's. */
    final List<File> wallpapers;
    final int wallpaperRotationMinutes;
    final int wallpaperDim;
    /** On-TV picture choice; theme keeps the external wallpaper, Aurora uses the bundled picture. */
    final String wallpaperChoice;
    /** Whether the overlay file was present and parsed. */
    final boolean overlayApplied;
    /** Festive skin from a theme (festival, festival_until, greeting, accent_color); {@link Festival#NONE} if none. */
    final Festival festival;

    final boolean showNetworkPill;
    final boolean iconOnlyButtons;
    final boolean libraryButton;
    final int wifiLabelWidth;
    final boolean alwaysShowNames;
    final boolean wallpaperTrueColor;
    final boolean cinematicHome;
    final boolean ambientGlow;
    final int homeIdleClockMinutes;
    final String wallpaperOrder;
    final List<RaceWeekend.Race> raceCalendar;
    final Map<String, String> launchActions;
    final Map<String, String> appLabels;
    final Map<String, String> appBanners;

    private Config(List<String> dockApps, List<String> hiddenApps, String liveTvPackage,
                   String liveTvAction, String sourcePanelService, String sourcePanelAction, String inputAction,
                   int columns, int rowsBelowDock, boolean animations, boolean advancedEffects,
                   boolean shamsiDate, String greetingName, boolean moduleCards, boolean reducedMotion,
                   boolean navigationSounds, boolean dockTitle, String vpnApp, boolean showHomeSettingsShortcut,
                   boolean showStats, boolean appLibrary, int backgroundColor, int skyIntensity,
                   int[] settingsKeycodes, boolean settingsKeyPanel, int[] inputKeycodes, List<String> appKeys,
                   boolean wallpaperEnabled, File wallpaper, List<File> wallpapers, int wallpaperRotationMinutes,
                   int wallpaperDim, String wallpaperChoice, boolean overlayApplied, Festival festival,
                   Map<String, String> settingsRoutes,
                   boolean showNetworkPill, boolean iconOnlyButtons, boolean libraryButton, int wifiLabelWidth,
                   boolean alwaysShowNames, boolean wallpaperTrueColor, boolean cinematicHome, boolean ambientGlow,
                   int homeIdleClockMinutes, String wallpaperOrder, List<RaceWeekend.Race> raceCalendar,
                   Map<String, String> launchActions, Map<String, String> appLabels, Map<String, String> appBanners, boolean continueWatching) {
        this.dockApps = Collections.unmodifiableList(dockApps);
        this.hiddenApps = Collections.unmodifiableList(hiddenApps);
        this.liveTvPackage = liveTvPackage;
        this.liveTvAction = liveTvAction;
        this.sourcePanelService = sourcePanelService;
        this.sourcePanelAction = sourcePanelAction;
        this.inputAction = inputAction;
        this.settingsRoutes = settingsRoutes;
        this.showNetworkPill = showNetworkPill;
        this.iconOnlyButtons = iconOnlyButtons;
        this.libraryButton = libraryButton;
        this.wifiLabelWidth = wifiLabelWidth;
        this.alwaysShowNames = alwaysShowNames;
        this.wallpaperTrueColor = wallpaperTrueColor;
        this.cinematicHome = cinematicHome;
        this.ambientGlow = ambientGlow;
        this.homeIdleClockMinutes = homeIdleClockMinutes;
        this.wallpaperOrder = wallpaperOrder;
        this.raceCalendar = Collections.unmodifiableList(raceCalendar);
        this.launchActions = Collections.unmodifiableMap(launchActions);
        this.appLabels = Collections.unmodifiableMap(appLabels);
        this.appBanners = Collections.unmodifiableMap(appBanners);

        this.columns = columns;
        this.rowsBelowDock = rowsBelowDock;
        this.animations = animations;
        this.advancedEffects = advancedEffects;
        this.shamsiDate = shamsiDate;
        this.greetingName = greetingName;
        this.moduleCards = moduleCards;
        this.reducedMotion = reducedMotion;
        this.navigationSounds = navigationSounds;
        this.dockTitle = dockTitle;
        this.vpnApp = vpnApp;
        this.showHomeSettingsShortcut = showHomeSettingsShortcut;
        this.showStats = showStats;
        this.appLibrary = appLibrary;
        this.continueWatching = continueWatching;
        this.backgroundColor = backgroundColor;
        this.skyIntensity = skyIntensity;
        this.settingsKeycodes = settingsKeycodes;
        this.settingsKeyPanel = settingsKeyPanel;
        this.inputKeycodes = inputKeycodes;
        this.appKeys = Collections.unmodifiableList(appKeys);
        this.wallpaperEnabled = wallpaperEnabled;
        this.wallpaper = wallpaper;
        this.wallpapers = Collections.unmodifiableList(wallpapers);
        this.wallpaperRotationMinutes = wallpaperRotationMinutes;
        this.wallpaperDim = wallpaperDim;
        this.wallpaperChoice = wallpaperChoice;
        this.overlayApplied = overlayApplied;
        this.festival = festival;
    }

    /** The folder the theme tools push into; null when external storage is unavailable. */
    static File dir(Context ctx) {
        return ctx.getExternalFilesDir(null);
    }

    static Config load(Context ctx) {
        Resources res = ctx.getResources();
        List<String> dock = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.dock_apps)));
        List<String> hidden = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.hidden_apps)));
        String liveTv = res.getString(R.string.live_tv_package).trim();
        String liveTvAction = res.getString(R.string.live_tv_action).trim();
        String panelService = res.getString(R.string.source_panel_service).trim();
        String panelAction = res.getString(R.string.source_panel_action).trim();
        String inputAction = res.getString(R.string.input_action).trim();
        int columns = res.getInteger(R.integer.columns);
        int rowsBelow = res.getInteger(R.integer.rows_below_dock);
        boolean animations = res.getBoolean(R.bool.animations);
        boolean advancedEffects = res.getBoolean(R.bool.advanced_effects);
        boolean shamsi = res.getBoolean(R.bool.shamsi_date);
        String greetingName = res.getString(R.string.greeting_name).trim();
        boolean moduleCards = res.getBoolean(R.bool.module_cards);
        boolean reducedMotion = res.getBoolean(R.bool.reduced_motion);
        boolean navigationSounds = res.getBoolean(R.bool.navigation_sounds);
        boolean dockTitle = res.getBoolean(R.bool.dock_title);
        String vpn = res.getString(R.string.vpn_app).trim();
        boolean homeSettings = res.getBoolean(R.bool.show_home_settings_shortcut);
        boolean showStats = res.getBoolean(R.bool.show_stats);
        boolean library = res.getBoolean(R.bool.app_library);
        boolean continueWatching = res.getBoolean(R.bool.continue_watching);
        int background = 0xFF000000;
        int skyIntensity = res.getInteger(R.integer.sky_intensity);
        int[] settingsKeycodes = res.getIntArray(R.array.settings_key_keycodes);
        boolean settingsKeyPanel = res.getBoolean(R.bool.settings_key_panel);
        int[] inputKeycodes = res.getIntArray(R.array.input_key_keycodes);
        List<String> appKeys = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.app_keys)));
        boolean showNetworkPill = res.getBoolean(R.bool.show_network_pill);
        boolean iconOnlyButtons = res.getBoolean(R.bool.icon_only_buttons);
        boolean libraryButton = res.getBoolean(R.bool.library_button);
        int wifiLabelWidth = res.getInteger(R.integer.wifi_label_width);
        boolean alwaysShowNames = res.getBoolean(R.bool.always_show_names);
        boolean wallpaperTrueColor = res.getBoolean(R.bool.wallpaper_true_color);
        boolean cinematicHome = res.getBoolean(R.bool.cinematic_home);
        boolean ambientGlow = res.getBoolean(R.bool.ambient_glow);
        int homeIdleClockMinutes = res.getInteger(R.integer.home_idle_clock_minutes);
        List<String> launchActions = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.launch_actions)));
        List<String> appLabels = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.app_labels)));
        List<String> appBanners = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.app_banners)));
        boolean applied = false;

        File dir = dir(ctx);
        File file = dir != null ? new File(dir, FILE_NAME) : null;
        boolean mounted = Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState());
        JSONObject theme = readOverlay(ctx, file, mounted);
        JSONObject json = FeaturePreferences.overlay(ctx, theme);
        applied = theme != null;
        if (json != null) {
            dock = stringList(json, "dock_apps", dock);
            hidden = stringList(json, "hidden_apps", hidden);
            liveTv = string(json, "live_tv_package", liveTv);
            liveTvAction = string(json, "live_tv_action", liveTvAction);
            panelService = string(json, "source_panel_service", panelService);
            panelAction = string(json, "source_panel_action", panelAction);
            inputAction = string(json, "input_action", inputAction);
            columns = integer(json, "columns", columns, 1, 8);
            rowsBelow = integer(json, "rows_below_dock", rowsBelow, 0, 3);
            animations = bool(json, "animations", animations);
            advancedEffects = bool(json, "advanced_effects", advancedEffects);
            shamsi = bool(json, "shamsi_date", shamsi);
            greetingName = string(json, "greeting_name", greetingName).trim();
            moduleCards = bool(json, "module_cards", moduleCards);
            reducedMotion = bool(json, "reduced_motion", reducedMotion);
            navigationSounds = bool(json, "navigation_sounds", navigationSounds);
            dockTitle = bool(json, "dock_title", dockTitle);
            vpn = string(json, "vpn_app", vpn);
            homeSettings = bool(json, "show_home_settings_shortcut", homeSettings);
            showStats = bool(json, "show_stats", showStats);
            library = bool(json, "app_library", library);
            continueWatching = bool(json, "continue_watching", continueWatching);
            background = color(json, "background_color", background);
            skyIntensity = integer(json, "sky_intensity", skyIntensity, 0, 100);
            settingsKeycodes = intArray(json, "settings_key_keycodes", settingsKeycodes);
            settingsKeyPanel = bool(json, "settings_key_panel", settingsKeyPanel);
            inputKeycodes = intArray(json, "input_key_keycodes", inputKeycodes);
            appKeys = stringList(json, "app_keys", appKeys);
            showNetworkPill = bool(json, "show_network_pill", showNetworkPill);
            iconOnlyButtons = bool(json, "icon_only_buttons", iconOnlyButtons);
            libraryButton = bool(json, "library_button", libraryButton);
            alwaysShowNames = bool(json, "always_show_names", alwaysShowNames);
            wallpaperTrueColor = bool(json, "wallpaper_true_color", wallpaperTrueColor);
            cinematicHome = bool(json, "cinematic_home", cinematicHome);
            ambientGlow = bool(json, "ambient_glow", ambientGlow);
            wifiLabelWidth = integer(json, "wifi_label_width", wifiLabelWidth, 0, 300);
            homeIdleClockMinutes = integer(json, "home_idle_clock_minutes", homeIdleClockMinutes, 0, 1440);
            launchActions = stringList(json, "launch_actions", launchActions);
            appLabels = stringList(json, "app_labels", appLabels);
            appBanners = stringList(json, "app_banners", appBanners);

        }
        // A theme push or reset invalidates choices made on the TV. On first use, remember the
        // current theme without clearing choices from an older launcher version.
        SharedPreferences choices = ctx.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE);
        // Do not mistake storage still mounting at boot for a theme reset.
        if (mounted) syncThemeChoices(choices, dir, file);
        int chosenRows = choices.getInt(ROWS_BELOW_DOCK_KEY, rowsBelow);
        if (chosenRows >= 0 && chosenRows <= 3) rowsBelow = chosenRows;
        int chosenColumns = choices.getInt("columns", columns);
        if (chosenColumns >= 4 && chosenColumns <= 7) columns = chosenColumns;
        animations = choices.getBoolean("animations", animations);
        advancedEffects = choices.getBoolean("advanced_effects", advancedEffects);
        navigationSounds = choices.getBoolean("navigation_sounds", navigationSounds);
        library = choices.getBoolean("app_library", library);
        showStats = choices.getBoolean("show_stats", showStats);
        String wallpaperChoice = choices.getString(WALLPAPER_CHOICE_KEY, WALLPAPER_THEME);
        if ("none".equals(wallpaperChoice)) wallpaperChoice = WALLPAPER_OFF;
        File wallpaper = dir != null ? new File(dir, WALLPAPER_NAME) : null;
        if (wallpaper != null && !(wallpaper.isFile() && wallpaper.length() > 0)) wallpaper = null;
        boolean wallpaperEnabled = json == null || bool(json, "wallpaper", true);
        int rotationMinutes = json == null ? 0 : integer(json, "wallpaper_rotation_minutes", 0, 0, 1440);
        int wallpaperDim = json == null ? 0 : integer(json, "wallpaper_dim", 0, 0, 90);
        String wallpaperOrder = json == null ? WallpaperRotation.ORDER_SHUFFLE
                : string(json, "wallpaper_order", WallpaperRotation.ORDER_SHUFFLE);
        if (!WallpaperRotation.validOrder(wallpaperOrder)) wallpaperOrder = WallpaperRotation.ORDER_SHUFFLE;
        List<File> wallpapers = new ArrayList<>();
        // Keep Theme / Aurora / Off choices from this fork. A collection filename pins one external picture.
        if (FeaturePreferences.SCENES.equals(wallpaperChoice)) {
            wallpapers.addAll(SceneFiles.images(ctx));
            wallpaper = wallpapers.isEmpty() ? null : wallpapers.get(0);
            wallpaperEnabled = wallpaper != null;
        } else if (wallpaperChoice.startsWith(FeaturePreferences.SCENE_PREFIX)) {
            wallpaper = WallpaperRotation.find(SceneFiles.images(ctx), wallpaperChoice.substring(FeaturePreferences.SCENE_PREFIX.length()));
            if (wallpaper != null) { wallpapers.add(wallpaper); wallpaperEnabled = true; }
            else wallpaperChoice = WALLPAPER_THEME;
        } else if (!WALLPAPER_THEME.equals(wallpaperChoice) && !WALLPAPER_AURORA.equals(wallpaperChoice)
                && !WALLPAPER_OFF.equals(wallpaperChoice)) {
            File picked = WallpaperRotation.find(WallpaperRotation.images(
                    dir == null ? null : new File(dir, "wallpapers")), wallpaperChoice);
            if (picked == null && wallpaper != null && wallpaper.getName().equals(wallpaperChoice)) picked = wallpaper;
            if (picked != null) {
                wallpaper = picked;
                wallpaperEnabled = true;
                wallpapers.add(picked);
            } else wallpaperChoice = WALLPAPER_THEME;
        }
        if (WALLPAPER_THEME.equals(wallpaperChoice)) {
            if (!wallpaperEnabled) wallpaper = null;
            if (rotationMinutes > 0 && dir != null && wallpaperEnabled)
                wallpapers.addAll(WallpaperRotation.images(new File(dir, "wallpapers")));
            if (wallpapers.isEmpty() && wallpaper != null) wallpapers.add(wallpaper);
        } else if (WALLPAPER_AURORA.equals(wallpaperChoice) || WALLPAPER_OFF.equals(wallpaperChoice)) {
            wallpaper = null;
            wallpaperEnabled = WALLPAPER_AURORA.equals(wallpaperChoice);
        }
        // A festive skin comes only from a theme; config.xml has no festival.
        Festival festival = json == null ? Festival.NONE : Festival.parse(string(json, "festival", ""),
                string(json, "festival_until", ""), string(json, "greeting", ""), string(json, "accent_color", ""));

        return new Config(dock, hidden, liveTv, liveTvAction, panelService, panelAction, inputAction,
                Math.max(1, columns), Math.max(0, rowsBelow), animations, advancedEffects,
                shamsi, greetingName, moduleCards, reducedMotion, navigationSounds, dockTitle, vpn, homeSettings,
                showStats, library, background, Math.max(0, Math.min(100, skyIntensity)),
                settingsKeycodes, settingsKeyPanel, inputKeycodes, appKeys,
                wallpaperEnabled, wallpaper, wallpapers, rotationMinutes, wallpaperDim, wallpaperChoice, applied,
                festival, settingsRoutes(res, json),
                showNetworkPill, iconOnlyButtons, libraryButton, wifiLabelWidth, alwaysShowNames, wallpaperTrueColor,
                cinematicHome, ambientGlow, homeIdleClockMinutes, wallpaperOrder,
                json == null ? Collections.emptyList() : raceCalendar(json),
                packageMap(launchActions), packageMap(appLabels), packageMap(appBanners), continueWatching);
    }

    private static Map<String, String> settingsRoutes(Resources res, JSONObject overlay) {
        int[] ids = {R.string.settings_package, R.string.settings_fallback_component,
                R.string.settings_slice_action, R.string.settings_slice_uri_extra,
                R.string.quick_audio_output_component, R.string.quick_audio_output_slice_resource,
                R.string.quick_apps_component, R.string.quick_bluetooth_component,
                R.string.quick_bluetooth_slice_resource, R.string.quick_screensaver_action,
                R.string.quick_screensaver_component};
        Map<String, String> routes = new LinkedHashMap<>();
        for (int id : ids) {
            String key = res.getResourceEntryName(id);
            String value = res.getString(id).trim();
            routes.put(key, overlay != null ? string(overlay, key, value) : value);
        }
        return Collections.unmodifiableMap(routes);
    }

    private static void syncThemeChoices(SharedPreferences choices, File dir, File config) {
        File revision = dir == null ? null : new File(dir, THEME_REVISION_NAME);
        String token = revisionToken(revision);
        String signature = fileStamp(config) + ":" + token;
        String seen = choices.getString(THEME_SIGNATURE_KEY, null);
        if (seen == null) {
            // Existing preferences survive an app upgrade, unless the first launch follows an
            // explicit theme push or reset by the updated tools.
            SharedPreferences.Editor editor = choices.edit();
            if (!"absent".equals(token)) editor.remove("columns").remove("animations")
                    .remove("advanced_effects").remove("navigation_sounds").remove("wallpaper_choice")
                    .remove("app_library").remove("show_stats").remove(ROWS_BELOW_DOCK_KEY);
            editor.putString(THEME_SIGNATURE_KEY, signature).commit();
        } else if (!seen.equals(signature)) {
            choices.edit().remove("columns").remove("animations").remove("advanced_effects")
                    .remove("navigation_sounds").remove("wallpaper_choice").remove("app_library").remove("show_stats").remove(ROWS_BELOW_DOCK_KEY)
                    .putString(THEME_SIGNATURE_KEY, signature).commit();
        }
    }

    private static String fileStamp(File file) {
        return file != null && file.isFile() ? file.length() + "@" + file.lastModified() : "absent";
    }

    private static String revisionToken(File file) {
        if (file == null || !file.isFile() || file.length() > 64) return "absent";
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int n = in.read(bytes);
            return n > 0 ? new String(bytes, 0, n, StandardCharsets.US_ASCII) : "absent";
        } catch (Exception ignored) {
            return "absent";
        }
    }

    /** Reads and parses the overlay; any problem means "no overlay", never a crash on the home screen. */
    private static JSONObject read(File file) {
        if (file == null || !file.isFile()) return null;
        if (file.length() > 64 * 1024) {
            Log.w(TAG, "ignoring " + file + ": larger than 64 KB");
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            String text = new String(buf, 0, n, StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) text = text.substring(1);   // Windows editors add a BOM
            return new JSONObject(text);
        } catch (Exception e) {
            Log.w(TAG, "ignoring " + file + ": " + e.getMessage());
            return null;
        }
    }

    private static String string(JSONObject o, String key, String fallback) {
        Object v = o.opt(key);
        return v instanceof String ? ((String) v).trim() : fallback;
    }

    private static boolean bool(JSONObject o, String key, boolean fallback) {
        Object v = o.opt(key);
        return v instanceof Boolean ? (Boolean) v : fallback;
    }

    private static int integer(JSONObject o, String key, int fallback, int min, int max) {
        Object v = o.opt(key);
        if (!(v instanceof Number)) return fallback;
        int i = ((Number) v).intValue();
        return i < min || i > max ? fallback : i;
    }

    private static int color(JSONObject o, String key, int fallback) {
        Object v = o.opt(key);
        if (!(v instanceof String)) return fallback;
        try {
            int c = Color.parseColor(((String) v).trim());
            return c | 0xFF000000;   // always opaque: the window behind is black anyway
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** Positive keycodes only (vendor remotes use codes in the thousands); a list with anything else in it is ignored as a whole. */
    private static int[] intArray(JSONObject o, String key, int[] fallback) {
        Object v = o.opt(key);
        if (!(v instanceof JSONArray)) return fallback;
        JSONArray a = (JSONArray) v;
        int[] out = new int[a.length()];
        for (int i = 0; i < a.length(); i++) {
            Object item = a.opt(i);
            if (!(item instanceof Number)) return fallback;
            out[i] = ((Number) item).intValue();
            if (out[i] < 1) return fallback;
        }
        return out;
    }

    private static List<String> stringList(JSONObject o, String key, List<String> fallback) {
        Object v = o.opt(key);
        if (!(v instanceof JSONArray)) return fallback;
        JSONArray a = (JSONArray) v;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            Object item = a.opt(i);
            if (item instanceof String && !((String) item).trim().isEmpty()) out.add(((String) item).trim());
        }
        return out;
    }

    private static JSONObject readOverlay(Context ctx, File file, boolean mounted) {
        File copy = new File(ctx.getFilesDir(), FILE_NAME);
        if (!mounted) return read(copy);
        JSONObject json = read(file);
        if (json == null) {
            copy.delete();
        } else {
            try (FileOutputStream out = new FileOutputStream(copy)) {
                out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                Log.w(TAG, "could not keep a copy of " + file + ": " + e.getMessage());
            }
        }
        return json;
    }

    private static List<RaceWeekend.Race> raceCalendar(JSONObject json) {
        List<RaceWeekend.Race> races = new ArrayList<>();
        JSONArray list = json.optJSONArray("race_calendar");
        if (list == null) return races;
        for (int i = 0; i < list.length(); i++) {
            JSONObject r = list.optJSONObject(i);
            JSONArray sessions = r == null ? null : r.optJSONArray("sessions");
            if (sessions == null) continue;
            List<RaceWeekend.Session> parsed = new ArrayList<>();
            for (int j = 0; j < sessions.length(); j++) {
                JSONObject s = sessions.optJSONObject(j);
                if (s == null) continue;
                String name = s.optString("name", "").trim();
                try {
                    if (!name.isEmpty()) parsed.add(new RaceWeekend.Session(name, java.time.Instant.parse(s.optString("start", ""))));
                } catch (java.time.format.DateTimeParseException ignored) {
                    // a session without a readable start time cannot be counted down
                }
            }
            if (!parsed.isEmpty()) races.add(new RaceWeekend.Race(r.optInt("round", 0),
                    r.optString("name", "Grand Prix").trim(), r.optString("place", "").trim(), parsed));
        }
        return races;
    }

    static Map<String, String> packageMap(List<String> entries) {
        Map<String, String> out = new HashMap<>();
        for (String entry : entries) {
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            String pkg = entry.substring(0, eq).trim(), value = entry.substring(eq + 1).trim();
            if (!pkg.isEmpty() && !value.isEmpty()) out.put(pkg, value);
        }
        return out;
    }
}
