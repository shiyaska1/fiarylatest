package com.billing.pos.poster

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.io.File

/** Poster shape. Square suits a feed post; Story suits WhatsApp/Instagram status. */
enum class PosterSize(val label: String, val w: Int, val h: Int) {
    SQUARE("Square 1:1", 1080, 1080),
    STORY("Story 9:16", 1080, 1920)
}

/** The built-in designs, plus CUSTOM which draws over an imported background image. */
enum class PosterTemplate(val label: String) {
    BOLD("Bold offer"),
    CLEAN("Clean product"),
    TAG("Price tag"),
    FESTIVE("Festive"),
    CUSTOM("My template")
}

data class PosterSpec(
    val template: PosterTemplate = PosterTemplate.BOLD,
    val size: PosterSize = PosterSize.SQUARE,
    val headline: String = "",
    val itemName: String = "",
    val price: String = "",
    val offer: String = "",
    val footer: String = "",
    /** Product photo drawn on the poster. */
    val photoPath: String? = null,
    /** Background for [PosterTemplate.CUSTOM] — an imported template image. */
    val backgroundPath: String? = null,
    val accent: Int = 0xFF1565C0.toInt()
)

/**
 * Draws a social-media poster onto a bitmap.
 *
 * Plain Android Canvas rather than a Compose screenshot: the output has to be a fixed
 * pixel size regardless of the phone's screen, and it must render identically whether it
 * is being previewed or exported.
 */
object PosterRenderer {

    fun render(context: Context, spec: PosterSpec): Bitmap {
        val w = spec.size.w
        val h = spec.size.h
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        when (spec.template) {
            PosterTemplate.BOLD -> drawBold(c, w, h, spec, context)
            PosterTemplate.CLEAN -> drawClean(c, w, h, spec, context)
            PosterTemplate.TAG -> drawTag(c, w, h, spec, context)
            PosterTemplate.FESTIVE -> drawFestive(c, w, h, spec, context)
            PosterTemplate.CUSTOM -> drawCustom(c, w, h, spec, context)
        }
        return bmp
    }

    // ---------- templates ----------

    private fun drawBold(c: Canvas, w: Int, h: Int, s: PosterSpec, ctx: Context) {
        c.drawColor(s.accent)
        // Light panel holding the product, leaving a bold band top and bottom.
        val pad = w * 0.06f
        val panel = RectF(pad, h * 0.22f, w - pad, h * 0.74f)
        c.drawRoundRect(panel, 40f, 40f, fill(Color.WHITE))

        text(c, s.headline.ifBlank { "SPECIAL OFFER" }, w / 2f, h * 0.13f,
            size = w * 0.085f, color = Color.WHITE, bold = true, center = true, maxWidth = w - pad * 2)

        val photo = loadPhoto(s.photoPath, (w * 0.5f).toInt())
        var y = panel.top + 40f
        if (photo != null) {
            val ph = (panel.height() * 0.52f)
            val dst = fitRect(photo, RectF(panel.left + 30f, y, panel.right - 30f, y + ph))
            c.drawBitmap(photo, null, dst, imagePaint())
            y += ph + 24f
        }
        text(c, s.itemName, w / 2f, y + w * 0.05f, size = w * 0.06f,
            color = Color.BLACK, bold = true, center = true, maxWidth = panel.width() - 60f)

        if (s.price.isNotBlank()) {
            text(c, s.price, w / 2f, panel.bottom - 30f, size = w * 0.095f,
                color = s.accent, bold = true, center = true, maxWidth = panel.width() - 60f)
        }
        if (s.offer.isNotBlank()) {
            text(c, s.offer, w / 2f, h * 0.82f, size = w * 0.055f,
                color = Color.WHITE, bold = true, center = true, maxWidth = w - pad * 2)
        }
        footer(c, w, h, s)
    }

    private fun drawClean(c: Canvas, w: Int, h: Int, s: PosterSpec, ctx: Context) {
        c.drawColor(Color.WHITE)
        val pad = w * 0.07f
        val photo = loadPhoto(s.photoPath, (w * 0.8f).toInt())
        var y = h * 0.10f

        text(c, s.headline, w / 2f, y, size = w * 0.06f, color = s.accent,
            bold = true, center = true, maxWidth = w - pad * 2)
        y += w * 0.04f

        if (photo != null) {
            val ph = h * 0.42f
            val dst = fitRect(photo, RectF(pad, y, w - pad, y + ph))
            c.drawBitmap(photo, null, dst, imagePaint())
            y += ph + w * 0.06f
        }
        text(c, s.itemName, w / 2f, y, size = w * 0.075f, color = Color.BLACK,
            bold = true, center = true, maxWidth = w - pad * 2)
        y += w * 0.09f
        if (s.price.isNotBlank()) {
            text(c, s.price, w / 2f, y, size = w * 0.105f, color = s.accent,
                bold = true, center = true, maxWidth = w - pad * 2)
            y += w * 0.09f
        }
        if (s.offer.isNotBlank()) {
            val chip = RectF(pad, y - w * 0.05f, w - pad, y + w * 0.02f)
            c.drawRoundRect(chip, 24f, 24f, fill(s.accent))
            text(c, s.offer, w / 2f, y, size = w * 0.045f, color = Color.WHITE,
                bold = true, center = true, maxWidth = chip.width() - 40f)
        }
        footer(c, w, h, s)
    }

