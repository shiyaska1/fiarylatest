package com.billing.pos.util

import android.content.Context
import android.content.Intent

/** Sends plain text to the system share sheet (WhatsApp, SMS, mail, anything installed). */
object ShareText {
    fun share(context: Context, text: String, subject: String = "") {
        if (text.isBlank()) return
        runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                if (subject.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, subject)
            }
            context.startActivity(
                Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
