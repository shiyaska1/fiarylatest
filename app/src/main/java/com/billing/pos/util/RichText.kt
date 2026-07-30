package com.billing.pos.util

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * A very small formatting markup for notes and terms.
 *
 * It is stored as plain text, so nothing about the database, the backup format or the
 * existing notes has to change — an unformatted note is simply one with no markers:
 *
 *   `*bold*`   `_italic_`   `{14|larger text}`
 *
 * The editor writes these markers for you and shows a live preview; the PDF reads them
 * back. Anything malformed is just printed as typed rather than throwing.
 */
data class RichSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    /** Point size for this run, or null to use whatever size the caller normally uses. */
    val sizePt: Float? = null
)

object RichText {

    /** Default point size the +/− buttons step away from. */
    const val BASE_PT = 12f

    fun parse(src: String): List<RichSpan> {
        val out = ArrayList<RichSpan>()
        val buf = StringBuilder()
        var bold = false
        var italic = false
        val sizes = ArrayList<Float>()

        fun flush() {
            if (buf.isEmpty()) return
            out.add(RichSpan(buf.toString(), bold, italic, sizes.lastOrNull()))
            buf.setLength(0)
        }

        var i = 0
        while (i < src.length) {
            val ch = src[i]
            when {
                ch == '*' -> { flush(); bold = !bold; i++ }
                ch == '_' -> { flush(); italic = !italic; i++ }
                ch == '{' -> {
                    val bar = src.indexOf('|', i)
                    val size = if (bar > i) src.substring(i + 1, bar).trim().toFloatOrNull() else null
                    if (size != null) { flush(); sizes.add(size); i = bar + 1 }
                    else { buf.append(ch); i++ }
                }
                ch == '}' && sizes.isNotEmpty() -> { flush(); sizes.removeAt(sizes.size - 1); i++ }
                else -> { buf.append(ch); i++ }
            }
        }
        flush()
        return out
    }

    /** The text with every marker removed — for previews, lists and sharing. */
    fun plain(src: String): String = parse(src).joinToString("") { it.text }

    /** Rendered form for the editor's preview. */
    fun annotated(src: String, baseSp: TextUnit = BASE_PT.sp): AnnotatedString = buildAnnotatedString {
        parse(src).forEach { s ->
            withStyle(
                SpanStyle(
                    fontWeight = if (s.bold) FontWeight.Bold else null,
                    fontStyle = if (s.italic) FontStyle.Italic else null,
                    fontSize = s.sizePt?.sp ?: baseSp
                )
            ) { append(s.text) }
        }
    }

    // ---- Editing helpers, used by the formatting toolbar ----

    /** Wraps [selection] of [text] in [marker], or drops the markers again if already wrapped. */
    fun toggleWrap(text: String, start: Int, end: Int, marker: String): Pair<String, IntRange> {
        if (start >= end) return text to start..start
        val sel = text.substring(start, end)
        val already = sel.length >= marker.length * 2 &&
            sel.startsWith(marker) && sel.endsWith(marker)
        val replaced = if (already) sel.substring(marker.length, sel.length - marker.length)
        else marker + sel + marker
        val out = text.substring(0, start) + replaced + text.substring(end)
        return out to start..(start + replaced.length)
    }

    /**
     * Steps the size of the selection by [delta] points. A selection that already carries a
     * size is re-sized in place rather than being wrapped again, so pressing the button
     * repeatedly does not build up nested markers.
     */
    fun stepSize(text: String, start: Int, end: Int, delta: Float): Pair<String, IntRange> {
        if (start >= end) return text to start..start
        val sel = text.substring(start, end)
        val existing = Regex("^\\{([0-9.]+)\\|(.*)\\}$", RegexOption.DOT_MATCHES_ALL).find(sel)
        val replaced = if (existing != null) {
            val size = (existing.groupValues[1].toFloatOrNull() ?: BASE_PT) + delta
            "{" + trimNum(size.coerceIn(6f, 48f)) + "|" + existing.groupValues[2] + "}"
        } else {
            "{" + trimNum((BASE_PT + delta).coerceIn(6f, 48f)) + "|" + sel + "}"
        }
        val out = text.substring(0, start) + replaced + text.substring(end)
        return out to start..(start + replaced.length)
    }

    private fun trimNum(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()
}

// ---- Laying formatted text out for the PDF ----

class RichRun(val text: String, val paint: Paint)
class RichLine(val runs: List<RichRun>, val height: Float)

/**
 * Breaks formatted text into drawable lines no wider than [maxWidth], measuring with each
 * run's own paint so bold and larger text wrap where they actually run out of room.
 */
fun layoutRich(src: String, maxWidth: Float, base: Paint): List<RichLine> {
    if (src.isBlank()) return emptyList()
    val out = ArrayList<RichLine>()
    var runs = ArrayList<RichRun>()
    var width = 0f
    var height = 0f

    fun flush(force: Boolean) {
        if (runs.isEmpty() && !force) return
        out.add(RichLine(runs.toList(), if (height > 0f) height else base.textSize * 1.25f))
        runs = ArrayList(); width = 0f; height = 0f
    }

    RichText.parse(src).forEach { span ->
        val paint = Paint(base).apply {
            span.sizePt?.let { textSize = it }
            typeface = Typeface.create(
                Typeface.DEFAULT,
                when {
                    span.bold && span.italic -> Typeface.BOLD_ITALIC
                    span.bold -> Typeface.BOLD
                    span.italic -> Typeface.ITALIC
                    else -> Typeface.NORMAL
                }
            )
        }
        span.text.split('\n').forEachIndexed { i, segment ->
            if (i > 0) flush(true)
            Regex("\\S+\\s*").findAll(segment).forEach { m ->
                val token = m.value
                val w = paint.measureText(token)
                if (width + w > maxWidth && runs.isNotEmpty()) flush(false)
                runs.add(RichRun(token, paint))
                width += w
                height = maxOf(height, paint.textSize * 1.25f)
            }
        }
    }
    flush(false)
    return out
}
