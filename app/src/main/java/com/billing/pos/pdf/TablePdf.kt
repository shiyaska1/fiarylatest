package com.billing.pos.pdf

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.billing.pos.data.CompanyInfo
import java.io.File

/** Generic titled-table PDF used by list/report "Download PDF" actions. */
object TablePdf {

    data class Col(val title: String, val weight: Float, val right: Boolean = false)

    private const val PAGE_W = 595   // ~A4 width in points (portrait)
    private const val MARGIN = 24f

    fun generate(
        context: Context, company: CompanyInfo, title: String, subtitle: String,
        columns: List<Col>, rows: List<List<String>>, footer: List<Pair<String, String>> = emptyList()
    ): File {
        val rowH = 16f
        val estHeight = (140f + (rows.size + footer.size + 3) * rowH + 40f).toInt().coerceAtLeast(220)
        val doc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_W, estHeight, 1).create()
        val page = doc.startPage(pageInfo)
        val c = page.canvas

        val titleP = Paint().apply { color = Color.BLACK; textSize = 16f; isFakeBoldText = true }
        val h = Paint().apply { color = Color.BLACK; textSize = 10f; isFakeBoldText = true }
        val hR = Paint(h).apply { textAlign = Paint.Align.RIGHT }
        val p = Paint().apply { color = Color.DKGRAY; textSize = 10f }
        val pR = Paint(p).apply { textAlign = Paint.Align.RIGHT }
        val small = Paint().apply { color = Color.GRAY; textSize = 9f }
        val rule = Paint().apply { color = Color.LTGRAY; strokeWidth = 1f }

        val contentW = PAGE_W - 2 * MARGIN
        val totalWeight = columns.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(1f)
        val xs = FloatArray(columns.size)
        var acc = MARGIN
        for (i in columns.indices) { xs[i] = acc; acc += contentW * (columns[i].weight / totalWeight) }
        fun colRight(i: Int) = if (i == columns.size - 1) PAGE_W - MARGIN else xs[i + 1] - 4f

        var y = 36f
        c.drawText(company.name, MARGIN, y, titleP); y += 18f
        if (company.address.isNotBlank()) { c.drawText(company.address, MARGIN, y, small); y += 12f }
        if (company.phone.isNotBlank()) { c.drawText("Ph: ${company.phone}", MARGIN, y, small); y += 12f }
        y += 4f
        c.drawText(title, MARGIN, y, h); y += 14f
        if (subtitle.isNotBlank()) { c.drawText(subtitle, MARGIN, y, small); y += 14f }
        c.drawLine(MARGIN, y, PAGE_W - MARGIN, y, rule); y += 14f

        columns.forEachIndexed { i, col ->
            if (col.right) c.drawText(col.title, colRight(i), y, hR) else c.drawText(col.title, xs[i], y, h)
        }
        y += 6f; c.drawLine(MARGIN, y, PAGE_W - MARGIN, y, rule); y += 12f

        for (row in rows) {
            columns.forEachIndexed { i, col ->
                val text = row.getOrElse(i) { "" }
                val maxW = colRight(i) - xs[i]
                val clipped = clip(text, p, maxW)
                if (col.right) c.drawText(clipped, colRight(i), y, pR) else c.drawText(clipped, xs[i], y, p)
            }
            y += rowH
        }

        if (footer.isNotEmpty()) {
            c.drawLine(MARGIN, y, PAGE_W - MARGIN, y, rule); y += 14f
            footer.forEach { (k, v) ->
                c.drawText(k, MARGIN, y, h); c.drawText(v, PAGE_W - MARGIN, y, hR); y += rowH
            }
        }

        doc.finishPage(page)
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val safe = title.replace(Regex("[^A-Za-z0-9]+"), "_").take(28).ifBlank { "list" }
        val file = File(dir, "$safe.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    private fun clip(s: String, paint: Paint, maxW: Float): String {
        if (maxW <= 0f || paint.measureText(s) <= maxW) return s
        var end = s.length
        while (end > 1 && paint.measureText(s.substring(0, end) + "…") > maxW) end--
        return s.substring(0, end) + "…"
    }
}
