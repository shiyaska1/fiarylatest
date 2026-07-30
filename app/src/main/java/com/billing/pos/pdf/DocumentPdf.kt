package com.billing.pos.pdf

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.CompanyInfo
import com.billing.pos.util.Format
import com.billing.pos.util.layoutRich
import java.io.File

/** One printable line on a document. [unit] is shown next to the quantity when set. */
data class PdfLine(
    val name: String,
    val qty: Double,
    val price: Double,
    val lineTotal: Double,
    val unit: String = "",
    /** Printed under the item name, wrapped over as many lines as it needs. */
    val note: String = ""
)

/** A generic printable business document (quotation, sales/purchase return, LPO, …). */
data class PdfDoc(
    val docTitle: String,          // e.g. "QUOTATION", "SALES RETURN"
    val docNo: String,
    val dateMillis: Long,
    val partyLabel: String,        // e.g. "Bill To", "Return From", "Order To"
    val partyName: String,
    val extraMeta: String = "",    // optional second-line meta, e.g. "Against: INV-0007"
    /** Longer meta printed one item per line under the party (a single row won't fit). */
    val metaLines: List<String> = emptyList(),
    val lines: List<PdfLine>,
    val subTotal: Double,
    val taxTotal: Double,
    val additionalCharge: Double,
    val discount: Double,
    val grandTotal: Double,
    val grandLabel: String = "GRAND TOTAL",
    val remarks: String = "",
    /** Terms and conditions, printed under the totals. Accepts the same formatting as notes. */
    val terms: String = "",
    /** When non-zero, an extra paid row and a BALANCE row print under the grand total. */
    val paid: Double = 0.0,
    val paidLabel: String = "Paid",
    val filePrefix: String         // used for the pdf file name, e.g. "quotation"
)

/**
 * Renders a [PdfDoc] to a rich, full-page A4 PDF (same look as the A4 invoice:
 * bordered table, big company header, optional logo) and returns a content:// Uri.
 */
object DocumentPdf {

    private const val PW = 595f      // A4 width in points (72 dpi)
    private const val PH = 842f      // A4 height
    private const val M = 36f        // margin

    /** Builds the PDF and returns a shareable content:// Uri. */
    fun make(context: Context, company: CompanyInfo, doc: PdfDoc): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.provider", file(context, company, doc))

