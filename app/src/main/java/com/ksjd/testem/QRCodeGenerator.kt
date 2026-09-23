package com.ksjd.testem

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.EnumMap

object QRCodeGenerator {
    fun generateQRCode(data: String, width: Int = 512, height: Int = 512): Bitmap? {
        return try {
            val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
            hints[EncodeHintType.MARGIN] = 1
            // Higher error correction yields a denser QR with more alignment patterns.
            hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.H

            val matrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, width, height, hints)
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val row = y * width
                for (x in 0 until width) {
                    pixels[row + x] = if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                }
            }
            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.RGB_565)
        } catch (e: Exception) {
            null
        }
    }
}
