package com.ksjd.testem

import com.google.gson.JsonObject
import com.ksjd.testem.hub.HubClient
import java.time.Instant
import java.time.LocalDate

/**
 * Timetables from emhub (open GTFS feeds of the Slovak bus carriers): stop suggestions,
 * connections, other carriers' departures and trips. Stops are emhub station ids; a trip is
 * [tripUrl] ("hub:trip?id=…&from=…&to=…"), stored where cp.sk route links used to be.
 */
class TimetableService {
    data class SearchRequest(
        val citySlug: String,
        val fromInput: String,
        val toInput: String,
        val timeInput: String,
        val directOnly: Boolean,
        val fromSuggestion: CpStopSuggestion? = null,
        val toSuggestion: CpStopSuggestion? = null,
        /** ISO date, "" = today. */
        val dateInput: String = ""
    )

    /** One row of a stop's departure board; [atMs] is the departure in epoch ms. */
    data class BoardRow(
        val line: String,
        val minutes: Int,
        val destination: String,
        val platform: String = "",
        val operator: String = "",
        val atMs: Long = 0L,
        /** [tripUrl] boarding at this stop. */
        val tripUrl: String = ""
    )

    suspend fun searchConnections(request: SearchRequest): Result<TimetableSearchResult> = runCatching {
        val date = runCatching { LocalDate.parse(request.dateInput) }.getOrElse { LocalDate.now(SK_ZONE) }
        val (h, m) = request.timeInput.trim().split(":").map { it.toInt() }
        plan(stopParam(request.fromSuggestion, request.fromInput), stopParam(request.toSuggestion, request.toInput),
            date.atTime(h, m).atZone(SK_ZONE).toEpochSecond(), request.directOnly, emptyList())
    }

    /** The next connections after the last one listed. */
    suspend fun loadMoreConnections(cursor: TimetablePagingCursor): Result<TimetableSearchResult> = runCatching {
        plan(cursor.fromText, cursor.toText, cursor.searchDate.toLong(), cursor.handle == "direct", cursor.listedIds)
    }

    private fun plan(from: String, to: String, epochS: Long, direct: Boolean, listed: List<String>): TimetableSearchResult {
        val root = HubClient.timetable("plan", mapOf("from" to from, "to" to to, "time" to epochS.toString(), "n" to "5",
            "direct" to if (direct) "1" else "0"))
        val connections = root.getAsJsonArray("connections").map { parseConnection(it.asJsonObject) }
            .filter { it.id !in listed }.distinctBy { it.id }
        val last = root.getAsJsonArray("connections").lastOrNull()?.asJsonObject?.get("departure")?.asLong
        val cursor = last?.let {
            TimetablePagingCursor(PLANNER_ALL, "", from, to, if (direct) "direct" else "all", (it / 1000 + 60).toString(),
                listed + connections.map { c -> c.id })
        }
        return TimetableSearchResult(connections, cursor)
    }

    suspend fun suggestStops(@Suppress("UNUSED_PARAMETER") citySlug: String, input: String): Result<List<CpStopSuggestion>> =
        runCatching {
            if (input.isBlank()) emptyList()
            else HubClient.timetable("stops", mapOf("q" to input.trim())).getAsJsonArray("stops").map { suggestion(it.asJsonObject) }
        }

    /** The timetable station at a sadzv stop, matched by position (names differ between the two). */
    fun intercityStopNear(@Suppress("UNUSED_PARAMETER") name: String, lat: Double, lon: Double): CpStopSuggestion? =
        HubClient.timetable("stops", mapOf("lat" to lat.toString(), "lon" to lon.toString(), "radius" to "300", "n" to "1"))
            .getAsJsonArray("stops").firstOrNull()?.let { suggestion(it.asJsonObject) }

    /**
     * Upcoming buses of other carriers at [stop]. S.A.D. Zvolen is left out: its buses are on
     * the board already, live from sadzv.
     */
    fun intercityBoard(stop: CpStopSuggestion): List<BoardRow> =
        board(mapOf("stop" to stop.value, "n" to "40", "exclude" to SADZV_FEED))

