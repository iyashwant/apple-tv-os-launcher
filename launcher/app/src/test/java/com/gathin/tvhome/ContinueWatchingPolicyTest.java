package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ContinueWatchingPolicyTest {
    @Test public void onlyUnfinishedPlaybackIsEligible() {
        assertTrue(ContinueWatchingPolicy.unfinished(0, true, 100, 1000));
        assertFalse(ContinueWatchingPolicy.unfinished(0, true, 0, 1000));
        assertFalse(ContinueWatchingPolicy.unfinished(0, true, 1000, 1000));
        assertFalse(ContinueWatchingPolicy.unfinished(0, true, 2000, 1000));
    }
    @Test public void nextNewAndWatchlistAreNotContinueWatching() {
        for (int type : new int[]{1, 2, 3, -1, 100}) assertFalse(ContinueWatchingPolicy.unfinished(type, true, 100, 1000));
    }
    @Test public void unfinishedRecommendationItemsCanSupplementWatchNext() {
        assertTrue(ContinueWatchingPolicy.unfinished(-1, false, 100, 1000));
        assertFalse(ContinueWatchingPolicy.unfinished(-1, false, 1000, 1000));
    }
    @Test public void missingOrInvalidDurationDoesNotInventProgress() {
        for (long duration : new long[]{0, -1, Long.MIN_VALUE}) assertFalse(ContinueWatchingPolicy.unfinished(0, true, 100, duration));
        assertFalse(ContinueWatchingPolicy.unfinished(0, true, -1, 1000));
    }
    @Test public void extremePlaybackTimesCannotOverflowProgress() {
        assertEquals(500, ContinueWatchingPolicy.progress(Long.MAX_VALUE / 2, Long.MAX_VALUE));
        assertEquals(1000, ContinueWatchingPolicy.progress(Long.MAX_VALUE, 1));
        assertEquals(0, ContinueWatchingPolicy.progress(-100, 10));
        assertEquals(0, ContinueWatchingPolicy.progress(10, 0));
    }
    @Test public void futureAndNegativeEngagementTimesStayBounded() {
        assertEquals(1000, ContinueWatchingPolicy.engagement(Long.MAX_VALUE, 1000));
        assertEquals(0, ContinueWatchingPolicy.engagement(-1, 1000));
        assertEquals(500, ContinueWatchingPolicy.engagement(500, 1000));
    }
    @Test public void contentIdDeduplicatesAcrossFeeds() {
        assertEquals(ContinueWatchingPolicy.identity("app", "movie42", "watch-id", "watch-uri", "Title"),
                ContinueWatchingPolicy.identity("app", "movie42", "preview-id", "preview-uri", "Title"));
    }
    @Test public void sameContentInDifferentAppsKeepsEachResumeDestination() {
        assertNotEquals(ContinueWatchingPolicy.identity("app1", "42", "", "", ""), ContinueWatchingPolicy.identity("app2", "42", "", "", ""));
    }
    @Test public void fallbacksKeepDistinctProviderItems() {
        assertNotEquals(ContinueWatchingPolicy.identity("app", "", "1", "", "Title"), ContinueWatchingPolicy.identity("app", "", "2", "", "Title"));
        assertNotEquals(ContinueWatchingPolicy.identity("app", "", "", "uri1", "Title"), ContinueWatchingPolicy.identity("app", "", "", "uri2", "Title"));
    }
}
