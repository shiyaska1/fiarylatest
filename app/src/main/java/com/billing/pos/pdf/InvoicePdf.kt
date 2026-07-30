package com.billing.pos.pdf

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import android.net.Uri
import com.billing.pos.data.Bill
import com.billing.pos.data.BillItem
import com.billing.pos.data.CompanyInfo
import com.billing.pos.util.Format
import java.io.File

/** Renders a saved bill to a shareable A5-ish PDF and returns a content:// Uri. */
object InvoicePdf {

    private const val PAGE_W = 420   // ~ A5 width in points
    private const val MARGIN = 24f

    /** Builds the invoice PDF per the width setting: rich A4 layout, else the thermal receipt. */
    fun make(context: Context, company: CompanyInfo, bill: Bill, lines: List<BillItem>, imagePaths: List<String> = emptyList(), docTitle: String = "TAX INVOICE"): Uri =
        if (com.billing.pos.data.AppPrefs(context).receiptWidth == "A4")
            A4InvoicePdf.invoice(context, company, bill, lines, imagePaths, docTitle)
        else ThermalPdf.invoice(context, company, bill, lines, imagePaths, docTitle)

    fun generate(context: Context, company: CompanyInfo, bill: Bill, lines: List<BillItem>, docTitle: String = "TAX INVOICE"): Uri {
        val doc = PdfDocument()
        val lineHeight = 20f
        val bodyRows = lines.size
        val estHeight = (260 + bodyRows * lineHeight + 220).toInt()
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_W, estHeight, 1).create()
        val page = doc.startPage(pageInfo)
        val c = page.canvas

        val title = Paint().apply { color = Color.BLACK; textSize = 20f; isFakeBoldText = true }
        val h = Paint().apply { color = Color.BLACK; textSize = 12f; isFakeBoldText = true }
        val p = Paint().apply { color = Color.DKGRAY; textSize = 11f }
        val rightP = Paint(p).apply { textAlign = Paint.Align.RIGHT }
        val rightH = Paint(h).apply { textAlign = Paint.Align.RIGHT }
        val rule = Paint().apply { color = Color.LTGRAY; strokeWidth = 1f }

        val right = PAGE_W - MARGIN
        var y = 40f

        c.drawText(company.name, MARGIN, y, title); y += 22f
        if (company.address.isNotBlank()) { c.drawText(company.address, MARGIN, y, p); y += 14f }
        if (company.phone.isNotBlank()) { c.drawText("Ph: ${company.phone}", MARGIN, y, p); y += 14f }
        y += 4f
        c.drawText(docTitle, MARGIN, y, h); y += 18f

        c.drawText("Bill No: ${bill.billNo}", MARGIN, y, p)
        c.drawText("Date: ${Format.date(bill.dateMillis)}", right, y, rightP); y += 16f
        c.drawText("Customer: ${bill.customerName}", MARGIN, y, p)
        c.drawText("Payment: ${bill.paymentMethod}", right, y, rightP); y += 14f

        c.drawLine(MARGIN, y, right, y, rule); y += 18f

        // Column headers
        c.drawText("Item", MARGIN, y, h)
        c.drawText("Qty", MARGIN + 190, y, h)
        c.drawText("Rate", MARGIN + 240, y, h)
        c.drawText("Tax%", MARGIN + 300, y, h)
        c.drawText("Amount", right, y, rightH); y += 6f
        c.drawLine(MARGIN, y, right, y, rule); y += 18f

        for (l in lines) {
            c.drawText(clip(l.name, 24), MARGIN, y, p)
            c.drawText(Format.qty(l.qty), MARGIN + 190, y, p)
            c.drawText(Format.money(l.price), MARGIN + 240, y, p)
            c.drawText(Format.money(l.taxPercent), MARGIN + 300, y, p)
            c.drawText(Format.money(l.lineTotal), right, y, rightP)
            y += lineHeight
        }

        c.drawLine(MARGIN, y, right, y, rule); y += 20f

        fun totalRow(label: String, value: Double, bold: Boolean = false) {
            c.drawText(label, MARGIN + 190, y, if (bold) h else p)
            c.drawText(Format.money(value), right, y, if (bold) rightH else rightP)
            y += 18f
        }
        totalRow("Sub Total", bill.subTotal)
        totalRow("Tax", bill.taxTotal)
        if (bill.additionalCharge != 0.0) totalRow("Additional", bill.additionalCharge)
        if (bill.discount != 0.0) totalRow("Discount", -bill.discount)
        c.drawLine(MARGIN + 180, y, right, y, rule); y += 20f
        totalRow("GRAND TOTAL", bill.grandTotal, bold = true)

        y += 24f
        c.drawText("Thank you! Visit again.", MARGIN, y, p)

        doc.finishPage(page)

        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "${bill.billNo}.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()

        return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }

    private fun clip(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1) + "…"
}
