package com.ksjd.testem.live

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.text.Normalizer
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Public real-time data from sadzv.qrbus.me (no login required): stops, departure
 * boards, vehicle positions and trip stop lists. These are the same endpoints the
 * operator's own map page calls.
 */
class LiveRepository(context: Context) {
    private val prefs = com.ksjd.testem.CredentialsManager(context)
    private val baseUrl = "https://sadzv.qrbus.me/index"
    private val cacheFile = File(context.cacheDir, "live_platforms.json")
    /** Operator times are Slovak local time, whatever zone the phone is set to. */
    private val zone: ZoneId = ZoneId.of("Europe/Bratislava")
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val stopsMutex = Mutex()
    private var stops: List<LiveStop>? = null

    suspend fun getStops(): List<LiveStop> = stopsMutex.withLock {
        stops?.let { return it }
        val raw = withContext(Dispatchers.IO) {
            val fresh = cacheFile.exists() &&
                System.currentTimeMillis() - cacheFile.lastModified() < STOPS_CACHE_MS
            if (fresh) {
                cacheFile.readText()
            } else {
                runCatching { get("getAllPlatforms").also { cacheFile.writeText(it) } }
                    .getOrElse { error ->
                        // Stale cache is better than nothing when offline.
                        if (cacheFile.exists()) cacheFile.readText() else throw error
                    }
            }
        }
        parseStops(JsonParser.parseString(raw)).also { stops = it }
    }

    suspend fun searchStops(query: String): List<LiveStop> {
        val key = normalize(query)
        if (key.isBlank()) return emptyList()
        return getStops()
            .filter { it.searchKey.contains(key) }
            .sortedWith(compareBy({ !it.searchKey.startsWith(key) }, { it.name }))
            .take(40)
    }

    suspend fun nearbyStops(lat: Double, lon: Double, limit: Int = 8): List<Pair<LiveStop, Int>> {
        return getStops()
            .map { it to distanceMeters(lat, lon, it.lat, it.lon) }
            .sortedBy { it.second }
            .take(limit)
    }

    suspend fun stopById(id: Int): LiveStop? = getStops().firstOrNull { it.id == id }

    /** Best match for a stop name coming from another source (e.g. the timetable). */
    suspend fun matchStopByName(name: String): LiveStop? {
        val key = normalize(name)
        if (key.isBlank()) return null
        val all = getStops()
        all.firstOrNull { it.searchKey == key }?.let { return it }
        // Timetable names often start with the town ("Banska Bystrica, Namestie slobody").
        return all
            .filter { it.searchKey.length >= 3 && key.endsWith(it.searchKey) }
            .maxByOrNull { it.searchKey.length }
            ?: all.filter { key.contains(it.searchKey) && it.searchKey.length >= 5 }
                .maxByOrNull { it.searchKey.length }
    }

