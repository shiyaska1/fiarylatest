package com.billing.pos.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import java.io.File

/** A photo/file attached to a receipt voucher (copied into app storage). */
@Entity(tableName = "receipt_attachments")
data class ReceiptAttachment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receiptId: Long,
    val path: String,
    val name: String,
    val mime: String
) {
    val isImage: Boolean get() = mime.startsWith("image/")
}

@Dao
interface ReceiptAttachmentDao {
    @Query("SELECT * FROM receipt_attachments WHERE receiptId = :id") suspend fun forReceipt(id: Long): List<ReceiptAttachment>
    @Insert suspend fun insertAll(list: List<ReceiptAttachment>)
    @Query("DELETE FROM receipt_attachments WHERE receiptId = :id") suspend fun deleteForReceipt(id: Long)
}

/** Copies picked/captured files attached to a receipt into app storage. */
object ReceiptAttachmentStore {

    fun dir(context: Context): File = File(context.filesDir, "receipts").apply { mkdirs() }

    fun copyIn(context: Context, uri: Uri): ReceiptAttachment? {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val displayName = queryName(context, uri) ?: "file_${System.nanoTime()}"
        val ext = displayName.substringAfterLast('.', "").ifBlank { extFromMime(mime) }
        val target = File(dir(context), "rcpt_${System.nanoTime()}" + if (ext.isNotBlank()) ".$ext" else "")
        return try {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            ReceiptAttachment(receiptId = 0, path = target.absolutePath, name = displayName, mime = mime)
        } catch (e: Exception) {
            target.delete()
            null
        }
    }

    fun delete(attachment: ReceiptAttachment) {
        runCatching { File(attachment.path).delete() }
    }

    fun uriFor(context: Context, attachment: ReceiptAttachment): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.provider", File(attachment.path))

    /** Opens the attachment in a viewer app. Returns false if nothing can open it. */
    fun open(context: Context, attachment: ReceiptAttachment): Boolean = runCatching {
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
