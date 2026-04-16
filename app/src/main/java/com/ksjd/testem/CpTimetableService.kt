package com.ksjd.testem

import com.google.gson.Gson
import com.google.gson.JsonArray
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class CpTimetableService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson()
) {
    data class SearchRequest(
        val citySlug: String,
        val fromInput: String,
        val toInput: String,
        val timeInput: String,
        val directOnly: Boolean
    )

    suspend fun searchConnections(request: SearchRequest): Result<List<TimetableConnection>> {
        return runCatching {
            val city = request.citySlug.trim().ifBlank { "slovensko" }
            val fromResolved = resolveStop(city, request.fromInput)
                ?: throw IllegalStateException("Unable to resolve departure stop")
            val toResolved = resolveStop(city, request.toInput)
                ?: throw IllegalStateException("Unable to resolve destination stop")

            val form = FormBody.Builder()
                .add("From", fromResolved.selectedText)
                .add("FromHidden", fromResolved.toHiddenFieldValue())
                .add("PositionFromHidden", fromResolved.toPositionFieldValue())
                .add("To", toResolved.selectedText)
                .add("ToHidden", toResolved.toHiddenFieldValue())
                .add("PositionToHidden", toResolved.toPositionFieldValue())
                .add("AdvancedForm.Via[0]", "")
                .add("AdvancedForm.ViaHidden[0]", "")
                .add("AdvancedForm_ViaHiddenCoor_0_", "")
                .add("Date", "")
                .add("Time", request.timeInput.trim())
                .add("IsArr", "False")
                .add("OnlyDirect", if (request.directOnly) "True" else "False")
                .add("ViaReverse", "False")
                .add("DefaultMaxArcLengthFrom", "true")
                .build()

            val searchUrl = "https://cp.sk/$city/spojenie/"
            val searchReq = Request.Builder()
                .url(searchUrl)
                .post(form)
                .addHeader("User-Agent", "Mozilla/5.0")
                .addHeader("Referer", searchUrl)
                .build()

            client.newCall(searchReq).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("CP search failed: HTTP ${response.code}")
                }
                val html = response.body?.string().orEmpty()
                parseConnectionsFromHtml(html)
            }
        }
    }

    suspend fun suggestStops(citySlug: String, input: String): Result<List<CpStopSuggestion>> {
        return runCatching {
            fetchSuggestions(citySlug, input)
        }
    }

    private fun resolveStop(citySlug: String, input: String): CpStopSuggestion? {
        val normalized = input.trim()
        if (normalized.isBlank()) return null
        val suggestions = fetchSuggestions(citySlug, normalized)
        if (suggestions.isEmpty()) return null

        val exact = suggestions.firstOrNull {
            it.selectedText.equals(normalized, ignoreCase = true)
        }
        return exact ?: suggestions.first()
    }

    private fun fetchSuggestions(citySlug: String, input: String): List<CpStopSuggestion> {
        val normalized = input.trim()
        if (normalized.isBlank()) return emptyList()

        val lookupUrl = "https://cp.sk/$citySlug/Ajax/SearchTimetableObjects/"
        val callbackName = "cb"
        val req = Request.Builder()
            .url(
                "$lookupUrl?callback=$callbackName&count=18" +
                    "&prefixText=${urlEncode(normalized)}&positionAccuracy=" +
                    "&searchByPosition=false&onlyStation=false&line=&format=json&bindTtIndex=&date="
            )
            .get()
            .addHeader("User-Agent", "Mozilla/5.0")
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .addHeader("Referer", "https://cp.sk/$citySlug/spojenie/")
            .build()

        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val raw = response.body?.string().orEmpty()
            val json = unwrapJsonp(raw)
            val arr = runCatching { gson.fromJson(json, JsonArray::class.java) }.getOrNull() ?: return emptyList()
            return arr.mapNotNull { element ->
                val obj = element.asJsonObject
                if (obj.has("isHint") && obj.get("isHint").asBoolean) return@mapNotNull null
                val text = obj.get("text")?.asString?.trim().orEmpty()
                val value = obj.get("value")?.asString?.trim().orEmpty()
                val value2 = obj.get("value2")?.asString?.trim().orEmpty()
                if (text.isBlank() || value.isBlank() || value2.isBlank()) return@mapNotNull null
                CpStopSuggestion(
                    selectedText = text,
                    value = value,
                    value2 = value2,
                    coorX = obj.get("coorX")?.asString,
                    coorY = obj.get("coorY")?.asString,
                    description = obj.get("description")?.asString.orEmpty()
                )
            }
        }
    }

    private fun parseConnectionsFromHtml(html: String): List<TimetableConnection> {
        val doc = Jsoup.parse(html)
        val boxes = doc.select("div[id^=connectionBox-].box.connection")
        if (boxes.isEmpty()) return emptyList()

        return boxes.mapNotNull { box ->
            val id = box.id().removePrefix("connectionBox-")
            val departureTime = box.selectFirst("div.connection-head h2.date")?.ownText()?.trim().orEmpty()
            val totalDuration = box.selectFirst("div.connection-head p.total strong")?.text()?.trim().orEmpty()

            val segments = box.select("div.connection-details div.line-item > div.outside-of-popup").mapNotNull { segment ->
                val line = segment.selectFirst("h3 span")?.text()?.trim().orEmpty()
                val operator = segment.selectFirst("p.line-right-part span.owner span")?.text()?.trim().orEmpty()
                val stationItems = segment.select("ul.stations li.item")
                if (stationItems.isEmpty()) return@mapNotNull null
                val first = stationItems.first() ?: return@mapNotNull null
                val last = stationItems.last() ?: return@mapNotNull null
                val depTime = first.selectFirst("p.time")?.text()?.trim().orEmpty()
                val depStop = first.selectFirst("p.station strong.name")?.text()?.trim().orEmpty()
                val arrTime = last.selectFirst("p.time")?.text()?.trim().orEmpty()
                val arrStop = last.selectFirst("p.station strong.name")?.text()?.trim().orEmpty()
                if (depTime.isBlank() || depStop.isBlank() || arrTime.isBlank() || arrStop.isBlank()) {
                    return@mapNotNull null
                }
                TimetableSegment(
                    line = line,
                    operatorName = operator,
                    departureTime = depTime,
                    departureStop = depStop,
                    arrivalTime = arrTime,
                    arrivalStop = arrStop
                )
            }

            if (segments.isEmpty()) return@mapNotNull null

            TimetableConnection(
                id = id,
                departureTime = departureTime.ifBlank { segments.first().departureTime },
                arrivalTime = segments.last().arrivalTime,
                totalDuration = totalDuration,
                segments = segments
            )
        }
    }

    private fun unwrapJsonp(content: String): String {
        val start = content.indexOf('(')
        val end = content.lastIndexOf(')')
        return if (start >= 0 && end > start) {
            content.substring(start + 1, end)
        } else {
            content
        }
    }

    private fun urlEncode(value: String): String {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
    }

    @Suppress("unused")
    private fun urlDecode(value: String): String {
        return URLDecoder.decode(value, StandardCharsets.UTF_8.toString())
    }
}
