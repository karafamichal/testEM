package com.ksjd.testem

import com.ksjd.testem.live.CatchEstimate
import com.ksjd.testem.live.StopPass
import com.ksjd.testem.live.StopPassDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StopPassDetectorTest {
    @Test
    fun passingWithoutStopping_interpolatesTheMoment() {
        val d = StopPassDetector()
        assertNull(d.onFix(0.8f, moving = true, atMs = 0))
        assertEquals(StopPass(1, 5_000L, stopped = false), d.onFix(1.2f, moving = true, atMs = 10_000))
    }

    @Test
    fun stoppedPastTheStop_leavesWhenItMovesAgain() {
        val d = StopPassDetector()
        d.onFix(0.9f, true, 0)
        assertNull(d.onFix(1.01f, false, 10_000))
        assertNull(d.onFix(1.01f, false, 40_000))
        assertEquals(StopPass(1, 45_000L, stopped = true), d.onFix(1.2f, true, 50_000))
    }

    @Test
    fun stoppedBeforeTheStop_countsWhenItDrivesPast() {
        val d = StopPassDetector()
        d.onFix(0.9f, true, 0)
        assertNull(d.onFix(0.99f, false, 10_000, atStop = true))
        assertNull(d.onFix(0.99f, false, 40_000, atStop = true))
        val (stop, at, stopped) = d.onFix(1.2f, true, 50_000)!!
        assertEquals(1, stop)
        assertEquals(40_476.0, at.toDouble(), 50.0)
        assertEquals(true, stopped)
    }

    @Test
    fun locationRecheck_isThreeQuartersOfTheWalk() {
        // ~460 m is 460 * 1.3 / 1.3 = 460 s walk -> 345 s.
        assertEquals(345_000L, CatchEstimate.nextCheckMs(460))
        assertEquals(CatchEstimate.MIN_RECHECK_MS, CatchEstimate.nextCheckMs(10))
        assertEquals(CatchEstimate.MAX_RECHECK_MS, CatchEstimate.nextCheckMs(20_000))
    }
}