    private fun board(params: Map<String, String>): List<BoardRow> =
        HubClient.timetable("departures", params).getAsJsonArray("departures").map { el ->
            val d = el.asJsonObject
            val at = d.get("at").asLong
            val local = Instant.ofEpochMilli(at).atZone(SK_ZONE)
            BoardRow(
                line = d.str("line"),
                minutes = local.hour * 60 + local.minute,
                destination = d.str("headsign"),
                platform = d.str("platform"),
                operator = d.str("operator"),
                atMs = at,
                tripUrl = tripUrl(d.str("tripId"), d.get("seq").asInt, null)
            )
        }

    /**
     * The timetable trip of a sadzv bus that has no stop list: the same line leaving a stop
     * within 300 m of [lat]/[lon] within 2 minutes of [plannedMinutes] (line numbers are written
     * differently: "606106" vs "60106", so one only has to end with the other).
     */
    fun findTrip(lat: Double, lon: Double, line: String, plannedMinutes: Int): TimetableSegment? {
        val today = LocalDate.now(SK_ZONE)
        val nowMinutes = java.time.LocalTime.now(SK_ZONE).let { it.hour * 60 + it.minute }
        // A planned time far in the past today is tomorrow morning's bus (or last night's): take the nearest day.
        val day = when {
            plannedMinutes - nowMinutes > 720 -> today.minusDays(1)
            nowMinutes - plannedMinutes > 720 -> today.plusDays(1)
            else -> today
        }
        val start = day.atStartOfDay(SK_ZONE).toEpochSecond() + (plannedMinutes - 3) * 60L
        val rows = board(mapOf("lat" to lat.toString(), "lon" to lon.toString(), "radius" to "300", "time" to start.toString(), "n" to "60"))
        val row = rows.filter { r -> sameLine(r.line, line) && kotlin.math.abs(r.minutes - plannedMinutes) <= 2 }
            .minByOrNull { kotlin.math.abs(it.minutes - plannedMinutes) } ?: return null
        val time = "%d:%02d".format(row.minutes / 60, row.minutes % 60)
        return TimetableSegment(row.line, row.operator, time, "", "", row.destination, row.platform, "",
            routeUrl = row.tripUrl, serviceDate = Instant.ofEpochMilli(row.atMs).atZone(SK_ZONE).toLocalDate().toString())
    }

    private fun parseConnection(c: JsonObject): TimetableConnection {
        val trips = c.getAsJsonArray("legs").map { it.asJsonObject }.filter { it.str("type") == "trip" }
        val segments = trips.map { l ->
            val from = l.getAsJsonObject("from")
            val to = l.getAsJsonObject("to")
            val mode = l.str("mode")
            TimetableSegment(
                // Trains carry their kind ("R 833"); buses get a word, like cp.sk did ("Bus 60107").
                line = if (mode == "rail") l.str("line") else "Bus ${l.str("line")}",
                operatorName = l.str("operator"),
                departureTime = clock(l.get("departure").asLong),
                departureStop = from.str("name"),
                arrivalTime = clock(l.get("arrival").asLong),
                arrivalStop = to.str("name"),
                departurePlatform = from.str("platform"),
                arrivalPlatform = to.str("platform"),
                // A city bus from SAD Zvolen's live data is followed live; others by their timetable trip.
                routeUrl = l.getAsJsonObject("live")?.let { live ->
                    liveUrl(LiveLink(live.str("line"), live.get("lineId").asLong, live.str("routeNumber"), live.get("tripNumber").asInt,
                        from.get("platformId").asInt, to.get("seq").asInt, live.get("stopId").asInt,
                        Instant.ofEpochMilli(l.get("departure").asLong).atZone(SK_ZONE).toLocalTime().toSecondOfDay(),
                        timetable = live.get("fromIndex")?.let { tripUrl(l.str("tripId"), it.asInt, live.get("toIndex").asInt) }.orEmpty()))
                } ?: tripUrl(l.str("tripId"), from.get("seq").asInt, to.get("seq").asInt),
                serviceDate = Instant.ofEpochMilli(l.get("departure").asLong).atZone(SK_ZONE).toLocalDate().toString()
            )
        }
        val minutes = ((c.get("arrival").asLong - c.get("departure").asLong) / 60_000L).toInt()
        return TimetableConnection(
            id = trips.joinToString("|") { it.str("tripId") },
            departureTime = segments.firstOrNull()?.departureTime ?: clock(c.get("departure").asLong),
            arrivalTime = segments.lastOrNull()?.arrivalTime ?: clock(c.get("arrival").asLong),
            totalDuration = if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min",
            segments = segments
        )
    }

