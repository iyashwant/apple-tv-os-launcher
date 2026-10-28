package com.gathin.tvhome;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

/** Dock commands must preserve the folder-aware store and ranks of unavailable applications. */
public class DockLayoutTest {
    private HomeLayout home(String... apps) {
        HomeLayout layout = new HomeLayout();
        layout.reconcile(Arrays.asList(apps));
        return layout;
    }

    @Test public void addMovesGridAppIntoChosenDockPlaceWithoutDroppingOtherApps() {
        HomeLayout layout = home("a", "b", "c", "d");
        layout.placeInDock("d", "b", false);
        assertEquals(Arrays.asList("a", "d", "b", "c"), layout.home);
    }

    @Test public void replaceSwapsWithGridAndRetainsUnavailableRanks() {
        HomeLayout layout = home("a", "unavailable", "b", "c");
        layout.placeInDock("a", "c", true);
        assertEquals(Arrays.asList("c", "unavailable", "b", "a"), layout.home);
    }

    @Test public void movingTheLastFolderAppToItsFoldersDockPlaceKeepsThatPlace() {
        HomeLayout layout = home("a", "b", "c");
        String folder = layout.createFolder("b", "Players");
        layout.placeInDock("b", folder, false);
        assertEquals(Arrays.asList("a", "b", "c"), layout.home);
        assertTrue(layout.folders.isEmpty());
    }

    @Test public void moveFromFolderKeepsItsNameAndOtherMembers() {
        HomeLayout layout = home("a", "b", "c", "d");
        String folder = layout.createFolder("b", "Players");
        layout.moveTo("c", folder);
        layout.placeInDock("b", "a", false);
        assertEquals("Players", layout.folders.get(folder).name);
        assertEquals(Arrays.asList("c"), layout.folders.get(folder).apps);
        assertEquals(Arrays.asList("b", "a", folder, "d"), layout.home);
    }

    @Test public void staleTargetDoesNotRemoveTheSourceApp() {
        HomeLayout layout = home("a", "b");
        layout.placeInDock("b", "missing", false);
        assertEquals(Arrays.asList("a", "b"), layout.home);
    }
}
