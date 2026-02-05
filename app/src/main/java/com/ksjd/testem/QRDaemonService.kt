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
            cookieStore.removeAll { existing ->
                cookies.any { incoming ->
                    incoming.name == existing.name &&
                        incoming.domain == existing.domain &&
                        incoming.path == existing.path
                }
            }
            cookieStore.addAll(cookies)
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return cookieStore.filter { it.matches(url) }
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

    fun startPolling() {
        pollingJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                onStatus("Starting polling…")
                // Check if already authenticated
                if (!isAuthenticated) {
                    performLogin()
                }
                
                // Start token polling
                pollTokens()
            } catch (e: Exception) {
                Log.e(TAG, "Polling error: ${e.message}", e)
                onError("Polling error: ${e.message}")
                onStatus("Polling error: ${e.message}")
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
                .add("email", username)
                .add("password", password)
                .build()
            
            val loginRequest = Request.Builder()
                .url("$sessionBaseUrl/account/login")
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
                val responseUrl = response.request.url.toString()
                Log.d(TAG, "Login response: ${response.code} -> $responseUrl")
                
                when {
                    response.code == 401 -> throw Exception("Invalid credentials")
                    responseUrl.contains("/account/login") -> {
                        throw Exception("Login redirect - credentials rejected")
                    }
                    response.code == 200 || response.code == 302 || response.code == 301 -> {
                        val tokenUrl = "$sessionBaseUrl/cardapi/getQrToken".toHttpUrlOrNull() ?: return@use
                        val allCookies = cookieJar.loadForRequest(tokenUrl)
                        Log.d(TAG, "Login successful. Cookies for token endpoint: ${allCookies.size}")
                        allCookies.forEach { cookie ->
                            Log.d(TAG, "  Cookie: ${cookie.name}=${cookie.value} (domain=${cookie.domain}, path=${cookie.path})")
                        }
                        
                        authFailures = 0
                        onStatus("Login successful (cookies: ${allCookies.size})")
                        isAuthenticated = true
                    }
                    else -> throw Exception("Login failed: ${response.code}")
                }
            }
            
            delay(2000)
            
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
            onStatus("Fetching token…")
                val tokenBytes = fetchQRToken()
                
                if (tokenBytes.isEmpty()) {
                    Log.w(TAG, "Empty token payload")
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
                Log.e(TAG, "Token fetch error: ${e.message}", e)
                
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
                    onError("Token fetch error: ${e.message}")
                    onStatus("Token fetch error: ${e.message}")
                    delay(5000)
                }
            }
        }
    }

    private suspend fun fetchQRToken(): ByteArray {
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
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                val finalUrl = response.request.url.toString()
                onStatus("Token response: HTTP ${response.code}")
                
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
                    
                    if (!success || data.isEmpty()) {
                        Log.w(TAG, "No token available: $responseBody")
                        onStatus("No token available")
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
                    val snippet = responseBody.take(120).replace("\n", " ").replace("\r", " ")
                    onStatus("Token parse error: ${e.message}. Body: $snippet")
                    return ByteArray(0)
                }
            }
        } catch (e: Exception) {
            onStatus("Token request failed: ${e.message}")
            throw e  // Re-throw to let pollTokens() handle 401
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
