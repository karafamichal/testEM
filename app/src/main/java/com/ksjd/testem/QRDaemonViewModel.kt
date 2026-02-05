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
    val serialNumber: String = ""
)

class QRDaemonViewModel : ViewModel() {
    private val _qrState = MutableStateFlow(QRState())
    val qrState: StateFlow<QRState> = _qrState
    
    private val _appState = MutableStateFlow(AppState())
    val appState: StateFlow<AppState> = _appState
    
    private var qrService: QRDaemonService? = null

    fun loadSavedCredentials(context: Context) {
        val manager = CredentialsManager(context)
        if (!manager.isConfigured()) return

        val (email, password, serialNumber) = manager.getCredentials()
        _appState.value = _appState.value.copy(
            email = email,
            password = password,
            serialNumber = serialNumber,
            loginError = ""
        )
    }

    fun loginAndRemember(context: Context, email: String, password: String, serialNumber: String) {
        login(email, password, serialNumber)
        if (_appState.value.isLoggedIn) {
            CredentialsManager(context).saveCredentials(email, password, serialNumber)
        }
    }
    
    fun login(email: String, password: String, serialNumber: String) {
        val emailTrimmed = email.trim()
        val serialTrimmed = serialNumber.trim()
        if (emailTrimmed.isEmpty() || password.isEmpty() || serialTrimmed.isEmpty()) {
            _appState.value = _appState.value.copy(
                loginError = "Please fill in all fields"
            )
            return
        }
        
        _appState.value = _appState.value.copy(
            isLoading = true,
            loginError = ""
        )
        
        initializeService(
            baseUrl = QRDaemonConfig.BASE_URL,
            username = emailTrimmed,
            password = password,
            serialNumber = serialTrimmed
        )
        
        _appState.value = _appState.value.copy(
            isLoggedIn = true,
            isLoading = false,
            email = emailTrimmed,
            password = password,
            serialNumber = serialTrimmed
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
            serialNumber = current.serialNumber
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
            serialNumber = serialNumber,
            onTokenUpdate = { hex, base64 ->
                viewModelScope.launch {
                    val bitmap = QRCodeGenerator.generateQRCode(base64, 512, 512)
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
            statusMessage = "Polling stopped"
        )
    }
    
    override fun onCleared() {
        super.onCleared()
        qrService?.stopPolling()
    }
}
