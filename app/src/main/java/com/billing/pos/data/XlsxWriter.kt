package com.billing.pos.data

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Minimal, dependency-free writer for a single-sheet .xlsx workbook. */
object XlsxWriter {

    sealed class Cell {
        data class Text(val v: String) : Cell()
        data class Num(val v: Double) : Cell()
    }

    fun text(s: String): Cell = Cell.Text(s)
    fun num(n: Double): Cell = Cell.Num(n)
    fun row(vararg cells: Cell): List<Cell> = cells.toList()

    fun write(file: File, sheetName: String, rows: List<List<Cell>>) {
        ZipOutputStream(file.outputStream().buffered()).use { zos ->
            put(zos, "[Content_Types].xml", CONTENT_TYPES)
            put(zos, "_rels/.rels", RELS)
            put(zos, "xl/workbook.xml", workbook(sheetName))
            put(zos, "xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            put(zos, "xl/worksheets/sheet1.xml", sheet(rows))
        }
    }

    private fun put(zos: ZipOutputStream, name: String, content: String) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun sheet(rows: List<List<Cell>>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        rows.forEachIndexed { r, cells ->
            sb.append("<row r=\"${r + 1}\">")
            cells.forEachIndexed { ci, cell ->
                val ref = colName(ci) + (r + 1)
                when (cell) {
                    is Cell.Text -> sb.append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(cell.v)}</t></is></c>")
                    is Cell.Num -> sb.append("<c r=\"$ref\"><v>${cell.v}</v></c>")
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun colName(index: Int): String {
        var i = index
        val sb = StringBuilder()
        while (true) {
            sb.insert(0, ('A' + (i % 26)))
            i = i / 26 - 1
            if (i < 0) break
        }
        return sb.toString()
    }

    private fun esc(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private const val CONTENT_TYPES =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>"""

    private const val RELS =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""

    private const val WORKBOOK_RELS =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>"""

    private fun workbook(sheetName: String): String {
        val safe = esc(sheetName).take(28)
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="$safe" sheetId="1" r:id="rId1"/></sheets></workbook>"""
    }
}
