package com.example.testem

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

object EnvFileReader {
    fun loadEnvFile(context: Context, filename: String = "qr-demo.env"): Map<String, String> {
        val envMap = mutableMapOf<String, String>()
        
        try {
            val inputStream = context.assets.open(filename)
            val reader = BufferedReader(InputStreamReader(inputStream))
            
            reader.use { br ->
                br.forEachLine { line ->
                    // Skip empty lines and comments
                    if (line.isNotBlank() && !line.startsWith("#")) {
                        val parts = line.split("=", limit = 2)
                        if (parts.size == 2) {
                            val key = parts[0].trim()
                            val value = parts[1].trim()
                            if (value.isNotEmpty()) {
                                envMap[key] = value
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return envMap
    }
}
