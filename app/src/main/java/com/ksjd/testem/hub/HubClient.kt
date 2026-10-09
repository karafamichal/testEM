package com.ksjd.testem.hub

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ksjd.testem.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Delay for one intercity bus, pooled from riders who opted in. */
data class CommunityDelay(
    val delaySeconds: Int,
    val reporters: Int,
    val updatedAtMs: Long,
    val lastStopName: String?,
    /** "gps" when fresh reports come from riders' phones on the bus, else "riders" (taps). */
    val source: String = "riders"
) {
    val isFreshGps: Boolean get() = source == "gps" && System.currentTimeMillis() - updatedAtMs < 3 * 60_000L
}

/** How late this trip usually is: its last [runs] on the same kind of day ([dayType]). */
data class TripHistory(
    val delaySeconds: Int,
    val runs: Int,
    /** Runs at least 2 min late. */
    val lateRuns: Int,
    /** "workday", "saturday" or "sunday". */
    val dayType: String
)

data class BugReport(
    val category: String,
    val title: String,
    val description: String,
    val name: String,
    val email: String,
    val device: String,
    val logs: String,
    /** "bug" or "feature" (a suggestion; emhub lists them separately). */
    val kind: String = "bug",
    /** Base64 JPEGs, at most 3 (emhub rejects anything else). */
    val images: List<String> = emptyList(),
    /** "android", or "wearos" for a report started on the watch (its logs came through the phone). */
    val platform: String = "android",
    val appVersion: String = BuildConfig.VERSION_NAME
)

