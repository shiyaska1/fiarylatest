package com.billing.pos.expenses

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.billing.pos.data.ExpenseAttachment
import java.io.File

/** Files attached to payments live here, copied in so they survive the source going away. */
object ExpenseAttachmentStore {

    fun dir(context: Context): File =
        File(context.filesDir, "expense_attachments").apply { mkdirs() }

    /** A fresh file for a voice recording. */
    fun newVoiceFile(context: Context): File =
        File(dir(context), "voice_${System.nanoTime()}.m4a")

    /** Copies a picked file in and returns an unsaved attachment row (expenseId filled later). */
    fun copyIn(context: Context, uri: Uri): ExpenseAttachment? = runCatching {
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val name = displayName(context, uri) ?: "attachment_${System.currentTimeMillis()}"
        val target = File(dir(context), "att_${System.nanoTime()}_${name.take(40).replace(Regex("[^A-Za-z0-9._-]"), "_")}")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        ExpenseAttachment(expenseId = 0, path = target.absolutePath, name = name, mime = mime)
    }.getOrNull()

    fun fromFile(file: File, name: String, mime: String): ExpenseAttachment =
        ExpenseAttachment(expenseId = 0, path = file.absolutePath, name = name, mime = mime)

    fun delete(a: ExpenseAttachment) { runCatching { File(a.path).delete() } }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    }.getOrNull()
}
