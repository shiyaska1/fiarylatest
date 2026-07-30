package com.billing.pos.pdf

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.billing.pos.data.CompanyInfo
import com.billing.pos.data.Item
import com.billing.pos.util.Format
import java.io.File

/** Renders a single item (name, selling price, details and photo) to a shareable PDF. */
object ProductPdf {

    private const val PAGE_W = 460
    private const val MARGIN = 24f

    fun generate(context: Context, company: CompanyInfo, item: Item, imagePath: String?): Uri {
        val bmp = imagePath?.let { path ->
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                val longest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
                var sample = 1
                while (longest / sample > 900) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
        }

        val imgW = (PAGE_W - 2 * MARGIN)
        val imgH = if (bmp != null) imgW * bmp.height / bmp.width.coerceAtLeast(1) else 0f
        val estHeight = (200 + imgH + 40).toInt()

        val doc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_W, estHeight, 1).create()
        val page = doc.startPage(pageInfo)
        val c = page.canvas

        val title = Paint().apply { color = Color.BLACK; textSize = 18f; isFakeBoldText = true }
        val name = Paint().apply { color = Color.BLACK; textSize = 20f; isFakeBoldText = true }
        val price = Paint().apply { color = Color.rgb(0x1B, 0x5E, 0x20); textSize = 22f; isFakeBoldText = true }
        val p = Paint().apply { color = Color.DKGRAY; textSize = 12f }
        val rule = Paint().apply { color = Color.LTGRAY; strokeWidth = 1f }
        val right = PAGE_W - MARGIN
        var y = 40f

        c.drawText(company.name, MARGIN, y, title); y += 20f
        if (company.address.isNotBlank()) { c.drawText(company.address, MARGIN, y, p); y += 14f }
        if (company.phone.isNotBlank()) { c.drawText("Ph: ${company.phone}", MARGIN, y, p); y += 14f }
        y += 6f
        c.drawLine(MARGIN, y, right, y, rule); y += 22f

        c.drawText(clip(item.name, 34), MARGIN, y, name); y += 22f
        val meta = buildList {
            if (item.category.isNotBlank()) add(item.category)
            if (item.unit.isNotBlank()) add("Unit: ${item.unit}")
            if (item.barcode.isNotBlank()) add("Code: ${item.barcode}")
        }.joinToString("   •   ")
        if (meta.isNotBlank()) { c.drawText(meta, MARGIN, y, p); y += 16f }
        c.drawText("Selling Price: ${Format.rupee(item.price)}", MARGIN, y, price); y += 22f
        if (item.storeLocation.isNotBlank()) { c.drawText("Location: ${item.storeLocation}", MARGIN, y, p); y += 16f }

        if (bmp != null) {
            y += 6f
            val dst = Rect(MARGIN.toInt(), y.toInt(), (MARGIN + imgW).toInt(), (y + imgH).toInt())
            c.drawBitmap(bmp, null, dst, null)
            y += imgH
        }

        doc.finishPage(page)

        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val safe = item.name.replace(Regex("[^A-Za-z0-9]+"), "_").take(30).ifBlank { "item" }
        val file = File(dir, "product_$safe.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()

        return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }

    private fun clip(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1) + "…"
}
