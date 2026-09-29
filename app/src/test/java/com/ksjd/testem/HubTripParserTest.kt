package com.ksjd.testem

import com.google.gson.JsonParser
import com.ksjd.testem.live.LiveRepository
import com.ksjd.testem.live.TripRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HubTripParserTest {
    private fun stop(name: String, arr: Long, dep: Long, platform: String = "", lat: String = "48.7") =
        """{"id":"x","name":"$name","lat":$lat,"lon":19.1,"arrival":$arr,"departure":$dep,"platform":"$platform"}"""

    @Test
    fun boardingAndAlightComeFromTheLink_andTheAlightStopUsesItsArrival() {
        val json = JsonParser.parseString(
            """{"tripId":"f:t:20260923","line":"60101","headsign":"","stops":[""" +
                stop("Before", 1000, 1000) + "," +
                stop("Banská Bystrica, AS", 2000, 2100, platform = "16") + "," +
                stop("Zvolen, AS", 3000, 3300, platform = "4", lat = "null") + "," +
                stop("Budapest", 4000, 4000) + "]}"
        ).asJsonObject
        val ref = TripRef("60101", 0, "", 0, "Zvolen", scheduleUrl = TimetableService.tripUrl("f:t:20260923", 1, 2))

        val trip = LiveRepository.parseHubTrip(json, ref, 1, 2)

        assertEquals(listOf("Before", "Banská Bystrica, AS", "Zvolen, AS", "Budapest"), trip.stops.map { it.name })
        assertEquals(listOf("", "16", "4", ""), trip.stops.map { it.platform })
        assertEquals(2, trip.boardingOrder)
        assertEquals(3, trip.alightOrder)
        assertEquals(listOf(1000L, 2100L, 3000L, 4000L), trip.stops.map { it.scheduledMs })
        assertNull(trip.stops[2].lat)
        assertEquals("Budapest", trip.destination)
    }

    @Test
    fun tripUrlsRoundTrip_andCpSkLinksAreNotOurs() {
        val url = TimetableService.tripUrl("sad-zvolen-as:T-5:20260930", 3, null)
        assertEquals(Triple("sad-zvolen-as:T-5:20260930", 3, null), TimetableService.parseTripUrl(url))
        assertNull(TimetableService.parseTripUrl("https://cp.sk/vlakbusmhd/draha/?x=1"))
        assertEquals(true, TimetableService.sameLine("60110", "110"))
        assertEquals(false, TimetableService.sameLine("10", ""))
    }
}