    suspend fun getDepartures(stop: LiveStop): List<LiveDeparture> = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            add("platformIds", JsonArray().also { arr -> stop.platforms.forEach { arr.add(it.id) } })
            addProperty("organizationSystemEntityId", 0)
        }
        val now = System.currentTimeMillis()
        val root = JsonParser.parseString(post("getDeparturesOnPlatformWeb", body)).asJsonObject
        val departures = root.getAsJsonArray("departures") ?: JsonArray()
        departures.mapNotNull { el ->
            val o = el.asObj() ?: return@mapNotNull null
            val plan = o.int("plan") ?: return@mapNotNull null
            val real = o.int("real") ?: 0
            val realtime = (o.int("realTimePaired") ?: 0) == 1
            LiveDeparture(
                line = o.str("lineNumberText").trimEnd('_'),
                lineId = o.long("lineId") ?: 0L,
                destination = o.str("destinationName"),
                platformNumber = o.str("platformNumber"),
                routeNumber = o.str("routeNumber"),
                tripNumber = o.int("tripNumber") ?: 0,
                plannedSecondOfDay = plan,
                secondsUntil = real,
                isRealtime = realtime,
                isCancelled = o.bool("cancelled"),
                delaySeconds = if (realtime) computeDelay(now, real, plan) else null,
                fetchedAtMs = now
            )
        }.sortedBy { it.secondsUntil }.distinctBy { it.key } // sadzv can list one bus twice; the board keys rows by it
    }

    /**
     * The whole board for a stop: sadzv's live departures plus other carriers' buses from
     * emhub's timetable (SAD Lučenec, Arriva, Slovak Lines, …) that sadzv doesn't know.
     * Fails only when both sources fail.
     */
    suspend fun getBoard(stop: LiveStop): List<LiveDeparture> {
        val live = runCatching { getDepartures(stop) }
        val other = runCatching { otherCarriers(stop, live.getOrDefault(emptyList())) }
        if (live.isFailure && other.isFailure) throw live.exceptionOrNull()!!
        return (live.getOrDefault(emptyList()) + other.getOrDefault(emptyList())).sortedBy { it.secondsUntil }.distinctBy { it.key }
    }

    /** Timetable station per sadzv stop (null = none nearby), and its board with when it was fetched. */
    private val cpStops = mutableMapOf<Int, com.ksjd.testem.CpStopSuggestion?>()
    private val cpBoards = mutableMapOf<Int, Pair<Long, List<com.ksjd.testem.TimetableService.BoardRow>>>()

    private suspend fun otherCarriers(stop: LiveStop, live: List<LiveDeparture>): List<LiveDeparture> = withContext(Dispatchers.IO) {
        val cpStop = if (synchronized(cpStops) { stop.id in cpStops }) synchronized(cpStops) { cpStops[stop.id] }
        else timetable.intercityStopNear(stop.name, stop.lat, stop.lon).also { synchronized(cpStops) { cpStops[stop.id] = it } }
        cpStop ?: return@withContext emptyList()
        val now = System.currentTimeMillis()
        // The timetable doesn't change: every 2 min is plenty (the board refreshes every 15 s).
        val rows = synchronized(cpBoards) { cpBoards[stop.id] }?.takeIf { now - it.first < CP_BOARD_MS }?.second
            ?: timetable.intercityBoard(cpStop).also { synchronized(cpBoards) { cpBoards[stop.id] = now to it } }
        rows.filter { row -> row.atMs > now - 60_000L && live.none { sameBus(it, row) } }.map { row ->
            LiveDeparture(
                line = row.line,
                lineId = 0L,
                destination = row.destination.split(",").map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", "),
                platformNumber = row.platform,
                routeNumber = "",
                tripNumber = 0,
                plannedSecondOfDay = row.minutes * 60,
                secondsUntil = ((row.atMs - now) / 1000L).toInt(),
                isRealtime = false,
                isCancelled = false,
                delaySeconds = null,
                fetchedAtMs = now,
                fromTimetable = true,
                operator = row.operator,
                scheduleUrl = row.tripUrl,
                serviceDate = java.time.Instant.ofEpochMilli(row.atMs).atZone(zone).toLocalDate().toString()
            )
        }
    }

    /** sadzv and the timetable write lines differently ("606106" vs "60106"): same minute and matching line. */
    private fun sameBus(d: LiveDeparture, row: com.ksjd.testem.TimetableService.BoardRow): Boolean {
        val gap = abs(d.plannedSecondOfDay / 60 - row.minutes).let { minOf(it, 1440 - it) }
        return gap <= 1 && com.ksjd.testem.TimetableService.sameLine(d.line, row.line)
    }

    suspend fun getVehicles(): List<LiveVehicle> = withContext(Dispatchers.IO) {
        val root = JsonParser.parseString(get("getAllRtdVehicles")).asJsonObject
        val vehicles = root.getAsJsonArray("vehicles") ?: JsonArray()
        vehicles.mapNotNull { el ->
            // Compact array format: [lat, lon, line, lineId, route, trip, lastComm, delay, ...]
            val a = el.takeIf { it.isJsonArray }?.asJsonArray ?: return@mapNotNull null
            if (a.size() < 8) return@mapNotNull null
            val lineId = a.longAt(3) ?: 0L
            if (lineId == 0L) return@mapNotNull null
            LiveVehicle(
                lat = (a.longAt(0) ?: 0L) / 1e5,
                lon = (a.longAt(1) ?: 0L) / 1e5,
                line = a.strAt(2),
                lineId = lineId,
                routeNumber = a.strAt(4),
                tripNumber = a.longAt(5)?.toInt() ?: 0,
                delaySeconds = a.longAt(7)?.toInt(),
                reportedAtMs = localTimestamp(a.strAt(6))
            )
        }
    }

    private val timetable = com.ksjd.testem.TimetableService()

    /**
     * The trip with the best known position. Sources, best first: a rider's phone on the
     * bus (community "gps"), the bus's own GPS (fresh and on the route), riders' taps,
     * sadzv's delay, the timetable.
     */
    suspend fun getTrip(ref: TripRef): TripDetail = withContext(Dispatchers.IO) {
        val base = when {
            ref.isScheduleOnly -> timetableTrip(ref)
            // A timetable bus without its trip link: sadzv knows nothing about it.
            ref.lineId == 0L -> timetableFallback(ref)?.let { t ->
                t.copy(line = ref.line, destination = ref.destination.ifBlank { t.destination }, alightOrder = null, fromTimetable = true)
            } ?: TripDetail(ref.line, ref.destination, emptyList(), null)
            else -> liveTrip(ref)
        }
        positioned(ref, base.copy(communityKey = communityKey(ref, base)))
    }

    private suspend fun positioned(ref: TripRef, trip: TripDetail): TripDetail {
        var detail = trip
        val bus = detail.busPosition
        if (bus != null && detail.stops.size >= 2) {
            detail = withCoordinates(ref, detail)
            val expected = TripProgress.from(detail, TrackedTrip(ref, emptyList()), System.currentTimeMillis()).position
            RoutePosition.snap(bus.first, bus.second, detail.stops.map { s -> s.lat?.let { it to s.lon!! } }, RoutePosition.BUS_GPS_MAX_M, expected)
                ?.let { (index, _) ->
                    val delay = RoutePosition.delayAt(index, detail.stops.map { it.scheduledMs }, System.currentTimeMillis())
                    detail = detail.copy(delaySeconds = delay, positionSource = PositionSource.BusGps)
                }
        }
        val key = detail.communityKey
        if (key == null || !prefs.getCommunityEnabled() || !com.ksjd.testem.hub.HubClient.isConfigured) return detail
        val (community, history) = runCatching { com.ksjd.testem.hub.HubClient.communityDelay(key) }.getOrNull()
            ?: return detail
        detail = detail.copy(history = history)
        if (community == null) {
            val shift = history != null && prefs.getPredictShift() && detail.positionSource == PositionSource.Timetable
            return if (shift) detail.copy(delaySeconds = history!!.delaySeconds, positionSource = PositionSource.History) else detail
        }
        return when {
            community.isFreshGps -> detail.copy(delaySeconds = community.delaySeconds, community = community, positionSource = PositionSource.RiderGps)
            // Taps correct the timetable or sadzv's delay, but not a live GPS position.
            detail.positionSource.isLive -> detail.copy(community = community)
            else -> detail.copy(delaySeconds = community.delaySeconds, community = community, positionSource = PositionSource.Riders)
        }
    }

    /**
     * Same bus, same key on every phone and on the web. Buses from sadzv's board: line and
     * trip ids and the date; timetable trips from the planner: line, first stop and time.
     */
    private fun communityKey(ref: TripRef, detail: TripDetail): String? {
        val first = detail.stops.firstOrNull() ?: return null
        if (!ref.isScheduleOnly && ref.lineId != 0L) {
            val day = java.time.Instant.ofEpochMilli(first.scheduledMs).atZone(zone).toLocalDate()
            return "sadzv|${ref.lineId}|${ref.tripNumber}|$day|${ref.line}"
        }
        return "${detail.line}|${first.name}|${first.scheduledMs}"
    }

    /** Stop coordinates: sadzv platforms for sadzv trips; timetable trips come with theirs from emhub. */
    suspend fun withCoordinates(ref: TripRef, detail: TripDetail): TripDetail = withContext(Dispatchers.IO) {
        if (detail.stops.all { it.lat != null } || ref.isScheduleOnly || detail.fromTimetable) return@withContext detail
        val platforms = runCatching { getStops() }.getOrDefault(emptyList()).flatMap { it.platforms }.associateBy { it.id }
        detail.copy(stops = detail.stops.map { stop -> platforms[stop.platformId]?.let { stop.copy(lat = it.lat, lon = it.lon) } ?: stop })
    }

    private suspend fun liveTrip(ref: TripRef): TripDetail {
        val body = JsonObject().apply {
            addProperty("lineId", ref.lineId)
            addProperty("lineNumber", ref.line)
            addProperty("tripNumber", ref.tripNumber)
            addProperty("routeNumber", ref.routeNumber)
            addProperty("organizationSystemEntityId", 0)
        }
        val root = JsonParser.parseString(post("getPlatformsDataWithHistory", body)).asJsonObject
        val platforms = root.getAsJsonArray("platforms") ?: JsonArray()
        val stops = platforms.mapNotNull { el ->
            val o = el.asObj() ?: return@mapNotNull null
            val actual = (o.long("realDeparture") ?: -1L).takeIf { it > 0 }
                ?: (o.long("realArrival") ?: -1L).takeIf { it > 0 }
            TripStop(
                name = o.str("stopName"),
                platformId = o.int("platformId") ?: 0,
                order = o.int("stopOrder") ?: 0,
                scheduledMs = wallClockToEpochMs(o.long("departureTime") ?: 0L),
                actualMs = actual?.times(1000L)
            )
        }.sortedBy { it.order }

        val vehicle = runCatching {
            getVehicles().firstOrNull { it.lineId == ref.lineId && it.tripNumber == ref.tripNumber }
        }.getOrNull()
        val vehicleDelay = vehicle?.delaySeconds
        // sadzv keeps some positions for months: only a recent one says where the bus is.
        val busPosition = vehicle?.takeIf {
            it.lat != 0.0 && System.currentTimeMillis() - it.reportedAtMs in -60_000L..RoutePosition.FRESH_MS
        }?.let { it.lat to it.lon }
        val lastPassed = stops.lastOrNull { it.isPassed }
        val passedDelay = lastPassed?.let { ((it.actualMs!! - it.scheduledMs) / 1000L).toInt() }
        if (stops.isEmpty()) {
            // sadzv often has no stop list (its own site too). Quietly use the timetable
            // for this trip, keeping sadzv's live delay when the bus reports one.
            timetableFallback(ref)?.let { timetable ->
                return timetable.copy(
                    line = ref.line,
                    destination = ref.destination.ifBlank { timetable.destination },
                    delaySeconds = vehicleDelay,
                    alightOrder = null, // the user hasn't chosen where to get off
                    fromTimetable = true,
                    positionSource = if (vehicleDelay != null) PositionSource.BusDelay else PositionSource.Timetable,
                    busPosition = busPosition
                )
            }
        }
        return TripDetail(
            line = ref.line,
            destination = ref.destination.ifBlank { stops.lastOrNull()?.name.orEmpty() },
            stops = stops,
            delaySeconds = vehicleDelay ?: passedDelay,
            positionSource = if (vehicleDelay ?: passedDelay != null) PositionSource.BusDelay else PositionSource.Timetable,
            busPosition = busPosition
        )
    }

    /** "2026-09-24T20:31:40Z" from sadzv is Slovak local time despite the Z. */
    private fun localTimestamp(text: String): Long = runCatching {
        java.time.LocalDateTime.parse(text.removeSuffix("Z").take(19)).atZone(zone).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    /** Timetable is fixed, so one download per trip is enough (the tracker asks every 15 s). */
    private val scheduleCache = mutableMapOf<String, TripDetail>()

    private fun timetableTrip(ref: TripRef): TripDetail =
        synchronized(scheduleCache) { scheduleCache[ref.scheduleUrl] } ?: run {
            // Links saved before 2.7 point at cp.sk, which the app no longer uses: such a trip has no stops.
            val (id, from, to) = com.ksjd.testem.TimetableService.parseTripUrl(ref.scheduleUrl)
                ?: return TripDetail(ref.line, ref.destination, emptyList(), null)
            parseHubTrip(com.ksjd.testem.hub.HubClient.timetable("trip", mapOf("id" to id)), ref, from, to).also { parsed ->
                if (parsed.stops.isNotEmpty()) synchronized(scheduleCache) { scheduleCache[ref.scheduleUrl] = parsed }
            }
        }

    /** Timetable lookups for live trips without a stop list: result (or miss) and when it was made. */
    private val fallbackCache = mutableMapOf<String, Pair<TripDetail?, Long>>()

    /**
     * The same trip in the timetable: the same line leaving near the boarding stop at (almost)
     * the same time. Misses are retried after 10 min.
     */
    private suspend fun timetableFallback(ref: TripRef): TripDetail? {
        if (ref.fromStopId == 0 || ref.plannedSecondOfDay < 0) return null
        val key = "${ref.lineId}-${ref.line}-${ref.tripNumber}-${ref.plannedSecondOfDay}-${LocalDate.now(zone)}"
        synchronized(fallbackCache) { fallbackCache[key] }?.let { (detail, at) ->
            if (detail != null || System.currentTimeMillis() - at < 10 * 60_000L) return detail
        }
        val stop = stopById(ref.fromStopId) ?: return null
        val segment = runCatching {
            timetable.findTrip(stop.lat, stop.lon, ref.line, ref.plannedSecondOfDay / 60 % (24 * 60))
        }.getOrNull()
        val detail = segment?.let { s ->
            runCatching {
                timetableTrip(TripRef(ref.line, 0, "", 0, ref.destination, scheduleUrl = s.routeUrl, serviceDate = s.serviceDate))
            }.getOrNull()?.takeIf { it.stops.isNotEmpty() }
        }
        synchronized(fallbackCache) { fallbackCache[key] = detail to System.currentTimeMillis() }
        return detail
    }


    /** Stop coordinates by platform id (live trips) or by name (timetable trips), for the catch estimate. */
    suspend fun stopLocation(platformIds: List<Int>, name: String): Pair<Double, Double>? {
        val stops = runCatching { getStops() }.getOrNull() ?: return null
        stops.asSequence().flatMap { it.platforms.asSequence() }.firstOrNull { it.id in platformIds }
            ?.let { return it.lat to it.lon }
        return matchStopByName(name)?.let { it.lat to it.lon }
    }

    private fun computeDelay(nowMs: Long, secondsUntil: Int, plannedSecondOfDay: Int): Int {
        val expectedMs = nowMs + secondsUntil * 1000L
        val today = LocalDate.now(zone)
        // The planned time may belong to yesterday/tomorrow around midnight; pick the closest.
        val candidates = listOf(today.minusDays(1), today, today.plusDays(1)).map { day ->
            day.atStartOfDay(zone).toInstant().toEpochMilli() + plannedSecondOfDay * 1000L
        }
        val plannedMs = candidates.minByOrNull { abs(it - expectedMs) } ?: return 0
        return ((expectedMs - plannedMs) / 1000L).toInt()
    }

    /** Trip times are local wall-clock values encoded as if they were UTC. */
    private fun wallClockToEpochMs(wallSeconds: Long): Long {
        if (wallSeconds <= 0L) return 0L
        val local = java.time.LocalDateTime.ofEpochSecond(wallSeconds, 0, ZoneOffset.UTC)
        return local.atZone(zone).toInstant().toEpochMilli()
    }

    private fun get(path: String): String {
        val request = Request.Builder()
            .url("$baseUrl/$path")
            .header("X-Requested-With", "XMLHttpRequest")
            .get()
            .build()
        return execute(request)
    }

    private fun post(path: String, body: JsonObject): String {
        val request = Request.Builder()
            .url("$baseUrl/$path")
            .header("X-Requested-With", "XMLHttpRequest")
            .post(body.toString().toRequestBody(JSON))
            .build()
        return execute(request)
    }

    private fun execute(request: Request): String {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private fun parseStops(root: JsonElement): List<LiveStop> {
        val array = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject -> root.asJsonObject.getAsJsonArray("platforms") ?: JsonArray()
            else -> JsonArray()
        }
        val platforms = array.mapNotNull { el ->
            when {
                // Current compact format: [id, name, lat, lon, platformNumber, stopId]
                el.isJsonArray -> {
                    val a = el.asJsonArray
                    if (a.size() < 6) return@mapNotNull null
                    LivePlatform(
                        id = a.longAt(0)?.toInt() ?: return@mapNotNull null,
                        name = a.strAt(1).trim(),
                        number = a.strAt(4),
                        lat = (a.longAt(2) ?: 0L) / 1e5,
                        lon = (a.longAt(3) ?: 0L) / 1e5,
                        stopId = a.longAt(5)?.toInt() ?: 0
                    )
                }
                // Older object format, kept in case the API switches back.
                el.isJsonObject -> {
                    val o = el.asJsonObject
                    LivePlatform(
                        id = o.int("id") ?: return@mapNotNull null,
                        name = o.str("name").trim(),
                        number = o.str("platf"),
                        lat = (o.long("lat") ?: 0L) / 1e5,
                        lon = (o.long("long") ?: 0L) / 1e5,
                        stopId = o.int("stopId") ?: 0
                    )
                }
                else -> null
            }
        }.filter { it.name.isNotBlank() && it.lat != 0.0 }

        return platforms.groupBy { it.stopId }.map { (stopId, group) ->
            val name = group.groupingBy { it.name }.eachCount().maxByOrNull { it.value }!!.key
            LiveStop(
                id = stopId,
                name = name,
                platforms = group.sortedBy { it.number },
                lat = group.map { it.lat }.average(),
                lon = group.map { it.lon }.average(),
                searchKey = normalize(name)
            )
        }.sortedBy { it.name }
    }

    companion object {
        /**
         * A trip from emhub's timetable. [from] and [to] are the stop indexes where the user gets
         * on and off (null: not chosen); where they get off, the arrival time counts.
         */
        fun parseHubTrip(json: JsonObject, ref: TripRef, from: Int?, to: Int?): TripDetail {
            val stops = json.getAsJsonArray("stops")?.mapIndexed { i, el ->
                val o = el.asJsonObject
                TripStop(
                    name = o.str("name"),
                    platformId = i + 1,
                    order = i + 1,
                    scheduledMs = (if (i == to) o.long("arrival") else o.long("departure")) ?: 0L,
                    actualMs = null,
                    platform = o.str("platform"),
                    lat = o.get("lat")?.takeIf { it.isJsonPrimitive }?.asDouble,
                    lon = o.get("lon")?.takeIf { it.isJsonPrimitive }?.asDouble
                )
            }.orEmpty()
            return TripDetail(
                line = ref.line,
                destination = json.str("headsign").ifBlank { stops.lastOrNull()?.name.orEmpty() },
                stops = stops,
                delaySeconds = null,
                boardingOrder = (from ?: 0) + 1,
                alightOrder = to?.plus(1)
            )
        }

        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val STOPS_CACHE_MS = 24L * 60 * 60 * 1000
        private const val CP_BOARD_MS = 2 * 60_000L

        fun normalize(value: String): String {
            val stripped = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
            return stripped.lowercase()
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()
        }

        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
            // Equirectangular approximation is plenty for "nearby stops".
            val x = Math.toRadians(lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2))
            val y = Math.toRadians(lat2 - lat1)
            return (sqrt(x * x + y * y) * 6_371_000).toInt()
        }
    }
}

private fun JsonElement.asObj(): JsonObject? = if (isJsonObject) asJsonObject else null

private fun JsonObject.str(key: String): String =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()

private fun JsonObject.long(key: String): Long? =
    get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() }

private fun JsonObject.int(key: String): Int? = long(key)?.toInt()

private fun JsonObject.bool(key: String): Boolean =
    get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() } ?: false

private fun JsonArray.strAt(i: Int): String =
    get(i)?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()

private fun JsonArray.longAt(i: Int): Long? =
    get(i)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() }
