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
    private val serialNumber: String,
    private val onTokenUpdate: (String, String) -> Unit,
    private val onError: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val userAgent = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
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
            
            Log.d(TAG, "Total cookies in store: ${cookieStore.size}")
            cookieStore.forEach { cookie ->
                Log.d(TAG, "  Stored: ${cookie.name} (domain=${cookie.domain}, path=${cookie.path})")
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val matching = cookieStore.filter { it.matches(url) }
            Log.d(TAG, "loadForRequest: url=$url, matched ${matching.size}/${cookieStore.size} cookies")
            return matching
        }
    }

    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .build()

    private fun cookieHeader(url: String): String {
        val httpUrl = url.toHttpUrlOrNull() ?: return ""
        val cookies = cookieJar.loadForRequest(httpUrl)
        return cookies.joinToString("; ") { "${it.name}=${it.value}" }
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
    private var pollingJob: Job? = null
    private var isAuthenticated = false
    private var authFailures = 0
    private var isPolling = false

    fun startPolling() {
        if (isPolling) {
            onStatus("[WARNING] Polling already running - ignoring duplicate call")
            Log.w(TAG, "startPolling() called while already polling - ignoring")
            return
        }
        
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
            } catch (e: Exception) {
                Log.e(TAG, "startPolling caught exception: ${e.javaClass.simpleName}: ${e.message}", e)
                onError("Fatal error: ${e.message}")
                onStatus("Fatal error - restarting in 5s: ${e.message}")
                delay(5000)
                startPolling()
            }
        }
    }

    fun stopPolling() {
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
                .addHeader("User-Agent", userAgent)
                .addHeader("Origin", sessionBaseUrl)
                .addHeader("Referer", "$sessionBaseUrl/account")
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
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
                    
                    val tokenUrl = "$sessionBaseUrl/cardapi/getQrToken".toHttpUrlOrNull() ?: return@use
                    val allCookies = cookieJar.loadForRequest(tokenUrl)
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
        onStatus("[FETCH] Building token request…")
        val body = FormBody.Builder()
            .add("post[serialnumber]", serialNumber)
            .build()
        
        val tokenUrl = "$sessionBaseUrl/cardapi/getQrToken"
        val cookieHeaderValue = cookieHeader(tokenUrl)
        if (cookieHeaderValue.isEmpty()) {
            onStatus("Token request: no cookies")
        } else {
            onStatus("Token request cookies: ${cookieHeaderValue.split(';').size}")
        }
        val request = Request.Builder()
            .url(tokenUrl)
            .post(body)
            .addHeader("Accept", "*/*")
            .addHeader("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .addHeader("Origin", sessionBaseUrl)
            .addHeader("Referer", "$sessionBaseUrl/account")
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
                    
                    Log.d(TAG, "Token response JSON: success=$success, data length=${data.length}")
                    onStatus("Token response: success=$success")
                    
                    if (!success || data.isEmpty()) {
                        Log.w(TAG, "No token available: $responseBody")
                        onStatus("No token available - waiting")
                        return ByteArray(0)
                    }
                    
                    val decoded = Base64.decode(data.trim(), Base64.DEFAULT)
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
