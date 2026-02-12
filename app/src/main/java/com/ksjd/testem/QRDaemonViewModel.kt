package com.ksjd.testem

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

data class QRState(
    val isPolling: Boolean = false,
    val qrBitmap: Bitmap? = null,
    val tokenHex: String = "",
    val tokenBase64: String = "",
    val userName: String = "",
    val accountDetails: AccountDetails = AccountDetails(),
    val errorMessage: String = "",
    val statusMessage: String = "",
    val lastUpdateTime: Long = 0
)

data class AppState(
    val isLoggedIn: Boolean = false,
    val isLoading: Boolean = false,
    val loginError: String = "",
    val email: String = "",
    val password: String = "",
    val serialNumber: String = "",
    val nfcUid: String = "",
    val nfcEnabled: Boolean = false,
    val themePresets: List<ThemePreset> = emptyList(),
    val selectedThemeId: String = "",
    val amoledEnabled: Boolean = false,
    val layoutOrder: List<String> = emptyList(),
    val hiddenSections: Set<String> = emptySet(),
    val isPinSet: Boolean = false,
    val isAppUnlocked: Boolean = false,
    val biometricEnabled: Boolean = true,
    val lockTimeoutSeconds: Int = 0,
    val languageCode: String = "sk"
)

class QRDaemonViewModel : ViewModel() {
    private val _qrState = MutableStateFlow(QRState())
    val qrState: StateFlow<QRState> = _qrState
    
    private val _appState = MutableStateFlow(AppState())
    val appState: StateFlow<AppState> = _appState
    
    private var qrService: QRDaemonService? = null
    private var credentialsManager: CredentialsManager? = null
    private var lastBackgroundTimeMs: Long = 0
    private var appContext: Context? = null

    private val defaultThemePresets = listOf(
        ThemePreset(
            id = "classic",
            name = "Classic",
            primary = 0xFF6650A4,
            secondary = 0xFF625B71,
            tertiary = 0xFF7D5260
        ),
        ThemePreset(
            id = "ocean",
            name = "Ocean",
            primary = 0xFF136F63,
            secondary = 0xFF0B4F6C,
            tertiary = 0xFF177E89
        ),
        ThemePreset(
            id = "sunset",
            name = "Sunset",
            primary = 0xFFE85D04,
            secondary = 0xFFDC2F02,
            tertiary = 0xFF6A040F
        )
    )

    private val defaultLayoutOrder = listOf(
        "status",
        "qr",
        "nfc",
        "controls",
        "error"
    )

    private val defaultHiddenSections = setOf("error", "nfc")

    init {
        _appState.value = _appState.value.copy(
            themePresets = defaultThemePresets,
            selectedThemeId = defaultThemePresets.first().id,
            layoutOrder = defaultLayoutOrder,
            hiddenSections = defaultHiddenSections
        )
    }

    private fun escapeVCard(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("\n", "\\n")
    }

    private fun buildNfcVCard(uid: String): String {
        val safeUid = escapeVCard(uid)
        return "BEGIN:VCARD\r\n" +
            "VERSION:3.0\r\n" +
            "FN:NFC UID\r\n" +
            "NOTE:NFC_UID=$safeUid\r\n" +
            "END:VCARD"
    }