/** emhub: timetables (planner, other carriers, stop lists), bug reports and community bus delays. */
object HubClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS) // a report with photos on slow mobile data
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()
    /** Share sockets stay open for a whole ride: no read timeout, our own pings keep the connection alive. */
    private val wsClient by lazy { client.newBuilder().readTimeout(0, TimeUnit.SECONDS).pingInterval(25, TimeUnit.SECONDS).build() }

    val isConfigured: Boolean get() = BuildConfig.HUB_APP_KEY.isNotBlank()

    /** Returns the bug code, e.g. "EM-0042". */
    suspend fun submitBug(report: BugReport): String = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("kind", report.kind)
            addProperty("category", report.category)
            addProperty("title", report.title)
            addProperty("description", report.description)
            addProperty("name", report.name)
            addProperty("email", report.email)
            addProperty("platform", report.platform)
            addProperty("appVersion", report.appVersion)
            addProperty("device", report.device)
            addProperty("logs", report.logs)
            addProperty("consent", true)
            add("images", JsonArray().apply { report.images.forEach(::add) })
        }
        post("bugs", body).get("code").asString
    }

    suspend fun reportPosition(
        tripKey: String,
        line: String,
        stopIndex: Int,
        stopName: String,
        scheduledMs: Long,
        reporter: String,
        kind: String = "position"
    ): CommunityDelay? = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("tripKey", tripKey)
            addProperty("line", line)
            addProperty("stopIndex", stopIndex)
            addProperty("stopName", stopName)
            addProperty("scheduledMs", scheduledMs)
            addProperty("reporter", reporter)
            addProperty("platform", "android")
            // "arrival": the bus reached the rider's stop (early counts as on time, it waits);
            // "position": a tap; "gps": the rider's phone on the bus (only the stop is sent).
            addProperty("kind", kind)
        }
        parseDelay(post("community/reports", body))
    }

    /** Live pooled delay and the trip's history, either may be null. */
    suspend fun communityDelay(tripKey: String): Pair<CommunityDelay?, TripHistory?> = withContext(Dispatchers.IO) {
        val url = "${BuildConfig.HUB_URL}/api/v1/community/delay".toHttpUrl().newBuilder()
            .addQueryParameter("trip", tripKey).build()
        val root = execute(Request.Builder().url(url).get())
        val h = root.get("history")?.takeIf { it.isJsonObject }?.asJsonObject
        parseDelay(root) to h?.let {
            TripHistory(it.get("delaySeconds").asInt, it.get("runs").asInt, it.get("lateRuns").asInt, it.get("dayType").asString)
        }
    }

    /** GET /api/v1/timetable/[path] (stops, departures, trip, plan); blocking, call off the main thread. */
    fun timetable(path: String, params: Map<String, String>): JsonObject {
        val url = "${BuildConfig.HUB_URL}/api/v1/timetable/$path".toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        return execute(Request.Builder().url(url).get())
    }

    // ------------------------------------------------------------ one-time share codes (hub: Shares)

    data class ShareCode(val id: String, val code: String, val token: String, val expiresAtMs: Long)
    data class ShareState(val status: String, val name: String, val position: SharedPosition? = null)
    /** Where the other side of a share last was (epoch ms). */
    data class SharedPosition(val lat: Double, val lon: Double, val accuracy: Int, val atMs: Long)

    private fun position(json: JsonObject): SharedPosition? = json.get("position")?.takeIf { it.isJsonObject }?.asJsonObject?.let {
        SharedPosition(it.get("lat").asDouble, it.get("lon").asDouble, it.get("accuracy")?.asInt ?: 0, it.get("at").asLong * 1000)
    }

    private fun positionBody(token: String, lat: Double, lon: Double, accuracy: Int) = JsonObject().apply {
        addProperty("token", token); addProperty("lat", lat); addProperty("lon", lon); addProperty("accuracy", accuracy)
    }

    /** The sharer's position to the one it was shared with; returns theirs, if they send it. */
    suspend fun sendSharePosition(id: String, token: String, lat: Double, lon: Double, accuracy: Int): SharedPosition? = withContext(Dispatchers.IO) {
        position(post("shares/$id/position", positionBody(token, lat, lon, accuracy)))
    }

    /** The other way: the one who entered the code sends theirs (their choice); returns the sharer's. */
    suspend fun sendClaimPosition(claimId: String, token: String, lat: Double, lon: Double, accuracy: Int): SharedPosition? = withContext(Dispatchers.IO) {
        position(post("shares/claim/$claimId/position", positionBody(token, lat, lon, accuracy)))
    }
    data class ShareClaim(val claimId: String, val token: String, val label: String)

    /** A 6-digit code for [trip] (ShareTrip's payload); [label] is what the other side sees before accepting is answered. */
    suspend fun createShare(trip: String, label: String): ShareCode = withContext(Dispatchers.IO) {
        val json = post("shares", JsonObject().apply { addProperty("trip", trip); addProperty("label", label) })
        ShareCode(json.get("id").asString, json.get("code").asString, json.get("token").asString, json.get("expiresAt").asLong * 1000)
    }

    /** The sharer asks: "waiting", "asked" (someone entered the code: [ShareState.name]), "accepted", "declined", "expired", "cancelled". */
    suspend fun shareState(id: String, token: String): ShareState = withContext(Dispatchers.IO) {
        val url = "${BuildConfig.HUB_URL}/api/v1/shares/$id".toHttpUrl().newBuilder().addQueryParameter("token", token).build()
        val json = execute(Request.Builder().url(url).get())
        ShareState(json.get("status").asString, json.get("name")?.asString.orEmpty(), position(json))
    }

    /** [action]: "accept", "decline" or "cancel". */
    suspend fun answerShare(id: String, token: String, action: String) = withContext(Dispatchers.IO) {
        post("shares/$id/$action", JsonObject().apply { addProperty("token", token) })
    }

    suspend fun claimShare(code: String, name: String): ShareClaim = withContext(Dispatchers.IO) {
        val json = post("shares/claim", JsonObject().apply { addProperty("code", code); addProperty("name", name) })
        ShareClaim(json.get("claimId").asString, json.get("token").asString, json.get("label")?.asString.orEmpty())
    }

    /** The one who entered the code asks: the status, and the bus once the sharer accepted. */
    suspend fun claimState(claimId: String, token: String): Pair<String, String?> = claimDetails(claimId, token).let { it.first to it.second }

    /** Status, the bus (once accepted) and the sharer's last position. */
    suspend fun claimDetails(claimId: String, token: String): Triple<String, String?, SharedPosition?> = withContext(Dispatchers.IO) {
        val url = "${BuildConfig.HUB_URL}/api/v1/shares/claim/$claimId".toHttpUrl().newBuilder().addQueryParameter("token", token).build()
        val json = execute(Request.Builder().url(url).get())
        Triple(json.get("status").asString, json.get("trip")?.takeIf { it.isJsonPrimitive }?.asString, position(json))
    }

    /**
     * One side of a share over a WebSocket (emhub's /shares/live): status news and the other side's positions come as
     * they happen; [send] our position. Reconnects by itself (2 s, 4 s … up to 30 s) until [close].
     */
    class ShareSocket(
        private val side: String,
        private val id: String,
        private val token: String,
        private val onStatus: (status: String, name: String, trip: String?) -> Unit,
        private val onPosition: (SharedPosition) -> Unit
    ) {
        @Volatile private var socket: okhttp3.WebSocket? = null
        @Volatile private var closed = false
        private var backoffMs = 2_000L

        fun open(): ShareSocket {
            val base = BuildConfig.HUB_URL.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
            val url = "$base/api/v1/shares/live?side=$side&id=$id&token=$token"
            socket = wsClient.newWebSocket(Request.Builder().url(url).build(), object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) { backoffMs = 2_000L }
                override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                    val json = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return
                    when (json.get("type")?.asString) {
                        "status" -> onStatus(json.get("status").asString, json.get("name")?.asString.orEmpty(),
                            json.get("trip")?.takeIf { it.isJsonPrimitive }?.asString)
                        "position" -> position(json)?.let(onPosition)
                    }
                }
                override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) = retry()
                override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) = retry()
            })
            return this
        }

        private fun retry() {
            if (closed) return
            val wait = backoffMs
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            java.util.Timer().schedule(object : java.util.TimerTask() { override fun run() { if (!closed) open() } }, wait)
        }

        fun send(lat: Double, lon: Double, accuracy: Int) {
            socket?.send(JsonObject().apply {
                addProperty("type", "position"); addProperty("lat", lat); addProperty("lon", lon); addProperty("accuracy", accuracy)
            }.toString())
        }

        fun close() {
            closed = true
            socket?.close(1000, null)
        }
    }

    private fun parseDelay(root: JsonObject): CommunityDelay? {
        val d = root.get("delay")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return CommunityDelay(
            delaySeconds = d.get("delaySeconds").asInt,
            reporters = d.get("reporters").asInt,
            updatedAtMs = d.get("updatedAt").asLong,
            lastStopName = d.get("lastStopName")?.takeIf { it.isJsonPrimitive }?.asString,
            source = d.get("source")?.takeIf { it.isJsonPrimitive }?.asString ?: "riders"
        )
    }

    private fun post(path: String, body: JsonObject): JsonObject =
        execute(Request.Builder().url("${BuildConfig.HUB_URL}/api/v1/$path").post(body.toString().toRequestBody(JSON)))

    private fun execute(builder: Request.Builder): JsonObject {
        val request = builder.header("X-App-Key", BuildConfig.HUB_APP_KEY).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: JsonObject()
            if (!response.isSuccessful) {
                throw IOException(json.get("error")?.asString ?: "HTTP ${response.code}")
            }
            return json
        }
    }
}
