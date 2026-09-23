package com.ksjd.testem

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.security.MessageDigest
import java.security.SecureRandom

/** App preferences: login, security, appearance, reminders and saved places. */
class CredentialsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("qr_daemon", Context.MODE_PRIVATE)
    private val gson = Gson()

    // ------------------------------------------------------------------ login

    fun saveCredentials(email: String, password: String) {
        prefs.edit()
            .putString("email", email)
            .putString("password", password)
            .putBoolean("is_configured", true)
            // NFC mode was removed; drop its leftover identifier.
            .remove("nfc_uid")
            .apply()
    }

    fun getEmail(): String = prefs.getString("email", "").orEmpty()
    fun getPassword(): String = prefs.getString("password", "").orEmpty()
    fun isConfigured(): Boolean = prefs.getBoolean("is_configured", false)

    /** Serial number of the card whose ticket is shown. */
    fun getSelectedCardSnr(): String = prefs.getString("serial_number", "").orEmpty()
    fun saveSelectedCardSnr(snr: String) = prefs.edit().putString("serial_number", snr).apply()

    fun clearCredentials() {
        prefs.edit()
            .remove("email")
            .remove("password")
            .remove("serial_number")
            .remove("nfc_uid")
            .remove("last_account")
            .putBoolean("is_configured", false)
            .apply()
    }

    /** Last account data seen, so reminders and the UI have something while offline. */
    fun saveLastAccount(snapshot: AccountSnapshot) =
        prefs.edit().putString("last_account", gson.toJson(snapshot)).apply()

    fun getLastAccount(): AccountSnapshot? = runCatching {
        gson.fromJson(prefs.getString("last_account", null), AccountSnapshot::class.java)
    }.getOrNull()

    // ------------------------------------------------------------- appearance

    fun saveThemePresets(presets: List<ThemePreset>) {
        val encoded = presets.joinToString("||") { preset ->
            val nameEncoded = Base64.encodeToString(preset.name.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
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
            ThemePreset(
                id = id,
                name = name,
                primary = primaryHex.toLongOrNull(16) ?: return@mapNotNull null,
                secondary = secondaryHex.toLongOrNull(16) ?: return@mapNotNull null,
                tertiary = tertiaryHex.toLongOrNull(16) ?: return@mapNotNull null
            )
        }
        // Built-in presets always come first; keep the user's own ones after them.
        val custom = presets.filter { p -> defaultPresets.none { it.id == p.id } && p.id !in LEGACY_PRESET_IDS }
        return defaultPresets + custom
    }

    fun saveSelectedThemeId(id: String) = prefs.edit().putString("selected_theme_id", id).apply()
    fun getSelectedThemeId(defaultId: String): String =
        prefs.getString("selected_theme_id", defaultId)?.takeIf { it !in LEGACY_PRESET_IDS } ?: defaultId

    fun saveAmoledEnabled(enabled: Boolean) = prefs.edit().putBoolean("theme_amoled", enabled).apply()
    fun getAmoledEnabled(): Boolean = prefs.getBoolean("theme_amoled", false)

    fun saveLanguageCode(code: String) = prefs.edit().putString("language_code", code).apply()
    fun getLanguageCode(): String = prefs.getString("language_code", "sk") ?: "sk"

    // --------------------------------------------------------------- security

    fun isPinSet(): Boolean =
        prefs.getString("pin_hash", null) != null && prefs.getString("pin_salt", null) != null

    fun savePin(pin: String) {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        val hash = hashPin(pin, salt)
        prefs.edit()
            .putString("pin_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("pin_hash", Base64.encodeToString(hash, Base64.NO_WRAP))
            .apply()
    }

    fun verifyPin(pin: String): Boolean {
        val saltEncoded = prefs.getString("pin_salt", null) ?: return false
        val hashEncoded = prefs.getString("pin_hash", null) ?: return false
        val salt = Base64.decode(saltEncoded, Base64.NO_WRAP)
        val expected = Base64.decode(hashEncoded, Base64.NO_WRAP)
        return MessageDigest.isEqual(expected, hashPin(pin, salt))
    }

    fun saveBiometricEnabled(enabled: Boolean) = prefs.edit().putBoolean("biometric_enabled", enabled).apply()
    fun getBiometricEnabled(): Boolean = prefs.getBoolean("biometric_enabled", true)

    fun saveLockTimeoutSeconds(seconds: Int) = prefs.edit().putInt("lock_timeout_seconds", seconds).apply()
    fun getLockTimeoutSeconds(): Int = prefs.getInt("lock_timeout_seconds", 0)

    // -------------------------------------------------------------- reminders

    fun saveLowCreditWarningThreshold(threshold: Double) =
        prefs.edit().putString("low_credit_warning_threshold", threshold.toString()).apply()

    fun getLowCreditWarningThreshold(): Double =
        prefs.getString("low_credit_warning_threshold", null)?.toDoubleOrNull()?.takeIf { it >= 0.0 } ?: 1.0

    fun getLowCreditAlertsEnabled(): Boolean = prefs.getBoolean("alerts_low_credit", false)
    fun saveLowCreditAlertsEnabled(enabled: Boolean) = prefs.edit().putBoolean("alerts_low_credit", enabled).apply()

    fun getExpiryAlertsEnabled(): Boolean = prefs.getBoolean("alerts_expiry", false)
    fun saveExpiryAlertsEnabled(enabled: Boolean) = prefs.edit().putBoolean("alerts_expiry", enabled).apply()

    /** Returns true the first time a given alert key is seen, so each alert fires once. */
    fun markAlertSent(key: String): Boolean {
        val sent = prefs.getStringSet("alerts_sent", emptySet()).orEmpty()
        if (key in sent) return false
        prefs.edit().putStringSet("alerts_sent", (sent + key).toList().takeLast(50).toSet()).apply()
        return true
    }

    fun clearAlertsWithPrefix(prefix: String) {
        val sent = prefs.getStringSet("alerts_sent", emptySet()).orEmpty()
        prefs.edit().putStringSet("alerts_sent", sent.filterNot { it.startsWith(prefix) }.toSet()).apply()
    }

    // ------------------------------------------------------- saved places

    fun getFavouriteStopIds(): List<Int> =
        prefs.getString("favourite_stops", "").orEmpty().split(",").mapNotNull { it.trim().toIntOrNull() }

    fun saveFavouriteStopIds(ids: List<Int>) =
        prefs.edit().putString("favourite_stops", ids.joinToString(",")).apply()

    fun getSavedRoutes(): List<SavedRoute> = readRoutes("saved_routes")
    fun saveSavedRoutes(routes: List<SavedRoute>) = writeRoutes("saved_routes", routes)

    fun getRecentRoutes(): List<SavedRoute> = readRoutes("recent_routes")
    fun saveRecentRoutes(routes: List<SavedRoute>) = writeRoutes("recent_routes", routes)

    private fun readRoutes(key: String): List<SavedRoute> = runCatching {
        JsonParser.parseString(prefs.getString(key, "[]")).asJsonArray.map {
            gson.fromJson(it, SavedRoute::class.java)
        }.filter { it.fromText.isNotBlank() && it.toText.isNotBlank() }
    }.getOrDefault(emptyList())

    private fun writeRoutes(key: String, routes: List<SavedRoute>) =
        prefs.edit().putString(key, gson.toJson(routes)).apply()

    private fun hashPin(pin: String, salt: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(pin.toByteArray(Charsets.UTF_8))
        return digest.digest()
    }

    companion object {
        /** Built-in presets from before the redesign, replaced by the new defaults. */
        private val LEGACY_PRESET_IDS = setOf("classic")
    }
}
