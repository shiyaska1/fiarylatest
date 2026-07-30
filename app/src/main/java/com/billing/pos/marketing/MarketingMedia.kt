package com.billing.pos.marketing

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File

/**
 * Files chosen for a WhatsApp marketing broadcast.
 *
 * Picked files are copied into the app's cache so they can be shared as content:// URIs,
 * and sent to one contact at a time — WhatsApp requires a person to tap send for each,
 * which is exactly the guided, one-by-one flow the broadcast uses.
 */
object MarketingMedia {

    // Must live under cache/shared, the only cache path the FileProvider exposes — otherwise
    // getUriForFile throws and the copied gallery/file never reaches WhatsApp.
    private fun dir(context: Context): File =
        File(File(context.cacheDir, "shared"), "marketing").apply { mkdirs() }

    /** Copies a picked file in and returns its cache Uri, or null on failure. */
    fun copyIn(context: Context, uri: Uri): Uri? = runCatching {
        val name = displayName(context, uri) ?: ("file_" + System.nanoTime())
        val safe = name.take(48).replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(dir(context), System.nanoTime().toString() + "_" + safe)
        context.contentResolver.openInputStream(uri)!!.use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        FileProvider.getUriForFile(context, context.packageName + ".provider", target)
    }.getOrNull()

    /**
     * Opens a WhatsApp chat to [phone] with [files] attached and [text] as the caption below
     * them, ready to send. Two things make the attachment actually go through rather than a
     * text-only message: the intent's MIME is set to the real media type (a wildcard type
     * makes WhatsApp fall back to text only), and the caption is also copied to the clipboard
     * so it can be pasted if WhatsApp drops it on a multi-image send.
     */
    /** Digits with a country code: a bare 10-digit number is assumed Indian and gets 91. */
    fun withCountryCode(phone: String): String {
        val d = phone.filter { it.isDigit() }
        return when {
            d.length == 10 -> "91$d"
            d.length == 11 && d.startsWith("0") -> "91" + d.drop(1)
            else -> d
        }
    }

    fun sendToWhatsApp(context: Context, phone: String, text: String, files: List<Uri>) {
        val digits = withCountryCode(phone)

        // With no files it is a plain text chat.
        if (files.isEmpty()) {
            if (digits.isNotBlank()) runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + digits + "?text=" + Uri.encode(text)))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return
        }

        // Keep the caption on the clipboard as a fallback for multi-image sends.
        if (text.isNotBlank()) runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("message", text))
        }

        val mime = mimeFor(context, files)

        // Keep the caption on the clipboard so it can be pasted if WhatsApp drops it.
        if (text.isNotBlank()) runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("message", text))
        }

        fun build(): Intent {
            val i = if (files.size == 1) Intent(Intent.ACTION_SEND) else Intent(Intent.ACTION_SEND_MULTIPLE)
            i.type = mime
            if (files.size == 1) i.putExtra(Intent.EXTRA_STREAM, files[0])
            else i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files))
            if (text.isNotBlank()) i.putExtra(Intent.EXTRA_TEXT, text)
            // ClipData grants WhatsApp read access to the shared files.
            val clip = android.content.ClipData.newUri(context.contentResolver, "media", files[0])
            for (k in 1 until files.size) clip.addItem(android.content.ClipData.Item(files[k]))
            i.clipData = clip
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            return i
        }

        // Target the customer's chat directly with the jid. With the files now readable this
        // lands on that contact with the media attached; the plain share below is the fallback.
        if (digits.isNotBlank()) {
            for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
                val direct = build().setPackage(pkg).putExtra("jid", digits + "@s.whatsapp.net")
                if (direct.resolveActivity(context.packageManager) != null) {
                    runCatching { context.startActivity(direct) }.onSuccess { return }
                }
            }
        }
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            val direct = build().setPackage(pkg)
            if (direct.resolveActivity(context.packageManager) != null) {
                runCatching { context.startActivity(direct) }.onSuccess { return }
            }
        }
        // No WhatsApp installed — offer the media through the general share sheet.
        runCatching {
            context.startActivity(Intent.createChooser(build(), "Send").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** A MIME that WhatsApp will treat as media: the shared type if all files match, else images. */
    private fun mimeFor(context: Context, files: List<Uri>): String {
        val types = files.mapNotNull { context.contentResolver.getType(it) }
        val allImages = types.isNotEmpty() && types.all { it.startsWith("image/") }
        val allVideos = types.isNotEmpty() && types.all { it.startsWith("video/") }
        return when {
            files.size == 1 -> types.firstOrNull() ?: "image/*"
            allImages -> "image/*"
            allVideos -> "video/*"
            else -> "*/*"
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    }.getOrNull()
}
