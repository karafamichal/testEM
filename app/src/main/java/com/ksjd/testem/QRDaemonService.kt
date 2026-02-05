package com.ksjd.testem

import android.util.Log
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import okhttp3.FormBody
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.CookieManager
import java.net.CookiePolicy
import kotlin.coroutines.coroutineContext

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
    private val onError: (String) -> Unit
) {
    private val cookieManager = CookieManager().apply {
        setCookiePolicy(CookiePolicy.ACCEPT_ALL)
    }
    private val client = OkHttpClient.Builder()
        .cookieJar(JavaNetCookieJar(cookieManager))
        .build()
    private val gson = Gson()
    private val TAG = "QRDaemon"
    
    private var lastTokenHex: String? = null
    private var pollingJob: Job? = null
    private var isAuthenticated = false

    fun startPolling() {
        pollingJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                // Check if already authenticated
                if (!isAuthenticated) {
                    performLogin()
                }
                
                // Start token polling
                pollTokens()
            } catch (e: Exception) {
                Log.e(TAG, "Polling error: ${e.message}", e)
                onError("Polling error: ${e.message}")
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
            
            // Step 1: Navigate to base URL
            val baseRequest = Request.Builder()
                .url(baseUrl)
                .get()
                .build()
            
            client.newCall(baseRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Failed to reach base URL: ${response.code}")
                }
            }
            
            delay(1000)
            
            // Step 2: Navigate to account page
            val accountRequest = Request.Builder()
                .url("$baseUrl/account")
                .get()
                .build()
            
            client.newCall(accountRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Failed to reach account: ${response.code}")
                }
            }
            
            delay(1000)
            
            // Step 3: Post login credentials
            val loginBody = FormBody.Builder()
                .add("login", username)
                .add("password", password)
                .build()
            
            val loginRequest = Request.Builder()
                .url("$baseUrl/account/login")
                .post(loginBody)
                .build()
            
            client.newCall(loginRequest).execute().use { response ->
                when {
                    response.code == 401 -> throw Exception("Invalid credentials")
                    response.code == 200 -> {
                        Log.d(TAG, "Login successful")
                        isAuthenticated = true
                    }
                    else -> throw Exception("Login failed: ${response.code}")
                }
            }
            
            delay(2000)
            
        } catch (e: Exception) {
            Log.e(TAG, "Login failed: ${e.message}", e)
            onError("Login failed: ${e.message}")
            throw e
        }
    }

    private suspend fun pollTokens() {
        Log.d(TAG, "Starting token polling…")
        
        while (coroutineContext.isActive) {
            try {
                val tokenBytes = fetchQRToken()
                
                // Validate token length (should be 57 bytes)
                if (tokenBytes.size != 57) {
                    Log.w(TAG, "Invalid token length: ${tokenBytes.size}")
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
                }
                
                // Poll every 25 seconds (observed rotation interval)
                delay(25000)
                
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Token fetch error: ${e.message}", e)
                
                // If 401, re-authenticate
                if (e.message?.contains("401") == true) {
                    isAuthenticated = false
                    performLogin()
                } else {
                    onError("Token fetch error: ${e.message}")
                    delay(5000)
                }
            }
        }
    }

    private suspend fun fetchQRToken(): ByteArray {
        val body = FormBody.Builder()
            .add("post[serialnumber]", serialNumber)
            .build()
        
        val request = Request.Builder()
            .url("$baseUrl/cardapi/getQrToken")
            .post(body)
            .addHeader("Accept", "*/*")
            .addHeader("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .build()
        
        return client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            
            when {
                response.code == 401 -> throw Exception("401 Unauthorized - Session expired")
                response.code != 200 -> throw Exception("HTTP ${response.code}")
                else -> {
                    try {
                        val json = gson.fromJson(responseBody, JsonObject::class.java)
                        val success = json.get("success")?.asBoolean ?: false
                        val data = json.get("data")?.asString ?: ""
                        
                        if (!success || data.isEmpty()) {
                            Log.w(TAG, "No token available: $responseBody")
                            throw Exception("No token available")
                        }
                        
                        Base64.decode(data.trim(), Base64.DEFAULT)
                        
                    } catch (e: IllegalArgumentException) {
                        Log.e(TAG, "Invalid base64: $responseBody")
                        throw Exception("Invalid token format")
                    }
                }
            }
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
