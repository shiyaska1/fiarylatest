package com.billing.pos.poster

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Where imported poster templates live, and how a finished poster leaves the app.
 *
 * Templates are just image files in app storage — no database table needed, the folder
 * listing is the list.
 */
object PosterStore {

    private fun templatesDir(context: Context): File =
        File(context.filesDir, "poster_templates").apply { mkdirs() }

    private fun outDir(context: Context): File =
        File(context.cacheDir, "shared").apply { mkdirs() }

    /** Imported template images, newest first. */
    fun templates(context: Context): List<File> =
        templatesDir(context).listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** Copies a picked image in as a reusable template. Returns the stored file. */
    fun importTemplate(context: Context, uri: Uri): File? = runCatching {
        val target = File(templatesDir(context), "tpl_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        target
    }.getOrNull()

    fun deleteTemplate(file: File) { runCatching { file.delete() } }

    /** Writes the poster to a shareable file. */
    fun writePoster(context: Context, bitmap: Bitmap): File? = runCatching {
        val f = File(outDir(context), "poster_${System.currentTimeMillis()}.jpg")
        f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        f
    }.getOrNull()

    /**
     * Opens the system share sheet for the poster. The chooser is what covers "all social
     * media" — WhatsApp, Instagram, Facebook, Telegram and anything else installed appear
     * in it, without the app needing to integrate with each one.
     */
    fun share(context: Context, file: File, caption: String = "", pkg: String? = null) {
        runCatching {
            val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                if (caption.isNotBlank()) putExtra(Intent.EXTRA_TEXT, caption)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val target = if (pkg != null) {
                Intent(send).setPackage(pkg).takeIf { it.resolveActivity(context.packageManager) != null }
                    ?: Intent.createChooser(send, "Share poster")
            } else {
                Intent.createChooser(send, "Share poster")
            }
            context.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