    private fun decodeTemplateBitmap(base64: String): Bitmap? {
        return try {
            val cleaned = base64.replace(Regex("[^A-Za-z0-9+/=_-]"), "")
            val needsUrlSafe = cleaned.contains('-') || cleaned.contains('_')
            val padLen = (4 - (cleaned.length % 4)) % 4
            val padded = cleaned + "=".repeat(padLen)
            val flags = if (needsUrlSafe) Base64.URL_SAFE or Base64.NO_WRAP else Base64.NO_WRAP
            val bytes = Base64.decode(padded, flags)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }

    fun loadSavedCredentials(context: Context) {
        appContext = context.applicationContext
        val manager = CredentialsManager(context)
        credentialsManager = manager
        loadThemeSettings(context)
        loadLayoutSettings(context)
        loadSecuritySettings(context)
        loadLanguageSettings(context)
        if (_qrState.value.statusMessage.isBlank()) {
            _qrState.value = _qrState.value.copy(
                statusMessage = getString(
                    R.string.status_ready,
                    "Ready"
                )
            )
        }
        if (!manager.isConfigured()) return

        val (email, password, serialNumber) = manager.getCredentials()
        val nfcUid = manager.getNfcUid()
        _appState.value = _appState.value.copy(
            email = email,
            password = password,
            serialNumber = serialNumber,
            nfcUid = nfcUid,
            nfcEnabled = false,
            loginError = ""
        )
    }

    fun loadThemeSettings(context: Context) {
        val manager = credentialsManager ?: CredentialsManager(context)
        credentialsManager = manager
        val presets = manager.getThemePresets(defaultThemePresets)
        val selectedId = manager.getSelectedThemeId(presets.first().id)
        val amoledEnabled = manager.getAmoledEnabled()
        _appState.value = _appState.value.copy(
            themePresets = presets,
            selectedThemeId = selectedId,
            amoledEnabled = amoledEnabled
        )
    }

    fun setAmoledEnabled(context: Context, enabled: Boolean) {
        if (_appState.value.amoledEnabled == enabled) return
        _appState.value = _appState.value.copy(amoledEnabled = enabled)
        val manager = credentialsManager ?: CredentialsManager(context)
        credentialsManager = manager
        manager.saveAmoledEnabled(enabled)
    }

    fun loadLayoutSettings(context: Context) {
        val manager = credentialsManager ?: CredentialsManager(context)
        credentialsManager = manager
        val order = manager.getLayoutOrder(defaultLayoutOrder)
        val storedHidden = manager.getHiddenSections()
        val hidden = if (storedHidden.isEmpty()) {
            manager.saveHiddenSections(defaultHiddenSections)
            defaultHiddenSections
        } else {
            storedHidden
        }
        _appState.value = _appState.value.copy(
            layoutOrder = order,
            hiddenSections = hidden
        )
    }

    fun setSectionHidden(context: Context, id: String, hidden: Boolean) {
        val current = _appState.value.hiddenSections
        val updated = if (hidden) current + id else current - id
        _appState.value = _appState.value.copy(hiddenSections = updated)
        val manager = credentialsManager ?: CredentialsManager(context)
        credentialsManager = manager
        manager.saveHiddenSections(updated)
    }

    fun loadSecuritySettings(context: Context) {
        val manager = credentialsManager ?: CredentialsManager(context)
        credentialsManager = manager
        val pinSet = manager.isPinSet()
        val biometricEnabled = manager.getBiometricEnabled()
        val lockTimeout = manager.getLockTimeoutSeconds()
        _appState.value = _appState.value.copy(
            isPinSet = pinSet,
            isAppUnlocked = !pinSet,
            biometricEnabled = biometricEnabled,
            lockTimeoutSeconds = lockTimeout
        )
    }

    fun loadLanguageSettings(context: Context) {
        val manager = credentialsManager ?: CredentialsManager(context)
        credentialsManager = manager
        val languageCode = manager.getLanguageCode()
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(languageCode)
        )
        _appState.value = _appState.value.copy(languageCode = languageCode)
    }

    private fun getString(resId: Int, fallback: String, vararg args: Any): String {
        val ctx = appContext ?: return if (args.isNotEmpty()) {
            String.format(Locale.getDefault(), fallback, *args)
        } else {
            fallback
        }
        return if (args.isNotEmpty()) {
            ctx.getString(resId, *args)
        } else {
            ctx.getString(resId)
        }
    }

    private fun localizeStatus(message: String): String {
        val ctx = appContext ?: return message
        val pollingErrorRestartPrefix = "Polling error - restarting in 5s: "
        val loginSuccessPrefix = "Login successful (cookies: "
        val getUidHttpPrefix = "getUId HTTP "
        val getAccountDetailHttpPrefix = "getAccountDetail HTTP "
        return when {
            message == "NFC mode enabled" -> ctx.getString(R.string.status_nfc_mode_enabled)
            message == "NFC mode disabled" -> ctx.getString(R.string.status_nfc_mode_disabled)
            message.startsWith("[WARNING] Polling already running") -> ctx.getString(R.string.status_polling_already_running_warning)
            message.startsWith("Starting polling") -> ctx.getString(R.string.status_polling_started)
            message == "Polling stopped unexpectedly" -> ctx.getString(R.string.status_polling_stopped_unexpectedly)
            message == "Polling stopped" -> ctx.getString(R.string.status_polling_stopped)
            message.startsWith(pollingErrorRestartPrefix) -> {
                val detail = message.removePrefix(pollingErrorRestartPrefix)
                ctx.getString(R.string.status_polling_error_restart, detail)
            }
            message.startsWith("Opening base URL") -> ctx.getString(R.string.status_opening_base_url)
            message.startsWith("Opening account page") -> ctx.getString(R.string.status_opening_account_page)
            message.startsWith("Submitting login") -> ctx.getString(R.string.status_submitting_login)
            message.startsWith(loginSuccessPrefix) -> {
                val countText = message.removePrefix(loginSuccessPrefix).removeSuffix(")")
                val count = countText.toIntOrNull()
                if (count != null) {
                    ctx.getString(R.string.status_login_successful, count)
                } else {
                    message
                }
            }
            message.startsWith("Verifying session (getUId)") -> ctx.getString(R.string.status_verifying_session)
            message.startsWith(getUidHttpPrefix) -> {
                val code = message.removePrefix(getUidHttpPrefix).toIntOrNull()
                if (code != null) {
                    ctx.getString(R.string.status_getuid_http, code)
                } else {
                    message
                }
            }
            message.startsWith("Loading account detail") -> ctx.getString(R.string.status_loading_account_detail)
            message.startsWith(getAccountDetailHttpPrefix) -> {
                val code = message.removePrefix(getAccountDetailHttpPrefix).toIntOrNull()
                if (code != null) {
                    ctx.getString(R.string.status_getaccountdetail_http, code)
                } else {
                    message
                }
            }
            message == "Loaded SNR" -> ctx.getString(R.string.status_loaded_snr)
            message.startsWith("Warming up session") -> ctx.getString(R.string.status_warming_session)
            message == "Session ready" -> ctx.getString(R.string.status_session_ready)
            else -> message
        }
    }

