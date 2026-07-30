package com.billing.pos.pdf

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.billing.pos.data.CompanyInfo
import com.billing.pos.data.Receipt
import com.billing.pos.util.Format
import java.io.File

/** Renders a receipt voucher to a shareable PDF and returns a content:// Uri. */
object ReceiptPdf {

    private const val PAGE_W = 400
    private const val MARGIN = 24f

    fun generate(context: Context, company: CompanyInfo, receipt: Receipt): Uri {
        val doc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_W, 340, 1).create()
        val page = doc.startPage(pageInfo)
        val c = page.canvas

        val title = Paint().apply { color = Color.BLACK; textSize = 20f; isFakeBoldText = true }
        val h = Paint().apply { color = Color.BLACK; textSize = 13f; isFakeBoldText = true }
        val p = Paint().apply { color = Color.DKGRAY; textSize = 12f }
        val big = Paint().apply { color = Color.BLACK; textSize = 18f; isFakeBoldText = true }
        val rightP = Paint(p).apply { textAlign = Paint.Align.RIGHT }
        val rule = Paint().apply { color = Color.LTGRAY; strokeWidth = 1f }

        val right = PAGE_W - MARGIN
        var y = 42f

        c.drawText(company.name, MARGIN, y, title); y += 20f
        if (company.address.isNotBlank()) { c.drawText(company.address, MARGIN, y, p); y += 14f }
        if (company.phone.isNotBlank()) { c.drawText("Ph: ${company.phone}", MARGIN, y, p); y += 14f }
        y += 6f
        c.drawText("RECEIPT VOUCHER", MARGIN, y, h); y += 8f
        c.drawLine(MARGIN, y, right, y, rule); y += 20f

        c.drawText("Receipt No: ${receipt.receiptNo}", MARGIN, y, p)
        c.drawText(Format.date(receipt.dateMillis), right, y, rightP); y += 18f
        c.drawText("Received from: ${receipt.payFrom.ifBlank { receipt.customerName }}", MARGIN, y, p); y += 18f
        if (receipt.billNo.isNotBlank()) { c.drawText("Against invoice: ${receipt.billNo}", MARGIN, y, p); y += 18f }
        c.drawText("Payment mode: ${receipt.paymentMode}", MARGIN, y, p); y += 20f

        c.drawLine(MARGIN, y, right, y, rule); y += 24f
        c.drawText("Amount received", MARGIN, y, h)
        c.drawText("Rs. " + Format.money(receipt.amount), right, y, Paint(big).apply { textAlign = Paint.Align.RIGHT })
        y += 20f
        c.drawLine(MARGIN, y, right, y, rule); y += 40f

        c.drawText("Signature", right - 60, y, p)
        y += 24f
        c.drawText("Thank you!", MARGIN, y, p)

        doc.finishPage(page)

        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val safe = receipt.receiptNo.ifBlank { "receipt" }.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(dir, "$safe.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()

        return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }
}
