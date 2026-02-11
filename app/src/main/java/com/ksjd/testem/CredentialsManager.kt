package com.ksjd.testem

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

class CredentialsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("qr_daemon", Context.MODE_PRIVATE)
    
    fun saveCredentials(
        email: String,
        password: String,
        serialNumber: String,
        nfcUid: String
    ) {
        prefs.edit().apply {
            putString("email", email)
            putString("password", password)
            putString("serial_number", serialNumber)
            putString("nfc_uid", nfcUid)
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

    
    fun isConfigured(): Boolean {
        return prefs.getBoolean("is_configured", false)
    }
    
    fun clearCredentials() {
        prefs.edit().apply {
            remove("email")
            remove("password")
            remove("serial_number")
            remove("nfc_uid")
            putBoolean("is_configured", false)
            apply()
        }
    }

    fun saveThemePresets(presets: List<ThemePreset>) {
        val encoded = presets.joinToString("||") { preset ->
            val nameEncoded = Base64.encodeToString(
                preset.name.toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
            val primary = preset.primary.toString(16).padStart(8, '0')
            val secondary = preset.secondary.toString(16).padStart(8, '0')
            val tertiary = preset.tertiary.toString(16).padStart(8, '0')
            "${preset.id}::${nameEncoded}::${primary}::${secondary}::${tertiary}"
        }
        prefs.edit().putString("theme_presets", encoded).apply()
    }

    fun getThemePresets(defaultPresets: List<ThemePreset>): List<ThemePreset> {
        val stored = prefs.getString("theme_presets", null) ?: return defaultPresets
        if (stored.isBlank()) return defaultPresets

        val presets = stored.split("||").mapNotNull { entry ->
            val parts = entry.split("::")
            if (parts.size != 5) return@mapNotNull null
            val (id, nameEncoded, primaryHex, secondaryHex, tertiaryHex) = parts
            val name = runCatching {
                String(Base64.decode(nameEncoded, Base64.NO_WRAP), Charsets.UTF_8)
            }.getOrNull().orEmpty()
            if (name.isBlank()) return@mapNotNull null

            val primary = primaryHex.toLongOrNull(16) ?: return@mapNotNull null
            val secondary = secondaryHex.toLongOrNull(16) ?: return@mapNotNull null
            val tertiary = tertiaryHex.toLongOrNull(16) ?: return@mapNotNull null
            ThemePreset(id = id, name = name, primary = primary, secondary = secondary, tertiary = tertiary)
        }

        return if (presets.isEmpty()) defaultPresets else presets
    }

    fun saveSelectedThemeId(id: String) {
        prefs.edit().putString("selected_theme_id", id).apply()
    }

    fun getSelectedThemeId(defaultId: String): String {
        return prefs.getString("selected_theme_id", defaultId) ?: defaultId
    }

    fun saveAmoledEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("theme_amoled", enabled).apply()
    }

    fun getAmoledEnabled(): Boolean {
        return prefs.getBoolean("theme_amoled", false)
    }

    fun saveLayoutOrder(order: List<String>) {
        prefs.edit().putString("layout_order", order.joinToString("||")).apply()
    }

    fun getLayoutOrder(defaultOrder: List<String>): List<String> {
        val stored = prefs.getString("layout_order", null) ?: return defaultOrder
        val order = stored.split("||").map { it.trim() }.filter { it.isNotEmpty() }
        return if (order.isEmpty()) defaultOrder else order
    }

    fun saveHiddenSections(hidden: Set<String>) {
        val stored = hidden.joinToString("||")
        prefs.edit().putString("layout_hidden", stored).apply()
    }

    fun getHiddenSections(): Set<String> {
        val stored = prefs.getString("layout_hidden", null) ?: return emptySet()
        return stored.split("||").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun isPinSet(): Boolean {
        return prefs.getString("pin_hash", null) != null && prefs.getString("pin_salt", null) != null
    }

    fun savePin(pin: String) {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        val hash = hashPin(pin, salt)
        prefs.edit().apply {
            putString("pin_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            putString("pin_hash", Base64.encodeToString(hash, Base64.NO_WRAP))
            apply()
        }
    }

    fun verifyPin(pin: String): Boolean {
        val saltEncoded = prefs.getString("pin_salt", null) ?: return false
        val hashEncoded = prefs.getString("pin_hash", null) ?: return false
        val salt = Base64.decode(saltEncoded, Base64.NO_WRAP)
        val expected = Base64.decode(hashEncoded, Base64.NO_WRAP)
        val actual = hashPin(pin, salt)
        return expected.contentEquals(actual)
    }

    fun saveBiometricEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("biometric_enabled", enabled).apply()
    }

    fun getBiometricEnabled(): Boolean {
        return prefs.getBoolean("biometric_enabled", true)
    }

    fun saveLockTimeoutSeconds(seconds: Int) {
        prefs.edit().putInt("lock_timeout_seconds", seconds).apply()
    }

    fun getLockTimeoutSeconds(): Int {
        return prefs.getInt("lock_timeout_seconds", 0)
    }

    fun saveLanguageCode(code: String) {
        prefs.edit().putString("language_code", code).apply()
    }

    fun getLanguageCode(): String {
        return prefs.getString("language_code", "sk") ?: "sk"
    }

    private fun hashPin(pin: String, salt: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(pin.toByteArray(Charsets.UTF_8))
        return digest.digest()
    }
}