    private fun suggestion(s: JsonObject) = CpStopSuggestion(
        selectedText = s.str("name"),
        value = s.str("id"),
        value2 = HUB_STOP,
        coorX = s.get("lat")?.takeIf { it.isJsonPrimitive }?.asString,
        coorY = s.get("lon")?.takeIf { it.isJsonPrimitive }?.asString
    )

    companion object {
        /** value2 of suggestions from emhub; older saved ones came from cp.sk and are looked up by name. */
        const val HUB_STOP = "hub"
        const val SADZV_FEED = "sad-zvolen-as"

        fun tripUrl(tripId: String, from: Int?, to: Int?): String =
            "hub:trip?id=" + java.net.URLEncoder.encode(tripId, "UTF-8") + (from?.let { "&from=$it" } ?: "") + (to?.let { "&to=$it" } ?: "")

        /** Trip id and the boarding/alighting stop indexes of a [tripUrl]. */
        fun parseTripUrl(url: String): Triple<String, Int?, Int?>? {
            if (!url.startsWith("hub:trip?")) return null
            val params = url.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
            val id = params["id"]?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: return null
            return Triple(id, params["from"]?.toIntOrNull(), params["to"]?.toIntOrNull())
        }

        /** A bus in sadzv's live data: enough to follow it live, with where the user gets on and off. */
        data class LiveLink(
            val line: String, val lineId: Long, val routeNumber: String, val tripNumber: Int,
            val boardingPlatformId: Int, val alightOrder: Int, val stopId: Int, val plannedSecondOfDay: Int,
            /** The same bus as a timetable trip link: used on other days, since sadzv only knows today's run. */
            val timetable: String = ""
        )

        fun liveUrl(l: LiveLink): String =
            "sadzv:trip?line=${java.net.URLEncoder.encode(l.line, "UTF-8")}&lineId=${l.lineId}&route=${java.net.URLEncoder.encode(l.routeNumber, "UTF-8")}" +
                "&trip=${l.tripNumber}&board=${l.boardingPlatformId}&alight=${l.alightOrder}&stop=${l.stopId}&planned=${l.plannedSecondOfDay}" +
                (if (l.timetable.isNotEmpty()) "&tt=" + java.net.URLEncoder.encode(l.timetable, "UTF-8") else "")

        fun parseLiveUrl(url: String): LiveLink? {
            if (!url.startsWith("sadzv:trip?")) return null
            val p = url.substringAfter('?').split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }
            return LiveLink(
                p["line"] ?: return null, p["lineId"]?.toLongOrNull() ?: return null, p["route"].orEmpty(), p["trip"]?.toIntOrNull() ?: return null,
                p["board"]?.toIntOrNull() ?: 0, p["alight"]?.toIntOrNull() ?: 0, p["stop"]?.toIntOrNull() ?: 0, p["planned"]?.toIntOrNull() ?: -1,
                p["tt"].orEmpty()
            )
        }

        fun sameLine(a: String, b: String): Boolean = a == b || (a.isNotEmpty() && b.isNotEmpty() && (a.endsWith(b) || b.endsWith(a)))

        private fun stopParam(s: CpStopSuggestion?, text: String): String =
            if (s != null && s.value2 == HUB_STOP) s.value else (s?.selectedText ?: text).trim()

        private fun clock(ms: Long): String = Instant.ofEpochMilli(ms).atZone(SK_ZONE).let { "%d:%02d".format(it.hour, it.minute) }

        private fun JsonObject.str(key: String): String = get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    }
}
