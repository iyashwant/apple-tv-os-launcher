package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The saved home and folder contents. Component keys survive app updates and hidden apps. */
final class HomeLayout {
    static final String FOLDER_PREFIX = "folder:";
    static final class Folder {
        final String id;
        String name;
        final List<String> apps = new ArrayList<>();
        Folder(String id, String name) { this.id = id; this.name = name; }
    }

    final List<String> home = new ArrayList<>();
    final Map<String, Folder> folders = new LinkedHashMap<>();

    HomeLayout copy() {
        HomeLayout copy = new HomeLayout();
        copy.home.addAll(home);
        for (Folder folder : folders.values()) {
            Folder f = new Folder(folder.id, folder.name);
            f.apps.addAll(folder.apps);
            copy.folders.put(f.id, f);
        }
        return copy;
    }

    /** Normalize malformed/duplicate assignments, retaining unavailable apps for later reappearance. */
    void reconcile(List<String> inventory) {
        Set<String> assigned = new LinkedHashSet<>();
        for (Folder folder : new ArrayList<>(folders.values())) {
            List<String> unique = new ArrayList<>();
            for (String key : folder.apps)
                if (!key.startsWith(FOLDER_PREFIX) && assigned.add(key)) unique.add(key);
            folder.apps.clear();
            folder.apps.addAll(unique);
            if (unique.isEmpty()) folders.remove(folder.id);
        }
        List<String> unique = new ArrayList<>();
        for (String key : home) {
            if (key.startsWith(FOLDER_PREFIX) && !folders.containsKey(key)) continue;
            if (assigned.add(key)) unique.add(key);
        }
        for (String id : folders.keySet()) if (assigned.add(id)) unique.add(id);
        for (String key : inventory) if (assigned.add(key)) unique.add(key);
        home.clear();
        home.addAll(unique);
    }

    List<String> visible(String folderId, List<String> inventory) {
        Set<String> available = new LinkedHashSet<>(inventory);
        List<String> result = new ArrayList<>();
        Folder folder = folders.get(folderId);
        List<String> source = folderId == null ? home : folder == null ? new ArrayList<>() : folder.apps;
        for (String key : source) {
            Folder f = folders.get(key);
            if (available.contains(key) || (f != null && f.apps.stream().anyMatch(available::contains)))
                result.add(key);
        }
        return result;
    }

    void reorder(String folderId, List<String> visible) {
        Folder folder = folders.get(folderId);
        List<String> target = folderId == null ? home : folder == null ? null : folder.apps;
        if (target == null) return;
        List<String> merged = AppOrder.merge(target, visible);
        target.clear();
        target.addAll(merged);
    }

    String createFolder(String app, String name) {
        if (app == null || app.startsWith(FOLDER_PREFIX)) return null;
        int index = home.indexOf(app);
        String id = FOLDER_PREFIX + UUID.randomUUID();
        Folder folder = new Folder(id, name);
        folders.put(id, folder);
        moveTo(app, id);
        home.remove(id);
        home.add(index < 0 ? home.size() : Math.min(index, home.size()), id);
        return id;
    }

    void placeInDock(String app, String target, boolean swap) {
        if (app == null || app.startsWith(FOLDER_PREFIX) || target == null || app.equals(target) || !home.contains(target)) return;
        int source = home.indexOf(app), slot = home.indexOf(target);
        if (swap && source >= 0) {
            java.util.Collections.swap(home, source, slot);
            return;
        }
        moveTo(app, null);
        home.remove(app);
        int targetSlot = home.indexOf(target);
        home.add(targetSlot >= 0 ? targetSlot : Math.min(slot,home.size()), app);
    }

    void moveTo(String app, String folderId) {
        if (app == null || app.startsWith(FOLDER_PREFIX)) return;
        if (folderId != null && !folders.containsKey(folderId)) return;
        home.remove(app);
        for (Folder folder : folders.values()) folder.apps.remove(app);
        if (folderId == null) home.add(app);
        else folders.get(folderId).apps.add(app);
        for (Folder folder : new ArrayList<>(folders.values())) {
            if (folder.apps.isEmpty()) { folders.remove(folder.id); home.remove(folder.id); }
        }
    }
}
