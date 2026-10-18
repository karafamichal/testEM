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
        val dateInput: String = "",
        /** EM-0053: [timeInput] is when to be there. */
        val arriveBy: Boolean = false
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
        val tripUrl: String = "",
        /** Timetable feed, e.g. "sad-zvolen-as-city". */
        val feed: String = "",
        /** The bus ends its trip at this stop (sadzv lists those as departures too). */
        val ends: Boolean = false
    )

    suspend fun searchConnections(request: SearchRequest): Result<TimetableSearchResult> = runCatching {
        val date = runCatching { LocalDate.parse(request.dateInput) }.getOrElse { LocalDate.now(SK_ZONE) }
        val (h, m) = request.timeInput.trim().split(":").map { it.toInt() }
        val at = date.atTime(h, m).atZone(SK_ZONE).toEpochSecond()
        try {
            plan(stopParam(request.fromSuggestion, request.fromInput), stopParam(request.toSuggestion, request.toInput),
                at, request.directOnly, emptyList(), request.arriveBy)
        } catch (e: java.io.IOException) {
            // 2.17.0: no connection to emHUB: the timetables kept on the phone, if they have both stops (leaving from the time).
            val offline = com.ksjd.testem.offline.OfflineTimetables.search(request.fromSuggestion, request.fromInput,
                request.toSuggestion, request.toInput, at, changeMinutes) ?: throw e
            TimetableSearchResult(offline.filter { !request.directOnly || it.isDirect }, null, offline = true)
        }
    }

    /** A stop of a planner leg's bus after where the rider gets on (for editing the change, EM-0026, EM-0030). */
    data class LegStop(val index: Int, val name: String, val lat: Double?, val lon: Double?, val arrivalMs: Long, val json: JsonObject)

    /** A stop to get on the next bus, with the walk to it from where the rider got off. */
    data class BoardStop(val stop: CpStopSuggestion, val walkSeconds: Long)

    /** Stops of [segment]'s bus after the boarding stop, to choose where to get off. Empty without a timetable trip. */
    fun stopsAfterBoarding(segment: TimetableSegment): List<LegStop> {
        val link = parseTripUrl(segment.routeUrl.timetableLink()) ?: return emptyList()
        val stops = HubClient.timetable("trip", mapOf("id" to link.first)).getAsJsonArray("stops").map { it.asJsonObject }
        return stops.mapIndexed { i, st -> LegStop(i, st.str("name"), st.num("lat"), st.num("lon"), st.get("arrival").asLong, st) }
            .drop((link.second ?: 0) + 1)
    }

    /** Stops around where the rider gets off (that stop first), each with the walk to it. */
    fun boardStopsNear(off: LegStop): List<BoardStop> {
        val lat = off.lat ?: return emptyList()
        val lon = off.lon ?: return emptyList()
        return HubClient.timetable("stops", mapOf("lat" to lat.toString(), "lon" to lon.toString(), "radius" to "600", "n" to "8"))
            .getAsJsonArray("stops").map { it.asJsonObject }
            .map { BoardStop(suggestion(it), walkSeconds(lat, lon, it.num("lat"), it.num("lon"))) }
            .sortedBy { it.walkSeconds }
    }

    /** Seconds on foot between two points, like emhub's planner (1.2 m/s, streets 30 % longer, +1 min); 0 for the same place. */
    fun walkSeconds(lat1: Double?, lon1: Double?, lat2: Double?, lon2: Double?): Long {
        if (lat1 == null || lon1 == null || lat2 == null || lon2 == null) return 0L
        val m = com.ksjd.testem.live.LiveRepository.distanceMeters(lat1, lon1, lat2, lon2)
        return if (m < 30) 0L else (m / (1.2 / 1.3)).toLong() + 60
    }

    /**
     * [c] with its change before segment [index] edited by the rider: get off that bus at [off], walk to [board],
     * then the first way from there to the destination after the walk and the change time. Null when there is none.
     */
    fun editTransfer(c: TimetableConnection, index: Int, off: LegStop, board: BoardStop, walkStart: Boolean = false): TimetableConnection? {
        val bus = c.segments[index - 1]
        val link = parseTripUrl(bus.routeUrl.timetableLink()) ?: return null
        val ready = off.arrivalMs / 1000 + board.walkSeconds + changeMinutes * 60L
        val onward = HubClient.timetable("plan", mapOf("from" to stopParam(board.stop, board.stop.selectedText), "to" to c.segments.last().arrivalStop,
            "time" to ready.toString(), "n" to "1", "change" to changeMinutes.toString(), "walkstart" to if (walkStart) "1" else "0"))
            .getAsJsonArray("connections")?.firstOrNull()?.asJsonObject ?: return null
        val next = parseConnection(onward).takeIf { it.segments.isNotEmpty() } ?: return null
        val cut = bus.copy(arrivalStop = off.name, arrivalTime = clock(off.arrivalMs), arrivalPlatform = off.json.str("platform"),
            routeUrl = moveLink(bus.routeUrl, link.first, link.second ?: 0, off.index, off.json, board = false))
        val first = c.segments.first()
        val start = runCatching { LocalDate.parse(first.serviceDate).atTime(parseClock(first.departureTime)!!).atZone(SK_ZONE).toInstant().toEpochMilli() }
            .getOrNull() ?: off.arrivalMs
        val minutes = ((onward.get("arrival").asLong - start) / 60_000L).toInt()
        return TimetableConnection(
            id = c.id + "~" + next.id,
            departureTime = c.departureTime,
            arrivalTime = next.arrivalTime,
            totalDuration = if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min",
            segments = c.segments.take(index - 1) + cut + next.segments
        )
    }

    /**
     * [c] with the rider getting off bus [busIndex] at its stop [order], tapped in that bus's stop list (EM-0039):
     * the rest planned from there, a short walk to another stop allowed. Null when nothing goes on from there.
     */
    fun getOffAt(c: TimetableConnection, busIndex: Int, order: Int): TimetableConnection? {
        if (busIndex !in 0 until c.segments.lastIndex) return null
        // The stop list numbers stops from 1 (order), the trip's stops are indexed from 0.
        val off = stopsAfterBoarding(c.segments[busIndex]).firstOrNull { it.index == order - 1 } ?: return null
        return editTransfer(c, busIndex + 1, off, BoardStop(suggestion(off.json), 0), walkStart = true)
    }

    /** The timetable trip link of a segment: its own, or the one a live (sadzv) link carries. */
    private fun String.timetableLink(): String = parseLiveUrl(this)?.timetable?.takeIf { it.isNotEmpty() } ?: this

    /** A leg's link with a new boarding ([board]) or alighting stop; a live link keeps following live. */
    private fun moveLink(url: String, tripId: String, from: Int, to: Int, stop: JsonObject, board: Boolean): String {
        val timetable = tripUrl(tripId, from, to)
        val live = parseLiveUrl(url) ?: return timetable
        val local = { ms: Long -> Instant.ofEpochMilli(ms).atZone(SK_ZONE).toLocalTime().toSecondOfDay() }
        return liveUrl(if (board) live.copy(
            boardingPlatformId = stop.get("platformId")?.asInt ?: 0, stopId = stop.get("stopId")?.asInt ?: live.stopId,
            plannedSecondOfDay = local(stop.get("departure").asLong), timetable = timetable)
        else live.copy(alightOrder = stop.get("seq")?.asInt ?: (to + 1), timetable = timetable))
    }

    /** The next connections after the last one listed. */
    suspend fun loadMoreConnections(cursor: TimetablePagingCursor): Result<TimetableSearchResult> = runCatching {
        plan(cursor.fromText, cursor.toText, cursor.searchDate.toLong(), cursor.handle == "direct", cursor.listedIds)
    }

    private fun plan(from: String, to: String, epochS: Long, direct: Boolean, listed: List<String>, arriveBy: Boolean = false): TimetableSearchResult {
        val root = HubClient.timetable("plan", mapOf("from" to from, "to" to to, "time" to epochS.toString(), "n" to "5",
            "direct" to if (direct) "1" else "0", "change" to changeMinutes.toString(), "arrive" to if (arriveBy) "1" else "0"))
        val connections = root.getAsJsonArray("connections").map { parseConnection(it.asJsonObject) }
            .filter { it.id !in listed }.distinctBy { it.id }
        // "More" lists later departures: none after the time to be there.
        val last = root.getAsJsonArray("connections").lastOrNull()?.asJsonObject?.get("departure")?.asLong?.takeUnless { arriveBy }
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

    /** 2.17.0: rain in the next 3 hours around [lat]/[lon] (emHUB asks Open-Meteo per town), or null. */
    fun rainy(lat: Double, lon: Double): Boolean? = runCatching {
        HubClient.timetable("weather", mapOf("lat" to "%.3f".format(java.util.Locale.ROOT, lat), "lon" to "%.3f".format(java.util.Locale.ROOT, lon)))
            .get("rainy").asBoolean
    }.getOrNull()

    /** EM-0068: the timetable stop nearest to the rider (within 1.5 km), or null. */
    fun nearestStop(lat: Double, lon: Double): CpStopSuggestion? =
        HubClient.timetable("stops", mapOf("lat" to lat.toString(), "lon" to lon.toString(), "radius" to "1500", "n" to "1"))
            .getAsJsonArray("stops").firstOrNull()?.let { suggestion(it.asJsonObject) }

    /** The timetable station at a sadzv stop, matched by position (names differ between the two). */
    fun intercityStopNear(@Suppress("UNUSED_PARAMETER") name: String, lat: Double, lon: Double): CpStopSuggestion? =
        HubClient.timetable("stops", mapOf("lat" to lat.toString(), "lon" to lon.toString(), "radius" to "300", "n" to "1"))
            .getAsJsonArray("stops").firstOrNull()?.let { suggestion(it.asJsonObject) }

    /**
     * Every timetable bus at [stop], also those ending there: other carriers' buses for the board, and
     * the destination of S.A.D. Zvolen / DPMBB buses that sadzv lists without one (EM-0019).
     */
    fun stopBoard(stop: CpStopSuggestion): List<BoardRow> =
        board(mapOf("stop" to stop.value, "n" to "100", "ending" to "1"))

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
                tripUrl = tripUrl(d.str("tripId"), d.get("seq").asInt, null),
                feed = d.str("feed"),
                ends = d.get("ends")?.asBoolean == true
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
        // "ending": a bus ending its trip here (sadzv lists it too) still has its stops up to here.
        val rows = board(mapOf("lat" to lat.toString(), "lon" to lon.toString(), "radius" to "300", "time" to start.toString(), "n" to "60",
            "ending" to "1"))
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
                serviceDate = Instant.ofEpochMilli(l.get("departure").asLong).atZone(SK_ZONE).toLocalDate().toString(),
                transferWorked = l.getAsJsonObject("transfer")?.get("worked")?.asInt ?: 0,
                transferRuns = l.getAsJsonObject("transfer")?.get("runs")?.asInt ?: 0
            )
        }
        val minutes = ((c.get("arrival").asLong - c.get("departure").asLong) / 60_000L).toInt()
        val walk = c.getAsJsonArray("legs").map { it.asJsonObject }.filter { it.str("type") == "walk" }
            .sumOf { w -> ((w.get("arrival")?.asLong ?: 0L) - (w.get("departure")?.asLong ?: 0L)) / 60_000L }.toInt()
        return TimetableConnection(
            walkMinutes = walk,
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

        /** Least time between two buses (minutes, 0 = any) the planner leaves for a change: the rider's setting. */
        @Volatile var changeMinutes = 1

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
        private fun JsonObject.num(key: String): Double? = get(key)?.takeIf { it.isJsonPrimitive }?.asDouble
    }
}