    private fun drawTag(c: Canvas, w: Int, h: Int, s: PosterSpec, ctx: Context) {
        c.drawColor(Color.WHITE)
        // Accent block behind the price, product photo above.
        val split = h * 0.55f
        c.drawRect(0f, split, w.toFloat(), h.toFloat(), fill(s.accent))

        val photo = loadPhoto(s.photoPath, (w * 0.8f).toInt())
        if (photo != null) {
            val dst = fitRect(photo, RectF(w * 0.08f, h * 0.08f, w * 0.92f, split - 30f))
            c.drawBitmap(photo, null, dst, imagePaint())
        } else {
            text(c, s.headline.ifBlank { "TODAY'S PRICE" }, w / 2f, split * 0.5f,
                size = w * 0.07f, color = s.accent, bold = true, center = true, maxWidth = w * 0.8f)
        }
        text(c, s.itemName, w / 2f, split + h * 0.09f, size = w * 0.065f,
            color = Color.WHITE, bold = true, center = true, maxWidth = w * 0.86f)
        if (s.price.isNotBlank()) {
            text(c, s.price, w / 2f, split + h * 0.22f, size = w * 0.14f,
                color = Color.WHITE, bold = true, center = true, maxWidth = w * 0.86f)
        }
        if (s.offer.isNotBlank()) {
            text(c, s.offer, w / 2f, split + h * 0.30f, size = w * 0.05f,
                color = Color.WHITE, bold = false, center = true, maxWidth = w * 0.86f)
        }
        footer(c, w, h, s, onAccent = true)
    }

    private fun drawFestive(c: Canvas, w: Int, h: Int, s: PosterSpec, ctx: Context) {
        val grad = Paint().apply {
            shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                s.accent, darken(s.accent), Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), grad)
        // Soft circles for decoration.
        val glow = Paint().apply { color = Color.WHITE; alpha = 28; isAntiAlias = true }
        c.drawCircle(w * 0.12f, h * 0.14f, w * 0.22f, glow)
        c.drawCircle(w * 0.9f, h * 0.85f, w * 0.28f, glow)

        val pad = w * 0.08f
        text(c, s.headline.ifBlank { "GRAND OFFER" }, w / 2f, h * 0.16f, size = w * 0.09f,
            color = Color.WHITE, bold = true, center = true, maxWidth = w - pad * 2)

