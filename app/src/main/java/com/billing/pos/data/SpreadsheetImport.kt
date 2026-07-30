package com.billing.pos.data

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** One item parsed from an imported spreadsheet row. Optional fields default sensibly. */
data class ImportedItemRow(
    val name: String,
    val price: Double,
    val taxPercent: Double,
    val category: String,
    val openingStock: Double,
    val unit: String,
    val barcode: String,
    val hsn: String,
    val location: String,
    val batchNo: String = "",
    val expiryMillis: Long = 0,
    val batchQty: Double = 0.0,
    val chemicalContent: String = ""
)

/**
 * Minimal, dependency-free reader for item import files. Handles .xlsx (unzips the
 * sheet XML and resolves shared strings) and .csv. The first row is treated as headers;
 * columns are matched to fields by name so column order doesn't matter.
 */
object SpreadsheetImport {

    /** Raw rows (first row = headers) from an .xlsx or .csv file. Used by importers other than items. */
    fun readRaw(context: Context, uri: Uri): List<List<String>> {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return emptyList()
        val isXlsx = bytes.size > 1 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        return if (isXlsx) parseXlsx(bytes) else parseCsv(bytes)
    }

    fun read(context: Context, uri: Uri): List<ImportedItemRow> {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return emptyList()
        val isXlsx = bytes.size > 1 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        val rows = if (isXlsx) parseXlsx(bytes) else parseCsv(bytes)
        if (rows.isEmpty()) return emptyList()

        val header = rows.first().map { it.trim().lowercase() }
        fun col(vararg keys: String): Int =
            header.indexOfFirst { h -> keys.any { h == it || h.contains(it) } }
        val iName = col("item name", "name", "item", "product")
        val iPrice = col("selling price", "sell price", "sale price", "price", "rate", "mrp")
        val iTax = col("tax")
        val iCat = col("category", "group")
        val iStock = col("opening stock", "stock", "quantity", "qty")
        val iUnit = col("unit", "uom")
        val iBarcode = col("barcode", "bar code")
        val iHsn = col("hsn", "sac")
        val iLoc = col("location", "rack", "shelf")
        val iBatch = col("batch no", "batch", "lot")
        val iExpiry = col("expiry", "exp date", "expiry date")
        val iBatchQty = col("batch qty", "batch quantity")
        val iChem = col("chemical content", "chemical", "composition", "salt", "content")

        return rows.drop(1).mapNotNull { r ->
            fun cell(i: Int) = if (i in 0 until r.size) r[i].trim() else ""
            val name = (if (iName >= 0) cell(iName) else r.firstOrNull()?.trim().orEmpty())
            if (name.isBlank()) return@mapNotNull null
            ImportedItemRow(
                name = name,
                price = cell(iPrice).toDoubleOrNull() ?: 0.0,
                taxPercent = cell(iTax).toDoubleOrNull() ?: 0.0,
                category = if (iCat >= 0) cell(iCat) else "",
                openingStock = cell(iStock).toDoubleOrNull() ?: 0.0,
                unit = (if (iUnit >= 0) cell(iUnit) else "").ifBlank { "PCS" },
                barcode = if (iBarcode >= 0) cell(iBarcode) else "",
                hsn = if (iHsn >= 0) cell(iHsn) else "",
                location = if (iLoc >= 0) cell(iLoc) else "",
                batchNo = if (iBatch >= 0) cell(iBatch) else "",
                expiryMillis = if (iExpiry >= 0) parseDate(cell(iExpiry)) else 0L,
                batchQty = if (iBatchQty >= 0) cell(iBatchQty).toDoubleOrNull() ?: 0.0 else 0.0,
                chemicalContent = if (iChem >= 0) cell(iChem) else ""
            )
        }
    }

