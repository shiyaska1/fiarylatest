package com.billing.pos.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.CompanyInfo
import com.billing.pos.data.LabBill
import com.billing.pos.data.LabResultValue
import com.billing.pos.util.Format
import java.io.File

/** A clean A4 laboratory report: lab header, patient box, grouped results, technician sign-off. */
object LabResultPdf {

    private const val PW = 595f
    private const val PH = 842f
    private const val M = 36f

    fun make(context: Context, company: CompanyInfo, bill: LabBill, results: List<LabResultValue>): Uri {
        val prefs = AppPrefs(context)
        val doc = PdfDocument()

        val title = Paint().apply { color = 0xFF000000.toInt(); isAntiAlias = true; textSize = 22f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val sub = Paint().apply { color = 0xFF333333.toInt(); isAntiAlias = true; textSize = 10f }
        val small = Paint().apply { color = 0xFF555555.toInt(); isAntiAlias = true; textSize = 9f }
        val cell = Paint().apply { color = 0xFF000000.toInt(); isAntiAlias = true; textSize = 10f }
        val cellBold = Paint(cell).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val abn = Paint(cellBold).apply { color = 0xFFC00000.toInt() }
        val line = Paint().apply { color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f }
        val faint = Paint().apply { color = 0xFFBBBBBB.toInt(); style = Paint.Style.STROKE; strokeWidth = 0.7f }

        val x0 = M
        val xEnd = PW - M
        val cResult = xEnd - 210f
        val cUnit = xEnd - 130f
        val cRef = xEnd - 60f

        // Optional pre-printed letterhead (image or PDF) drawn as the page background.
        val letterhead = loadLetterhead(prefs.labLetterheadPath)
        val seal = prefs.labSealPath.takeIf { it.isNotBlank() }?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
        val sign = prefs.labSignaturePath.takeIf { it.isNotBlank() }?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
        val lineH = 15f
        val topSkip = if (letterhead != null) prefs.labTopSkipLines * lineH else 0f
        val bottomLimit = if (letterhead != null) PH - (prefs.labBottomSkipLines * lineH) else PH - 90f

        var page = doc.startPage(PdfDocument.PageInfo.Builder(PW.toInt(), PH.toInt(), 1).create())
        var c = page.canvas
        var y: Float

        fun drawBackground() { letterhead?.let { c.drawBitmap(it, null, Rect(0, 0, PW.toInt(), PH.toInt()), null) } }
        drawBackground()

        if (letterhead != null) {
            // Letterhead already carries the lab name/address — skip the app header entirely.
            y = M + topSkip
        } else {
            val logo = prefs.logoPath.takeIf { it.isNotBlank() }?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
            var textX = x0
            if (logo != null) {
                val h = 56f; val w = h * logo.width / logo.height
                c.drawBitmap(logo, null, Rect(x0.toInt(), M.toInt(), (x0 + w).toInt(), (M + h).toInt()), null)
                textX = x0 + w + 12f
            }
            c.drawText(company.name.ifBlank { "Laboratory" }, textX, M + 20f, title)
            var hy = M + 36f
            if (company.address.isNotBlank()) { c.drawText(company.address, textX, hy, sub); hy += 13f }
            if (company.phone.isNotBlank()) { c.drawText("Phone: ${company.phone}", textX, hy, sub); hy += 13f }
            y = maxOf(hy, M + 60f) + 6f
            c.drawLine(x0, y, xEnd, y, line); y += 18f
        }

        val tp = Paint(cellBold).apply { textSize = 13f; textAlign = Paint.Align.CENTER }
        c.drawText("LABORATORY REPORT", (x0 + xEnd) / 2f, y, tp); y += 16f

        // Patient box
        val boxTop = y
        c.drawText("Patient : ${bill.patientName}", x0 + 4f, y + 14f, cellBold)
        c.drawText("Bill No : ${bill.billNo}", cResult, y + 14f, sub)
        c.drawText("Age/Sex : ${listOf(bill.age, bill.gender).filter { it.isNotBlank() }.joinToString(" / ")}", x0 + 4f, y + 30f, sub)
        c.drawText("Date : ${Format.date(if (bill.resultDateMillis > 0) bill.resultDateMillis else bill.dateMillis)}", cResult, y + 30f, sub)
        c.drawText("Referred by : ${bill.referredBy.ifBlank { "-" }}", x0 + 4f, y + 46f, sub)
        c.drawRect(x0, boxTop, xEnd, boxTop + 56f, line)
        y = boxTop + 56f + 16f

        fun columnHeader() {
            c.drawText("INVESTIGATION", x0 + 2f, y + 11f, cellBold)
            c.drawText("RESULT", cResult, y + 11f, cellBold)
            c.drawText("UNIT", cUnit, y + 11f, cellBold)
            c.drawText("REFERENCE", cRef, y + 11f, cellBold)
            y += 16f
            c.drawLine(x0, y, xEnd, y, line); y += 6f
        }
        fun newPage() {
            doc.finishPage(page)
            page = doc.startPage(PdfDocument.PageInfo.Builder(PW.toInt(), PH.toInt(), doc.pages.size + 1).create())
            c = page.canvas; drawBackground(); y = M + topSkip
        }
        fun ensureSpace(need: Float) { if (y + need > bottomLimit) newPage() }

        // Group by test (preserve first-seen order), then by evaluation group within the test.
        val byTest = LinkedHashMap<String, MutableList<LabResultValue>>()
        results.forEach { byTest.getOrPut(it.testName) { mutableListOf() }.add(it) }

        fun testTitle(name: String) {
            ensureSpace(64f)
            c.drawText(name.uppercase(), x0 + 2f, y + 11f, cellBold); y += 16f
            c.drawLine(x0, y, xEnd, y, faint); y += 4f
            columnHeader()
        }

        byTest.forEach { (testName, rows) ->
            testTitle(testName)
            var lastGroup: String? = null
            rows.forEach { r ->
                if (r.isPageBreak) {
                    // Manual page break: continue on a fresh A4 page.
                    newPage(); testTitle(testName); lastGroup = null; return@forEach
                }
                if (r.groupName.isNotBlank() && r.groupName != lastGroup) {
                    ensureSpace(18f); c.drawText(r.groupName, x0 + 6f, y + 11f, cellBold); y += 18f
                }
                lastGroup = r.groupName
                ensureSpace(18f)
                if (r.isHeading) {
                    c.drawText(r.evaluationName, x0 + 6f, y + 11f, cellBold)
                } else {
                    val indent = if (r.groupName.isNotBlank()) 12f else 2f
                    c.drawText(clip(r.evaluationName, 34), x0 + indent, y + 11f, cell)
                    c.drawText(r.result.ifBlank { "-" }, cResult, y + 11f, if (isOutOfRange(r)) abn else cellBold)
                    c.drawText(clip(r.unit, 12), cUnit, y + 11f, cell)
                    c.drawText(clip(r.normalValue, 16), cRef, y + 11f, small)
                }
                y += 18f
                c.drawLine(x0, y, xEnd, y, faint)   // separator at the row's bottom edge
            }
            y += 10f
        }

        // Sign-off with optional seal + signature above the technician line.
        ensureSpace(80f)
        val signBaseY = if (letterhead != null) (bottomLimit - 14f).coerceAtLeast(y + 30f) else maxOf(y + 30f, PH - 80f)
        seal?.let {
            val h = 58f; val w = h * it.width / it.height
            c.drawBitmap(it, null, Rect((xEnd - 250f).toInt(), (signBaseY - 54f).toInt(), (xEnd - 250f + w).toInt(), (signBaseY + 4f).toInt()), null)
        }
        sign?.let {
            val h = 40f; val w = h * it.width / it.height
            c.drawBitmap(it, null, Rect((xEnd - 150f).toInt(), (signBaseY - 42f).toInt(), (xEnd - 150f + w).toInt(), (signBaseY - 2f).toInt()), null)
        }
        c.drawLine(xEnd - 150f, signBaseY + 2f, xEnd - 20f, signBaseY + 2f, line)
        c.drawText("Lab Technician", xEnd - 140f, signBaseY + 16f, small)
        if (letterhead == null) c.drawText("** End of report **", (x0 + xEnd) / 2f, PH - M, Paint(small).apply { textAlign = Paint.Align.CENTER })

        doc.finishPage(page)
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val safe = bill.billNo.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(dir, "labreport_$safe.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }

    /** Loads the letterhead as an A4-sized bitmap: renders page 0 of a PDF, or decodes an image. */
    private fun loadLetterhead(path: String): Bitmap? {
        if (path.isBlank()) return null
        val f = File(path)
        if (!f.exists()) return null
        return if (path.endsWith(".pdf", ignoreCase = true)) runCatching {
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                val r = PdfRenderer(pfd)
                try {
                    val pg = r.openPage(0)
                    val bmp = Bitmap.createBitmap((PW * 2).toInt(), (PH * 2).toInt(), Bitmap.Config.ARGB_8888)
                    Canvas(bmp).drawColor(0xFFFFFFFF.toInt())
                    pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    pg.close()
                    bmp
                } finally { r.close() }
            }
        }.getOrNull() else runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
    }

    /** Best-effort abnormal flag: numeric result outside a "lo - hi" numeric reference range. */
    private fun isOutOfRange(r: LabResultValue): Boolean {
        val v = r.result.trim().toDoubleOrNull() ?: return false
        val m = Regex("(-?\\d+(?:\\.\\d+)?)\\s*-\\s*(-?\\d+(?:\\.\\d+)?)").find(r.normalValue) ?: return false
        val lo = m.groupValues[1].toDoubleOrNull() ?: return false
        val hi = m.groupValues[2].toDoubleOrNull() ?: return false
        return v < lo || v > hi
    }

    private fun clip(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1) + "…"
}
