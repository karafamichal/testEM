package com.ksjd.testem

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.QRCodeWriter
import java.util.EnumMap

object QRCodeGenerator {
    private const val BLACK = android.graphics.Color.BLACK
    private const val WHITE = android.graphics.Color.WHITE

    private val hints: EnumMap<EncodeHintType, Any> = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
        put(EncodeHintType.MARGIN, 1)
        put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H)
    }

    private data class CacheKey(val data: String, val width: Int, val height: Int)

    // Small LRU-ish cache keyed by payload+size. Keeps at most a handful of recent bitmaps.
    private const val MAX_CACHE = 4
    private val cache = object : java.util.LinkedHashMap<CacheKey, Bitmap>(MAX_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, Bitmap>?): Boolean {
            return size > MAX_CACHE
        }
    }
    private val cacheLock = Any()

    fun generateQRCode(data: String, width: Int = 512, height: Int = 512): Bitmap? {
        val key = CacheKey(data, width, height)
        synchronized(cacheLock) {
            cache[key]?.let { return it }
        }
        return try {
            val bitMatrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, width, height, hints)
            val pixels = IntArray(width * height)
            var offset = 0
            for (y in 0 until height) {
                for (x in 0 until width) {
                    pixels[offset + x] = if (bitMatrix[x, y]) BLACK else WHITE
                }
                offset += width
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            synchronized(cacheLock) { cache[key] = bitmap }
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
