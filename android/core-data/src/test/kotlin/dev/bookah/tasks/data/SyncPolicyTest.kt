package dev.bookah.tasks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPolicyTest {
    private val now = 1_800_000_000_000L
    private val min = 60_000L

    @Test fun neverSyncedIsStale() = assertTrue(SyncPolicy.isStale(null, now, SyncPolicy.RESUME_STALE_MS))

    @Test fun aSyncTwoMinutesAgoIsFreshOnResume() = assertFalse(SyncPolicy.isStale(now - 2 * min, now, SyncPolicy.RESUME_STALE_MS))

    @Test fun aSyncSixMinutesAgoIsStaleOnResume() = assertTrue(SyncPolicy.isStale(now - 6 * min, now, SyncPolicy.RESUME_STALE_MS))

    @Test fun exactlyAtTheLimitIsStillFresh() = assertFalse(SyncPolicy.isStale(now - SyncPolicy.RESUME_STALE_MS, now, SyncPolicy.RESUME_STALE_MS))

    @Test fun aClockSetBackMakesTheOldValueStaleNotFreshForever() = assertTrue(SyncPolicy.isStale(now + 3 * 60 * min, now, SyncPolicy.RESUME_STALE_MS))

    @Test fun theWidgetLimitIsLongerThanTheResumeLimit() {
        assertEquals(5 * min, SyncPolicy.RESUME_STALE_MS)
        assertEquals(15 * min, SyncPolicy.WIDGET_STALE_MS)
        assertFalse(SyncPolicy.isStale(now - 10 * min, now, SyncPolicy.WIDGET_STALE_MS))
        assertTrue(SyncPolicy.isStale(now - 16 * min, now, SyncPolicy.WIDGET_STALE_MS))
    }

    @Test fun onlyAnErrorFreeOnlineRunCountsAsASync() {
        assertTrue(SyncPolicy.succeeded(SyncReport(2, 5, 0, 0, offline = false)))
        assertFalse(SyncPolicy.succeeded(SyncReport(0, 0, 0, 0, offline = true)))
        assertFalse(SyncPolicy.succeeded(SyncReport(0, 0, 0, 0, offline = false, error = "X", errorCode = 401)))
    }
}