    private fun localizeError(message: String): String {
        val ctx = appContext ?: return message
        val pollingErrorPrefix = "Polling error: "
        val loginFailedPrefix = "Login failed: "
        return when {
            message.startsWith(pollingErrorPrefix) -> {
                val detail = message.removePrefix(pollingErrorPrefix)
                ctx.getString(R.string.status_polling_error, detail)
            }
            message.startsWith(loginFailedPrefix) -> {
                val detail = message.removePrefix(loginFailedPrefix)
                ctx.getString(R.string.error_login_failed, detail)
            }
            else -> message
        }
    }

    fun setPin(context: Context, pin: String) {
        val manager = credentialsManager ?: CredentialsManager(context)
        credentialsManager = manager
        manager.savePin(pin)
        _appState.value = _appState.value.copy(
            isPinSet = true,
            isAppUnlocked = true
        )
    }

    fun changePin(context: Context, currentPin: String, newPin: String): Boolean {
        val manager = credentialsManager ?: CredentialsManager(context)
        credentialsManager = manager
        if (!manager.verifyPin(currentPin)) return false
        manager.savePin(newPin)
        return true
    }

    fun verifyPin(context: Context, pin: String, allowBlank: Boolean = false): Boolean {
        val manager = credentialsManager ?: CredentialsManager(context)
        credentialsManager = manager
        val ok = if (pin.isBlank()) {
            allowBlank
        } else {
            manager.verifyPin(pin)
        }
        if (ok) {
            _appState.value = _appState.value.copy(isAppUnlocked = true)
        }
        return ok
    }

    fun setBiometricEnabled(context: Context, enabled: Boolean) {
        val manager = credentialsManager ?: CredentialsManager(context)
        credentialsManager = manager
        manager.saveBiometricEnabled(enabled)
        _appState.value = _appState.value.copy(biometricEnabled = enabled)
    }

    fun setLockTimeoutSeconds(context: Context, seconds: Int) {
        val manager = credentialsManager ?: CredentialsManager(context)
        credentialsManager = manager
        manager.saveLockTimeoutSeconds(seconds)
        _appState.value = _appState.value.copy(lockTimeoutSeconds = seconds)
    }