        val photo = loadPhoto(s.photoPath, (w * 0.6f).toInt())
        if (photo != null) {
            val d = w * 0.5f
            val dst = RectF(w / 2f - d / 2, h * 0.24f, w / 2f + d / 2, h * 0.24f + d)
            c.drawRoundRect(dst, 30f, 30f, fill(Color.WHITE))
            val inner = RectF(dst.left + 12f, dst.top + 12f, dst.right - 12f, dst.bottom - 12f)
            c.drawBitmap(photo, null, fitRect(photo, inner), imagePaint())
        }
        text(c, s.itemName, w / 2f, h * 0.68f, size = w * 0.07f, color = Color.WHITE,
            bold = true, center = true, maxWidth = w - pad * 2)
        if (s.price.isNotBlank()) {
            text(c, s.price, w / 2f, h * 0.78f, size = w * 0.11f, color = Color.WHITE,
                bold = true, center = true, maxWidth = w - pad * 2)
        }
        if (s.offer.isNotBlank()) {
            text(c, s.offer, w / 2f, h * 0.85f, size = w * 0.05f, color = Color.WHITE,
                bold = false, center = true, maxWidth = w - pad * 2)
        }
        footer(c, w, h, s, onAccent = true)
    }

    /** Imported template: the user's own artwork, with the text drawn over it. */
    private fun drawCustom(c: Canvas, w: Int, h: Int, s: PosterSpec, ctx: Context) {
        c.drawColor(Color.WHITE)
        val bg = loadPhoto(s.backgroundPath, maxOf(w, h))
        if (bg != null) {
            // Cover the whole canvas, cropping the overflow.
            val scale = maxOf(w / bg.width.toFloat(), h / bg.height.toFloat())
            val dw = bg.width * scale
            val dh = bg.height * scale
            val dst = RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
            c.drawBitmap(bg, null, dst, imagePaint())
        }
        // Dark scrim at the bottom so text stays readable over any artwork.
        val scrim = Paint().apply {
            shader = LinearGradient(
                0f, h * 0.45f, 0f, h.toFloat(),
                Color.TRANSPARENT, Color.argb(200, 0, 0, 0), Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, h * 0.45f, w.toFloat(), h.toFloat(), scrim)

        val pad = w * 0.07f
        if (s.headline.isNotBlank()) {
            text(c, s.headline, w / 2f, h * 0.62f, size = w * 0.07f, color = Color.WHITE,
                bold = true, center = true, maxWidth = w - pad * 2)
        }
        text(c, s.itemName, w / 2f, h * 0.72f, size = w * 0.065f, color = Color.WHITE,
            bold = true, center = true, maxWidth = w - pad * 2)
        if (s.price.isNotBlank()) {
            text(c, s.price, w / 2f, h * 0.82f, size = w * 0.10f, color = Color.WHITE,
                bold = true, center = true, maxWidth = w - pad * 2)
        }
        if (s.offer.isNotBlank()) {
            text(c, s.offer, w / 2f, h * 0.88f, size = w * 0.045f, color = Color.WHITE,
                bold = false, center = true, maxWidth = w - pad * 2)
        }
        footer(c, w, h, s, onAccent = true)
    }

    // ---------- helpers ----------

    private fun footer(c: Canvas, w: Int, h: Int, s: PosterSpec, onAccent: Boolean = false) {
        if (s.footer.isBlank()) return
        text(
            c, s.footer, w / 2f, h - h * 0.035f,
            size = w * 0.038f,
            color = if (onAccent) Color.WHITE else Color.DKGRAY,
            bold = false, center = true, maxWidth = w * 0.9f
        )
    }

    private fun fill(color: Int) = Paint().apply {
        this.color = color; isAntiAlias = true; style = Paint.Style.FILL
    }

    private fun imagePaint() = Paint().apply { isAntiAlias = true; isFilterBitmap = true }

    private fun darken(color: Int): Int {
        val f = 0.55f
        return Color.rgb(
            (Color.red(color) * f).toInt(),
            (Color.green(color) * f).toInt(),
            (Color.blue(color) * f).toInt()
        )
    }

    /** Scales [src] into [box] keeping its aspect ratio, centred. */
    private fun fitRect(src: Bitmap, box: RectF): RectF {
        val scale = minOf(box.width() / src.width, box.height() / src.height)
        val dw = src.width * scale
        val dh = src.height * scale
        val cx = box.centerX()
        val cy = box.centerY()
        return RectF(cx - dw / 2, cy - dh / 2, cx + dw / 2, cy + dh / 2)
    }

    private fun loadPhoto(path: String?, targetPx: Int): Bitmap? {
        if (path.isNullOrBlank()) return null
        val f = File(path)
        if (!f.exists()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > targetPx * 2) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }

    /**
     * Draws [value] centred (or left) at [y], wrapping to [maxWidth] and shrinking the type
     * if it still will not fit — a long item name must not run off the poster.
     */
    private fun text(
        c: Canvas,
        value: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        bold: Boolean,
        center: Boolean,
        maxWidth: Float
    ) {
        if (value.isBlank()) return
        val p = Paint().apply {
            this.color = color
            isAntiAlias = true
            textSize = size
            typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
            textAlign = if (center) Paint.Align.CENTER else Paint.Align.LEFT
        }
        var lines = wrap(value, p, maxWidth)
        // Two lines is the most any of these slots can hold; shrink rather than overflow.
        var guard = 0
        while (lines.size > 2 && guard < 6) {
            p.textSize = p.textSize * 0.85f
            lines = wrap(value, p, maxWidth)
            guard++
        }
        val lineHeight = p.textSize * 1.15f
        var ty = y - (lines.size - 1) * lineHeight / 2f
        lines.forEach { line ->
            c.drawText(line, x, ty, p)
            ty += lineHeight
        }
    }

    private fun wrap(value: String, p: Paint, maxWidth: Float): List<String> {
        val words = value.trim().split(Regex("\\s+"))
        val out = ArrayList<String>()
        var line = StringBuilder()
        words.forEach { word ->
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (p.measureText(candidate) <= maxWidth || line.isEmpty()) {
                line = StringBuilder(candidate)
            } else {
                out.add(line.toString())
                line = StringBuilder(word)
            }
        }
        if (line.isNotEmpty()) out.add(line.toString())
        return out
    }

    /** Measures nothing — kept so callers can reason about the drawn bounds if needed. */
    fun measure(text: String, p: Paint): Rect =
        Rect().also { p.getTextBounds(text, 0, text.length, it) }
}
