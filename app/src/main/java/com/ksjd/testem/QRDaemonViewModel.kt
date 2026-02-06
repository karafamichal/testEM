package com.ksjd.testem

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class QRState(
    val isPolling: Boolean = false,
    val qrBitmap: Bitmap? = null,
    val tokenHex: String = "",
    val tokenBase64: String = "",
    val userName: String = "",
    val accountDetails: AccountDetails = AccountDetails(),
    val errorMessage: String = "",
    val statusMessage: String = "Ready",
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
    val nfcEnabled: Boolean = false
)

class QRDaemonViewModel : ViewModel() {
    private val _qrState = MutableStateFlow(QRState())
    val qrState: StateFlow<QRState> = _qrState
    
    private val _appState = MutableStateFlow(AppState())
    val appState: StateFlow<AppState> = _appState
    
    private var qrService: QRDaemonService? = null
    private var credentialsManager: CredentialsManager? = null

    private fun escapeVCard(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("\n", "\\n")
    }

    private fun buildVCard(name: String, email: String, token: String): String {
        val safeName = escapeVCard(name.ifBlank { "testEM" })
        val safeEmail = escapeVCard(email)
        val safeToken = escapeVCard(token)
        return "BEGIN:VCARD\r\n" +
            "VERSION:3.0\r\n" +
            "FN:$safeName\r\n" +
            "N:$safeName;;;;\r\n" +
            (if (safeEmail.isNotBlank()) "EMAIL:$safeEmail\r\n" else "") +
            "NOTE:QR_TOKEN=$safeToken\r\n" +
            "END:VCARD"
    }

    fun loadSavedCredentials(context: Context) {
        val manager = CredentialsManager(context)
        credentialsManager = manager
        if (!manager.isConfigured()) return

        val (email, password, serialNumber) = manager.getCredentials()
        val nfcUid = manager.getNfcUid()
        val nfcEnabled = manager.getNfcEnabled()
        _appState.value = _appState.value.copy(
            email = email,
            password = password,
            serialNumber = serialNumber,
            nfcUid = nfcUid,
            nfcEnabled = nfcEnabled,
            loginError = ""
        )
    }

    fun loginAndRemember(context: Context, email: String, password: String) {
        credentialsManager = CredentialsManager(context)
        login(email, password)
        if (_appState.value.isLoggedIn) {
            val current = _appState.value
            credentialsManager?.saveCredentials(
                email,
                password,
                current.serialNumber,
                current.nfcUid,
                current.nfcEnabled
            )
        }
    }
    
    fun login(email: String, password: String) {
        val emailTrimmed = email.trim()
        if (emailTrimmed.isEmpty() || password.isEmpty()) {
            _appState.value = _appState.value.copy(
                loginError = "Please fill in all fields"
            )
            return
        }
        val cachedSerial = _appState.value.serialNumber.trim()
        val cachedNfcUid = _appState.value.nfcUid.trim()
        val cachedNfcEnabled = _appState.value.nfcEnabled
        
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
        val current = _appState.value
        _appState.value = AppState(
            isLoggedIn = false,
            isLoading = false,
            loginError = "",
            email = current.email,
            password = current.password,
            serialNumber = current.serialNumber,
            nfcUid = current.nfcUid,
            nfcEnabled = current.nfcEnabled
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
                    val name = _qrState.value.userName
                    val email = _appState.value.email
                    val vcard = buildVCard(name, email, base64)
                    val bitmap = QRCodeGenerator.generateQRCode(vcard, 512, 512)
                    _qrState.emit(_qrState.value.copy(
                        qrBitmap = bitmap,
                        tokenHex = hex,
                        tokenBase64 = base64,
                        lastUpdateTime = System.currentTimeMillis(),
                        statusMessage = "Token updated: ${hex.take(16)}…"
                    ))
                }
            },
            onError = { error ->
                viewModelScope.launch {
                    _qrState.emit(_qrState.value.copy(
                        errorMessage = error,
                        statusMessage = "Error: $error"
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
                        current.nfcUid,
                        current.nfcEnabled
                    )
                }
            },
            onAccountInfo = { details ->
                viewModelScope.launch {
                    _qrState.emit(_qrState.value.copy(accountDetails = details))
                }
            },
            onStatus = { status ->
                viewModelScope.launch {
                    _qrState.emit(_qrState.value.copy(
                        statusMessage = status
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
                statusMessage = "Starting polling…"
            )
            service.startPolling()
        }
    }
    
    fun stopPolling() {
        qrService?.stopPolling()
        _qrState.value = _qrState.value.copy(
            isPolling = false,
            statusMessage = "Polling stopped",
            errorMessage = ""
        )
    }

    fun toggleNfc(context: Context): String? {
        credentialsManager = credentialsManager ?: CredentialsManager(context)
        val current = _appState.value
        val enabling = !current.nfcEnabled
        val uid = if (enabling && current.nfcUid.isBlank()) {
            generateUid()
        } else {
            current.nfcUid
        }
        _appState.value = current.copy(nfcEnabled = enabling, nfcUid = uid)
        qrService?.setNfcMode(enabling, uid)
        credentialsManager?.saveCredentials(
            current.email,
            current.password,
            current.serialNumber,
            uid,
            enabling
        )
        return if (enabling && current.nfcUid.isBlank()) uid else null
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
