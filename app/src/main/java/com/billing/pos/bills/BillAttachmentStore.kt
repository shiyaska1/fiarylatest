package com.billing.pos.bills

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.billing.pos.data.BillAttachment
import java.io.File

/** Copies picked/captured documents attached to a bill into app storage. */
object BillAttachmentStore {

    fun dir(context: Context): File = File(context.filesDir, "bills").apply { mkdirs() }

    fun copyIn(context: Context, uri: Uri): BillAttachment? {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val displayName = queryName(context, uri) ?: "file_${System.nanoTime()}"
        val ext = displayName.substringAfterLast('.', "").ifBlank { extFromMime(mime) }
        val target = File(dir(context), "bill_${System.nanoTime()}" + if (ext.isNotBlank()) ".$ext" else "")
        return try {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            BillAttachment(billId = 0, path = target.absolutePath, name = displayName, mime = mime)
        } catch (e: Exception) {
            target.delete()
            null
        }
    }

    fun fromFile(file: File, name: String, mime: String): BillAttachment =
        BillAttachment(billId = 0, path = file.absolutePath, name = name, mime = mime)

    fun delete(attachment: BillAttachment) {
        runCatching { File(attachment.path).delete() }
    }

    fun uriFor(context: Context, attachment: BillAttachment): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.provider", File(attachment.path))

    /** Opens the attachment in a viewer app. Returns false if nothing can open it. */
    fun open(context: Context, attachment: BillAttachment): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriFor(context, attachment), attachment.mime.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)

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
