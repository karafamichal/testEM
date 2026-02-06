package com.ksjd.testem

import android.content.Context
import android.content.SharedPreferences

class CredentialsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("qr_daemon", Context.MODE_PRIVATE)
    
    fun saveCredentials(
        email: String,
        password: String,
        serialNumber: String,
        nfcUid: String,
        nfcEnabled: Boolean
    ) {
        prefs.edit().apply {
            putString("email", email)
            putString("password", password)
            putString("serial_number", serialNumber)
            putString("nfc_uid", nfcUid)
            putBoolean("nfc_enabled", nfcEnabled)
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

    fun getNfcUid(): String {
        return prefs.getString("nfc_uid", "") ?: ""
    }

    fun getNfcEnabled(): Boolean {
        return prefs.getBoolean("nfc_enabled", false)
    }
    
    fun isConfigured(): Boolean {
        return prefs.getBoolean("is_configured", false)
    }
    
    fun clearCredentials() {
        prefs.edit().apply {
            remove("email")
            remove("password")
            remove("serial_number")
            remove("nfc_uid")
            remove("nfc_enabled")
            putBoolean("is_configured", false)
            apply()
        }
    }
}
