package com.ksjd.testem

import com.ksjd.testem.live.CatchEstimate
import com.ksjd.testem.live.CatchEstimate.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class CatchEstimateTest {
    // 600 m straight line -> 780 m of streets -> 600 s on foot.
    private fun verdict(meters: Int, secondsToBus: Int) = CatchEstimate.from(meters, secondsToBus).verdict

    @Test
    fun walkTimeIncludesDetour() {
        val e = CatchEstimate.from(600, 900)
        assertEquals(600, e.walkSeconds)
        assertEquals(300, e.marginSeconds)
    }

    @Test
    fun verdicts() {
        assertEquals(Verdict.AtStop, verdict(30, 60))
        assertEquals(Verdict.Relaxed, verdict(600, 1500))
        assertEquals(Verdict.LeaveSoon, verdict(600, 800))
        assertEquals(Verdict.LeaveNow, verdict(600, 630))
        assertEquals(Verdict.Miss, verdict(600, 400))
    }
}
