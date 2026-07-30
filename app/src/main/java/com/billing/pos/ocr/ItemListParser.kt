package com.billing.pos.ocr

/** One item parsed from a scanned printed list: a name and an optional trailing price. */
data class ScannedItem(val name: String, val price: Double)

/**
 * Turns raw OCR lines from a printed item list into name + price rows.
 * A trailing number (optionally prefixed by ₹ / Rs / INR and suffixed by /-) is
 * treated as the price; the rest of the line is the item name.
 */
object ItemListParser {

    private val priceAtEnd = Regex(
        """^(.*?)[\s:.\-–—]*(?:₹|rs\.?|inr)?\s*(\d{1,7}(?:[.,]\d{1,2})?)\s*/?-?\s*$""",
        RegexOption.IGNORE_CASE
    )

    fun parse(lines: List<String>): List<ScannedItem> =
        lines.mapNotNull { raw ->
            val line = raw.trim()
            // Skip blanks, single characters and lines with no letters (page numbers, rules).
            if (line.length < 2 || line.none { it.isLetter() }) return@mapNotNull null

            val match = priceAtEnd.find(line)
            val namePart = match?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (match != null && namePart.length >= 2 && namePart.any { it.isLetter() }) {
                val name = namePart.trimEnd('-', ':', '.', ' ', '\t')
                val price = match.groupValues[2].replace(",", ".").toDoubleOrNull() ?: 0.0
                ScannedItem(name, price)
            } else {
                ScannedItem(line, 0.0)
            }
        }
}
