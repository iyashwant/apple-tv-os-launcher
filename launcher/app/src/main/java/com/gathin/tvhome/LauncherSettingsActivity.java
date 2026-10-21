package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private SettingsRowView animationsChoice;
    private SettingsRowView advancedChoice;
    private SettingsRowView soundsChoice;
    private SettingsRowView wallpaperChoice;
    private SettingsRowView rowsChoice;
    private int rowsBelowDock;
    private AlertDialog wallpaperPicker;
    private SettingsRowView libraryChoice;
    private SettingsRowView statsChoice;
    private SettingsRowView followThemeChoice;
    private String previewChoice;
    private int columns;
    private boolean animations;
    private boolean advancedEffects;
    private boolean navigationSounds;
    private boolean appLibrary;
    private boolean showStats;
    private String wallpaper;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;
        navigationSounds = config.navigationSounds;
        appLibrary = config.appLibrary;
        showStats = config.showStats;
        wallpaper = config.wallpaperChoice;

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                setResult(RESULT_OK);
                refresh();
            });
        }
        // Keep the numeric choices increasing in the Hebrew reading direction. Reorder the
        // actual views so D-pad focus follows their positions without changing the saved values.
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            LinearLayout row = (LinearLayout) columnChoices[0].getParent();
            row.removeAllViews();
            for (int i = columnChoices.length - 1; i >= 0; i--) row.addView(columnChoices[i]);
        }
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            setResult(RESULT_OK);
            refresh();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                AlertDialog dialog = new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (d, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                glass(dialog);
            }
        });
        soundsChoice = findViewById(R.id.soundsChoice);
        soundsChoice.setOnClickListener(v -> {
            navigationSounds = !navigationSounds;
            choices.edit().putBoolean("navigation_sounds", navigationSounds).apply();
            setResult(RESULT_OK);
            refresh();
        });
        libraryChoice = findViewById(R.id.libraryChoice);
        libraryChoice.setOnClickListener(v -> {
            appLibrary = !appLibrary;
            choices.edit().putBoolean("app_library", appLibrary).apply();
            setResult(RESULT_OK);
            refresh();
        });
        statsChoice = findViewById(R.id.statsChoice);
        statsChoice.setOnClickListener(v -> {
            showStats = !showStats;
            choices.edit().putBoolean("show_stats", showStats).apply();
            setResult(RESULT_OK);
            refresh();
        });
        rowsChoice = findViewById(R.id.rowsChoice);
        rowsChoice.setIcon(R.drawable.ic_quick_apps, 0xFF5E5CE6);
        rowsChoice.setOnClickListener(v -> {
            rowsBelowDock = (rowsBelowDock + 1) % 4;
            choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, rowsBelowDock).apply();
            setResult(RESULT_OK);
            refresh();
        });
        wallpaperChoice = findViewById(R.id.wallpaperChoice);
        wallpaperChoice.setOnClickListener(v -> {
            if (!wallpaperImages().isEmpty()) {
                showWallpaperPicker();
                return;
            }
            wallpaper = Config.WALLPAPER_THEME.equals(wallpaper) ? Config.WALLPAPER_AURORA
                    : Config.WALLPAPER_AURORA.equals(wallpaper) ? Config.WALLPAPER_OFF : Config.WALLPAPER_THEME;
            if (Config.WALLPAPER_THEME.equals(wallpaper)) choices.edit().remove("wallpaper_choice").apply();
            else choices.edit().putString("wallpaper_choice", wallpaper).apply();
            setResult(RESULT_OK);
            refresh();
        });
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove("animations").remove("advanced_effects")
                    .remove("navigation_sounds").remove("wallpaper_choice").remove("app_library")
                    .remove("show_stats").remove(Config.ROWS_BELOW_DOCK_KEY).commit();
            Config current = Config.load(this);
            columns = current.columns;
            rowsBelowDock = current.rowsBelowDock;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            navigationSounds = current.navigationSounds;
            appLibrary = current.appLibrary;
            showStats = current.showStats;
            wallpaper = current.wallpaperChoice;
            setResult(RESULT_OK);
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(new Intent(Settings.ACTION_SETTINGS)));
        wallpaperChoice.setIcon(R.drawable.ic_quick_screensaver, 0xFF0A84FF);
        animationsChoice.setIcon(R.drawable.ic_settings_motion, 0xFFBF5AF2);
        advancedChoice.setIcon(R.drawable.ic_quick_launcher_settings, 0xFFFF9F0A);
        soundsChoice.setIcon(R.drawable.ic_quick_sound, 0xFFFF375F);
        libraryChoice.setIcon(R.drawable.ic_apps, 0xFF5E5CE6);
        statsChoice.setIcon(R.drawable.ic_settings_stats, 0xFF30D158);
        followThemeChoice.setIcon(R.drawable.ic_quick_launcher_settings, 0xFF64D2FF);
        SettingsRowView defaultHome = findViewById(R.id.defaultHomeChoice);
        defaultHome.setIcon(R.drawable.ic_home_settings, 0xFF30B85A);
        defaultHome.setValue("", true);
        SettingsRowView tvSettings = findViewById(R.id.tvSettingsChoice);
        tvSettings.setIcon(R.drawable.ic_settings, 0xFF737989);
        tvSettings.setValue("", true);
        // White focus and a raised shadow stay within the row's width. No scaling into a clip edge.
        for (int id : new int[]{R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7, R.id.animationsChoice,
                R.id.advancedChoice, R.id.soundsChoice, R.id.libraryChoice, R.id.statsChoice,
                R.id.wallpaperChoice, R.id.rowsChoice, R.id.followThemeChoice,
                R.id.defaultHomeChoice, R.id.tvSettingsChoice}) {
            findViewById(id).setOnFocusChangeListener((v, focused) -> {
                v.animate().translationZ(focused ? 6 * getResources().getDisplayMetrics().density : 0)
                        .setDuration(animations ? 130 : 0).start();
                if (focused) showDetail(id);
            });
        }
        for (TextView column : columnChoices) column.setNextFocusDownId(R.id.wallpaperChoice);
        refresh();
        columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();
    }

    /** The confirmation in Home's frosted dialog colours: dark rounded panel, white glass buttons. */
    private void glass(AlertDialog dialog) {
        float density = getResources().getDisplayMetrics().density;
        if (dialog.getWindow() != null) {
            GradientDrawable panel = new GradientDrawable();
            panel.setColor(0xF2343840);
            panel.setCornerRadius(28 * density);
            dialog.getWindow().setBackgroundDrawable(panel);
            dialog.getWindow().setDimAmount(.32f);
        }
        for (int which : new int[]{AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_POSITIVE}) {
            Button button = dialog.getButton(which);
            if (button == null) continue;
            int pad = Math.round(18 * density), padV = Math.round(10 * density);
            button.setAllCaps(false);
            button.setTextSize(16);
            button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            button.setTextColor(getResources().getColorStateList(R.color.glass_action_text, getTheme()));
            button.setBackgroundResource(R.drawable.glass_menu_row);
            button.setPadding(pad, padV, pad, padV);
        }
        View message = dialog.findViewById(android.R.id.message);
        if (message instanceof TextView) ((TextView) message).setTextColor(0xD9FFFFFF);
        if (dialog.getWindow() != null) setSounds(dialog.getWindow().getDecorView());
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        setResult(RESULT_OK);
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        animationsChoice.setToggle(animations);
        advancedChoice.setToggle(advancedEffects);
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        soundsChoice.setToggle(navigationSounds);
        libraryChoice.setToggle(appLibrary);
        statsChoice.setToggle(showStats);
        String wallpaperLabel = Config.WALLPAPER_AURORA.equals(wallpaper) ? getString(R.string.wallpaper_aurora)
                : Config.WALLPAPER_OFF.equals(wallpaper) ? getString(R.string.off)
                : Config.WALLPAPER_THEME.equals(wallpaper) ? getString(R.string.wallpaper_theme) : wallpaper;
        wallpaperChoice.setValue(wallpaperLabel, true);
        rowsChoice.setValue(String.valueOf(rowsBelowDock), true);
        wallpaperChoice.setNextFocusUpId(columnChoices[Math.max(0, Math.min(columns - 4, 3))].getId());
        followThemeChoice.setSelected(!hasOnTvChoices());
        followThemeChoice.setValue(getString(hasOnTvChoices() ? R.string.settings_custom : R.string.wallpaper_theme), true);
        ((TextView) findViewById(R.id.settingsPreviewCaption)).setText(getString(R.string.wallpaper_state, wallpaperLabel));
        updatePreview();
        setSounds(getWindow().getDecorView());
    }

    private void showDetail(int id) {
        int title, hint;
        if (id == R.id.wallpaperChoice) {
            title = R.string.settings_wallpaper; hint = R.string.wallpaper_hint;
        } else if (id == R.id.rowsChoice) {
            title = R.string.rows_below_dock_label; hint = R.string.settings_rows_hint;
        } else if (id == R.id.animationsChoice) {
            title = R.string.settings_animations; hint = R.string.settings_animations_hint;
        } else if (id == R.id.advancedChoice) {
            title = R.string.advanced_effects; hint = R.string.advanced_effects_warning;
        } else if (id == R.id.soundsChoice) {
            title = R.string.settings_sounds; hint = R.string.settings_sounds_hint;
        } else if (id == R.id.libraryChoice) {
            title = R.string.app_library; hint = R.string.settings_app_library_hint;
        } else if (id == R.id.statsChoice) {
            title = R.string.settings_stats; hint = R.string.settings_stats_hint;
        } else if (id == R.id.followThemeChoice) {
            title = R.string.settings_follow_theme; hint = R.string.follow_theme_hint;
        } else if (id == R.id.defaultHomeChoice) {
            title = R.string.home_settings; hint = R.string.home_settings_help;
        } else if (id == R.id.tvSettingsChoice) {
            title = R.string.tv_settings; hint = R.string.settings_system_hint;
        } else {
            title = R.string.columns_label; hint = R.string.settings_columns_hint;
        }
        ((TextView) findViewById(R.id.settingsDetailTitle)).setText(title);
        ((TextView) findViewById(R.id.settingsDetailHint)).setText(hint);
    }

    private void updatePreview() {
        if (wallpaper.equals(previewChoice)) return;
        previewChoice = wallpaper;
        ImageView image = findViewById(R.id.settingsPreviewImage);
        image.setImageDrawable(null);
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            Bitmap bitmap = null;
            if (Config.WALLPAPER_AURORA.equals(wallpaper)) {
                options.inSampleSize = 4;
                try (java.io.InputStream in = getResources().openRawResource(R.raw.aurora_wallpaper)) {
                    bitmap = BitmapFactory.decodeStream(in, null, options);
                }
            } else if (!Config.WALLPAPER_OFF.equals(wallpaper)) {
                // A theme's rotating collection previews the picture Home shows now.
                java.io.File file = WallpaperPlayer.current(this, Config.load(this));
                if (file != null) {
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(file.getPath(), options);
                    options.inSampleSize = 1;
                    while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 512)
                        options.inSampleSize *= 2;
                    options.inJustDecodeBounds = false;
                    bitmap = BitmapFactory.decodeFile(file.getPath(), options);
                }
            }
            image.setImageBitmap(bitmap);
        } catch (Exception | OutOfMemoryError ignored) {
            // Preview failures keep the plain mini Home; they never interrupt the settings controls.
        }
    }

    private void setSounds(View view) {
        view.setSoundEffectsEnabled(navigationSounds);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) setSounds(group.getChildAt(i));
        }
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains(Config.ROWS_BELOW_DOCK_KEY) || choices.contains("animations")
                || choices.contains("advanced_effects") || choices.contains("navigation_sounds")
                || choices.contains("wallpaper_choice") || choices.contains("app_library")
                || choices.contains("show_stats");
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }

    private java.util.List<java.io.File> wallpaperImages() {
        java.io.File dir = Config.dir(this);
        java.util.List<java.io.File> images = new java.util.ArrayList<>(WallpaperRotation.images(
                dir == null ? null : new java.io.File(dir, "wallpapers")));
        java.io.File single = dir == null ? null : new java.io.File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile() && single.length() > 0) images.add(0, single);
        return images;
    }

    private void showWallpaperPicker() {
        if (wallpaperPicker != null) return;
        java.util.List<java.io.File> images = wallpaperImages();
        String[] labels = new String[images.size() + 3];
        String[] values = new String[labels.length];
        labels[0] = getString(R.string.wallpaper_theme_default); values[0] = Config.WALLPAPER_THEME;
        labels[1] = getString(R.string.wallpaper_aurora); values[1] = Config.WALLPAPER_AURORA;
        labels[2] = getString(R.string.off); values[2] = Config.WALLPAPER_OFF;
        for (int i = 0; i < images.size(); i++) {
            labels[i + 3] = images.get(i).getName(); values[i + 3] = images.get(i).getName();
        }
        int checked = java.util.Arrays.asList(values).indexOf(wallpaper);
        android.widget.ArrayAdapter<String> rows = new android.widget.ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_single_choice, labels) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                TextView row = (TextView) super.getView(position, recycled, parent);
                row.setTextColor(0xFFFFFFFF);
                row.setTextSize(16);
                row.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                row.setSoundEffectsEnabled(navigationSounds);
                return row;
            }
        };
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.wallpaper_label)
                .setSingleChoiceItems(rows, checked, (d, which) -> {
                    wallpaper = values[which];
                    if (Config.WALLPAPER_THEME.equals(wallpaper)) choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).apply();
                    else choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, wallpaper).apply();
                    setResult(RESULT_OK);
                    refresh();
                    d.dismiss();
                }).setNegativeButton(android.R.string.cancel, null).show();
        wallpaperPicker = dialog;
        dialog.setOnDismissListener(d -> wallpaperPicker = null);
        glass(dialog);
    }
}
