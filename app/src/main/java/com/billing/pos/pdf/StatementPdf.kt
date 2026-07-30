package com.billing.pos.pdf

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.billing.pos.data.CompanyInfo
import com.billing.pos.util.Format
import java.io.File

/** Renders an outstanding statement for one party (customer/supplier) to a PDF file. */
object StatementPdf {

    data class Line(val no: String, val date: Long, val balance: Double)

    private const val PAGE_W = 460
    private const val MARGIN = 24f

    fun generate(
        context: Context, company: CompanyInfo, partyName: String, heading: String,
        phone: String, lines: List<Line>, total: Double
    ): File {
        val rowH = 18f
        val estHeight = (200 + lines.size * rowH + 60).toInt()
        val doc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_W, estHeight, 1).create()
        val page = doc.startPage(pageInfo)
        val c = page.canvas

        val title = Paint().apply { color = Color.BLACK; textSize = 18f; isFakeBoldText = true }
        val h = Paint().apply { color = Color.BLACK; textSize = 12f; isFakeBoldText = true }
        val p = Paint().apply { color = Color.DKGRAY; textSize = 11f }
        val rightP = Paint(p).apply { textAlign = Paint.Align.RIGHT }
        val rightH = Paint(h).apply { textAlign = Paint.Align.RIGHT }
        val rule = Paint().apply { color = Color.LTGRAY; strokeWidth = 1f }
        val right = PAGE_W - MARGIN
        var y = 40f

        c.drawText(company.name, MARGIN, y, title); y += 20f
        if (company.address.isNotBlank()) { c.drawText(company.address, MARGIN, y, p); y += 14f }
        if (company.phone.isNotBlank()) { c.drawText("Ph: ${company.phone}", MARGIN, y, p); y += 14f }
        y += 4f
        c.drawText(heading, MARGIN, y, h); y += 18f
        c.drawText("Party: $partyName", MARGIN, y, p)
        if (phone.isNotBlank()) c.drawText("Ph: $phone", right, y, rightP)
        y += 16f
        c.drawLine(MARGIN, y, right, y, rule); y += 16f

        c.drawText("Voucher", MARGIN, y, h)
        c.drawText("Date", MARGIN + 180, y, h)
        c.drawText("Balance", right, y, rightH); y += 6f
        c.drawLine(MARGIN, y, right, y, rule); y += 16f

        for (l in lines.sortedBy { it.date }) {
            c.drawText(l.no, MARGIN, y, p)
            c.drawText(Format.date(l.date), MARGIN + 180, y, p)
            c.drawText(Format.money(l.balance), right, y, rightP)
            y += rowH
        }

        c.drawLine(MARGIN, y, right, y, rule); y += 18f
        c.drawText("TOTAL DUE", MARGIN, y, h)
        c.drawText(Format.money(total), right, y, rightH)

        doc.finishPage(page)

        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val safe = partyName.replace(Regex("[^A-Za-z0-9]+"), "_").take(24).ifBlank { "party" }
        val file = File(dir, "statement_$safe.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }
}
