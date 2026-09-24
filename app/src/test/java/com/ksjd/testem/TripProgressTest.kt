package com.ksjd.testem

import com.ksjd.testem.live.TrackedTrip
import com.ksjd.testem.live.TripDetail
import com.ksjd.testem.live.TripProgress
import com.ksjd.testem.live.TripRef
import com.ksjd.testem.live.TripStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The community buttons follow the predicted position, so they move on without taps. */
class TripProgressTest {
    private val t0 = 1_800_000_000_000L
    private val min = 60_000L
    // Boarding at stop 1 (19:35), then 20:00, then 20:35 where the rider gets off.
    private val stops = listOf(0L, 35L, 60L, 95L).mapIndexed { i, m -> TripStop("S$i", i + 1, i + 1, t0 + m * min, null) }
    private val trip = TrackedTrip(TripRef("2", 0, "", 0, "S3"), boardingPlatformIds = listOf(2), alightOrder = 4)
    private fun at(ms: Long, delaySeconds: Int? = null) = TripProgress.from(TripDetail("2", "S3", stops, delaySeconds), trip, ms)

    @Test
    fun earlyArrivalCountsAsOnTime_soTheRiderStaysAtTheStopUntilDeparture() {
        // "Bus is here" 6 minutes before departure is reported as delay 0 (it waits).
        val waiting = at(t0 + 29 * min, delaySeconds = 0)
        assertFalse(waiting.onBoard)
        assertEquals(1, waiting.boardingIndex)
        // Still waiting a moment before the timetable departure: "Bus is leaving" stays offered.
        assertFalse(at(t0 + 35 * min + 20_000, delaySeconds = 0).onBoard)
    }

    @Test
    fun withoutAnyTap_theNextStopTakesOverOnItsOwn() {
        val justLeft = at(t0 + 36 * min, delaySeconds = 0)
        assertTrue(justLeft.onBoard)
        assertEquals(2, justLeft.nextIndex) // "At S2"
        val later = at(t0 + 61 * min, delaySeconds = 0)
        assertEquals(3, later.nextIndex)     // forgot to mark S2: now "At S3"
        assertTrue(later.nextIsAlight)
    }

    @Test
    fun aReportedDelayShiftsWhichStopIsNext() {
        // 10 minutes late: at 20:05 the bus has not reached S2 (due 20:10 now).
        assertEquals(2, at(t0 + 65 * min, delaySeconds = 600).nextIndex)
        assertEquals(3, at(t0 + 65 * min, delaySeconds = 0).nextIndex)
    }
}
