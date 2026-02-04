package com.example.testem

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
    
    fun login(email: String, password: String, serialNumber: String) {
        if (email.isEmpty() || password.isEmpty() || serialNumber.isEmpty()) {
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
            username = email,
            password = password,
            serialNumber = serialNumber
        )
        
        _appState.value = _appState.value.copy(
            isLoggedIn = true,
            isLoading = false,
            email = email,
            password = password,
            serialNumber = serialNumber
        )
        
        // Auto-start polling
        startPolling()
    }
    
    fun logout() {
        stopPolling()
        qrService = null
        _appState.value = AppState()
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
