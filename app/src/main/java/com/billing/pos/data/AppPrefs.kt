package com.billing.pos.data

import android.content.Context

data class CompanyInfo(val name: String, val address: String, val phone: String, val gstin: String = "")

/** Simple SharedPreferences store for the persisted session and company/print settings. */
class AppPrefs(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("pos_prefs", Context.MODE_PRIVATE)

    var loggedInUserId: Long
        get() = p.getLong("user_id", -1L)
        set(v) { p.edit().putLong("user_id", v).apply() }

    var companyName: String
        get() = p.getString("company_name", "My Shop") ?: "My Shop"
        set(v) { p.edit().putString("company_name", v).apply() }

    var companyAddress: String
        get() = p.getString("company_address", "") ?: ""
        set(v) { p.edit().putString("company_address", v).apply() }

    var companyPhone: String
        get() = p.getString("company_phone", "") ?: ""
        set(v) { p.edit().putString("company_phone", v).apply() }

    var companyGstin: String
        get() = p.getString("company_gstin", "") ?: ""
        set(v) { p.edit().putString("company_gstin", v).apply() }

    val company: CompanyInfo get() = CompanyInfo(companyName, companyAddress, companyPhone, companyGstin)

    /** UPI ID (VPA) money is collected to, e.g. name@okaxis, and the payee name shown. */
    var upiId: String
        get() = p.getString("upi_id", "") ?: ""
        set(v) { p.edit().putString("upi_id", v.trim()).apply() }
    var upiName: String
        get() = p.getString("upi_name", "") ?: ""
        set(v) { p.edit().putString("upi_name", v.trim()).apply() }
    /** When on, a UPI payment QR for the bill total is drawn at the foot of invoices. */
    var showUpiQrOnPrint: Boolean
        get() = p.getBoolean("upi_qr_print", false)
        set(v) { p.edit().putBoolean("upi_qr_print", v).apply() }

    /**
     * When on (APK build only), a background service voice-records continuously and, every hour,
     * saves a diary entry titled with the date-time, of type "voice", with the recording attached.
     */
    var autoVoiceDiary: Boolean
        get() = p.getBoolean("auto_voice_diary", false)
        set(v) { p.edit().putBoolean("auto_voice_diary", v).apply() }

    // ---- LAN sync (two-counter, offline over WiFi) ----
    /** Short label for this device (e.g. "A"), prefixed onto bill numbers so two phones don't clash. */
    var deviceTag: String
        get() = (p.getString("sync_device_tag", "") ?: "").trim()
        set(v) { p.edit().putString("sync_device_tag", v.trim()).apply() }
    /** TCP port this device's sync server listens on when acting as host. */
    var syncPort: Int
        get() = p.getInt("sync_port", 8765)
        set(v) { p.edit().putInt("sync_port", v).apply() }
    /** Last host IP a client connected to, so "Sync now" is one tap next time. */
    var syncHostIp: String
        get() = (p.getString("sync_host_ip", "") ?: "").trim()
        set(v) { p.edit().putString("sync_host_ip", v.trim()).apply() }
    /** When on, this device runs the sync server so other phones can reach it. */
    var syncHostMode: Boolean
        get() = p.getBoolean("sync_host_mode", false)
        set(v) { p.edit().putBoolean("sync_host_mode", v).apply() }
    /** When on, a client re-syncs with the host automatically every few seconds while the app is open. */
    var syncAuto: Boolean
        get() = p.getBoolean("sync_auto", false)
        set(v) { p.edit().putBoolean("sync_auto", v).apply() }

    // ---- Bulk SMS gateway (generic, provider-agnostic) ----
    /**
     * Send URL template with placeholders {number} {message} {apikey} {sender}. Example:
     * https://api.msg91.com/api/v5/flow/?authkey={apikey}&mobiles={number}&message={message}&sender={sender}
     */
    var smsGatewayUrl: String
        get() = (p.getString("sms_url", "") ?: "").trim()
        set(v) { p.edit().putString("sms_url", v.trim()).apply() }
    /** "GET" or "POST". */
    var smsGatewayMethod: String
        get() = (p.getString("sms_method", "GET") ?: "GET")
        set(v) { p.edit().putString("sms_method", v).apply() }
    var smsApiKey: String
        get() = (p.getString("sms_apikey", "") ?: "").trim()
        set(v) { p.edit().putString("sms_apikey", v.trim()).apply() }
    var smsSenderId: String
        get() = (p.getString("sms_sender", "") ?: "").trim()
        set(v) { p.edit().putString("sms_sender", v.trim()).apply() }
    /** Optional balance-check URL with {apikey} placeholder. */
    var smsBalanceUrl: String
        get() = (p.getString("sms_balance_url", "") ?: "").trim()
        set(v) { p.edit().putString("sms_balance_url", v.trim()).apply() }
    /** Default send channel: "Gateway" or "SIM" (SIM works in the APK build only). */
    var smsChannel: String
        get() = (p.getString("sms_channel", "Gateway") ?: "Gateway")
        set(v) { p.edit().putString("sms_channel", v).apply() }
    /**
     * Optional JSON request body for token-style APIs (e.g. LM6/gjinfotech). When non-blank the
     * gateway POSTs this JSON (Content-Type application/json) with {number} {message} {sender}
     * {apikey} filled in, instead of query/form parameters.
     */
    var smsJsonBody: String
        get() = (p.getString("sms_json_body", "") ?: "")
        set(v) { p.edit().putString("sms_json_body", v).apply() }
    /** When on, send Authorization: Bearer <API key> header (token APIs). */
    var smsBearer: Boolean
        get() = p.getBoolean("sms_bearer", false)
        set(v) { p.edit().putBoolean("sms_bearer", v).apply() }

    /** Gym training slots / batch times (a saved list, like customer types). */
    var gymSlots: List<String>
        get() = (p.getString("gym_slots", "") ?: "").split("|").map { it.trim() }.filter { it.isNotBlank() }
        set(v) { p.edit().putString("gym_slots", v.joinToString("|")).apply() }
    fun addGymSlot(name: String) {
        val n = name.trim()
        if (n.isBlank()) return
        if (gymSlots.none { it.equals(n, true) }) gymSlots = gymSlots + n
    }

    /** User-added customer types (a saved set, in addition to any already used by customers). */
    var customerTypes: List<String>
        get() = (p.getString("customer_types", "") ?: "").split("|").map { it.trim() }.filter { it.isNotBlank() }
        set(v) { p.edit().putString("customer_types", v.joinToString("|")).apply() }

    fun addCustomerType(name: String) {
        val n = name.trim()
        if (n.isBlank()) return
        if (customerTypes.none { it.equals(n, true) }) customerTypes = customerTypes + n
    }

    // ---- licensing / trial ----
    var mobileNumber: String
        get() = p.getString("mobile", "") ?: ""
        set(v) { p.edit().putString("mobile", v).apply() }

    var installDateMillis: Long
        get() = p.getLong("install_date", 0L)
        set(v) { p.edit().putLong("install_date", v).apply() }

    /**
     * Highest renewal milestone already activated (0 = none). An existing customer who
     * activated under the old single-key scheme counts as milestone 1, so they are not
     * asked again the moment they update.
     */
    var licensedMilestone: Int
        get() {
            val stored = p.getInt("licensed_milestone", 0)
            return if (stored == 0 && licensed) 1 else stored
        }
        set(v) { p.edit().putInt("licensed_milestone", v).apply() }

    var licensed: Boolean
        get() = p.getBoolean("licensed", false)
        set(v) { p.edit().putBoolean("licensed", v).apply() }

    // ---- thermal printer ----
    /** Bluetooth MAC address of the chosen thermal printer ("" = auto-pick). */
    var printerAddress: String
        get() = p.getString("printer_addr", "") ?: ""
        set(v) { p.edit().putString("printer_addr", v).apply() }

    var printerName: String
        get() = p.getString("printer_name", "") ?: ""
        set(v) { p.edit().putString("printer_name", v).apply() }

    /** Print/paper width for receipts & PDFs: "58mm", "80mm" or "A4". */
    var receiptWidth: String
        get() = p.getString("receipt_width", "58mm") ?: "58mm"
        set(v) { p.edit().putString("receipt_width", v).apply() }

    /** Absolute path to the company logo image ("" = none). Shown on A4 invoices. */
    var logoPath: String
        get() = p.getString("logo_path", "") ?: ""
        set(v) { p.edit().putString("logo_path", v).apply() }

    /** True if the logo image is a full-width banner (contains name/address itself). */
    var logoFullWidth: Boolean
        get() = p.getBoolean("logo_full_width", false)
        set(v) { p.edit().putBoolean("logo_full_width", v).apply() }

    companion object {
        val RECEIPT_WIDTHS = listOf("58mm", "80mm", "A4")

        const val OCR_ENGLISH = "English"
        const val OCR_MALAYALAM = "Malayalam"
        const val OCR_AUTO = "Auto"
        val OCR_LANGUAGES = listOf(OCR_ENGLISH, OCR_MALAYALAM, OCR_AUTO)
        /** Monospace columns for a given width. */
        fun colsFor(width: String): Int = when (width) { "80mm" -> 48; "A4" -> 64; else -> 32 }
        /** PDF page width in points for a given width (58mm ≈ 165pt). */
        fun pageWidthFor(width: String): Float = when (width) { "80mm" -> 227f; "A4" -> 560f; else -> 165f }
    }

    // ---- item batch tracking (batch no + expiry) ----
    var requireItemBatch: Boolean
        get() = p.getBoolean("require_item_batch", false)
        set(v) { p.edit().putBoolean("require_item_batch", v).apply() }

    /** Business vertical, drives medical (chemical content) and restaurant (sizes) features. */
    /**
     * True once the one-time business-type question has been answered. An install that
     * already has a business type set counts as onboarded, so existing users are never
     * asked.
     */
    var onboarded: Boolean
        get() = p.getBoolean("onboarded", false) || businessType.isNotBlank()
        set(v) { p.edit().putBoolean("onboarded", v).apply() }

    /** Personal mode hides the shop-keeping features, leaving the everyday tools. */
    val isPersonal: Boolean get() = businessType == "Personal"

    /** JSON of the most recent merge-restore, so its log can be reopened. */
    var lastMergeReport: String
        get() = p.getString("last_merge_report", "") ?: ""
        set(v) { p.edit().putString("last_merge_report", v).apply() }

    var businessType: String
        get() = p.getString("business_type", "") ?: ""
        set(v) { p.edit().putString("business_type", v).apply() }

    /**
     * Which script the camera/gallery OCR should read.
     * "English" = ML Kit Latin, "Malayalam" = Tesseract, "Auto" = try Latin then fall back.
     */
    var ocrLanguage: String
        get() = p.getString("ocr_language", OCR_ENGLISH) ?: OCR_ENGLISH
        set(v) { p.edit().putString("ocr_language", v).apply() }

    /** When on, a full-screen handwriting sticky-note canvas opens on launch (before the dashboard). */
    var stickyNoteOnLaunch: Boolean
        get() = p.getBoolean("sticky_note", false)
        set(v) { p.edit().putBoolean("sticky_note", v).apply() }

    /** When on, opening the app asks for the phone's own lock (PIN/pattern/fingerprint). */
    var appLock: Boolean
        get() = p.getBoolean("app_lock", false)
        set(v) { p.edit().putBoolean("app_lock", v).apply() }

    // ---- medical store: expiring-stock alerts ----
    /** Medical store only: warn about batches nearing expiry (daily notification + popup on open). */
    var expiryAlert: Boolean
        get() = p.getBoolean("expiry_alert", false)
        set(v) { p.edit().putBoolean("expiry_alert", v).apply() }

    /** How many days before expiry the warning starts. */
    var expiryAlertDays: Int
        get() = p.getInt("expiry_alert_days", 30)
        set(v) { p.edit().putInt("expiry_alert_days", v.coerceIn(1, 3650)).apply() }

    // ---- medical lab print assets ----
    /** Seal/stamp image path shown above the technician sign-off ("" = none). */
    var labSealPath: String
        get() = p.getString("lab_seal_path", "") ?: ""
        set(v) { p.edit().putString("lab_seal_path", v).apply() }

    /** Technician signature image path ("" = none). */
    var labSignaturePath: String
        get() = p.getString("lab_sign_path", "") ?: ""
        set(v) { p.edit().putString("lab_sign_path", v).apply() }

    /** Pre-printed letterhead (JPG/PNG/PDF) drawn as the page background ("" = none). */
    var labLetterheadPath: String
        get() = p.getString("lab_letterhead_path", "") ?: ""
        set(v) { p.edit().putString("lab_letterhead_path", v).apply() }

    /** Blank lines to skip at the TOP of a letterhead page before results start. */
    var labTopSkipLines: Int
        get() = p.getInt("lab_top_skip", 6)
        set(v) { p.edit().putInt("lab_top_skip", v).apply() }

    /** Blank lines to leave at the BOTTOM (above the printed footer) on a letterhead page. */
    var labBottomSkipLines: Int
        get() = p.getInt("lab_bottom_skip", 5)
        set(v) { p.edit().putInt("lab_bottom_skip", v).apply() }

    fun clearSession() { loggedInUserId = -1L }
}
