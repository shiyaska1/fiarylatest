package com.billing.pos.pdf

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.billing.pos.data.AppPrefs
import com.billing.pos.util.Format
import java.io.File

/** A one-page A4 PDF of a diary entry: type, title, body, with the date and time. */
object DiaryPdf {

    private const val PW = 595f   // A4 @72dpi
    private const val PH = 842f
    private const val M = 40f

    fun make(context: Context, type: String, title: String, body: String, dateMillis: Long): Uri? =
        runCatching {
            val company = AppPrefs(context).company
            val doc = PdfDocument()
            var page = doc.startPage(PdfDocument.PageInfo.Builder(PW.toInt(), PH.toInt(), 1).create())
            var c = page.canvas

            val h1 = Paint().apply {
                isAntiAlias = true; textSize = 20f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val small = Paint().apply { isAntiAlias = true; textSize = 10f; color = 0xFF555555.toInt() }
            val label = Paint().apply {
                isAntiAlias = true; textSize = 11f; color = 0xFF555555.toInt()
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val bodyPaint = Paint().apply { isAntiAlias = true; textSize = 12f }
            val rule = Paint().apply { color = 0xFFCCCCCC.toInt(); strokeWidth = 1f }

            var y = M + 12f
            if (company.name.isNotBlank()) { c.drawText(company.name, M, y, h1); y += 18f }
            if (company.phone.isNotBlank()) { c.drawText("Ph: ${company.phone}", M, y, small); y += 14f }

            // Date and time of the entry.
            c.drawText(Format.dateTime(dateMillis), PW - M, y - 14f, Paint(small).apply {
                textAlign = Paint.Align.RIGHT
            })
            y += 6f
            c.drawLine(M, y, PW - M, y, rule); y += 20f

            if (type.isNotBlank()) {
                c.drawText("TYPE", M, y, label)
                c.drawText(type, M + 60f, y, bodyPaint)
                y += 18f
            }
            if (title.isNotBlank()) {
                c.drawText(title, M, y, h1); y += 22f
            }
            c.drawLine(M, y, PW - M, y, rule); y += 18f

            // Body, wrapped, with a new page when it runs past the bottom.
            val maxWidth = PW - M * 2
            body.split('\n').forEach { raw ->
                wrap(raw, bodyPaint, maxWidth).forEach { line ->
                    if (y > PH - M) {
                        doc.finishPage(page)
                        page = doc.startPage(
                            PdfDocument.PageInfo.Builder(PW.toInt(), PH.toInt(), doc.pages.size + 1).create()
                        )
                        c = page.canvas
                        y = M + 12f
                    }
                    c.drawText(line, M, y, bodyPaint)
                    y += 16f
                }
            }
            doc.finishPage(page)

            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val f = File(dir, "diary_${System.currentTimeMillis()}.pdf")
            f.outputStream().use { doc.writeTo(it) }
            doc.close()
            FileProvider.getUriForFile(context, "${context.packageName}.provider", f)
        }.getOrNull()

    private fun wrap(text: String, p: Paint, maxWidth: Float): List<String> {
        if (text.isBlank()) return listOf("")
        val words = text.trim().split(Regex("\\s+"))
        val out = ArrayList<String>()
        var line = StringBuilder()
        words.forEach { w ->
            val candidate = if (line.isEmpty()) w else "$line $w"
            if (p.measureText(candidate) <= maxWidth || line.isEmpty()) line = StringBuilder(candidate)
            else { out.add(line.toString()); line = StringBuilder(w) }
        }
        if (line.isNotEmpty()) out.add(line.toString())
        return out
    }
}