    /** Parses an Excel serial number or a text date (several common formats) to millis; 0 if none. */
    private fun parseDate(s: String): Long {
        val t = s.trim()
        if (t.isBlank()) return 0L
        t.toDoubleOrNull()?.let { serial ->
            if (serial in 20000.0..90000.0) return ((serial - 25569.0) * 86400000.0).toLong()
        }
        for (f in listOf("yyyy-MM-dd", "dd/MM/yyyy", "dd-MM-yyyy", "MM/dd/yyyy", "yyyy/MM/dd", "dd/MM/yy")) {
            val parsed = runCatching {
                val sdf = java.text.SimpleDateFormat(f, java.util.Locale.US).apply { isLenient = false }
                sdf.parse(t)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return 0L
    }

    // ---- xlsx ----
    private fun parseXlsx(bytes: ByteArray): List<List<String>> {
        var sharedXml: ByteArray? = null
        val sheets = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val n = e.name
                if (n == "xl/sharedStrings.xml") sharedXml = zis.readBytes()
                else if (n.startsWith("xl/worksheets/sheet") && n.endsWith(".xml")) sheets[n] = zis.readBytes()
                e = zis.nextEntry
            }
        }
        val shared = ArrayList<String>()
        sharedXml?.let { parseSharedStrings(it, shared) }
        val sheet = sheets["xl/worksheets/sheet1.xml"] ?: sheets.toSortedMap().values.firstOrNull() ?: return emptyList()
        return parseSheet(sheet, shared)
    }

    private fun parseSharedStrings(data: ByteArray, out: MutableList<String>) {
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(data), null)
        var event = parser.eventType
        var depthInSi = 0
        val sb = StringBuilder()
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> if (parser.name == "si") { depthInSi = 1; sb.setLength(0) }
                XmlPullParser.TEXT -> if (depthInSi > 0) sb.append(parser.text)
                XmlPullParser.END_TAG -> if (parser.name == "si") { out.add(sb.toString()); depthInSi = 0 }
            }
            event = parser.next()
        }
    }

    private fun parseSheet(data: ByteArray, shared: List<String>): List<List<String>> {
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(data), null)
        val rows = ArrayList<List<String>>()
        var event = parser.eventType
        var curRow: ArrayList<Pair<Int, String>>? = null
        var cellType: String? = null
        var cellCol = -1
        var reading = false
        val vsb = StringBuilder()
        var cellValue = ""
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> curRow = ArrayList()
                    "c" -> {
                        cellType = parser.getAttributeValue(null, "t")
                        cellCol = colIndex(parser.getAttributeValue(null, "r"))
                        cellValue = ""
                    }
                    "v" -> { reading = true; vsb.setLength(0) }
                    "t" -> if (cellType == "inlineStr" || cellType == "str") { reading = true; vsb.setLength(0) }
                }
                XmlPullParser.TEXT -> if (reading) vsb.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v" -> {
                        reading = false
                        val raw = vsb.toString()
                        cellValue = if (cellType == "s") shared.getOrNull(raw.toIntOrNull() ?: -1) ?: "" else raw
                    }
                    "t" -> if (reading) { reading = false; cellValue = vsb.toString() }
                    "c" -> { curRow?.add(cellCol to cellValue) }
                    "row" -> curRow?.let { cells ->
                        val maxCol = cells.maxOfOrNull { it.first } ?: -1
                        if (maxCol >= 0) {
                            val dense = MutableList(maxCol + 1) { "" }
                            cells.forEach { (c, v) -> if (c in 0..maxCol) dense[c] = v }
                            rows.add(dense)
                        }
                        curRow = null
                    }
                }
            }
            event = parser.next()
        }
        return rows
    }

    private fun colIndex(ref: String?): Int {
        if (ref.isNullOrEmpty()) return -1
        var idx = 0
        for (ch in ref) {
            if (ch.isLetter()) idx = idx * 26 + (ch.uppercaseChar() - 'A' + 1) else break
        }
        return idx - 1
    }

    // ---- csv ----
    private fun parseCsv(bytes: ByteArray): List<List<String>> =
        String(bytes, Charsets.UTF_8).split(Regex("\r?\n"))
            .filter { it.isNotBlank() }
            .map { splitCsvLine(it) }

    private fun splitCsvLine(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }
}
