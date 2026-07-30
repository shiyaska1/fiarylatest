package com.billing.pos.pdf

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.Bill
import com.billing.pos.data.BillItem
import com.billing.pos.data.CompanyInfo
import com.billing.pos.data.Receipt
import com.billing.pos.util.Format
import java.io.File

/**
 * Renders a bill / receipt as a narrow 58mm thermal-receipt-style PDF (32 columns,
 * monospace), so a shared PDF looks like the paper receipt from the thermal printer.
 */
object ThermalPdf {

    private var COLS = 32
    private var PAGE_W = 165f   // ~58mm in points
    private const val MARGIN = 6f

    /** Sizes the page/columns from the chosen receipt width. */
    private fun applyWidth(context: Context) {
        val w = com.billing.pos.data.AppPrefs(context).receiptWidth
        COLS = com.billing.pos.data.AppPrefs.colsFor(w)
        PAGE_W = com.billing.pos.data.AppPrefs.pageWidthFor(w)
    }

    private data class Line(val text: String, val bold: Boolean = false)

    fun invoice(context: Context, company: CompanyInfo, bill: Bill, lines: List<BillItem>, imagePaths: List<String> = emptyList(), title: String = "TAX INVOICE"): Uri {
        applyWidth(context)
        val out = ArrayList<Line>()
        fun add(text: String, bold: Boolean = false) { out.add(Line(text, bold)) }
        addHeader(out, company)
        add(center(title), true)
        add(rule())
        add("Bill: ${bill.billNo}")
        add("Date: ${Format.dateTime(bill.dateMillis)}")
        add("Cust: " + clip(bill.customerName, 26))
        add("Pay : ${bill.paymentMethod}")
        add(rule())
        add(row("Item", "Qty", "Amount"))
        add(rule())
        for (l in lines) {
            add(clip(l.name, COLS))
            add(row("  @${Format.money(l.price)}" + (if (l.unit.isNotBlank()) "/${l.unit}" else ""), Format.qty(l.qty), Format.money(l.lineTotal)))
        }
        add(rule())
        add(kv("Sub Total", Format.money(bill.subTotal)))
        add(kv("Tax", Format.money(bill.taxTotal)))
        if (bill.additionalCharge != 0.0) add(kv("Additional", Format.money(bill.additionalCharge)))
        if (bill.discount != 0.0) add(kv("Discount", "-" + Format.money(bill.discount)))
        add(rule())
        add(kv("GRAND TOTAL", Format.money(bill.grandTotal)), true)
        add(rule())
        if (bill.remarks.isNotBlank()) {
            add("Note:", true)
            bill.remarks.chunked(COLS).forEach { add(it, true) }
            add(rule())
        }
        add(center("Thank you! Visit again."))
        // Optional UPI QR for the total, centred under the receipt.
        val prefs = AppPrefs(context)
        val qr = if (prefs.showUpiQrOnPrint && prefs.upiId.isNotBlank())
            com.billing.pos.util.UpiQr.bitmap(
                com.billing.pos.util.UpiQr.link(prefs.upiId, prefs.upiName.ifBlank { company.name }, bill.grandTotal, bill.billNo), 360
            ) else null
        if (qr != null) add(center("Scan to pay " + Format.money(bill.grandTotal)))
        return write(context, "invoice_${bill.billNo}", out, imagePaths, qr)
    }

    fun receipt(context: Context, company: CompanyInfo, r: Receipt): Uri {
        applyWidth(context)
        val out = ArrayList<Line>()
        fun add(text: String, bold: Boolean = false) { out.add(Line(text, bold)) }
        addHeader(out, company)
        add(center("RECEIPT VOUCHER"), true)
        add(rule())
        add("No  : ${r.receiptNo}")
        add("Date: ${Format.dateTime(r.dateMillis)}")
        add("From: " + clip(r.payFrom.ifBlank { r.customerName }, 26))
        if (r.billNo.isNotBlank()) add("Ref : ${r.billNo}")
        add("Mode: ${r.paymentMode}")
        add(rule())
        add(kv("RECEIVED", Format.money(r.amount)), true)
        add(rule())
        add(center("Thank you"))
        return write(context, "receipt_${r.receiptNo}", out)
    }

    private fun addHeader(out: ArrayList<Line>, company: CompanyInfo) {
        out.add(Line(center(clip(company.name)), bold = true))
        if (company.address.isNotBlank()) out.add(Line(center(clip(company.address))))
        if (company.phone.isNotBlank()) out.add(Line(center(clip("Ph: ${company.phone}"))))
    }

    private fun write(context: Context, name: String, lines: List<Line>, imagePaths: List<String> = emptyList(), qr: android.graphics.Bitmap? = null): Uri {
        val body = Paint().apply { typeface = Typeface.MONOSPACE; isAntiAlias = true; color = Color.BLACK; textSize = 10f }
        val content = PAGE_W - 2 * MARGIN
        val measured = body.measureText("0".repeat(COLS)).coerceAtLeast(1f)
        body.textSize = 10f * (content / measured)
        val boldP = Paint(body).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
        val lineH = body.textSize * 1.35f

        // Decode attached photos and size each to the receipt width.
        val bitmaps = imagePaths.mapNotNull { runCatching { android.graphics.BitmapFactory.decodeFile(it) }.getOrNull() }
        val imgHeights = bitmaps.map { content * it.height / it.width }
        val imagesBlock = if (bitmaps.isEmpty()) 0f else imgHeights.sum() + lineH * bitmaps.size
        // The QR is drawn at 60% of the receipt width, centred.
        val qrSize = if (qr != null) content * 0.6f else 0f
        val qrBlock = if (qr != null) qrSize + lineH else 0f

        val height = (MARGIN * 2 + lines.size * lineH + lineH + qrBlock + imagesBlock).toInt().coerceAtLeast(40)

        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W.toInt(), height, 1).create())
        val c = page.canvas
        var y = MARGIN + lineH
        for (l in lines) {
            c.drawText(l.text, MARGIN, y, if (l.bold) boldP else body)
            y += lineH
        }
        // UPI QR, centred, under the thank-you line.
        if (qr != null) {
            val left = MARGIN + (content - qrSize) / 2f
            c.drawBitmap(qr, null, android.graphics.RectF(left, y, left + qrSize, y + qrSize), null)
            y += qrSize + lineH
        }
        // Photos below.
        bitmaps.forEachIndexed { i, bmp ->
            val h = imgHeights[i]
            c.drawBitmap(bmp, null, android.graphics.RectF(MARGIN, y, MARGIN + content, y + h), null)
            y += h + lineH
            bmp.recycle()
        }
        doc.finishPage(page)

        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val safe = name.replace(Regex("[^A-Za-z0-9]+"), "_").take(28).ifBlank { "receipt" }
        val file = File(dir, "$safe.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }

    // ---- 32-column text helpers (match the thermal printer) --------------------

    private fun rule() = "-".repeat(COLS)

    private fun clip(s: String, max: Int = COLS) = if (s.length <= max) s else s.take(max)

    private fun center(s: String): String {
        val t = clip(s, COLS)
        val pad = (COLS - t.length) / 2
        return " ".repeat(pad.coerceAtLeast(0)) + t
    }

    private fun kv(key: String, value: String): String {
        val space = (COLS - key.length - value.length).coerceAtLeast(1)
        return key + " ".repeat(space) + value
    }

    private fun row(a: String, b: String, cRight: String): String {
        val left = a.take(16).padEnd(16)
        val mid = b.take(6).padStart(6)
        val right = cRight.take(10).padStart(10)
        return (left + mid + right).take(COLS)
    }
}
