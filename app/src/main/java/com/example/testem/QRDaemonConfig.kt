package com.example.testem

object QRDaemonConfig {
    // These should be loaded from environment or secure storage in production
    const val BASE_URL = "https://sadzv.qrbus.me"
    const val ACCOUNT_URL = "$BASE_URL/account"
    const val LOGIN_URL = "$BASE_URL/account/login"
    const val TOKEN_API = "$BASE_URL/cardapi/getQrToken"
    
    // Credentials - should be moved to environment or secure storage
    var USERNAME = ""
    var PASSWORD = ""
    var SERIAL_NUMBER = ""
    
    // Polling configuration
    const val POLL_INTERVAL_MS = 25000L  // 25 seconds
    const val TOKEN_LENGTH = 57  // Expected token byte length
    const val RETRY_DELAY_MS = 5000L  // 5 seconds
    const val SHORT_RETRY_MS = 1500L  // 1.5 seconds
    
    // UI Configuration
    const val QR_CODE_SIZE = 512  // pixels
}
