package com.billing.pos.data

import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Reads/writes the backup JSON wrapped in a .zip file. */
object Backup {

    private const val ENTRY = "pos-backup.json"

    /** Writes [json] into a zip under cache/shared and returns the file. */
    fun writeZip(context: Context, baseName: String, json: String): File {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val zip = File(dir, "$baseName.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { zos ->
            zos.putNextEntry(ZipEntry(ENTRY))
            zos.write(json.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return zip
    }

    /**
     * Reads a backup from [uri]. Accepts either a .zip (extracts the first .json
     * entry) or a plain .json file. Returns the JSON text, or null on failure.
     */
    fun readBackup(context: Context, uri: Uri): String? {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null

        val isZip = bytes.size >= 2 &&
            bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

        if (!isZip) return bytes.toString(Charsets.UTF_8)

        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".json", ignoreCase = true)) {
                    return zis.readBytes().toString(Charsets.UTF_8)
                }
                entry = zis.nextEntry
            }
        }
        return null
    }
}
