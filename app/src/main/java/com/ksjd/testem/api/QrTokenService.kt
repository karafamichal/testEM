package com.ksjd.testem.api

import okhttp3.OkHttpClient
import okhttp3.FormBody
import okhttp3.Request
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.ksjd.testem.NetworkClient
import java.io.IOException

data class QrTokenResponse(
    val success: Boolean,
    val data: String?
)

class QrTokenService(
    private val baseUrl: String = "https://sadzv.qrbus.me",
    private val httpClient: OkHttpClient = NetworkClient.base
) {
    private val gson = Gson()
    private val tag = "QrTokenService"

    suspend fun getQrToken(serialNumber: String): Result<ByteArray> {
        return try {
            val body = FormBody.Builder()
                .add("post[serialnumber]", serialNumber)
                .build()

            val request = Request.Builder()
                .url("$baseUrl/cardapi/getQrToken")
                .post(body)
                .header("accept", "*/*")
                .header("content-type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("x-requested-with", "XMLHttpRequest")
                .build()

            val response = httpClient.newCall(request).execute()

            when {
                response.code == 401 -> {
                    Result.failure(Exception("Session expired (401 Unauthorized)"))
                }
                !response.isSuccessful -> {
                    Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
                }
                else -> {
                    val responseBody = response.body?.string()
                        ?: return Result.failure(Exception("Empty response body"))

                    val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)
                    val success = jsonResponse.get("success").asBoolean
                    val data = jsonResponse.get("data")?.asString

                    when {
                        !success || data.isNullOrEmpty() -> {
                            Result.failure(Exception("Token unavailable"))
                        }
                        else -> {
                            val bytes = b64ToBytes(data)
                            if (bytes.size != 57) {
                                Result.failure(Exception("Invalid token length: ${bytes.size}"))
                            } else {
                                Result.success(bytes)
                            }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(tag, "Network error: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(tag, "Error: ${e.message}")
            Result.failure(e)
        }
    }

    private fun b64ToBytes(b64: String): ByteArray {
        val clean = b64.replace("\\s+".toRegex(), "")
        return android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
    }

    fun hexToString(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
