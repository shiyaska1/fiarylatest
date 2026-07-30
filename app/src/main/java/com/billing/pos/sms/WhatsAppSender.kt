package com.billing.pos.sms

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Opens a WhatsApp chat with the message pre-filled, one number at a time. WhatsApp does not allow
 * fully automated bulk sending, so the flow is: open the chat, the user taps send, then advance to
 * the next contact.
 */
object WhatsAppSender {

    /** 10-digit local numbers get a default 91 (India) country code; existing codes are kept. */
    fun normalize(number: String): String {
        val digits = number.filter { it.isDigit() }
        return when {
            digits.length == 10 -> "91$digits"
            digits.startsWith("0") && digits.length == 11 -> "91" + digits.drop(1)
            else -> digits
        }
    }

    /** Launch the WhatsApp chat for [number] with [text] pre-filled. Returns true if an app opened. */
    fun open(context: Context, number: String, text: String): Boolean {
        val n = normalize(number)
        val url = "https://wa.me/$n?text=" + Uri.encode(text)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            intent.setPackage("com.whatsapp")
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            try {
                // WhatsApp Business, or let the user pick.
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e2: Exception) {
                false
            }
        }
    }
}
