package com.billing.pos.data

import android.content.Context
import android.provider.Settings
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Trial + device-locked activation.
 *
 * The activation key is derived from the device id with a keyed HMAC-SHA256, so it can't be
 * forged without [SECRET]. To activate a customer: they read you their Device ID from the
 * expiry screen, you compute the key (any HMAC-SHA256 tool: key = SECRET, message = the Device
 * ID exactly as shown, take the first 16 hex chars, upper-case), and they type it in.
 */
object License {
    const val TRIAL_DAYS = 30

    // ---- Support / purchase contact, shown wherever a licence key is needed ----
    /** WhatsApp number in international form, no "+" (wa.me wants it that way). */
    const val SUPPORT_WHATSAPP = "919961128378"
    /** Same number, formatted for display and for a plain dial link. */
    const val SUPPORT_PHONE = "+919961128378"
    const val SUPPORT_EMAIL = "shiyaska2009@gmail.com"

    /** Where "Buy app" sends the user — a WhatsApp chat with the support number. */
    const val BUY_URL = "https://wa.me/$SUPPORT_WHATSAPP?text=I%20want%20to%20buy%20POS%20Billing"

    /** WhatsApp chat pre-filled with the device id, so the licence key can be issued straight away. */
    fun buyUrlFor(deviceId: String): String {
        val msg = java.net.URLEncoder.encode(
            "I want to buy POS Billing. My Device ID is $deviceId", "UTF-8"
        )
        return "https://wa.me/$SUPPORT_WHATSAPP?text=$msg"
    }

    /** >>> CHANGE THIS to your own private secret before publishing. Keep it secret. <<< */
    private const val SECRET = "POSB-change-this-secret-2024"

    /** Stable per-device identifier (Android ID), shown to the user for activation. */
    fun deviceId(context: Context): String {
        val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        return (if (id.isBlank()) "UNKNOWNDEVICE" else id).uppercase()
    }

    /**
     * Renewal points, in months since installation. 1 is the end of the free trial; after
     * that the app asks again at each later milestone, each needing its own key.
     */
    val MILESTONES = listOf(1, 6, 12, 36, 48)

    /** Whole months elapsed since [installMillis], by calendar rather than by 30-day blocks. */
    fun monthsSince(installMillis: Long): Int {
        if (installMillis <= 0L) return 0
        val now = java.util.Calendar.getInstance()
        val probe = java.util.Calendar.getInstance().apply { timeInMillis = installMillis }
        var months = 0
        while (true) {
            probe.add(java.util.Calendar.MONTH, 1)
            if (probe.timeInMillis > now.timeInMillis) break
            months++
        }
        return months
    }

    /**
     * The milestone the device has reached, or 0 while still inside the free trial.
     * Compared against the highest milestone already activated to decide whether to ask.
     */
    fun dueMilestone(installMillis: Long): Int {
        // Month 1 only falls due once the trial days are actually up.
        if (!trialExpired(installMillis)) return 0
        val months = monthsSince(installMillis)
        return MILESTONES.filter { it <= maxOf(months, 1) }.maxOrNull() ?: 0
    }

    /** The next renewal after [milestone], or null when the last one has been activated. */
    fun nextMilestone(milestone: Int): Int? = MILESTONES.firstOrNull { it > milestone }

    /**
     * Activation key for a device at a given renewal point.
     *
     * Milestone 1 keeps the original device-id-only form, so keys already issued to
     * customers stay valid. Later milestones mix the month count in, which is what makes a
     * renewal key different from the one before it.
     */
    fun activationKey(deviceId: String, milestone: Int = 1): String {
        val message = if (milestone <= 1) deviceId.trim().uppercase()
        else deviceId.trim().uppercase() + milestone
        val hex = hmacHex(message).take(16).uppercase()
        return hex.chunked(4).joinToString("-")
    }

    /** True when [key] matches the key for this device at [milestone]. */
    fun isValid(deviceId: String, key: String, milestone: Int = 1): Boolean {
        val norm = key.uppercase().replace(Regex("[^0-9A-F]"), "")
        if (norm.isEmpty()) return false
        val accepted = mutableListOf(activationKey(deviceId, milestone).replace("-", ""))
        // The 48-month point was specified as "+46"; accept both so a key issued either way works.
        if (milestone == 48) {
            accepted += hmacHex(deviceId.trim().uppercase() + "46").take(16).uppercase()
        }
        return accepted.any { it == norm }
    }

    private fun hmacHex(message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun daysSince(installMillis: Long): Long {
        if (installMillis <= 0L) return 0L
        return (System.currentTimeMillis() - installMillis) / (1000L * 60 * 60 * 24)
    }

    fun trialExpired(installMillis: Long): Boolean = daysSince(installMillis) >= TRIAL_DAYS

    fun daysLeft(installMillis: Long): Int =
        (TRIAL_DAYS - daysSince(installMillis)).toInt().coerceIn(0, TRIAL_DAYS)
}
