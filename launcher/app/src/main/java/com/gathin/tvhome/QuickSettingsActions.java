package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Resolve optional configured firmware destinations, then fall back to public Android settings. */
final class QuickSettingsActions {
    static Intent intent(Context context, int control) {
        return intent(context, control, Config.load(context).settingsRoutes);
    }

    static Intent intent(Context context, int control, Map<String, String> routes) {
        if (control == R.id.quickAudioOutput) {
            Intent output = component(context, routes.get("quick_audio_output_component"));
            if (output != null) return output;
            Intent slice = settingsSlice(context, routes, "quick_audio_output_slice_resource");
            if (slice != null) return slice;
            return systemIntent(context, Settings.ACTION_SOUND_SETTINGS, routes);
        }
        if (control == R.id.quickApps) {
            Intent apps = component(context, routes.get("quick_apps_component"));
            if (apps != null) return apps;
            return systemIntent(context, Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS, routes);
        }
        if (control == R.id.quickBluetooth) {
            Intent slice = settingsSlice(context, routes, "quick_bluetooth_slice_resource");
            if (slice != null) return slice;
            Intent bluetooth = action(context, Settings.ACTION_BLUETOOTH_SETTINGS);
            if (bluetooth != null) return bluetooth;
            Intent accessories = component(context, routes.get("quick_bluetooth_component"));
            if (accessories != null) return accessories;
            return systemIntent(context, Settings.ACTION_BLUETOOTH_SETTINGS, routes);
        }
        if (control == R.id.quickScreensaver) {
            Intent dream = action(context, routes.get("quick_screensaver_action"));
            if (dream != null) return dream;
            Intent settings = action(context, Settings.ACTION_DREAM_SETTINGS);
            if (settings != null) return settings;
            settings = component(context, routes.get("quick_screensaver_component"));
            if (settings != null) return settings;
            return systemIntent(context, Settings.ACTION_SETTINGS, routes);
        }
        String standardAction = control == R.id.quickPicture ? Settings.ACTION_DISPLAY_SETTINGS
                : control == R.id.quickSound ? Settings.ACTION_SOUND_SETTINGS : Settings.ACTION_SETTINGS;
        return systemIntent(context, standardAction, routes);
    }

    static Intent systemIntent(Context context, String standardAction, Map<String, String> routes) {
        Intent chosen=chooseSystemIntent(standardAction,routes,intent -> available(context,intent)!=null);
        if(available(context,chosen)!=null) return chosen;
        for(String value:context.getResources().getStringArray(R.array.settings_fallback_components)){
            Intent candidate=component(context,value);if(candidate!=null)return candidate;
        }
        Intent aosp=component(context,"com.android.tv.settings/.MainSettings");
        return aosp!=null?aosp:chosen;
    }

    static Intent chooseSystemIntent(String standardAction, Map<String, String> routes,
                                     Predicate<Intent> canLaunch) {
        for (String candidate : relatedActions(standardAction)) {
            Intent standard = new Intent(candidate);
            if (canLaunch.test(standard)) return standard;
        }
        Intent general = new Intent(Settings.ACTION_SETTINGS);
        if (canLaunch.test(general)) return general;
        Intent fallback = componentIntent(routes.get("settings_fallback_component"));
        if (fallback != null && canLaunch.test(fallback)) return fallback;
        // The caller catches ActivityNotFoundException/SecurityException and shows Not available.
        return new Intent(standardAction);
    }

    /** Firmware can expose one of several public entry points for the same category. */
    private static String[] relatedActions(String requested) {
        if (Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS.equals(requested))
            return new String[]{requested, Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS,
                    Settings.ACTION_APPLICATION_SETTINGS};
        if (Settings.ACTION_WIFI_SETTINGS.equals(requested) || Settings.ACTION_BLUETOOTH_SETTINGS.equals(requested))
            return new String[]{requested, Settings.ACTION_WIRELESS_SETTINGS};
        return new String[]{requested};
    }

