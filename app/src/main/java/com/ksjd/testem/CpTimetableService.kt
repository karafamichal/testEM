package com.ksjd.testem

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
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
        val directOnly: Boolean,
        val fromSuggestion: CpStopSuggestion? = null,
        val toSuggestion: CpStopSuggestion? = null
    )

    suspend fun searchConnections(request: SearchRequest): Result<TimetableSearchResult> {
        return try {
            val city = normalizeCitySlug(request.citySlug)
            val routePrefixes = routePrefixCandidates(city)
            val fromResolved = request.fromSuggestion ?: resolveStop(city, request.fromInput)
                ?: throw IllegalStateException("Unable to resolve departure stop")
            val toResolved = request.toSuggestion ?: resolveStop(city, request.toInput)
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

            var searchResult = TimetableSearchResult(emptyList(), null)
            for (routePrefix in routePrefixes) {
                val searchUrl = buildPageUrl(routePrefix, "/spojenie/")
                val searchReq = Request.Builder()
                    .url(searchUrl)
                    .post(form)
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .addHeader("Referer", searchUrl)
                    .build()

                val candidate = client.newCall(searchReq).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("CP search failed: HTTP ${response.code}")
                    }
                    val html = response.body?.string().orEmpty()
                    val connections = parseConnectionsFromHtml(html)
                    val pagingCursor = extractPagingCursor(
                        html = html,
                        citySlug = city,
                        routePrefix = routePrefix,
                        fromText = fromResolved.selectedText,
                        toText = toResolved.selectedText,
                        listedIds = connections.map { it.id }
                    )
                    TimetableSearchResult(
                        connections = connections,
                        pagingCursor = pagingCursor
                    )
                }
                searchResult = candidate
                if (candidate.connections.isNotEmpty()) {
                    break
                }
            }
            Result.success(searchResult)
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun loadMoreConnections(cursor: TimetablePagingCursor): Result<TimetableSearchResult> {
        if (cursor.listedIds.isEmpty()) {
            return Result.success(TimetableSearchResult(emptyList(), null))
        }

        return try {
            val city = normalizeCitySlug(cursor.citySlug)
            val routePrefix = if (cursor.routePrefix.isNotBlank()) {
                cursor.routePrefix
            } else {
                routePrefixCandidates(city).first()
            }

            val formBuilder = FormBody.Builder()
            cursor.listedIds.forEach { id ->
                formBuilder.add("listedIds[]", id)
            }
            val form = formBuilder
                .add("isPrev", "false")
                .add("handle", cursor.handle)
                .add("searchDate", cursor.searchDate)
                .add("connId", cursor.listedIds.last())
                .add("arrivalThere", "0001-01-01T00:00:00")
                .add("from", cursor.fromText)
                .add("to", cursor.toText)
                .build()

            val pagingUrl = buildPageUrl(routePrefix, "/Ajax/ConnPaging/?callback=cb")
            val refererUrl = buildPageUrl(routePrefix, "/spojenie/")
            val pagingReq = Request.Builder()
                .url(pagingUrl)
                .post(form)
                .addHeader("User-Agent", "Mozilla/5.0")
                .addHeader("X-Requested-With", "XMLHttpRequest")
                .addHeader("Referer", refererUrl)
                .build()

            val pagingResult = client.newCall(pagingReq).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("CP paging failed: HTTP ${response.code}")
                }

                val raw = response.body?.string().orEmpty()
                val json = unwrapJsonp(raw)
                val payload = try {
                    gson.fromJson(json, JsonObject::class.java)
                } catch (_: Exception) {
                    null
                }
                val chunks = payload
                    ?.getAsJsonArray("newConnections")
                    ?.mapNotNull { it?.asString }
                    .orEmpty()

                if (chunks.isNotEmpty()) {
                    val combinedHtml = chunks.joinToString("\n")
                    val newConnections = parseConnectionsFromHtml(combinedHtml)
                    if (newConnections.isNotEmpty()) {
                        val updatedIds = (cursor.listedIds + newConnections.map { it.id }).distinct()
                        TimetableSearchResult(
                            connections = newConnections,
                            pagingCursor = cursor.copy(listedIds = updatedIds)
                        )
                    } else {
                        TimetableSearchResult(emptyList(), null)
                    }
                } else {
                    TimetableSearchResult(emptyList(), null)
                }
            }
            Result.success(pagingResult)
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun suggestStops(citySlug: String, input: String): Result<List<CpStopSuggestion>> {
        return try {
            Result.success(fetchSuggestions(citySlug, input))
        } catch (error: Exception) {
            Result.failure(error)
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
        val normalizedCity = normalizeCitySlug(citySlug)
        val normalized = input.trim()
        if (normalized.isBlank()) return emptyList()

        val routePrefixes = routePrefixCandidates(normalizedCity)
        for (routePrefix in routePrefixes) {
            val lookupUrl = buildPageUrl(routePrefix, "/Ajax/SearchTimetableObjects/")
            val refererUrl = buildPageUrl(routePrefix, "/spojenie/")
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
                .addHeader("Referer", refererUrl)
                .build()

            val suggestions = client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    emptyList()
                } else {
                val raw = response.body?.string().orEmpty()
                val json = unwrapJsonp(raw)
                    val arr = runCatching { gson.fromJson(json, JsonArray::class.java) }.getOrNull()
                    if (arr == null) {
                        emptyList()
                    } else {
                        val parsed = mutableListOf<CpStopSuggestion>()
                        for (element in arr) {
                            val obj = element.asJsonObject
                            if (obj.has("isHint") && obj.get("isHint").asBoolean) {
                                continue
                            }
                            val text = obj.get("text")?.asString?.trim().orEmpty()
                            val value = obj.get("value")?.asString?.trim().orEmpty()
                            val value2 = obj.get("value2")?.asString?.trim().orEmpty()
                            if (text.isBlank() || value.isBlank() || value2.isBlank()) {
                                continue
                            }
                            parsed += CpStopSuggestion(
                                selectedText = text,
                                value = value,
                                value2 = value2,
                                coorX = obj.get("coorX")?.asString,
                                coorY = obj.get("coorY")?.asString,
                                description = obj.get("description")?.asString.orEmpty()
                            )
                        }
                        parsed
                    }
                }
            }

            if (suggestions.isNotEmpty()) {
                return suggestions
            }
        }

        return emptyList()
    }

    private fun parseConnectionsFromHtml(html: String): List<TimetableConnection> {
        val doc = Jsoup.parse(html)
        val boxes = doc.select("div[id^=connectionBox-].box.connection, div[id^=connectionBox-].connection")
        if (boxes.isEmpty()) return emptyList()

        val parsedConnections = mutableListOf<TimetableConnection>()
        for (box in boxes) {
            val id = box.id().removePrefix("connectionBox-")
            val departureTime = box.selectFirst("div.connection-head h2.date")?.ownText()?.trim().orEmpty()
            val totalDuration = box.selectFirst("div.connection-head p.total strong")?.text()?.trim().orEmpty()

            val segments = mutableListOf<TimetableSegment>()
            for (segment in box.select("div.connection-details div.line-item > div.outside-of-popup")) {
                val line = segment.selectFirst("h3 span")?.text()?.trim().orEmpty()
                val operator = segment.selectFirst("p.line-right-part span.owner span")?.text()?.trim().orEmpty()
                val stationItems = segment.select("ul.stations li.item")
                if (stationItems.isEmpty()) continue
                val first = stationItems.first()
                val last = stationItems.last()
                val depTime = first.selectFirst("p.time")?.text()?.trim().orEmpty()
                val depStop = first.selectFirst("p.station strong.name")?.text()?.trim().orEmpty()
                val arrTime = last.selectFirst("p.time")?.text()?.trim().orEmpty()
                val arrStop = last.selectFirst("p.station strong.name")?.text()?.trim().orEmpty()
                if (depTime.isBlank() || depStop.isBlank() || arrTime.isBlank() || arrStop.isBlank()) {
                    continue
                }
                segments += TimetableSegment(
                    line = line,
                    operatorName = operator,
                    departureTime = depTime,
                    departureStop = depStop,
                    arrivalTime = arrTime,
                    arrivalStop = arrStop
                )
            }

            if (segments.isEmpty()) continue

            parsedConnections += TimetableConnection(
                id = id,
                departureTime = departureTime.ifBlank { segments.first().departureTime },
                arrivalTime = segments.last().arrivalTime,
                totalDuration = totalDuration,
                segments = segments
            )
        }
        return parsedConnections
    }

    private fun extractPagingCursor(
        html: String,
        citySlug: String,
        routePrefix: String,
        fromText: String,
        toText: String,
        listedIds: List<String>
    ): TimetablePagingCursor? {
        if (listedIds.isEmpty()) return null

        val handle = extractJsonLikeToken(html, "handle")
        val searchDate = Regex("""[\"']?searchDate[\"']?\s*:\s*[\"']([^\"']+)[\"']""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            .orEmpty()

        if (handle.isBlank() || searchDate.isBlank()) return null

        return TimetablePagingCursor(
            citySlug = citySlug,
            routePrefix = routePrefix,
            fromText = fromText,
            toText = toText,
            handle = handle,
            searchDate = searchDate,
            listedIds = listedIds
        )
    }

    private fun normalizeCitySlug(citySlug: String): String {
        return when (citySlug.trim().lowercase()) {
            "", "slovakia" -> "slovensko"
            "banska-bystrica", "banská-bystrica", "banska bystrica", "banská bystrica" -> "banskabystrica"
            else -> citySlug.trim().lowercase()
        }
    }

    private fun routePrefixCandidates(normalizedCity: String): List<String> {
        return if (normalizedCity == "slovensko") {
            listOf("/slovensko", "")
        } else {
            listOf("/$normalizedCity")
        }
    }

    private fun buildPageUrl(routePrefix: String, suffix: String): String {
        return "https://cp.sk${routePrefix}${suffix}"
    }

    private fun extractJsonLikeToken(html: String, key: String): String {
        val pattern = Regex(
            """[\"']?$key[\"']?\s*:\s*(?:[\"']([^\"']+)[\"']|([^,}\s]+))"""
        )
        val match = pattern.find(html) ?: return ""
        return match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
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
