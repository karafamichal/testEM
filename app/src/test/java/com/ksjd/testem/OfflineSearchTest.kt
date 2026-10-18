package com.ksjd.testem

import com.ksjd.testem.offline.OfflineTimetables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class OfflineSearchTest {
    private val day = LocalDate.of(2026, 10, 16)
    private fun at(h: Int, m: Int) = day.atTime(h, m).atZone(SK_ZONE).toEpochSecond()
    private fun s(h: Int, m: Int) = h * 3600 + m * 60

    // Stops: 0 A, 1 X, 2 Y, 3 B. Line 1: A 8:00 -> X 8:10. Line 2: X 8:11 -> B 8:30 (too tight a change).
    // Line 3: X 8:15 -> Y 8:20. Line 4: Y 8:25 -> B 8:40. Line 5: A 9:00 -> B 9:30 direct. Line 6 runs only tomorrow.
    private val pack = OfflineTimetables.Pack(
        version = 1, from = day,
        ids = arrayOf("a", "x", "y", "b"), names = arrayOf("A", "X", "Y", "B"),
        lines = arrayOf("1", "2", "3", "4", "5", "6"), headsigns = Array(6) { "" }, operators = Array(6) { "" },
        stops = arrayOf(intArrayOf(0, 1), intArrayOf(1, 3), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(0, 3), intArrayOf(0, 3)),
        times = arrayOf(intArrayOf(s(8, 0), s(8, 10)), intArrayOf(s(8, 11), s(8, 30)), intArrayOf(s(8, 15), s(8, 20)),
            intArrayOf(s(8, 25), s(8, 40)), intArrayOf(s(9, 0), s(9, 30)), intArrayOf(s(7, 0), s(7, 5))),
        days = intArrayOf(1, 1, 1, 1, 1, 2)
    )

    @Test fun twoChangesWhenTheQuickOneIsTooTight() {
        val found = OfflineTimetables.searchPack(pack, setOf(0), setOf(3), at(7, 50), changeMin = 2)
        assertEquals(listOf("Bus 1", "Bus 3", "Bus 4"), found.first().segments.map { it.line })
        assertEquals("8:40", found.first().arrivalTime)
        // The next one is the direct bus at 9:00, then tomorrow's 7:00.
        assertEquals(listOf("9:00", "7:00"), found.drop(1).map { it.departureTime })
    }

    @Test fun aOneMinuteChangeIsFineWhenAllowed() {
        val found = OfflineTimetables.searchPack(pack, setOf(0), setOf(3), at(7, 50), changeMin = 1)
        assertEquals(listOf("Bus 1", "Bus 2"), found.first().segments.map { it.line })
    }

    @Test fun nothingBackwardsOrBeforeTheTime() {
        assertTrue(OfflineTimetables.searchPack(pack, setOf(3), setOf(0), at(7, 0), changeMin = 2).isEmpty())
        assertEquals("7:00", OfflineTimetables.searchPack(pack, setOf(0), setOf(3), at(9, 1), changeMin = 2).first().departureTime)
    }
}
