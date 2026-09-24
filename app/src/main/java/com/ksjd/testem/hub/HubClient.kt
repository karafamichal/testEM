package com.ksjd.testem.hub

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

data class BugReport(
    val category: String,
    val title: String,
    val description: String,
    val name: String,
    val email: String,
    val device: String,
    val logs: String
)

/** emhub: bug reports and community bus delays. Only called for features the user turned on. */
object HubClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    val isConfigured: Boolean get() = BuildConfig.HUB_APP_KEY.isNotBlank()

    /** Returns the bug code, e.g. "EM-0042". */
    suspend fun submitBug(report: BugReport): String = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("category", report.category)
            addProperty("title", report.title)
            addProperty("description", report.description)
            addProperty("name", report.name)
            addProperty("email", report.email)
            addProperty("platform", "android")
            addProperty("appVersion", BuildConfig.VERSION_NAME)
            addProperty("device", report.device)
            addProperty("logs", report.logs)
            addProperty("consent", true)
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

    suspend fun communityDelay(tripKey: String): CommunityDelay? = withContext(Dispatchers.IO) {
        val url = "${BuildConfig.HUB_URL}/api/v1/community/delay".toHttpUrl().newBuilder()
            .addQueryParameter("trip", tripKey).build()
        parseDelay(execute(Request.Builder().url(url).get()))
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
