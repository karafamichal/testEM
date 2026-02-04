package com.example.testem

import android.content.Context
import android.content.SharedPreferences

class CredentialsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("qr_daemon", Context.MODE_PRIVATE)
    
    fun saveCredentials(email: String, password: String, serialNumber: String) {
        prefs.edit().apply {
            putString("email", email)
            putString("password", password)
            putString("serial_number", serialNumber)
            putBoolean("is_configured", true)
            apply()
        }
    }
    
    fun getCredentials(): Triple<String, String, String> {
        val email = prefs.getString("email", "") ?: ""
        val password = prefs.getString("password", "") ?: ""
        val serial = prefs.getString("serial_number", "") ?: ""
        return Triple(email, password, serial)
    }
    
    fun isConfigured(): Boolean {
        return prefs.getBoolean("is_configured", false)
    }
    
    fun clearCredentials() {
        prefs.edit().apply {
            remove("email")
            remove("password")
            remove("serial_number")
            putBoolean("is_configured", false)
            apply()
        }
    }
}