    private static Intent action(Context context, String action) {
        return action == null || action.isEmpty() ? null : available(context, new Intent(action));
    }

    private static Intent component(Context context, String value) {
        Intent intent = componentIntent(value);
        return intent != null ? available(context, intent) : null;
    }

    private static Intent componentIntent(String value) {
        if (value == null || value.isEmpty()) return null;
        ComponentName component = ComponentName.unflattenFromString(value);
        if (component == null) return null;
        return new Intent().setComponent(component);
    }

    private static Intent available(Context context, Intent intent) {
        try {
            ResolveInfo resolved = context.getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            return resolved != null && !resolved.activityInfo.name.contains("CTSDummy")
                    && launchable(context, resolved.activityInfo) ? intent : null;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    static boolean launchable(Context context, ActivityInfo info) {
        return info != null && info.enabled && info.exported
                && info.applicationInfo != null && info.applicationInfo.enabled
                && (info.permission == null || info.permission.isEmpty()
                || context.checkSelfPermission(info.permission) == PackageManager.PERMISSION_GRANTED);
    }

    private static Intent settingsSlice(Context context, Map<String, String> routes, String key) {
        String pkg = routes.get("settings_package");
        String resource = routes.get(key);
        String action = routes.get("settings_slice_action");
        String extra = routes.get("settings_slice_uri_extra");
        if (pkg == null || pkg.isEmpty() || resource == null || resource.isEmpty()
                || action == null || action.isEmpty() || extra == null || extra.isEmpty()) return null;
        // Keep the configured package first, then discover the firmware's public Settings handlers.
        // Vendor builds can use their own settings package rather than the AOSP package name.
        for (String candidate : settingsPackages(context, pkg)) {
            try {
                android.content.res.Resources res = context.getPackageManager().getResourcesForApplication(candidate);
                int id = res.getIdentifier(resource, "string", candidate);
                Uri uri = id == 0 ? null : Uri.parse(res.getString(id));
                if (uri != null && "content".equals(uri.getScheme())
                        && uri.getAuthority() != null && !uri.getAuthority().isEmpty()) {
                    Intent slice = available(context,
                            new Intent(action).setPackage(candidate).putExtra(extra, uri.toString()));
                    if (slice != null) return slice;
                }
            } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
                // A different settings handler may expose this optional resource and slice protocol.
            }
        }
        return null;
    }

    private static List<String> settingsPackages(Context context, String configured) {
        List<ResolveInfo> handlers = new ArrayList<>();
        try {
            PackageManager pm = context.getPackageManager();
            Intent settings = new Intent(Settings.ACTION_SETTINGS);
            ResolveInfo preferred = pm.resolveActivity(settings, PackageManager.MATCH_DEFAULT_ONLY);
            if (preferred != null) handlers.add(preferred);
            handlers.addAll(pm.queryIntentActivities(settings, PackageManager.MATCH_DEFAULT_ONLY));
        } catch (RuntimeException unavailable) {
            // Keep the configured destination when discovery is unavailable or restricted.
        }
        return settingsPackages(configured, handlers, info -> launchable(context, info));
    }

    static List<String> settingsPackages(String configured, List<ResolveInfo> handlers,
                                         Predicate<ActivityInfo> canLaunch) {
        LinkedHashSet<String> packages = new LinkedHashSet<>();
        if (configured != null && !configured.isEmpty()) packages.add(configured);
        for (ResolveInfo handler : handlers) {
            ActivityInfo info = handler == null ? null : handler.activityInfo;
            if (info != null && info.applicationInfo != null && info.packageName != null
                    && !info.packageName.isEmpty() && canLaunch.test(info)
                    && (info.applicationInfo.flags & (ApplicationInfo.FLAG_SYSTEM
                    | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0)
                packages.add(info.packageName);
        }
        return new ArrayList<>(packages);
    }

    private QuickSettingsActions() { }
}
