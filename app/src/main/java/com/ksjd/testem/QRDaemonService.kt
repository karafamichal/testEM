package com.ksjd.testem

import android.util.Log
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.coroutines.coroutineContext
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.math.abs
import java.util.Locale
import java.util.TimeZone

data class TokenResponse(
    val success: Boolean,
    val data: String
)

data class QrTokenPayload(
    val rawBase64: String,
    val decodedBytes: ByteArray
)

class QRDaemonService(
    private val baseUrl: String,
    private val username: String,
    private val password: String,
    initialSerialNumber: String,
    initialNfcUid: String,
    initialNfcEnabled: Boolean,
    private val onTokenUpdate: (String, String) -> Unit,
    private val onError: (String) -> Unit,
    private val onUserName: (String) -> Unit,
    private val onSerialNumber: (String) -> Unit,
    private val onAccountInfo: (AccountDetails) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val userAgent = "Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Mobile Safari/537.36"
    private var sessionBaseUrl: String = baseUrl
    private val cookieJar = object : CookieJar {
        private val cookieStore = ArrayList<Cookie>(16)
        private val consentDomain = "sadzv.qrbus.me"
        private var consentSeeded = false

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            val iter = cookieStore.iterator()
            while (iter.hasNext()) {
                val existing = iter.next()
                if (cookies.any { it.name == existing.name && it.domain == existing.domain && it.path == existing.path }) {
                    iter.remove()
                }
            }
            cookieStore.addAll(cookies)

            if (!consentSeeded && url.host == consentDomain) {
                val expiry = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000
                if (cookieStore.none { it.name == "pisnotshowhint" }) {
                    cookieStore.add(
                        Cookie.Builder()
                            .name("pisnotshowhint").value("true")
                            .domain(consentDomain).path("/").expiresAt(expiry).build()
                    )
                }
                if (cookieStore.none { it.name == "piscookiewindow" }) {
                    cookieStore.add(
                        Cookie.Builder()
                            .name("piscookiewindow")
                            .value("{%22requiredCookies%22:true%2C%22analyticsCookies%22:true}")
                            .domain(consentDomain).path("/").expiresAt(expiry).build()
                    )
                }
                consentSeeded = true
            }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            val iter = cookieStore.iterator()
            val matching = ArrayList<Cookie>(cookieStore.size)
            while (iter.hasNext()) {
                val cookie = iter.next()
                if (cookie.expiresAt in 1..now - 1) {
                    iter.remove()
                    continue
                }
                if (cookie.matches(url)) matching.add(cookie)
            }
            return matching
        }
    }

    private val client: OkHttpClient = NetworkClient.base.newBuilder()
        .cookieJar(cookieJar)
        .build()

    private fun getCsrfToken(url: String): String? {
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        val cookies = cookieJar.loadForRequest(httpUrl)
        val raw = cookies.firstOrNull { it.name.equals("XSRF-TOKEN", ignoreCase = true) }?.value
            ?: cookies.firstOrNull { it.name.equals("CSRF-TOKEN", ignoreCase = true) }?.value
            ?: cookies.firstOrNull { it.name.equals("csrftoken", ignoreCase = true) }?.value
            ?: return null
        return URLDecoder.decode(raw, StandardCharsets.UTF_8.name())
    }
    private val gson = Gson()
    private val TAG = "QRDaemon"
    
    private var lastTokenHex: String? = null
    private var lastTokenBase64: String? = null
    @Volatile private var serialNumber: String = initialSerialNumber
    @Volatile private var nfcUid: String = initialNfcUid
    @Volatile private var nfcEnabled: Boolean = initialNfcEnabled
    private var pollingJob: Job? = null
    private val pollingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var isAuthenticated = false
    private var authFailures = 0
    @Volatile private var isPolling = false

    fun setNfcMode(enabled: Boolean, uid: String) {
        nfcEnabled = enabled
        nfcUid = uid
        if (enabled) {
            onStatus("NFC mode enabled")
        } else {
            onStatus("NFC mode disabled")
        }
    }

    fun startPolling() {
        if (isPolling && pollingJob?.isActive == true) {
            return
        }
        pollingJob?.cancel()
        isPolling = true

        pollingJob = pollingScope.launch {
            try {
                onStatus("Starting polling…")
                if (!isAuthenticated) performLogin()
                pollTokens()
            } catch (_: CancellationException) {
                onStatus("Polling stopped")
            } catch (e: Exception) {
                Log.e(TAG, "startPolling error", e)
                onError("Polling error: ${e.message}")
                onStatus("Polling error - restarting in 5s: ${e.message}")
                delay(5000)
                if (isPolling) startPolling()
            } finally {
                isPolling = false
            }
        }
    }

    fun stopPolling() {
        isPolling = false
        pollingJob?.cancel()
        pollingJob = null
    }

    fun shutdown() {
        stopPolling()
        pollingScope.cancel()
    }

    private suspend fun performLogin() {
        try {
            Log.d(TAG, "Attempting login…")
            onStatus("Opening base URL…")
            
            // Step 1: Navigate to base URL
            val baseRequest = Request.Builder()
                .url(sessionBaseUrl)
                .get()
                .addHeader("User-Agent", userAgent)
                .build()
            
            client.newCall(baseRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Failed to reach base URL: ${response.code}")
                }
                val effectiveUrl = response.request.url
                sessionBaseUrl = "${effectiveUrl.scheme}://${effectiveUrl.host}"
                Log.d(TAG, "Base URL set to: $sessionBaseUrl")
            }

            // Step 2: Navigate to account page
            onStatus("Opening account page…")
            val accountRequest = Request.Builder()
                .url("$sessionBaseUrl/account")
                .get()
                .addHeader("User-Agent", userAgent)
                .addHeader("Referer", sessionBaseUrl)
                .build()
            
            client.newCall(accountRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Failed to reach account: ${response.code}")
                }
            }

            // Step 3: Post login credentials
            onStatus("Submitting login…")
            val loginBody = FormBody.Builder()
                .add("post[login]", username)
                .add("post[password]", password)
                .build()
            
            val loginRequest = Request.Builder()
                .url("$sessionBaseUrl/accountapi/login")
                .post(loginBody)
                .addHeader("Accept", "*/*")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .addHeader("Cache-Control", "no-cache")
                .addHeader("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .addHeader("X-Requested-With", "XMLHttpRequest")
                .addHeader("Origin", sessionBaseUrl)
                .addHeader("Pragma", "no-cache")
                .addHeader("Referer", "$sessionBaseUrl/account/login")
                .addHeader("Sec-CH-UA", "\"Not(A:Brand\";v=\"8\", \"Chromium\";v=\"144\", \"Google Chrome\";v=\"144\"")
                .addHeader("Sec-CH-UA-Mobile", "?1")
                .addHeader("Sec-CH-UA-Platform", "\"Android\"")
                .addHeader("Sec-Fetch-Dest", "empty")
                .addHeader("Sec-Fetch-Mode", "cors")
                .addHeader("Sec-Fetch-Site", "same-origin")
                .addHeader("User-Agent", userAgent)
                .apply {
                    val csrf = getCsrfToken(sessionBaseUrl)
                    if (!csrf.isNullOrEmpty()) {
                        addHeader("X-XSRF-TOKEN", csrf)
                        addHeader("X-CSRF-TOKEN", csrf)
                    }
                }
                .build()
            
            client.newCall(loginRequest).execute().use { response ->
                val loginUrl = response.request.url
                response.headers("Set-Cookie").forEach { setCookieHeader ->
                    val wpisMatch = Regex("WPIS=([^;]+)").find(setCookieHeader) ?: return@forEach
                    val wpisValue = wpisMatch.groupValues[1]
                    val wpisCookie = Cookie.Builder()
                        .name("WPIS")
                        .value(wpisValue)
                        .domain("sadzv.qrbus.me")
                        .path("/")
                        .secure()
                        .expiresAt(System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000)
                        .build()
                    cookieJar.saveFromResponse(loginUrl, listOf(wpisCookie))
                }

                if (response.code != 200) {
                    throw Exception("Login failed: ${response.code}")
                }
                val responseBody = response.body?.string() ?: ""
                val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)
                val success = jsonResponse.get("success")?.asBoolean ?: false
                if (!success) throw Exception("Login failed: API returned success=false")

                val tokenUrl = "$sessionBaseUrl/cardapi/getQrToken".toHttpUrlOrNull() ?: return@use
                val allCookies = cookieJar.loadForRequest(tokenUrl)
                authFailures = 0
                onStatus("Login successful (cookies: ${allCookies.size})")
                isAuthenticated = true
            }

            // Run post-login requests in parallel
            coroutineScope {
            launch {
            try {
                onStatus("Verifying session (getUId)…")
                val uidRequest = Request.Builder()
                    .url("$sessionBaseUrl/accountapi/getUId")
                    .get()
                    .addHeader("Accept", "*/*")
                    .addHeader("Accept-Language", "en-US,en;q=0.9")
                    .addHeader("Cache-Control", "no-cache")
                    .addHeader("Pragma", "no-cache")
                    .addHeader("Referer", "$sessionBaseUrl/")
                    .addHeader("Sec-CH-UA", "\"Not(A:Brand\";v=\"8\", \"Chromium\";v=\"144\", \"Google Chrome\";v=\"144\"")
                    .addHeader("Sec-CH-UA-Mobile", "?1")
                    .addHeader("Sec-CH-UA-Platform", "\"Android\"")
                    .addHeader("Sec-Fetch-Dest", "empty")
                    .addHeader("Sec-Fetch-Mode", "cors")
                    .addHeader("Sec-Fetch-Site", "same-origin")
                    .addHeader("User-Agent", userAgent)
                    .addHeader("X-Requested-With", "XMLHttpRequest")
                    .build()

                client.newCall(uidRequest).execute().use { response ->
                    val bodyText = response.body?.string() ?: ""
                    Log.d(TAG, "getUId response: ${response.code} body=$bodyText")
                    onStatus("getUId HTTP ${response.code}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "getUId failed: ${e.message}")
            }
            }
            launch {
            try {
                onStatus("Loading account detail…")
                val detailRequest = Request.Builder()
                    .url("$sessionBaseUrl/userapi/getAccountDetail")
                    .get()
                    .addHeader("Accept", "*/*")
                    .addHeader("Accept-Language", "en-US,en;q=0.9")
                    .addHeader("Cache-Control", "no-cache")
                    .addHeader("Pragma", "no-cache")
                    .addHeader("Referer", "$sessionBaseUrl/account/login")
                    .addHeader("Sec-CH-UA", "\"Not(A:Brand\";v=\"8\", \"Chromium\";v=\"144\", \"Google Chrome\";v=\"144\"")
                    .addHeader("Sec-CH-UA-Mobile", "?1")
                    .addHeader("Sec-CH-UA-Platform", "\"Android\"")
                    .addHeader("Sec-Fetch-Dest", "empty")
                    .addHeader("Sec-Fetch-Mode", "cors")
                    .addHeader("Sec-Fetch-Site", "same-origin")
                    .addHeader("User-Agent", userAgent)
                    .addHeader("X-Requested-With", "XMLHttpRequest")
                    .build()

                client.newCall(detailRequest).execute().use { response ->
                    val bodyText = response.body?.string() ?: ""
                    val hasSerial = bodyText.contains(serialNumber)
                    Log.d(TAG, "getAccountDetail response: ${response.code} len=${bodyText.length} hasSerial=$hasSerial")
                    onStatus("getAccountDetail HTTP ${response.code}")
                    if (response.isSuccessful && bodyText.isNotBlank()) {
                        try {
                            val json = gson.fromJson(bodyText, JsonObject::class.java)
                            val data = json.getAsJsonObject("data") ?: json

                            fun readString(obj: JsonObject?, vararg keys: String): String {
                                if (obj == null) return ""
                                for (key in keys) {
                                    val value = obj.get(key)?.asString?.trim().orEmpty()
                                    if (value.isNotEmpty()) return value
                                }
                                return ""
                            }

                            fun extractTemplateBase64(templateRaw: String): String {
                                if (templateRaw.isBlank()) return ""
                                val cleaned = templateRaw
                                    .replace("\\\\", "\\")
                                    .replace("\\\"", "\"")
                                return try {
                                    val templateJson = gson.fromJson(cleaned, JsonObject::class.java)
                                    templateJson.get("base64")?.asString?.trim().orEmpty()
                                } catch (_: Exception) {
                                    val match = Regex("base64\\\\\":\\\\\"([^\\\\\"]+)").find(templateRaw)
                                    match?.groupValues?.getOrNull(1)?.trim().orEmpty()
                                }
                            }

                            fun readLong(obj: JsonObject?, vararg keys: String): Long {
                                if (obj == null) return 0
                                for (key in keys) {
                                    val value = obj.get(key)
                                    if (value != null && value.isJsonPrimitive) {
                                        try {
                                            return value.asLong
                                        } catch (_: Exception) {
                                            // Ignore
                                        }
                                    }
                                }
                                return 0
                            }

                            fun readDouble(obj: JsonObject?, vararg keys: String): Double? {
                                if (obj == null) return null
                                val centKeys = setOf("creditLastBalance", "credit")
                                for (key in keys) {
                                    val value = obj.get(key)
                                    if (value == null || !value.isJsonPrimitive) continue
                                    val prim = value.asJsonPrimitive
                                    if (prim.isString) {
                                        val raw = prim.asString.trim()
                                        if (raw.isEmpty()) continue
                                        val normalized = raw.replace(",", ".")
                                        val parsed = normalized.toDoubleOrNull() ?: continue
                                        if (key in centKeys && !normalized.contains(".")) {
                                            return parsed / 100.0
                                        }
                                        return parsed
                                    }
                                    if (prim.isNumber) {
                                        val parsed = try {
                                            prim.asDouble
                                        } catch (_: Exception) {
                                            continue
                                        }
                                        if (key in centKeys && parsed % 1.0 == 0.0) {
                                            return parsed / 100.0
                                        }
                                        return parsed
                                    }
                                }
                                return null
                            }

                            fun firstCard(obj: JsonObject?): JsonObject? {
                                if (obj == null) return null
                                obj.getAsJsonObject("card")?.let { return it }
                                obj.getAsJsonArray("cards")?.let { arr ->
                                    if (arr.size() > 0 && arr[0].isJsonObject) return arr[0].asJsonObject
                                }
                                obj.getAsJsonObject("wertyzUser")?.let { return firstCard(it) }
                                obj.getAsJsonObject("user")?.let { return firstCard(it) }
                                return null
                            }

                            fun firstTicket(card: JsonObject?): JsonObject? {
                                if (card == null) return null
                                val tickets = card.getAsJsonArray("tickets") ?: return null
                                var first: JsonObject? = null
                                for (i in 0 until tickets.size()) {
                                    val t = tickets[i]
                                    if (!t.isJsonObject) continue
                                    val obj = t.asJsonObject
                                    if (first == null) first = obj
                                    if (obj.get("active")?.asBoolean == true) return obj
                                }
                                return first
                            }

                            val userObj = data.getAsJsonObject("wertyzUser")
                                ?: data.getAsJsonObject("user")
                                ?: data
                            val cardObj = firstCard(userObj) ?: firstCard(data)
                            val ticketObj = firstTicket(cardObj)

                            val cardFullName = readString(cardObj, "fullName", "fullname", "ownerFullName", "name")
                            val cardFirstName = readString(cardObj, "ownerFirstName", "firstName", "firstname", "first_name")
                            val cardLastName = readString(cardObj, "ownerLastName", "lastName", "lastname", "last_name")
                            val dataFullName = readString(userObj, "fullName", "fullname", "name")
                            val dataFirstName = readString(userObj, "firstName", "firstname", "first_name")
                            val dataLastName = readString(userObj, "lastName", "lastname", "last_name")

                            val derived = when {
                                cardFullName.isNotEmpty() -> cardFullName
                                dataFullName.isNotEmpty() -> dataFullName
                                cardFirstName.isNotEmpty() || cardLastName.isNotEmpty() -> listOf(cardFirstName, cardLastName)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" ")
                                dataFirstName.isNotEmpty() || dataLastName.isNotEmpty() -> listOf(dataFirstName, dataLastName)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" ")
                                else -> ""
                            }
                            if (derived.isNotEmpty()) onUserName(derived)

                            val snr = readString(
                                cardObj,
                                "snr",
                                "cardSnr",
                                "cardSNR",
                                "cardNumber",
                                "cardnumber",
                                "serialNumber",
                                "serialnumber"
                            ).ifBlank {
                                readString(
                                    data,
                                    "snr",
                                    "cardSnr",
                                    "cardSNR",
                                    "cardNumber",
                                    "cardnumber",
                                    "serialNumber",
                                    "serialnumber"
                                )
                            }
                            if (snr.isNotEmpty() && snr != serialNumber) {
                                serialNumber = snr
                                onSerialNumber(snr)
                                onStatus("Loaded SNR")
                            }

                            val templateRaw = readString(cardObj, "template")
                            val templateBase64 = readString(cardObj, "base64", "cardBase64").ifBlank {
                                extractTemplateBase64(templateRaw)
                            }

                            val details = AccountDetails(
                                cardTypeName = readString(cardObj, "cardTypeName", "typeName", "cardType"),
                                organizationName = readString(cardObj, "organizationName", "organization", "companyName"),
                                cardValidFrom = readLong(cardObj, "validFrom", "cardValidFrom"),
                                cardValidTo = readLong(cardObj, "validTo", "cardValidTo"),
                                ticketValidFrom = readLong(ticketObj, "timeValidityFrom", "validFrom"),
                                ticketValidTo = readLong(ticketObj, "timeValidityTo", "validTo"),
                                discountValidFrom = readLong(cardObj, "discountValidFrom"),
                                discountValidTo = readLong(cardObj, "discountValidTo"),
                                creditLastBalance = readDouble(cardObj, "creditLastBalance", "credit"),
                                currencySymbol = readString(cardObj, "currencySymbol", "currency"),
                                cardTemplateBase64 = templateBase64
                            )
                            onAccountInfo(details)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse user name: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "getAccountDetail failed: ${e.message}")
            }
            }
            }
            onStatus("Session ready")

        } catch (e: Exception) {
            Log.e(TAG, "Login failed: ${e.message}", e)
            onError("Login failed: ${e.message}")
            onStatus("Login failed: ${e.message}")
            throw e
        }
    }

    private suspend fun pollTokens() {
        var consecutiveEmpty = 0
        while (coroutineContext.isActive) {
            try {
                val payload = fetchQRToken()
                if (payload == null || payload.decodedBytes.isEmpty() || payload.rawBase64.isBlank()) {
                    consecutiveEmpty += 1
                    val backoff = (1500L * consecutiveEmpty).coerceAtMost(10_000L)
                    delay(backoff)
                    continue
                }
                consecutiveEmpty = 0

                val hex = bytesToHex(payload.decodedBytes)
                if (hex != lastTokenHex || payload.rawBase64 != lastTokenBase64) {
                    lastTokenHex = hex
                    lastTokenBase64 = payload.rawBase64
                    onTokenUpdate(hex, payload.rawBase64)
                }

                // Token rotates every ~25s; nudge slightly early to avoid missing the edge.
                delay(24_500)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "pollTokens error: ${e.message}", e)

                if (e.message?.contains("401") == true) {
                    isAuthenticated = false
                    authFailures += 1
                    if (authFailures >= 3) {
                        onError("Authentication failed repeatedly")
                        return
                    }
                    onStatus("Session expired, re-authenticating…")
                    performLogin()
                } else {
                    onError("Unexpected error: ${e.message}")
                    delay(5000)
                }
            }
        }
    }

    private suspend fun fetchQRToken(): QrTokenPayload? {
        val identifier = if (nfcEnabled && nfcUid.isNotBlank()) nfcUid else serialNumber
        if (identifier.isBlank()) {
            onStatus("Waiting for serial number…")
            return null
        }
        val body = FormBody.Builder()
            .add("post[serialnumber]", identifier)
            .build()

        val tokenUrl = "$sessionBaseUrl/cardapi/getQrToken"
        val builder = Request.Builder()
            .url(tokenUrl)
            .post(body)
            .addHeader("Accept", "*/*")
            .addHeader("Accept-Language", "en-US,en;q=0.9")
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .addHeader("Origin", sessionBaseUrl)
            .addHeader("Referer", "$sessionBaseUrl/account")
            .addHeader("User-Agent", userAgent)
        val csrf = getCsrfToken(tokenUrl)
        if (!csrf.isNullOrEmpty()) {
            builder.addHeader("X-XSRF-TOKEN", csrf).addHeader("X-CSRF-TOKEN", csrf)
        }
        val request = builder.build()

        return try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                val finalUrl = response.request.url.toString()
                if (code == 401 || finalUrl.contains("/account/login")) {
                    throw Exception("401 Unauthorized - Session expired")
                }
                if (code != 200) {
                    onStatus("Token HTTP $code")
                    return null
                }
                val responseBody = response.body?.string().orEmpty()
                if (responseBody.isEmpty()) return null

                val json = try {
                    gson.fromJson(responseBody, JsonObject::class.java)
                } catch (_: Exception) {
                    return null
                }
                val success = json.get("success")?.asBoolean ?: false
                val data = json.get("data")?.asString ?: ""
                val base64Field = json.get("base64")?.asString ?: ""
                if (!success || (data.isEmpty() && base64Field.isEmpty())) {
                    return null
                }

                val rawToken = if (base64Field.isNotBlank()) base64Field else data
                val decoded = decodeBase64Token(rawToken) ?: return null
                authFailures = 0
                QrTokenPayload(rawBase64 = rawToken, decodedBytes = decoded)
            }
        } catch (e: Exception) {
            if (e.message?.contains("401") == true || e.message?.contains("Unauthorized") == true) {
                throw e
            }
            Log.w(TAG, "Token request failed: ${e.message}")
            null
        }
    }

    private fun decodeBase64Token(raw: String): ByteArray? {
        return try {
            // Fast-path: most tokens are plain standard base64 already.
            Base64.decode(raw, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            val normalized = raw.replace(' ', '+')
            var clean = StringBuilder(normalized.length)
            for (c in normalized) {
                if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
                    c == '+' || c == '/' || c == '=' || c == '-' || c == '_'
                ) clean.append(c)
            }
            val needsUrlSafe = clean.contains('-') || clean.contains('_')
            val padLen = (4 - (clean.length % 4)) % 4
            repeat(padLen) { clean.append('=') }
            val flags = if (needsUrlSafe) Base64.URL_SAFE or Base64.NO_WRAP else Base64.NO_WRAP
            try {
                Base64.decode(clean.toString(), flags)
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun fetchCardHistory(limit: Int = 20): Result<List<CardHistoryItem>> {
        return try {
            val snr = serialNumber.trim()
            if (snr.isBlank()) {
                return Result.failure(Exception("Card serial number is not available"))
            }

            val historyUrl = "$sessionBaseUrl/cardapi/getCardHistory/$snr/0/$limit"
            onStatus("Loading ticket and payment history...")

            val request = Request.Builder()
                .url(historyUrl)
                .get()
                .addHeader("Accept", "*/*")
                .addHeader("Referer", "$sessionBaseUrl/account")
                .addHeader("User-Agent", userAgent)
                .addHeader("X-Requested-With", "XMLHttpRequest")
                .build()

            client.newCall(request).execute().use { response ->
                val finalUrl = response.request.url.toString()
                Log.d(TAG, "History response: code=${response.code}, finalUrl=$finalUrl")
                if (response.code == 401) {
                    isAuthenticated = false
                    return Result.failure(Exception("Session expired (401)"))
                }
                if (!response.isSuccessful) {
                    return Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
                }
                if (finalUrl.contains("/account/login")) {
                    isAuthenticated = false
                    return Result.failure(Exception("Session expired - redirected to login"))
                }

                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    return Result.success(emptyList())
                }
                if (body.trimStart().startsWith("<")) {
                    Log.w(TAG, "History endpoint returned HTML instead of JSON")
                    return Result.failure(Exception("History endpoint returned HTML (not authenticated)"))
                }

                val root = gson.fromJson(body, JsonObject::class.java)
                val tickets = root.getAsJsonArray("tickets")
                val transactions = root.getAsJsonArray("transactions")
                val currency = tickets?.firstOrNull()?.asJsonObject?.get("currencySymbol")?.asString.orEmpty()
                val result = mutableListOf<CardHistoryItem>()

                tickets?.forEachIndexed { index, element ->
                    val obj = element.asJsonObject
                    val ticketId = obj.get("ticketSNR")?.asString?.trim().orEmpty().ifBlank {
                        "ticket-${obj.get("saleTime")?.asLong ?: 0L}-$index"
                    }
                    val saleTime = normalizeHistoryTimestamp((obj.get("saleTime")?.asLong ?: 0L) * 1000L)
                    val tariffName = obj.get("tariffName")?.asString?.trim().orEmpty()
                    val ticketTypeName = obj.get("ticketTypeName")?.asString?.trim().orEmpty()
                    val ticketTypeId = obj.get("ticketTypeId")?.asInt ?: 0
                    val priceCents = obj.get("price")?.asLong ?: 0L
                    val oldBalance = obj.get("oldBalance")?.asLong
                    val newBalance = obj.get("newBalance")?.asLong
                    val title = if (ticketTypeName.isNotBlank()) ticketTypeName else "Ticket"
                    val detailLabel = if (tariffName.isNotBlank()) tariffName else "Card event"
                    val balancePart = if (oldBalance != null && newBalance != null) {
                        " | ${formatAmount(oldBalance, currency)} -> ${formatAmount(newBalance, currency)}"
                    } else {
                        ""
                    }
                    val amount = when {
                        ticketTypeId == 3 -> "+${formatAmount(abs(priceCents), currency)}"
                        priceCents < 0 -> "+${formatAmount(abs(priceCents), currency)}"
                        else -> "-${formatAmount(abs(priceCents), currency)}"
                    }
                    result += CardHistoryItem(
                        id = ticketId,
                        sourceType = HistorySourceType.TICKET,
                        timestampMs = saleTime,
                        title = title,
                        subtitle = detailLabel + balancePart,
                        amountText = amount
                    )
                }

                transactions?.forEachIndexed { index, element ->
                    val obj = element.asJsonObject
                    val createdAt = normalizeHistoryTimestamp((obj.get("createdAt")?.asLong ?: 0L) * 1000L)
                    val type = obj.get("transactionType")?.asInt ?: 0
                    val changes = obj.getAsJsonArray("changes")
                    val subtitle = if (changes != null && changes.size() > 0) {
                        buildString {
                            changes.forEach { change ->
                                val changeObj = change.asJsonObject
                                val value = changeObj.get("value")?.asString
                                    ?: changeObj.get("valueAfter")?.asString
                                    ?: changeObj.get("valueBefore")?.asString
                                    ?: ""
                                if (value.isNotBlank()) {
                                    if (isNotEmpty()) append(" | ")
                                    append(value)
                                }
                            }
                        }.ifBlank { "Transaction details" }
                    } else {
                        "Transaction details"
                    }

                    result += CardHistoryItem(
                        id = "transaction-${createdAt}-$index",
                        sourceType = HistorySourceType.TRANSACTION,
                        timestampMs = createdAt,
                        title = "Transaction #$type",
                        subtitle = subtitle,
                        amountText = ""
                    )
                }

                Result.success(result.sortedByDescending { it.timestampMs })
            }
        } catch (e: Exception) {
            Log.e(TAG, "History fetch failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun formatAmount(cents: Long, currency: String): String {
        val symbol = if (currency.isBlank()) "" else " $currency"
        return String.format(Locale.US, "%.2f%s", cents / 100.0, symbol)
    }

    private fun normalizeHistoryTimestamp(rawTimestampMs: Long): Long {
        if (rawTimestampMs <= 0L) return rawTimestampMs
        // History API uses local wall-clock values; remove local offset to avoid +1h/+2h shift.
        return rawTimestampMs - TimeZone.getDefault().getOffset(rawTimestampMs)
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hex = HEX_CHARS
        val out = CharArray(bytes.size * 2)
        var i = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out[i++] = hex[v ushr 4]
            out[i++] = hex[v and 0x0F]
        }
        return String(out)
    }

    private companion object {
        private val HEX_CHARS = "0123456789abcdef".toCharArray()
    }
}
