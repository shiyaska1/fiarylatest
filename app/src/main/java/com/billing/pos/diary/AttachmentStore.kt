package com.billing.pos.diary

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.billing.pos.data.AttachmentType
import com.billing.pos.data.DiaryAttachment
import java.io.File

/** Copies picked/recorded files into app-private storage for the diary. */
object AttachmentStore {

    fun dir(context: Context): File = File(context.filesDir, "diary").apply { mkdirs() }

    /** Copies [uri] into app storage and returns a (not-yet-persisted) attachment. */
    fun copyIn(context: Context, uri: Uri): DiaryAttachment? {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val displayName = queryName(context, uri) ?: "file_${System.nanoTime()}"
        val ext = displayName.substringAfterLast('.', "").ifBlank { extFromMime(mime) }
        val target = File(dir(context), "att_${System.nanoTime()}" + if (ext.isNotBlank()) ".$ext" else "")
        return try {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            DiaryAttachment(
                entryId = 0,
                path = target.absolutePath,
                name = displayName,
                mime = mime,
                type = typeOf(mime)
            )
        } catch (e: Exception) {
            target.delete()
            null
        }
    }

    /** Registers an already-written file (e.g. a voice recording) as an attachment. */
    fun fromFile(file: File, name: String, mime: String): DiaryAttachment =
        DiaryAttachment(entryId = 0, path = file.absolutePath, name = name, mime = mime, type = typeOf(mime))

    /** A fresh file in the diary storage dir with the given extension. */
    fun newFile(context: Context, ext: String): File =
        File(dir(context), "blk_${System.nanoTime()}.$ext")

    /**
     * Decodes [uri], scales it so the longest side is at most [maxDim]px, and writes a
     * JPEG at [quality] into [dest]. Returns true on success. Keeps file size small.
     */
    fun compressImageTo(context: Context, uri: Uri, dest: File, maxDim: Int = 1600, quality: Int = 80): Boolean {
        val resolver = context.contentResolver
        return try {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
            val w = bounds.outWidth; val h = bounds.outHeight
            if (w <= 0 || h <= 0) return false
            var sample = 1
            while (w / (sample * 2) >= maxDim || h / (sample * 2) >= maxDim) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
                ?: return false
            val scale = maxDim.toFloat() / maxOf(decoded.width, decoded.height)
            val bmp = if (scale < 1f)
                android.graphics.Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
            else decoded
            dest.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, it) }
            if (bmp !== decoded) bmp.recycle()
            decoded.recycle()
            dest.exists() && dest.length() > 0
        } catch (e: Exception) {
            dest.delete()
            false
        }
    }

    fun delete(attachment: DiaryAttachment) {
        runCatching { File(attachment.path).delete() }
    }

    /** A content:// Uri for opening the attachment in another app. */
    fun uriFor(context: Context, attachment: DiaryAttachment): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.provider", File(attachment.path))

    fun typeOf(mime: String): AttachmentType = when {
        mime.startsWith("image/") -> AttachmentType.IMAGE
        mime.startsWith("video/") -> AttachmentType.VIDEO
        mime.startsWith("audio/") -> AttachmentType.AUDIO
        else -> AttachmentType.DOCUMENT
    }

    private fun extFromMime(mime: String): String = when (mime) {
        "image/jpeg" -> "jpg"; "image/png" -> "png"; "video/mp4" -> "mp4"
        "audio/mp4", "audio/aac" -> "m4a"; "application/pdf" -> "pdf"; else -> ""
    }

    private fun queryName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }
}