    /** Builds the PDF into the cache and returns the raw File (for saving to Downloads). */
    fun file(context: Context, company: CompanyInfo, doc: PdfDoc): File {
        val prefs = AppPrefs(context)
        val pdf = PdfDocument()

        val black = Paint().apply { color = 0xFF000000.toInt(); isAntiAlias = true }
        val title = Paint(black).apply { textSize = 24f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val sub = Paint(black).apply { textSize = 11f }
        val small = Paint(black).apply { textSize = 9f; color = 0xFF555555.toInt() }
        val cell = Paint(black).apply { textSize = 10f }
        val cellBold = Paint(cell).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val line = Paint().apply { color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f }
        val head = Paint(cellBold).apply { textSize = 10.5f }

        val x0 = M
        val xEnd = PW - M
        val cNo = x0
        val cItem = x0 + 28f
        val cQty = xEnd - 210f
        val cRate = xEnd - 140f
        val cAmt = xEnd - 70f

        val logo = prefs.logoPath.takeIf { it.isNotBlank() }?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }

        var page = pdf.startPage(PdfDocument.PageInfo.Builder(PW.toInt(), PH.toInt(), 1).create())
        var c = page.canvas
        var y: Float

        // ---- Header ----
        if (logo != null && prefs.logoFullWidth) {
            val w = xEnd - x0
            val h = w * logo.height / logo.width
            val cappedH = h.coerceAtMost(140f)
            val cappedW = cappedH * logo.width / logo.height
            c.drawBitmap(logo, null, Rect(x0.toInt(), M.toInt(), (x0 + cappedW).toInt(), (M + cappedH).toInt()), null)
            y = M + cappedH + 14f
        } else {
            var textX = x0
            if (logo != null) {
                val h = 70f; val w = h * logo.width / logo.height
                c.drawBitmap(logo, null, Rect(x0.toInt(), M.toInt(), (x0 + w).toInt(), (M + h).toInt()), null)
                textX = x0 + w + 14f
            }
            c.drawText(company.name.ifBlank { "My Shop" }, textX, M + 22f, title)
            var hy = M + 40f
            if (company.address.isNotBlank()) { c.drawText(company.address, textX, hy, sub); hy += 15f }
            if (company.phone.isNotBlank()) { c.drawText("Phone: ${company.phone}", textX, hy, sub); hy += 15f }
            if (company.gstin.isNotBlank()) { c.drawText("GSTIN: ${company.gstin}", textX, hy, sub); hy += 15f }
            y = maxOf(hy, M + 78f) + 8f
        }

        // ---- Title bar ----
        c.drawLine(x0, y, xEnd, y, line)
        y += 20f
        val tp = Paint(cellBold).apply { textSize = 15f; textAlign = Paint.Align.CENTER }
        c.drawText(doc.docTitle, (x0 + xEnd) / 2f, y, tp)
        y += 18f

        // ---- Doc meta ----
        c.drawText("No: ${doc.docNo}", x0, y, sub)
        c.drawText("Date: ${Format.date(doc.dateMillis)}", cRate - 40f, y, sub)
        y += 15f
        c.drawText("${doc.partyLabel}: ${doc.partyName}", x0, y, sub)
        if (doc.extraMeta.isNotBlank()) c.drawText(doc.extraMeta, cRate - 40f, y, sub)
        doc.metaLines.forEach { ml -> y += 14f; c.drawText(ml, x0, y, sub) }
        y += 12f

        // ---- Table header ----
        val rowH = 20f
        var secTop = y
        fun tableHeader() {
            secTop = y
            c.drawRect(x0, y, xEnd, y + rowH, line)
            val ty = y + 14f
            c.drawText("#", cNo + 4f, ty, head)
            c.drawText("Item", cItem + 4f, ty, head)
            val ra = Paint(head).apply { textAlign = Paint.Align.RIGHT }
            c.drawText("Qty", cRate - 4f, ty, ra)
            c.drawText("Rate", cAmt - 4f, ty, ra)
            c.drawText("Amount", xEnd - 4f, ty, ra)
            y += rowH
        }
        tableHeader()

        val rightCell = Paint(cell).apply { textAlign = Paint.Align.RIGHT }
        // Notes print smaller and greyer than the item name, so the item still reads first.
        val notePaint = Paint(cell).apply { textSize = cell.textSize - 2f; color = 0xFF444444.toInt() }
        val itemColWidth = cQty - cItem - 8f
        doc.lines.forEachIndexed { i, l ->
            // A description replaces the item name rather than sitting under it, so a line
            // that has been described prints only the description.
            val noteLines = layoutRich(l.note, itemColWidth, notePaint)
            val notesH = noteLines.sumOf { it.height.toDouble() }.toFloat()
            val thisRowH = if (noteLines.isEmpty()) rowH else maxOf(rowH, notesH + 10f)
            if (y + thisRowH > PH - 120f) {
                drawVerticalBorders(c, line, x0, xEnd, cItem, cQty, cRate, cAmt, secTop, y)
                pdf.finishPage(page)
                page = pdf.startPage(PdfDocument.PageInfo.Builder(PW.toInt(), PH.toInt(), pdf.pages.size + 1).create())
                c = page.canvas
                y = M
                tableHeader()
            }
            c.drawText("${i + 1}", cNo + 4f, y + 14f, cell)
            val qtyText = Format.qty(l.qty) + if (l.unit.isNotBlank()) " ${l.unit}" else ""
            c.drawText(qtyText, cRate - 4f, y + 14f, rightCell)
            c.drawText(Format.money(l.price), cAmt - 4f, y + 14f, rightCell)
            c.drawText(Format.money(l.lineTotal), xEnd - 4f, y + 14f, rightCell)
            if (noteLines.isEmpty()) {
                c.drawText(clip(l.name, 44), cItem + 4f, y + 14f, cell)
            } else {
                var ny = y + 6f
                noteLines.forEach { nl ->
                    ny += nl.height
                    var nx = cItem + 4f
                    nl.runs.forEach { run ->
                        c.drawText(run.text, nx, ny, run.paint)
                        nx += run.paint.measureText(run.text)
                    }
                }
            }
            y += thisRowH
            c.drawLine(x0, y, xEnd, y, line)
        }

        drawVerticalBorders(c, line, x0, xEnd, cItem, cQty, cRate, cAmt, secTop, y)

        // ---- Totals ----
        y += 14f
        val labelR = cAmt - 8f
        val valueR = xEnd - 4f
        fun total(label: String, value: String, bold: Boolean = false) {
            val lp = Paint(if (bold) cellBold else cell).apply { textAlign = Paint.Align.RIGHT }
            val vp = Paint(if (bold) cellBold else cell).apply { textAlign = Paint.Align.RIGHT }
            c.drawText(label, labelR, y, lp)
            c.drawText(value, valueR, y, vp)
            y += 17f
        }
        total("Sub Total", Format.money(doc.subTotal))
        if (doc.taxTotal != 0.0) total("Tax", Format.money(doc.taxTotal))
        if (doc.additionalCharge != 0.0) total("Additional", Format.money(doc.additionalCharge))
        if (doc.discount != 0.0) total("Discount", "-" + Format.money(doc.discount))
        c.drawLine(cRate, y - 2f, xEnd, y - 2f, line)
        y += 8f
        val gt = Paint(cellBold).apply { textSize = 13f; textAlign = Paint.Align.RIGHT }
        c.drawText(doc.grandLabel, labelR, y + 4f, gt)
        c.drawText(Format.money(doc.grandTotal), valueR, y + 4f, gt)
        y += 30f

        if (doc.paid != 0.0) {
            total(doc.paidLabel, Format.money(doc.paid))
            total("BALANCE", Format.money(doc.grandTotal - doc.paid), bold = true)
            y += 6f
        }

        if (doc.remarks.isNotBlank()) {
            val note = Paint(cellBold).apply { textSize = 12f; color = 0xFF000000.toInt() }
            c.drawText("Note: ${clip(doc.remarks, 80)}", x0, y, note); y += 18f
        }

        if (doc.terms.isNotBlank()) {
            c.drawText("Terms & Conditions", x0, y, Paint(cellBold).apply { textSize = 11f })
            y += 14f
            val termsPaint = Paint(cell).apply { textSize = 10f; color = 0xFF222222.toInt() }
            layoutRich(doc.terms, xEnd - x0, termsPaint).forEach { tl ->
                // Start a new page rather than printing the terms over the footer.
                if (y + tl.height > PH - M - 14f) {
                    pdf.finishPage(page)
                    page = pdf.startPage(PdfDocument.PageInfo.Builder(PW.toInt(), PH.toInt(), pdf.pages.size + 1).create())
                    c = page.canvas
                    y = M + 14f
                }
                y += tl.height
                var tx = x0
                tl.runs.forEach { run ->
                    c.drawText(run.text, tx, y, run.paint)
                    tx += run.paint.measureText(run.text)
                }
            }
            y += 8f
        }
        c.drawText("This is a computer-generated document.", x0, PH - M, small)

        pdf.finishPage(page)

        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val safeNo = doc.docNo.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val out = File(dir, "${doc.filePrefix}_$safeNo.pdf")
        out.outputStream().use { pdf.writeTo(it) }
        pdf.close()
        return out
    }

    private fun drawVerticalBorders(c: Canvas, line: Paint, x0: Float, xEnd: Float, cItem: Float, cQty: Float, cRate: Float, cAmt: Float, startY: Float, endY: Float) {
        for (x in listOf(x0, cItem, cQty, cRate, cAmt, xEnd)) c.drawLine(x, startY, x, endY, line)
    }

    private fun clip(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1) + "…"
}