    fun setLanguageCode(context: Context, languageCode: String) {
        val manager = credentialsManager ?: CredentialsManager(context)
        credentialsManager = manager
        manager.saveLanguageCode(languageCode)
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(languageCode)
        )
        _appState.value = _appState.value.copy(languageCode = languageCode)
    }

    fun onAppBackgrounded() {
        if (!_appState.value.isPinSet) return
        lastBackgroundTimeMs = System.currentTimeMillis()
    }

    fun onAppForegrounded() {
        val state = _appState.value
        if (!state.isPinSet || lastBackgroundTimeMs == 0L) return
        val timeoutMs = state.lockTimeoutSeconds * 1000L
        val elapsed = System.currentTimeMillis() - lastBackgroundTimeMs
        if (timeoutMs == 0L || elapsed >= timeoutMs) {
            _appState.value = state.copy(isAppUnlocked = false)
        }
    }

    fun moveLayoutItem(context: Context, id: String, direction: Int) {
        val current = _appState.value.layoutOrder
        val index = current.indexOf(id)
        if (index == -1) return
        val newIndex = index + direction
        if (newIndex !in current.indices) return
        val updated = current.toMutableList()
        val moved = updated.removeAt(index)
        updated.add(newIndex, moved)
        _appState.value = _appState.value.copy(layoutOrder = updated)
        val manager = credentialsManager ?: CredentialsManager(context)
        credentialsManager = manager
        manager.saveLayoutOrder(updated)
    }

    fun selectThemePreset(context: Context, presetId: String) {
        if (presetId == _appState.value.selectedThemeId) return
        val manager = credentialsManager ?: CredentialsManager(context)
        credentialsManager = manager
        _appState.value = _appState.value.copy(selectedThemeId = presetId)
        manager.saveSelectedThemeId(presetId)
    }

    fun addThemePreset(
        context: Context,
        name: String,
        primary: Long,
        secondary: Long,
        tertiary: Long
    ) {
        val manager = credentialsManager ?: CredentialsManager(context)
        credentialsManager = manager
        val newPreset = ThemePreset(
            id = java.util.UUID.randomUUID().toString(),
            name = name,
            primary = primary,
            secondary = secondary,
            tertiary = tertiary
        )
        val updated = _appState.value.themePresets + newPreset
        _appState.value = _appState.value.copy(
            themePresets = updated,
            selectedThemeId = newPreset.id
        )
        manager.saveThemePresets(updated)
        manager.saveSelectedThemeId(newPreset.id)
    }

    fun loginAndRemember(context: Context, email: String, password: String) {
        credentialsManager = CredentialsManager(context)
        login(context, email, password)
        if (_appState.value.isLoggedIn) {
            val current = _appState.value
            credentialsManager?.saveCredentials(
                email,
                password,
                current.serialNumber,
                current.nfcUid
            )
        }
    }
    
    fun login(context: Context, email: String, password: String) {
        appContext = context.applicationContext
        val emailTrimmed = email.trim()
        if (emailTrimmed.isEmpty() || password.isEmpty()) {
            _appState.value = _appState.value.copy(
                loginError = getString(
                    R.string.login_error_fill_fields,
                    "Please fill in all fields"
                )
            )
            return
        }
        val cachedSerial = _appState.value.serialNumber.trim()
        val storedNfcUid = credentialsManager?.getNfcUid().orEmpty().trim()
        val cachedNfcUid = _appState.value.nfcUid.trim().ifBlank { storedNfcUid }
        val cachedNfcEnabled = false
        
        _appState.value = _appState.value.copy(
            isLoading = true,
            loginError = ""
        )
        
        initializeService(
            baseUrl = QRDaemonConfig.BASE_URL,
            username = emailTrimmed,
            password = password,
            serialNumber = cachedSerial
        )
        
        _appState.value = _appState.value.copy(
            isLoggedIn = true,
            isLoading = false,
            email = emailTrimmed,
            password = password,
            serialNumber = cachedSerial,
            nfcUid = cachedNfcUid,
            nfcEnabled = cachedNfcEnabled
        )
        
        // Auto-start polling
        startPolling()
    }
    
    fun logout() {
        stopPolling()
        qrService = null
        credentialsManager?.clearCredentials()
        val current = _appState.value
        _appState.value = AppState(
            isLoggedIn = false,
            isLoading = false,
            loginError = "",
            email = "",
            password = "",
            serialNumber = "",
            nfcUid = "",
            nfcEnabled = false,
            themePresets = current.themePresets,
            selectedThemeId = current.selectedThemeId,
            amoledEnabled = current.amoledEnabled,
            layoutOrder = current.layoutOrder,
            hiddenSections = current.hiddenSections,
            isPinSet = current.isPinSet,
            isAppUnlocked = current.isAppUnlocked,
            biometricEnabled = current.biometricEnabled,
            lockTimeoutSeconds = current.lockTimeoutSeconds,
            languageCode = current.languageCode
        )
        _qrState.value = QRState()
    }
    
    private fun initializeService(
        baseUrl: String,
        username: String,
        password: String,
        serialNumber: String
    ) {
        qrService = QRDaemonService(
            baseUrl = baseUrl,
            username = username,
            password = password,
            initialSerialNumber = serialNumber,
            initialNfcUid = _appState.value.nfcUid,
            initialNfcEnabled = _appState.value.nfcEnabled,
            onTokenUpdate = { hex, base64 ->
                viewModelScope.launch {
                    val appState = _appState.value
                    val current = _qrState.value
                    val updated = if (appState.nfcEnabled) {
                        current.copy(
                            tokenHex = hex,
                            tokenBase64 = base64,
                            lastUpdateTime = System.currentTimeMillis(),
                            statusMessage = getString(
                                R.string.status_nfc_uid_active,
                                "NFC UID active"
                            )
                        )
                    } else {
                        val size = QRDaemonConfig.QR_CODE_SIZE
                        val bitmap = QRCodeGenerator.generateQRCode(base64, size, size)
                        val shortHex = hex.take(16)
                        current.copy(
                            qrBitmap = bitmap,
                            tokenHex = hex,
                            tokenBase64 = base64,
                            lastUpdateTime = System.currentTimeMillis(),
                            statusMessage = getString(
                                R.string.status_token_updated_prefix,
                                "Token updated: %s...",
                                shortHex
                            )
                        )
                    }
                    _qrState.emit(updated)
                }
            },
            onError = { error ->
                viewModelScope.launch {
                    _qrState.emit(_qrState.value.copy(
                        errorMessage = localizeError(error),
                        statusMessage = getString(
                            R.string.status_error_format,
                            "Error: %s",
                            localizeError(error)
                        )
                    ))
                }
            },
            onUserName = { name ->
                viewModelScope.launch {
                    _qrState.emit(_qrState.value.copy(userName = name))
                }
            },
            onSerialNumber = { snr ->
                viewModelScope.launch {
                    _appState.emit(_appState.value.copy(serialNumber = snr))
                    val current = _appState.value
                    credentialsManager?.saveCredentials(
                        current.email,
                        current.password,
                        snr,
                        current.nfcUid
                    )
                }
            },
            onAccountInfo = { details ->
                viewModelScope.launch {
                    val current = _qrState.value
                    var updated = current.copy(accountDetails = details)
                    if (_appState.value.nfcEnabled && details.cardTemplateBase64.isNotBlank()) {
                        val bmp = decodeTemplateBitmap(details.cardTemplateBase64)
                        if (bmp != null) {
                            updated = updated.copy(qrBitmap = bmp)
                        }
                    }
                    _qrState.emit(updated)
                }
            },
            onStatus = { status ->
                viewModelScope.launch {
                    _qrState.emit(_qrState.value.copy(
                        statusMessage = localizeStatus(status)
                    ))
                }
            }
        )
    }
    
    fun startPolling() {
        if (_qrState.value.isPolling) return
        
        qrService?.let { service ->
            _qrState.value = _qrState.value.copy(
                isPolling = true,
                statusMessage = getString(
                    R.string.status_polling_started,
                    "Starting polling..."
                )
            )
            service.startPolling()
        }
    }
    
    fun stopPolling() {
        qrService?.stopPolling()
        _qrState.value = _qrState.value.copy(
            isPolling = false,
            statusMessage = getString(
                R.string.status_polling_stopped,
                "Polling stopped"
            ),
            errorMessage = ""
        )
    }

    fun toggleNfc(context: Context): String? {
        credentialsManager = credentialsManager ?: CredentialsManager(context)
        val current = _appState.value
        val enabling = !current.nfcEnabled
        val storedUid = credentialsManager?.getNfcUid().orEmpty()
        val uid = if (enabling) {
            current.nfcUid.ifBlank { storedUid }.ifBlank { generateUid() }
        } else {
            current.nfcUid
        }
        _appState.value = current.copy(nfcEnabled = enabling, nfcUid = uid)
        qrService?.setNfcMode(enabling, uid)
        credentialsManager?.saveCredentials(
            current.email,
            current.password,
            current.serialNumber,
            uid
        )
        if (enabling && uid.isNotBlank()) {
            val template = _qrState.value.accountDetails.cardTemplateBase64
            val bitmap = if (template.isNotBlank()) {
                decodeTemplateBitmap(template)
            } else {
                val vcard = buildNfcVCard(uid)
                QRCodeGenerator.generateQRCode(vcard, QRDaemonConfig.QR_CODE_SIZE, QRDaemonConfig.QR_CODE_SIZE)
            }
            _qrState.value = _qrState.value.copy(
                qrBitmap = bitmap,
                statusMessage = getString(
                    R.string.status_nfc_uid_active,
                    "NFC UID active"
                )
            )
        } else if (!enabling && _qrState.value.tokenBase64.isNotBlank()) {
            val size = QRDaemonConfig.QR_CODE_SIZE
            val bitmap = QRCodeGenerator.generateQRCode(_qrState.value.tokenBase64, size, size)
            _qrState.value = _qrState.value.copy(
                qrBitmap = bitmap,
                statusMessage = getString(
                    R.string.status_token_updated,
                    "Token updated"
                )
            )
        }
        return if (enabling && current.nfcUid.isBlank() && storedUid.isBlank()) uid else null
    }

    private fun generateUid(): String {
        val bytes = ByteArray(4)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
    
    override fun onCleared() {
        super.onCleared()
        qrService?.stopPolling()
    }
}
