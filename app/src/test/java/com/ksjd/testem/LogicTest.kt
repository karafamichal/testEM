package com.ksjd.testem

import com.ksjd.testem.live.StopPassDetector
import com.ksjd.testem.rides.RideScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZonedDateTime

class LogicTest {
    // ------------------------------------------------ stop passes (bug EM-0007)

    /** Fixes every 5 s: (index, moving, atStop). */
    private fun run(vararg fixes: Triple<Float, Boolean, Boolean>) =
        StopPassDetector().let { d -> fixes.mapIndexedNotNull { i, (index, moving, atStop) -> d.onFix(index, moving, i * 5_000L, atStop) } }

    @Test fun stopsJustPastTheStopPoint() {
        // Crosses stop 1 moving, halts 20 m further on, stands 15 s, drives on.
        val passes = run(
            Triple(0.80f, true, false), Triple(0.97f, true, true), Triple(1.03f, true, true),
            Triple(1.04f, false, true), Triple(1.04f, false, true), Triple(1.04f, false, true),
            Triple(1.08f, true, true), Triple(1.30f, true, false)
        )
        assertEquals(1, passes.size)
        assertEquals(1, passes[0].stop)
        assertTrue("stopped, not drove past", passes[0].stopped)
        assertEquals(27_500L, passes[0].atMs) // between the last standing fix and the first moving one
    }

    @Test fun drivesThroughWithoutStopping() {
        val passes = run(Triple(0.80f, true, false), Triple(0.95f, true, true), Triple(1.05f, true, true), Triple(1.30f, true, false))
        assertEquals(1, passes.size)
        assertEquals(false, passes[0].stopped)
        assertEquals(1, passes[0].stop)
    }

    @Test fun stopsJustBeforeTheStopPoint() {
        val passes = run(
            Triple(0.80f, true, false), Triple(0.95f, false, true), Triple(0.95f, false, true),
            Triple(1.02f, true, true), Triple(1.30f, true, false)
        )
        assertEquals(1, passes.size)
        assertTrue(passes[0].stopped)
    }

    // ------------------------------------------------ recurring rides

    private val route = SavedRoute("vlakbusmhd", "Zvolen,,AS", "Banská Bystrica,,AS")

    @Test fun workdayRideSkipsTheWeekend() {
        val ride = RecurringRide("r", route, "07:25", days = RecurringRide.WORKDAYS, leadMinutes = 10)
        // Friday 2026-10-02 at 08:00: today's 7:25 is gone, next is Monday.
        val next = ride.nextDeparture(ZonedDateTime.of(2026, 10, 2, 8, 0, 0, 0, SK_ZONE))!!
        assertEquals(LocalDate.of(2026, 10, 5), next.toLocalDate())
        assertEquals("07:25", next.toLocalTime().toString())
        // 7:16 on Friday: the follow start (7:15) has passed, so Monday too.
        assertEquals(LocalDate.of(2026, 10, 5), ride.nextDeparture(ZonedDateTime.of(2026, 10, 2, 7, 16, 0, 0, SK_ZONE))!!.toLocalDate())
        // 7:14 on Friday: still today.
        assertEquals(LocalDate.of(2026, 10, 2), ride.nextDeparture(ZonedDateTime.of(2026, 10, 2, 7, 14, 0, 0, SK_ZONE))!!.toLocalDate())
    }

    @Test fun datesAndRanges() {
        val ride = RecurringRide("r", route, "18:00", days = setOf(6), dates = listOf("2026-10-07"),
            startDate = "2026-10-10", endDate = "2026-10-17")
        val from = ZonedDateTime.of(2026, 10, 1, 12, 0, 0, 0, SK_ZONE)
        assertEquals(LocalDate.of(2026, 10, 7), ride.nextDeparture(from)!!.toLocalDate()) // single date
        assertEquals(LocalDate.of(2026, 10, 10), ride.nextDeparture(from.plusDays(7))!!.toLocalDate()) // first Saturday in range
        assertNull(ride.nextDeparture(ZonedDateTime.of(2026, 10, 18, 0, 0, 0, 0, SK_ZONE))) // range over
        assertNull(ride.copy(enabled = false).nextDeparture(from))
    }

    @Test fun picksTheSameBus() {
        fun conn(id: String, time: String, vararg lines: String) = TimetableConnection(id, time, "", "",
            lines.map { TimetableSegment("Bus $it", "", time, "A", time, "B", serviceDate = "2026-10-05") })
        val ride = RecurringRide("r", route, "07:25", lines = listOf("101"))
        val at = ZonedDateTime.of(2026, 10, 5, 7, 25, 0, 0, SK_ZONE)
        val results = listOf(conn("a", "7:20", "106"), conn("b", "7:25", "101"), conn("c", "7:40", "101"))
        assertEquals("b", RideScheduler.pick(results, ride, at)?.id)
        // Timetable changed by 5 min: same line still wins over a nearer other line.
        assertEquals("c", RideScheduler.pick(listOf(conn("a", "7:24", "106"), conn("c", "7:30", "101")), ride, at)?.id)
        assertNull(RideScheduler.pick(listOf(conn("x", "9:00", "5")), ride, at))
    }
}
