package com.ksjd.testem

import com.ksjd.testem.live.RoutePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePositionTest {
    // Four stops going north, ~1.1 km apart (0.01° latitude), in Zvolen.
    private val coords = listOf(48.570 to 19.125, 48.580 to 19.125, 48.590 to 19.125, 48.600 to 19.125)
    private val t0 = 1_800_000_000_000L
    private val sched = listOf(0L, 3L, 6L, 9L).map { t0 + it * 60_000L }

    @Test
    fun pointBetweenStops_givesFractionalIndex() {
        val (index, meters) = RoutePosition.snap(48.5825, 19.1252, coords, RoutePosition.BUS_GPS_MAX_M)!!
        assertEquals(1.25f, index, 0.03f)
        assertTrue(meters < 30)
    }

    @Test
    fun pointFarFromRoute_isRejected() {
        // The feed sometimes pairs a position with the wrong trip: 16.9 km away in testing.
        assertNull(RoutePosition.snap(48.73, 19.15, coords, RoutePosition.BUS_GPS_MAX_M))
        // A rider walking 150 m beside the road is not on the bus.
        assertNull(RoutePosition.snap(48.5825, 19.1272, coords, RoutePosition.RIDER_GPS_MAX_M))
    }

    @Test
    fun missingStopCoordinates_areSkipped() {
        val gaps = listOf(coords[0], null, coords[2], coords[3])
        val (index, _) = RoutePosition.snap(48.595, 19.125, gaps, RoutePosition.BUS_GPS_MAX_M)!!
        assertEquals(2.5f, index, 0.03f)
    }

    @Test
    fun routeComingBack_prefersTheExpectedDirection() {
        // Out and back along the same street: stop 1 and stop 3 are the same place.
        val loop = listOf(48.570 to 19.125, 48.580 to 19.125, 48.590 to 19.125, 48.580 to 19.125, 48.570 to 19.125)
        val out = RoutePosition.snap(48.575, 19.125, loop, 300.0, expectedIndex = 0.4f)!!.first
        val back = RoutePosition.snap(48.575, 19.125, loop, 300.0, expectedIndex = 3.6f)!!.first
        assertEquals(0.5f, out, 0.05f)
        assertEquals(3.5f, back, 0.05f)
    }

    @Test
    fun delayFromPosition() {
        // Halfway between stop 1 (t0+3 min) and 2 (t0+6 min) = t0+4:30; seen at t0+6 min -> 90 s late.
        assertEquals(90, RoutePosition.delayAt(1.5f, sched, t0 + 6 * 60_000L))
        // Timetable times are departures: at stop 2 a minute before its time the bus waits, on time.
        assertEquals(0, RoutePosition.delayAt(2f, sched, t0 + 5 * 60_000L))
        // Halfway to stop 2 at t0+2 min: it left stop 1 (t0+3) a minute early.
        assertEquals(-60, RoutePosition.delayAt(1.5f, sched, t0 + 2 * 60_000L))
        // Still at the first stop early: waiting, not early.
        assertEquals(0, RoutePosition.delayAt(0.05f, sched, t0 - 60_000L))
    }
}
