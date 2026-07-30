package com.billing.pos.util

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.util.Locale

/**
 * Builds UPI payment QR codes on the phone — no gateway, no server.
 *
 * The QR just encodes the standard `upi://pay` link. Any UPI app (GPay, PhonePe, Paytm)
 * that scans it opens payment with the payee and amount already filled in; the payer only
 * enters their PIN. Money goes straight to the merchant's own UPI ID.
 */
object UpiQr {

    /** The upi:// link for a fixed amount. */
    fun link(vpa: String, name: String, amount: Double, note: String = ""): String {
        val am = String.format(Locale.US, "%.2f", amount)
        val sb = StringBuilder("upi://pay?pa=").append(Uri.encode(vpa.trim()))
        if (name.isNotBlank()) sb.append("&pn=").append(Uri.encode(name.trim()))
        sb.append("&am=").append(am).append("&cu=INR")
        if (note.isNotBlank()) sb.append("&tn=").append(Uri.encode(note.trim()))
        return sb.toString()
    }

    /** Encodes any text as a square QR bitmap, or null if it can't. */
    fun bitmap(content: String, size: Int = 640): Bitmap? = runCatching {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        bmp
    }.getOrNull()
}
