package com.ksjd.testem

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.util.EnumMap

object QRCodeGenerator {
    fun generateQRCode(data: String, width: Int = 512, height: Int = 512): Bitmap? {
        return try {
            val payload = if (data.startsWith("BEGIN:VCARD")) {
                data
            } else {
                // Wrap token data in a minimal vCard so scanners interpret it as contact data.
                "BEGIN:VCARD\r\nVERSION:3.0\r\nNOTE:$data\r\nEND:VCARD"
            }
            val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
            hints[EncodeHintType.MARGIN] = 1
            
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(payload, BarcodeFormat.QR_CODE, width, height, hints)
            
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
