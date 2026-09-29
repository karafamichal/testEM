package com.ksjd.testem

import com.ksjd.testem.live.LiveRepository
import com.ksjd.testem.live.TripRef
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ScheduledTripParserTest {
    private val zone = ZoneId.of("Europe/Bratislava")

    private fun item(name: String, arr: String, dep: String, inactive: Boolean, platform: String = "") = """
        <li class="item${if (inactive) " inactive" else ""}">
          <span class="arrival"><span class="label out"></span>$arr</span>
          <span class="departure"><span class="label out"></span>$dep</span>
          <strong class="name">$name</strong>
          ${if (platform.isEmpty()) "" else """<span class="fixed-codes"><span><span title="nástupište" class="color-green">$platform</span></span></span>"""}
          <span class="distance"><span class="label out"></span>12 km</span>
        </li>"""

    @Test
    fun boardingAndAlightFollowActiveStops_andDaysRollOverMidnight() {
        val html = "<ul class=\"reset line-itinerary\">" +
            item("Before", "", "20:40", inactive = true) +
            item("Banská Bystrica,,AS", "", "21:00", inactive = false, platform = "16") +
            item("Zvolen,,AS", "21:20", "21:25", inactive = false, platform = "4") +
            item("Budapest", "23:50", "23:55", inactive = true) +
            item("Népliget", "0:15", "", inactive = true) +
            "</ul>"
        val ref = TripRef("N976", 0, "", 0, "Zvolen", scheduleUrl = "x", serviceDate = "2026-09-23")

        val trip = LiveRepository.parseScheduledTrip(html, ref, zone)

        assertEquals(listOf("Before", "Banská Bystrica, AS", "Zvolen, AS", "Budapest", "Népliget"), trip.stops.map { it.name })
        assertEquals(listOf("", "16", "4", "", ""), trip.stops.map { it.platform })
        assertEquals(2, trip.boardingOrder)
        assertEquals(3, trip.alightOrder)
        fun at(ms: Long) = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), zone)
        assertEquals(LocalDateTime.of(2026, 9, 23, 21, 0), at(trip.stops[1].scheduledMs))
        assertEquals(LocalDateTime.of(2026, 9, 23, 20, 40), at(trip.stops[0].scheduledMs))
        assertEquals(LocalDateTime.of(2026, 9, 23, 21, 20), at(trip.stops[2].scheduledMs)) // arrival where you get off
        assertEquals(LocalDateTime.of(2026, 9, 24, 0, 15), at(trip.stops[4].scheduledMs))
    }
}
