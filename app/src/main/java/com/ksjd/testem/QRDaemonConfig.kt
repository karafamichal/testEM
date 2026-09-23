package com.ksjd.testem

object QRDaemonConfig {
    const val BASE_URL = "https://sadzv.qrbus.me"

    // Polling configuration
    const val POLL_INTERVAL_MS = 25000L  // tokens rotate roughly every 25 seconds
    const val RETRY_DELAY_MS = 5000L     // first back-off step after a network error
    const val SHORT_RETRY_MS = 1500L     // server had no token yet

    // A code older than this is shown as stale.
    const val STALE_AFTER_MS = 60_000L

    // Bitmap resolution of the generated QR code
    const val QR_BITMAP_SIZE = 720
}
