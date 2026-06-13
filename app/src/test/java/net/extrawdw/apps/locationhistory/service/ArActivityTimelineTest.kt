package net.extrawdw.apps.locationhistory.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Table tests for [ArActivityTimeline] — the per-activity ENTER/EXIT recency tracker. */
class ArActivityTimelineTest {

    private val t0 = 1_750_000_000_000L
    private val timeline = ArActivityTimeline()

    @Test
    fun activeWithin_trueAfterRecentEnter() {
        timeline.recordEnter(ArActivity.WALKING, t0)
        assertTrue(timeline.activeWithin(ArActivity.WALKING, t0 + 30_000, windowMs = 60_000))
        assertEquals(t0, timeline.lastEnter(ArActivity.WALKING))
    }

    @Test
    fun activeWithin_falseAfterWindowExpires() {
        timeline.recordEnter(ArActivity.WALKING, t0)
        assertFalse(timeline.activeWithin(ArActivity.WALKING, t0 + 120_000, windowMs = 60_000))
    }

    @Test
    fun activeWithin_falseAfterExitFollowingEnter() {
        timeline.recordEnter(ArActivity.WALKING, t0)
        timeline.recordExit(ArActivity.WALKING, t0 + 10_000)
        assertFalse(timeline.activeWithin(ArActivity.WALKING, t0 + 20_000, windowMs = 60_000))
    }

    @Test
    fun activeWithin_trueWhenReEnteredAfterExit() {
        timeline.recordEnter(ArActivity.WALKING, t0)
        timeline.recordExit(ArActivity.WALKING, t0 + 10_000)
        timeline.recordEnter(ArActivity.WALKING, t0 + 20_000)
        assertTrue(timeline.activeWithin(ArActivity.WALKING, t0 + 25_000, windowMs = 60_000))
    }

    @Test
    fun anyMovingActiveWithin_ignoresStill() {
        timeline.recordEnter(ArActivity.STILL, t0)
        assertFalse(timeline.anyMovingActiveWithin(t0 + 1_000, windowMs = 60_000))
        timeline.recordEnter(ArActivity.IN_VEHICLE, t0 + 2_000)
        assertTrue(timeline.anyMovingActiveWithin(t0 + 3_000, windowMs = 60_000))
    }

    @Test
    fun lastEnter_nullWhenNeverEntered() {
        assertNull(timeline.lastEnter(ArActivity.RUNNING))
    }
}
