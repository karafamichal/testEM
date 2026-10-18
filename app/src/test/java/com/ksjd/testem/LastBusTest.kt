package com.ksjd.testem

import com.ksjd.testem.reminders.LastBus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZonedDateTime

class LastBusTest {
    private val now = ZonedDateTime.of(2026, 10, 15, 22, 0, 0, 0, SK_ZONE)

    @Test fun theLatestDepartureCountsWhenItIsSoon() {
        val last = LastBus.lastWithin(listOf("2026-10-15 21:30", "2026-10-15 22:35", "2026-10-15 22:10"), now)
        assertEquals(22, last?.hour); assertEquals(35, last?.minute)
    }

    @Test fun nothingWhileTheLastOneIsStillFarOrGone() {
        assertNull(LastBus.lastWithin(listOf("2026-10-15 23:30"), now))
        assertNull(LastBus.lastWithin(listOf("2026-10-15 21:50"), now))
        assertNull(LastBus.lastWithin(listOf("", "junk"), now))
    }
}

class YearSummaryTest {
    private val zone = java.time.ZoneId.of("Europe/Bratislava")
    private fun ms(y: Int, m: Int, d: Int) = java.time.ZonedDateTime.of(y, m, d, 8, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun countsOnlyThisYearsRidesAndPicksTheFavourites() {
        val rides = listOf(RideLog(ms(2026, 3, 1), "7", "A", "B", 12), RideLog(ms(2026, 3, 2), "7", "B", "A", 14),
            RideLog(ms(2026, 4, 1), "36", "A", "C", 20), RideLog(ms(2025, 12, 31), "1", "X", "Y", 30))
        val s = YearSummary.from(emptyList(), rides, 2026, zone)
        assertEquals(3, s.trips); assertEquals(46, s.minutesOnBoard); assertEquals("7" to 2, s.topLine)
        assertNull(s.topStop)
        assertEquals(true, YearSummary.from(emptyList(), emptyList(), 2026, zone).isEmpty)
    }
}

class RideDefaultDayTest {
    private val ride = RecurringRide(id = "r", route = SavedRoute(PLANNER_ALL, "A", "B"), time = "13:19", days = emptySet(), leadMinutes = 10)
    private fun at(h: Int, m: Int) = java.time.ZonedDateTime.of(2026, 10, 16, h, m, 0, 0, SK_ZONE)

    @Test fun noDayMeansTodayWhileThereIsTime() {
        assertEquals(listOf("2026-10-16"), ride.withDefaultDay(at(11, 2)).dates)
    }

    @Test fun noDayMeansTomorrowOnceItsTimeHasGone() {
        assertEquals(listOf("2026-10-17"), ride.withDefaultDay(at(13, 10)).dates)
    }

    @Test fun chosenDaysStayAsTheyAre() {
        val workdays = ride.copy(days = setOf(1, 2, 3, 4, 5))
        assertEquals(workdays, workdays.withDefaultDay(at(11, 2)))
    }
}
