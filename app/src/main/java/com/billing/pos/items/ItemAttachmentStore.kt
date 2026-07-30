package com.billing.pos.items

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.billing.pos.data.ItemAttachment
import java.io.File

/** Copies picked/captured item files (photos, location photos, PDF catalogues) into app storage. */
object ItemAttachmentStore {

    fun dir(context: Context): File = File(context.filesDir, "items").apply { mkdirs() }

    /** Copies [uri] into app storage and returns a (not-yet-persisted) attachment of [kind]. */
    fun copyIn(context: Context, uri: Uri, kind: String): ItemAttachment? {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val displayName = queryName(context, uri) ?: "file_${System.nanoTime()}"
        val ext = displayName.substringAfterLast('.', "").ifBlank { extFromMime(mime) }
        val target = File(dir(context), "itm_${System.nanoTime()}" + if (ext.isNotBlank()) ".$ext" else "")
        return try {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            ItemAttachment(itemId = 0, path = target.absolutePath, name = displayName, mime = mime, kind = kind)
        } catch (e: Exception) {
            target.delete()
            null
        }
    }

    /** Registers an already-written file (e.g. a camera capture) as an attachment. */
    fun fromFile(file: File, name: String, mime: String, kind: String): ItemAttachment =
        ItemAttachment(itemId = 0, path = file.absolutePath, name = name, mime = mime, kind = kind)

    fun delete(attachment: ItemAttachment) {
        runCatching { File(attachment.path).delete() }
    }

    /** A content:// Uri for opening the attachment (e.g. a PDF) in another app. */
    fun uriFor(context: Context, attachment: ItemAttachment): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.provider", File(attachment.path))

    private fun extFromMime(mime: String): String = when (mime) {
        "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"
        "application/pdf" -> "pdf"; else -> ""
    }

    private fun queryName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
}
