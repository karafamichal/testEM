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
                delaySeconds = a.longAt(7)?.toInt()
            )
        }
    }

    suspend fun getTrip(ref: TripRef): TripDetail = withContext(Dispatchers.IO) {
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

        val vehicleDelay = runCatching {
            getVehicles().firstOrNull { it.lineId == ref.lineId && it.tripNumber == ref.tripNumber }
        }.getOrNull()?.delaySeconds
        val lastPassed = stops.lastOrNull { it.isPassed }
        val passedDelay = lastPassed?.let { ((it.actualMs!! - it.scheduledMs) / 1000L).toInt() }
        TripDetail(
            line = ref.line,
            destination = ref.destination.ifBlank { stops.lastOrNull()?.name.orEmpty() },
            stops = stops,
            delaySeconds = vehicleDelay ?: passedDelay
        )
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
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val STOPS_CACHE_MS = 24L * 60 * 60 * 1000

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
