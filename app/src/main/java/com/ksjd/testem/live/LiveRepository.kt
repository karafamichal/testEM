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

    /** Best match for a stop name coming from another source (e.g. cp.sk). */
    suspend fun matchStopByName(name: String): LiveStop? {
        val key = normalize(name)
        if (key.isBlank()) return null
        val all = getStops()
        all.firstOrNull { it.searchKey == key }?.let { return it }
        // cp.sk often prefixes the town ("Banska Bystrica,,Namestie slobody").
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
        }.sortedBy { it.secondsUntil }
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

    private val cp = com.ksjd.testem.CpTimetableService()

    /**
     * The trip with the best known position. Sources, best first: a rider's phone on the
     * bus (community "gps"), the bus's own GPS (fresh and on the route), riders' taps,
     * sadzv's delay, the timetable.
     */
    suspend fun getTrip(ref: TripRef): TripDetail = withContext(Dispatchers.IO) {
        val base = if (ref.isScheduleOnly) timetableTrip(ref) else liveTrip(ref)
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
        val community = runCatching { com.ksjd.testem.hub.HubClient.communityDelay(key) }.getOrNull()
            ?: return detail
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

    /** Stop coordinates: sadzv platforms for sadzv trips, cp.sk for timetable stops (cached by name). */
    private val coordinateCache = mutableMapOf<String, Pair<Double, Double>?>()

    suspend fun withCoordinates(ref: TripRef, detail: TripDetail): TripDetail {
        if (detail.stops.all { it.lat != null }) return detail
        val platforms = if (ref.isScheduleOnly || detail.fromTimetable) emptyMap()
        else runCatching { getStops() }.getOrDefault(emptyList()).flatMap { it.platforms }.associateBy { it.id }
        val stops = detail.stops.map { stop ->
            val c = platforms[stop.platformId]?.let { it.lat to it.lon }
                ?: synchronized(coordinateCache) { coordinateCache[stop.name] }
                ?: cp.coordinatesOf(stop.name).also { found -> synchronized(coordinateCache) { coordinateCache[stop.name] = found } }
            stop.copy(lat = c?.first, lon = c?.second)
        }
        return detail.copy(stops = stops)
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
            // sadzv often has no stop list (its own site too). Quietly use cp.sk's timetable
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
            val request = Request.Builder().url(ref.scheduleUrl).header("User-Agent", "Mozilla/5.0").get().build()
            parseScheduledTrip(execute(request), ref, zone).also { parsed ->
                if (parsed.stops.isNotEmpty()) synchronized(scheduleCache) { scheduleCache[ref.scheduleUrl] = parsed }
            }
        }

    /** cp.sk lookups for live trips without a stop list: result (or miss) and when it was made. */
    private val fallbackCache = mutableMapOf<String, Pair<TripDetail?, Long>>()

    /**
     * The same trip on cp.sk: a direct connection from the boarding stop to the bus's
     * destination at its departure time, with the same line. Misses are retried after 10 min.
     */
    private suspend fun timetableFallback(ref: TripRef): TripDetail? {
        if (ref.fromStopId == 0 || ref.plannedSecondOfDay < 0) return null
        val key = "${ref.lineId}-${ref.tripNumber}-${ref.plannedSecondOfDay}-${LocalDate.now(zone)}"
        synchronized(fallbackCache) { fallbackCache[key] }?.let { (detail, at) ->
            if (detail != null || System.currentTimeMillis() - at < 10 * 60_000L) return detail
        }
        val stop = stopById(ref.fromStopId) ?: return null
        val segment = runCatching {
            cp.findTrip(stop.name, stop.lat, stop.lon, ref.line, ref.plannedSecondOfDay / 60 % (24 * 60), cityFor(stop.lat, stop.lon))
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
         * Parses a cp.sk route page ("Dráha spoja"). Stops outside the user's part of the
         * journey are marked inactive; the first active one is where they board, on
         * [TripRef.serviceDate]. Days roll over wherever the clock goes backwards.
         */
        fun parseScheduledTrip(html: String, ref: TripRef, zone: ZoneId): TripDetail {
            data class Row(val name: String, val departure: Int?, val arrival: Int?, val active: Boolean, val platform: String) {
                val minutes: Int get() = departure ?: arrival!!
            }
            val rows = org.jsoup.Jsoup.parse(html).select("ul.line-itinerary li.item").mapNotNull { li ->
                val name = li.selectFirst("strong.name")?.text().orEmpty()
                    .split(",").map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
                val departure = clockMinutes(li.selectFirst("span.departure")?.ownText().orEmpty())
                val arrival = clockMinutes(li.selectFirst("span.arrival")?.ownText().orEmpty())
                // Platform (nástupište) or track (koľaj), like on the connection list.
                val platform = li.select("span[title]").firstOrNull {
                    val title = it.attr("title").lowercase()
                    title.startsWith("nást") || title.startsWith("koľ") || title.startsWith("kol")
                }?.text()?.trim().orEmpty()
                if (name.isBlank() || (departure == null && arrival == null)) null
                else Row(name, departure, arrival, !li.hasClass("inactive"), platform)
            }
            if (rows.isEmpty()) return TripDetail(ref.line, ref.destination, emptyList(), null)
            val boarding = rows.indexOfFirst { it.active }.takeIf { it >= 0 } ?: 0
            val alight = rows.indexOfLast { it.active }.takeIf { it > boarding }
            val date = runCatching { LocalDate.parse(ref.serviceDate) }.getOrElse { LocalDate.now(zone) }
            val days = IntArray(rows.size)
            for (i in boarding + 1 until rows.size) {
                days[i] = days[i - 1] + if (rows[i].minutes < rows[i - 1].minutes) 1 else 0
            }
            for (i in boarding - 1 downTo 0) {
                days[i] = days[i + 1] - if (rows[i].minutes > rows[i + 1].minutes) 1 else 0
            }
            val stops = rows.mapIndexed { i, row ->
                // Where the user gets off, the arrival is what matters (long dwell times at bus stations).
                val useArrival = i == alight && row.arrival != null
                val minutes = if (useArrival) row.arrival!! else row.minutes
                val day = days[i] - if (useArrival && row.arrival!! > row.minutes) 1 else 0
                TripStop(
                    name = row.name,
                    platformId = i + 1,
                    order = i + 1,
                    scheduledMs = date.plusDays(day.toLong()).atStartOfDay(zone).toInstant().toEpochMilli() + minutes * 60_000L,
                    actualMs = null,
                    platform = row.platform
                )
            }
            return TripDetail(
                line = ref.line,
                destination = stops.last().name,
                stops = stops,
                delaySeconds = null,
                boardingOrder = stops[boarding].order,
                alightOrder = alight?.let { stops[it].order }
            )
        }

        private fun clockMinutes(time: String): Int? {
            val match = Regex("""(\d{1,2}):(\d{2})""").find(time) ?: return null
            return match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
        }

        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val STOPS_CACHE_MS = 24L * 60 * 60 * 1000

        fun normalize(value: String): String {
            val stripped = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
            return stripped.lowercase()
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()
        }

        /** cp.sk city timetable for a stop: Zvolen or Banská Bystrica city buses, else all of Slovakia. */
        fun cityFor(lat: Double, lon: Double): String =
            listOf("zvolen" to (48.577 to 19.125), "banskabystrica" to (48.736 to 19.146))
                .map { (slug, c) -> slug to distanceMeters(lat, lon, c.first, c.second) }
                .filter { it.second < 12_000 }
                .minByOrNull { it.second }?.first ?: "slovensko"

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
