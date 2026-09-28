package com.ksjd.testem

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.security.MessageDigest
import java.security.SecureRandom

const val MAX_ARRIVAL_ALERT_MINUTES = 60
const val COMMUNITY_OFF = "off"
const val COMMUNITY_BUTTONS = "buttons"
const val COMMUNITY_AUTO = "auto"

/** App preferences: login, security, appearance, reminders and saved places. */
class CredentialsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("qr_daemon", Context.MODE_PRIVATE)
    private val gson = Gson()

    // ------------------------------------------------------------------ login

    /** The password is stored encrypted with a Keystore key (see [SecretBox]), never in plain text. */
    fun saveCredentials(email: String, password: String) {
        prefs.edit()
            .putString("email", email)
            .putString("password_enc", if (password.isEmpty()) "" else SecretBox.encrypt(password))
            .remove("password")
            .putBoolean("is_configured", password.isNotEmpty())
            // NFC mode was removed; drop its leftover identifier.
            .remove("nfc_uid")
            .apply()
    }

    fun getEmail(): String = prefs.getString("email", "").orEmpty()

    fun getPassword(): String {
        // Before 2.6 the password was kept in plain text: encrypt it on first read.
        prefs.getString("password", null)?.let { legacy ->
            saveCredentials(getEmail(), legacy)
            return legacy
        }
        val sealed = prefs.getString("password_enc", "").orEmpty()
        return if (sealed.isEmpty()) "" else SecretBox.decrypt(sealed).orEmpty()
    }

    fun isConfigured(): Boolean = prefs.getBoolean("is_configured", false)

    /** A SADZV account is saved on this phone (the app works without one). */
    fun hasAccount(): Boolean = getEmail().isNotBlank() && getPassword().isNotEmpty()

    /** The welcome screen (add an account or skip) was answered. */
    fun isOnboarded(): Boolean = prefs.getBoolean("onboarded", false) || isConfigured() || isPinSet()
    fun markOnboarded() = prefs.edit().putBoolean("onboarded", true).apply()

    /** Serial number of the card whose ticket is shown. */
    fun getSelectedCardSnr(): String = prefs.getString("serial_number", "").orEmpty()
    fun saveSelectedCardSnr(snr: String) = prefs.edit().putString("serial_number", snr).apply()

    fun clearCredentials() {
        prefs.edit()
            .remove("email")
            .remove("password")
            .remove("password_enc")
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
        prefs.edit()
            .putString("pin_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("pin_hash", Base64.encodeToString(pbkdf2(pin, salt), Base64.NO_WRAP))
            .putString("pin_kdf", PIN_KDF)
            .remove("pin_failures")
            .remove("pin_locked_until")
            .apply()
    }

    fun clearPin() {
        prefs.edit().remove("pin_salt").remove("pin_hash").remove("pin_kdf")
            .remove("pin_failures").remove("pin_locked_until").apply()
    }

    /** Wrong PINs lock entry for a while (30 s, doubling up to an hour), so a PIN can't be guessed quickly. */
    fun pinLockedUntil(): Long = prefs.getLong("pin_locked_until", 0L)

    fun verifyPin(pin: String): Boolean {
        if (System.currentTimeMillis() < pinLockedUntil()) return false
        val saltEncoded = prefs.getString("pin_salt", null) ?: return false
        val hashEncoded = prefs.getString("pin_hash", null) ?: return false
        val salt = Base64.decode(saltEncoded, Base64.NO_WRAP)
        val expected = Base64.decode(hashEncoded, Base64.NO_WRAP)
        val legacy = prefs.getString("pin_kdf", null) == null
        val ok = MessageDigest.isEqual(expected, if (legacy) sha256Pin(pin, salt) else pbkdf2(pin, salt))
        if (ok) {
            // PINs saved before 2.6 used one round of SHA-256: move them to the slow hash.
            if (legacy) savePin(pin) else prefs.edit().remove("pin_failures").remove("pin_locked_until").apply()
        } else {
            val failures = prefs.getInt("pin_failures", 0) + 1
            val lock = if (failures < PIN_FREE_TRIES) 0L
            else minOf(30_000L shl (failures - PIN_FREE_TRIES).coerceAtMost(7), 60 * 60_000L)
            prefs.edit().putInt("pin_failures", failures)
                .putLong("pin_locked_until", if (lock > 0) System.currentTimeMillis() + lock else 0L).apply()
        }
        return ok
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

    // ------------------------------------------------------- following a bus

    /** How many minutes before the bus reaches the user's stop to alert them (1–60). */
    fun getArrivalAlertMinutes(): Int = prefs.getInt("arrival_alert_minutes", 2).coerceIn(1, MAX_ARRIVAL_ALERT_MINUTES)
    fun saveArrivalAlertMinutes(minutes: Int) =
        prefs.edit().putInt("arrival_alert_minutes", minutes.coerceIn(1, MAX_ARRIVAL_ALERT_MINUTES)).apply()

    // ------------------------------------------------------- opt-in features

    /** Asked once on first run; both features stay off until the user says yes. */
    fun wasPrivacyAsked(): Boolean = prefs.getBoolean("privacy_asked", false)
    fun markPrivacyAsked() = prefs.edit().putBoolean("privacy_asked", true).apply()

    /**
     * Helping with bus positions: [COMMUNITY_OFF], [COMMUNITY_BUTTONS] (tap where the bus is)
     * or [COMMUNITY_AUTO] (the phone's position marks it while riding; nothing but the stop is sent).
     */
    fun getCommunityMode(): String = prefs.getString("community_mode", null)
        ?: if (prefs.getBoolean("community_enabled", false)) COMMUNITY_BUTTONS else COMMUNITY_OFF
    fun saveCommunityMode(mode: String) = prefs.edit().putString("community_mode", mode).apply()

    /** Community data is fetched in either mode (the request reveals which bus is followed). */
    fun getCommunityEnabled(): Boolean = getCommunityMode() != COMMUNITY_OFF

    /** Use the phone's location (on the phone only) to say whether the user will catch the bus. */
    /** Opt-in: after an update, show what changed once (see WhatsNewDialog). */
    fun getWhatsNewEnabled(): Boolean = prefs.getBoolean("whats_new_enabled", false)
    fun saveWhatsNewEnabled(enabled: Boolean) = prefs.edit().putBoolean("whats_new_enabled", enabled).apply()
    fun getWhatsNewSeenVersion(): Int = prefs.getInt("whats_new_seen", 0)
    fun saveWhatsNewSeenVersion(versionCode: Int) = prefs.edit().putInt("whats_new_seen", versionCode).apply()

    /** Anonymous usage statistics (Firebase Analytics); on unless the user turns them off. */
    fun getAnalyticsEnabled(): Boolean = prefs.getBoolean("analytics_enabled", true)
    fun saveAnalyticsEnabled(enabled: Boolean) = prefs.edit().putBoolean("analytics_enabled", enabled).apply()

    fun getCatchEnabled(): Boolean = prefs.getBoolean("catch_enabled", false)
    fun saveCatchEnabled(enabled: Boolean) = prefs.edit().putBoolean("catch_enabled", enabled).apply()

    /** Alert on timetable times too, not only when the bus's real position is known. */
    /** No live data: shift the timetable by how late the trip usually is (else only a hint). */
    fun getPredictShift(): Boolean = prefs.getBoolean("predict_shift", false)
    fun savePredictShift(enabled: Boolean) = prefs.edit().putBoolean("predict_shift", enabled).apply()

    fun getCatchTimetableAlerts(): Boolean = prefs.getBoolean("catch_timetable_alerts", false)
    fun saveCatchTimetableAlerts(enabled: Boolean) = prefs.edit().putBoolean("catch_timetable_alerts", enabled).apply()

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

    fun getSavedJourneys(): List<SavedJourney> = readList("saved_journeys", SavedJourney::class.java)
        .filter { runCatching { it.connection.segments.isNotEmpty() && it.route.fromText.isNotBlank() }.getOrDefault(false) }
    fun saveSavedJourneys(journeys: List<SavedJourney>) = prefs.edit().putString("saved_journeys", gson.toJson(journeys)).apply()

    fun getRecurringRides(): List<RecurringRide> = readList("recurring_rides", RecurringRide::class.java)
        .filter { runCatching { it.route.fromText.isNotBlank() && it.route.toText.isNotBlank() && it.days.size >= 0 && it.dates.size >= 0 }.getOrDefault(false) }
    fun saveRecurringRides(rides: List<RecurringRide>) = prefs.edit().putString("recurring_rides", gson.toJson(rides)).apply()

    /** Rides with an alarm set, so deleted ones can be cancelled. */
    fun getScheduledRideIds(): List<String> = prefs.getString("rides_scheduled", "").orEmpty().split(",").filter { it.isNotBlank() }
    fun saveScheduledRideIds(ids: List<String>) = prefs.edit().putString("rides_scheduled", ids.joinToString(",")).apply()

    /** Departure (epoch ms) of the last occurrence of a ride that fired, so it doesn't fire twice. */
    fun getRideFiredAt(id: String): Long = prefs.getLong("ride_fired_$id", 0L)
    fun saveRideFiredAt(id: String, departureMs: Long) = prefs.edit().putLong("ride_fired_$id", departureMs).apply()

    private fun <T> readList(key: String, type: Class<T>): List<T> = runCatching {
        JsonParser.parseString(prefs.getString(key, "[]")).asJsonArray.mapNotNull { runCatching { gson.fromJson(it, type) }.getOrNull() }
    }.getOrDefault(emptyList())

    private fun sha256Pin(pin: String, salt: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(pin.toByteArray(Charsets.UTF_8))
        return digest.digest()
    }

    private fun pbkdf2(pin: String, salt: ByteArray): ByteArray =
        javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, PIN_ITERATIONS, 256))
            .encoded

    companion object {
        private const val PIN_ITERATIONS = 120_000
        private const val PIN_KDF = "pbkdf2-sha256-$PIN_ITERATIONS"
        /** Wrong PINs allowed before entry locks for a while. */
        private const val PIN_FREE_TRIES = 5
        /** Built-in presets from before the redesign, replaced by the new defaults. */
        private val LEGACY_PRESET_IDS = setOf("classic")
    }
}
