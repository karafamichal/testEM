package com.ksjd.testem

import android.util.Log
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
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

data class TokenResponse(
    val success: Boolean,
    val data: String
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
        private val cookieStore = mutableListOf<Cookie>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            Log.d(TAG, "saveFromResponse: url=$url, incoming cookies=${cookies.size}")
            cookies.forEach { cookie ->
                Log.d(TAG, "  Incoming cookie: ${cookie.name}=${cookie.value} (domain=${cookie.domain}, path=${cookie.path})")
            }
            
            cookieStore.removeAll { existing ->
                cookies.any { incoming ->
                    incoming.name == existing.name &&
                        incoming.domain == existing.domain &&
                        incoming.path == existing.path
                }
            }
            cookieStore.addAll(cookies)
            
            // Add cookie consent cookies that are normally set by JavaScript
            // These are required for the session to work properly
            if (url.host == "sadzv.qrbus.me" && cookies.isNotEmpty()) {
                val consentCookies = listOf(
                    Cookie.Builder()
                        .name("pisnotshowhint")
                        .value("true")
                        .domain("sadzv.qrbus.me")
                        .path("/")
                        .expiresAt(System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000) // 1 year
                        .build(),
                    Cookie.Builder()
                        .name("piscookiewindow")
                        .value("{%22requiredCookies%22:true%2C%22analyticsCookies%22:true}")
                        .domain("sadzv.qrbus.me")
                        .path("/")
                        .expiresAt(System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000) // 1 year
                        .build()
                )
                
                // Only add consent cookies if they don't already exist
                consentCookies.forEach { consentCookie ->
                    if (cookieStore.none { it.name == consentCookie.name }) {
                        cookieStore.add(consentCookie)
                        Log.d(TAG, "  Added consent cookie: ${consentCookie.name}")
                    }
                }
            }
            
            Log.d(TAG, "Total cookies in store: ${cookieStore.size}")
            cookieStore.forEach { cookie ->
                Log.d(TAG, "  Stored: ${cookie.name} (domain=${cookie.domain}, path=${cookie.path})")
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val matching = cookieStore.filter { cookie ->
                val matches = cookie.matches(url)
                if (!matches) {
                    Log.d(TAG, "  Cookie REJECTED: ${cookie.name}=${cookie.value.take(20)} | domain=${cookie.domain} vs ${url.host} | path=${cookie.path} vs ${url.encodedPath}")
                }
                matches
            }
            Log.d(TAG, "loadForRequest: url=$url, matched ${matching.size}/${cookieStore.size} cookies")
            if (matching.isNotEmpty()) {
                matching.forEach { cookie ->
                    Log.d(TAG, "  SENT: ${cookie.name}=${cookie.value.take(20)}")
                }
            }
            return matching
        }
    }

    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            Log.d(TAG, ">>> REQUEST: ${request.method} ${request.url}")
            Log.d(TAG, "    Headers:")
            request.headers.forEach { (name, value) ->
                if (name.lowercase() == "cookie") {
                    val hasWpis = value.contains("WPIS=")
                    Log.d(TAG, "    $name: len=${value.length}, hasWPIS=$hasWpis, head=${value.take(80)}...")
                } else {
                    Log.d(TAG, "    $name: ${value.take(60)}")
                }
            }
            
            val response = chain.proceed(request)
            Log.d(TAG, "<<< RESPONSE: ${response.code} for ${request.url}")
            response
        }
        .build()

    private fun cookieHeader(url: String): String {
        val httpUrl = url.toHttpUrlOrNull() ?: return ""
        val cookies = cookieJar.loadForRequest(httpUrl)
        val sorted = cookies.sortedWith(
            compareBy<Cookie> { if (it.name.equals("WPIS", ignoreCase = true)) 0 else 1 }
                .thenBy { it.name }
        )
        return sorted.joinToString("; ") { "${it.name}=${it.value}" }
    }
    
    private fun getCookieValue(url: String, name: String): String? {
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        return cookieJar.loadForRequest(httpUrl)
            .firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?.value
    }
    
    private fun getCsrfToken(url: String): String? {
        val raw = getCookieValue(url, "XSRF-TOKEN")
            ?: getCookieValue(url, "CSRF-TOKEN")
            ?: getCookieValue(url, "csrftoken")
        return raw?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
    }
    private val gson = Gson()
    private val TAG = "QRDaemon"
    
    private var lastTokenHex: String? = null
    private var serialNumber: String = initialSerialNumber
    private var nfcUid: String = initialNfcUid
    private var nfcEnabled: Boolean = initialNfcEnabled
    private var pollingJob: Job? = null
    private var isAuthenticated = false
    private var authFailures = 0
    private var isPolling = false

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
            onStatus("[WARNING] Polling already running - ignoring duplicate call")
            Log.w(TAG, "startPolling() called while already polling - ignoring")
            return
        }
        isPolling = false
        isPolling = true
        // Cancel any existing polling job first
        pollingJob?.cancel()
        
        pollingJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                onStatus("Starting polling…")
                Log.d(TAG, "startPolling: isAuthenticated=$isAuthenticated")
                // Check if already authenticated
                if (!isAuthenticated) {
                    performLogin()
                }
                
                // Start token polling
                Log.d(TAG, "Starting pollTokens()")
                pollTokens()
                Log.d(TAG, "pollTokens() completed (should never happen)")
                onStatus("Polling stopped unexpectedly")
            } catch (e: CancellationException) {
                onStatus("Polling stopped")
                Log.d(TAG, "Polling cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "startPolling caught exception: ${e.javaClass.simpleName}: ${e.message}", e)
                onError("Polling error: ${e.message}")
                onStatus("Polling error - restarting in 5s: ${e.message}")
                delay(5000)
                startPolling()
            } finally {
                isPolling = false
            }
        }
        pollingJob?.invokeOnCompletion { isPolling = false }
    }

    fun stopPolling() {
        isPolling = false
        pollingJob?.cancel()
        pollingJob = null
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
            
            delay(1000)
            
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
            
            delay(1000)
            
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
                Log.d(TAG, "Login response: ${response.code}")
                
                // Log Set-Cookie headers from login response
                val setCookieHeaders = response.headers("Set-Cookie")
                Log.d(TAG, "Login response Set-Cookie headers: ${setCookieHeaders.size}")
                setCookieHeaders.forEach { header ->
                    Log.d(TAG, "  Set-Cookie: ${header.take(100)}")
                }
                
                // Manually parse and add WPIS cookie since OkHttp isn't capturing it automatically
                // The cookie domain might not match, so we manually create it for sadzv.qrbus.me
                val loginUrl = response.request.url
                Log.d(TAG, "Attempting to manually parse ${setCookieHeaders.size} Set-Cookie headers")
                setCookieHeaders.forEach { setCookieHeader ->
                    try {
                        // Extract WPIS value using regex
                        val wpisMatch = Regex("WPIS=([^;]+)").find(setCookieHeader)
                        if (wpisMatch != null) {
                            val wpisValue = wpisMatch.groupValues[1]
                            Log.d(TAG, "Found WPIS value: ${wpisValue.take(30)}")
                            
                            // Manually create the cookie for sadzv.qrbu    s.me domain
                            // IMPORTANT: Must set secure(true) for HTTPS so OkHttp will actually send it
                            val wpisCookie = Cookie.Builder()
                                .name("WPIS")
                                .value(wpisValue)
                                .domain("sadzv.qrbus.me")
                                .path("/")
                                    .secure()  // Required for HTTPS cookies
                                .expiresAt(System.currentTimeMillis() + 2592000 * 1000L) // 30 days
                                .build()
                            
                                Log.d(TAG, "Manually saving WPIS cookie to sadzv.qrbus.me")
                            cookieJar.saveFromResponse(loginUrl, listOf(wpisCookie))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse WPIS cookie: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
                
                if (response.code != 200) {
                    throw Exception("Login failed: ${response.code}")
                }
                
                // Parse JSON response to check success
                val responseBody = response.body?.string() ?: ""
                Log.d(TAG, "Login response body: $responseBody")
                
                try {
                    val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)
                    val success = jsonResponse.get("success")?.asBoolean ?: false
                    
                    if (!success) {
                        throw Exception("Login failed: API returned success=false")
                    }
                    
                    // Check if WPIS cookie was saved
                    val tokenUrl = "$sessionBaseUrl/cardapi/getQrToken".toHttpUrlOrNull() ?: return@use
                    val allCookies = cookieJar.loadForRequest(tokenUrl)
                    Log.d(TAG, "After login, total cookies in store: ${allCookies.size}")
                    allCookies.forEach { cookie ->
                        Log.d(TAG, "  After login cookie: ${cookie.name}=${cookie.value.take(20)} (domain=${cookie.domain}, path=${cookie.path})")
                    }
                    
                    Log.d(TAG, "Login successful. Cookies for token endpoint: ${allCookies.size}")
                    allCookies.forEach { cookie ->
                        Log.d(TAG, "  Cookie: ${cookie.name}=${cookie.value} (domain=${cookie.domain}, path=${cookie.path})")
                    }
                    
                    authFailures = 0
                    onStatus("Login successful (cookies: ${allCookies.size})")
                    isAuthenticated = true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse login response: ${e.message}")
                    throw Exception("Login response parsing failed: ${e.message}")
                }
            }

            // Step 3b: Verify session with getUId (browser does this periodically)
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
                    .addHeader("Cookie", cookieHeader("$sessionBaseUrl/accountapi/getUId"))
                    .build()

                client.newCall(uidRequest).execute().use { response ->
                    val bodyText = response.body?.string() ?: ""
                    Log.d(TAG, "getUId response: ${response.code} body=$bodyText")
                    onStatus("getUId HTTP ${response.code}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "getUId failed: ${e.message}")
            }

            // Step 3c: Load account detail (browser does this after login)
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
                    .addHeader("Cookie", cookieHeader("$sessionBaseUrl/userapi/getAccountDetail"))
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
                                for (key in keys) {
                                    val value = obj.get(key)
                                    if (value != null && value.isJsonPrimitive) {
                                        try {
                                            return value.asDouble
                                        } catch (_: Exception) {
                                            // Ignore
                                        }
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
            
            // Warm up session by visiting /account page
            delay(1000)
            onStatus("Warming up session…")
            val warmupRequest = Request.Builder()
                .url("$sessionBaseUrl/account")
                .get()
                .addHeader("User-Agent", userAgent)
                .addHeader("Referer", sessionBaseUrl)
                .build()
            
            client.newCall(warmupRequest).execute().use { response ->
                Log.d(TAG, "Account page warmup: ${response.code}")
                if (response.code == 200) {
                    onStatus("Session ready")
                }
            }
            
            delay(1000)
            
        } catch (e: Exception) {
            Log.e(TAG, "Login failed: ${e.message}", e)
            onError("Login failed: ${e.message}")
            onStatus("Login failed: ${e.message}")
            throw e
        }
    }

    private suspend fun pollTokens() {
        Log.d(TAG, "Starting token polling…")
        onStatus("Polling for token…")
        
        while (coroutineContext.isActive) {
            try {
                onStatus("[POLL] Loop iteration starting…")
                onStatus("Fetching token…")
                val tokenBytes = fetchQRToken()
                Log.d(TAG, "fetchQRToken returned ${tokenBytes.size} bytes")
                
                if (tokenBytes.isEmpty()) {
                    Log.w(TAG, "Empty token payload - will retry")
                    onStatus("No token yet - retrying in 1.5s")
                    delay(1500)
                    continue
                }
                
                val hex = bytesToHex(tokenBytes)
                
                // Only update if token changed
                if (hex != lastTokenHex) {
                    lastTokenHex = hex
                    val base64 = Base64.encodeToString(tokenBytes, Base64.NO_WRAP)
                    
                    Log.d(TAG, "New token: $hex")
                    onTokenUpdate(hex, base64)
                    onStatus("Token updated")
                }
                
                // Poll every 25 seconds (observed rotation interval)
                delay(25000)
                
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "pollTokens caught exception: ${e.javaClass.simpleName}: ${e.message}", e)
                
                // If 401, re-authenticate
                if (e.message?.contains("401") == true) {
                    isAuthenticated = false
                    authFailures += 1
                    if (authFailures >= 3) {
                        onError("Authentication failed repeatedly")
                        onStatus("Authentication failed repeatedly")
                        return
                    }
                    onStatus("Session expired, re-authenticating…")
                    performLogin()
                } else {
                    onError("Unexpected error: ${e.message}")
                    onStatus("Error in poll loop: ${e.message}")
                    delay(5000)
                }
            }
        }
    }

    private suspend fun fetchQRToken(): ByteArray {
        val identifier = if (nfcEnabled && nfcUid.isNotBlank()) nfcUid else serialNumber
        if (identifier.isBlank()) {
            onStatus("No SNR yet - waiting")
            return ByteArray(0)
        }
        onStatus("[FETCH] Building token request…")
        val body = FormBody.Builder()
            .add("post[serialnumber]", identifier)
            .build()
        Log.d(TAG, "[TOKEN] identifier='${identifier}' len=${identifier.length} (nfc=$nfcEnabled)")
        if (body.size > 0) {
            Log.d(TAG, "[TOKEN] form body: ${body.name(0)}=${body.value(0)}")
        }
        
        val tokenUrl = "$sessionBaseUrl/cardapi/getQrToken"
        val httpUrl = tokenUrl.toHttpUrlOrNull()
        val matchedCookies = if (httpUrl != null) cookieJar.loadForRequest(httpUrl).size else 0
        onStatus("[TOKEN] URL=$tokenUrl, matched cookies=$matchedCookies/4")
        val cookieHeaderValue = cookieHeader(tokenUrl)
        val hasWpis = cookieHeaderValue.contains("WPIS=")
        onStatus("[TOKEN] Cookie header ${if (hasWpis) "contains" else "missing"} WPIS")
        Log.d(TAG, "[TOKEN] Cookie header contains WPIS=$hasWpis, len=${cookieHeaderValue.length}")
        
        val request = Request.Builder()
            .url(tokenUrl)
            .post(body)
            .addHeader("Accept", "*/*")
            .addHeader("Accept-Language", "en-US,en;q=0.9")
            .addHeader("Cache-Control", "no-cache")
            .addHeader("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .addHeader("Origin", sessionBaseUrl)
            .addHeader("Pragma", "no-cache")
            .addHeader("Referer", "$sessionBaseUrl/account")
            .addHeader("Sec-CH-UA", "\"Not(A:Brand\";v=\"8\", \"Chromium\";v=\"144\", \"Google Chrome\";v=\"144\"")
            .addHeader("Sec-CH-UA-Mobile", "?1")
            .addHeader("Sec-CH-UA-Platform", "\"Android\"")
            .addHeader("Sec-Fetch-Dest", "empty")
            .addHeader("Sec-Fetch-Mode", "cors")
            .addHeader("Sec-Fetch-Site", "same-origin")
            .addHeader("User-Agent", userAgent)
            .addHeader("Cookie", cookieHeaderValue)
            .apply {
                val csrf = getCsrfToken(tokenUrl)
                if (!csrf.isNullOrEmpty()) {
                    addHeader("X-XSRF-TOKEN", csrf)
                    addHeader("X-CSRF-TOKEN", csrf)
                }
            }
            .build()
        
        return try {
            onStatus("[TOKEN] Making HTTP request…")
            val response = try {
                client.newCall(request).execute()
            } catch (e: CancellationException) {
                onStatus("[TOKEN] Coroutine cancelled!")
                throw e
            } catch (e: Exception) {
                onStatus("[TOKEN] HTTP call failed: ${e.javaClass.simpleName}: ${e.message}")
                Log.e(TAG, "HTTP execute failed", e)
                throw e
            }
            
            onStatus("[TOKEN] HTTP call succeeded")
            response.use {
                onStatus("[TOKEN] Response received")
                val responseBody = try {
                    response.body?.string() ?: ""
                } catch (e: Exception) {
                    onStatus("[TOKEN] Body read failed: ${e.message}")
                    throw e
                }
                onStatus("[TOKEN] Body read: ${responseBody.length} chars")
                val finalUrl = response.request.url.toString()
                onStatus("[TOKEN] Got HTTP ${response.code}")
                
                // Handle 401 as a special error that needs re-authentication
                if (response.code == 401) {
                    Log.e(TAG, "Token 401 response body: $responseBody")
                    Log.e(TAG, "Token 401 response headers: ${response.headers}")
                    onStatus("Token 401 body: ${responseBody.take(200)}")
                    throw Exception("401 Unauthorized - Session expired")
                }
                
                if (finalUrl.contains("/account/login")) {
                    onStatus("Token redirect to login")
                    throw Exception("401 Unauthorized - Session expired")
                }
                
                if (response.code != 200) {
                    val snippet = responseBody.take(120).replace("\n", " ").replace("\r", " ")
                    onStatus("Token HTTP ${response.code}: $snippet")
                    return ByteArray(0)
                }
                
                try {
                    val json = gson.fromJson(responseBody, JsonObject::class.java)
                    val success = json.get("success")?.asBoolean ?: false
                    val data = json.get("data")?.asString ?: ""
                    val base64Field = json.get("base64")?.asString ?: ""
                    
                    Log.d(TAG, "Token response JSON: success=$success, data length=${data.length}")
                    onStatus("Token response: success=$success")
                    
                    if (!success || (data.isEmpty() && base64Field.isEmpty())) {
                        Log.w(TAG, "No token available: $responseBody")
                        onStatus("No token available - waiting")
                        return ByteArray(0)
                    }

                    val rawToken = if (base64Field.isNotBlank()) base64Field else data
                    val normalized = rawToken.replace(' ', '+')
                    val stripped = normalized.replace(Regex("[^A-Za-z0-9+/=_-]"), "")
                    val needsUrlSafe = stripped.contains('-') || stripped.contains('_')
                    val base = stripped.trim()
                    val padLen = (4 - (base.length % 4)) % 4
                    val padded = base + "=".repeat(padLen)
                    val flags = if (needsUrlSafe) Base64.URL_SAFE or Base64.NO_WRAP else Base64.NO_WRAP
                    val decoded = Base64.decode(padded, flags)
                    authFailures = 0
                    decoded
                    
                } catch (e: IllegalArgumentException) {
                    Log.e(TAG, "Invalid base64: $responseBody")
                    onStatus("Invalid token format")
                    return ByteArray(0)
                } catch (e: Exception) {
                    Log.e(TAG, "Token parse error: ${e.message}", e)
                    val snippet = responseBody.take(120).replace("\n", " ").replace("\r", " ")
                    onStatus("Token parse error: ${e.message}")
                    return ByteArray(0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Token request failed: ${e.message}", e)
            // Only re-throw for 401 errors to trigger re-authentication
            if (e.message?.contains("401") == true || e.message?.contains("Unauthorized") == true) {
                onStatus("Session expired: ${e.message}")
                throw e
            } else {
                // For other errors, log and return empty to continue polling
                onStatus("Token request error: ${e.message}")
                return ByteArray(0)
            }
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
