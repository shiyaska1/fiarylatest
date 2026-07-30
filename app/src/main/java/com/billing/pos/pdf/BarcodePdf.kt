package com.billing.pos.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.billing.pos.data.Item
import com.billing.pos.util.Format
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import java.io.File
import kotlin.math.min

/** Generates a printable PDF of barcode labels for an item. */
object BarcodePdf {

    private const val PAGE_W = 595   // A4 @ 72dpi
    private const val PAGE_H = 842
    private const val COLS = 3

    /** Returns the generated PDF file, or null if the item has no barcode. */
    fun generate(context: Context, item: Item, count: Int): File? {
        val code = item.barcode.trim()
        if (code.isBlank() || count <= 0) return null
        val bmp = barcodeBitmap(code, 500, 150) ?: return null

        val marginX = 24f
        val marginY = 24f
        val gapX = 10f
        val gapY = 10f
        val cellW = (PAGE_W - 2 * marginX - (COLS - 1) * gapX) / COLS
        val cellH = 96f
        val barcodeH = cellH - 26f
        val rowsPerPage = ((PAGE_H - 2 * marginY + gapY) / (cellH + gapY)).toInt().coerceAtLeast(1)
        val perPage = COLS * rowsPerPage

        val namePaint = Paint().apply { color = Color.BLACK; textSize = 9f; isFakeBoldText = true }
        val smallPaint = Paint().apply { color = Color.DKGRAY; textSize = 8f }

        val doc = PdfDocument()
        var index = 0
        var pageNum = 1
        while (index < count) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create())
            val c = page.canvas
            val onThisPage = min(perPage, count - index)
            for (k in 0 until onThisPage) {
                val col = k % COLS
                val row = k / COLS
                val x = marginX + col * (cellW + gapX)
                val y = marginY + row * (cellH + gapY)
                c.drawBitmap(bmp, null, RectF(x, y, x + cellW, y + barcodeH), null)
                c.drawText(clip(item.name, 22), x, y + barcodeH + 11f, namePaint)
                c.drawText("Rs. ${Format.money(item.price)}   $code", x, y + barcodeH + 22f, smallPaint)
            }
            doc.finishPage(page)
            index += onThisPage
            pageNum++
        }

        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val safe = code.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(dir, "barcodes-$safe.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    private fun barcodeBitmap(content: String, w: Int, h: Int): Bitmap? = runCatching {
        val hints = mapOf(EncodeHintType.MARGIN to 2)
        val matrix = MultiFormatWriter().encode(content, BarcodeFormat.CODE_128, w, h, hints)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        for (x in 0 until w) {
            for (y in 0 until h) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        bmp
    }.getOrNull()

    private fun clip(s: String, max: Int) = if (s.length <= max) s else s.take(max)
}
