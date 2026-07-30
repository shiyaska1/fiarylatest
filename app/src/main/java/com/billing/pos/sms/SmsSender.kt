package com.billing.pos.sms

import android.content.Context
import com.billing.pos.data.AppDatabase
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.SmsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Result of one send attempt. */
data class SmsResult(val ok: Boolean, val response: String)

/**
 * Sends SMS through a generic HTTP gateway (any provider, configured as a URL template) or, in the
 * APK build only, through the phone's SIM. Every attempt is written to the sms_log for the delivery
 * report.
 */
object SmsSender {

    /** Replace {name} {mobile} and any {custom} tokens in [template] from [values]. */
    fun fillTemplate(template: String, values: Map<String, String>): String {
        var out = template
        values.forEach { (k, v) -> out = out.replace("{$k}", v) }
        return out
    }

    /** Split a contact's stored "9x, 9y" number string into individual numbers. */
    fun splitNumbers(mobile: String): List<String> =
        mobile.split(",").map { it.trim() }.filter { it.isNotBlank() }

    private val VAR = Regex("\\{([A-Za-z0-9_]+)\\}")
    /** Custom {variable} names in a template body, excluding the built-in name/mobile. */
    fun customVars(body: String): List<String> =
        VAR.findAll(body).map { it.groupValues[1] }.distinct().filter { it != "name" && it != "mobile" }.toList()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /**
     * Send one message to one number. [channel] is "Gateway" or "SIM". Logs the attempt.
     * Returns the result. Runs network/IO on the IO dispatcher.
     */
    suspend fun send(context: Context, number: String, message: String, channel: String, contactName: String = "", batchId: Long = 0): SmsResult {
        val app = context.applicationContext
        val dao = AppDatabase.get(app).smsLogDao()
        val logId = dao.insert(
            SmsLog(dateMillis = System.currentTimeMillis(), mobile = number, contactName = contactName,
                body = message, channel = channel, status = "QUEUED")
        )
        val result = try {
            if (channel == "SIM") sendViaSim(app, number, message) else sendViaGateway(app, number, message)
        } catch (e: Exception) {
            SmsResult(false, e.message ?: "error")
        }
        dao.updateStatus(logId, if (result.ok) "SENT" else "FAILED", result.response.take(500))
        return result
    }

    private suspend fun sendViaGateway(context: Context, number: String, message: String): SmsResult {
        val prefs = AppPrefs(context)
        val url = prefs.smsGatewayUrl
        if (url.isBlank()) return SmsResult(false, "No gateway URL set in SMS Settings")

        // JSON token API (e.g. LM6/gjinfotech): POST the JSON body with placeholders filled.
        val jsonTpl = prefs.smsJsonBody
        if (jsonTpl.isNotBlank()) {
            val body = jsonTpl
                .replace("{number}", jsonEsc(number))
                .replace("{message}", jsonEsc(message))
                .replace("{apikey}", jsonEsc(prefs.smsApiKey))
                .replace("{sender}", jsonEsc(prefs.smsSenderId))
            val bearer = if (prefs.smsBearer) prefs.smsApiKey else null
            return withContext(Dispatchers.IO) { httpCall(url, "POST", body, "application/json", bearer) }
        }

        val method = if (prefs.smsGatewayMethod.equals("POST", true)) "POST" else "GET"
        fun subst(raw: Boolean): String = url
            .replace("{number}", if (raw) number else enc(number))
            .replace("{message}", if (raw) message else enc(message))
            .replace("{apikey}", if (raw) prefs.smsApiKey else enc(prefs.smsApiKey))
            .replace("{sender}", if (raw) prefs.smsSenderId else enc(prefs.smsSenderId))
        return withContext(Dispatchers.IO) {
            if (method == "POST") {
                val full = subst(false)
                val q = full.indexOf('?')
                val base = if (q >= 0) full.substring(0, q) else full
                val body = if (q >= 0) full.substring(q + 1) else ""
                httpCall(base, "POST", body, "application/x-www-form-urlencoded", if (prefs.smsBearer) prefs.smsApiKey else null)
            } else {
                httpCall(subst(false), "GET", null, null, if (prefs.smsBearer) prefs.smsApiKey else null)
            }
        }
    }

    /** Escape a value for safe inclusion inside a JSON string. */
    private fun jsonEsc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")

    /** Phone-SIM sending. The SEND_SMS permission ships in the APK (debug) manifest only. */
    private fun sendViaSim(context: Context, number: String, message: String): SmsResult {
        if (!com.billing.pos.BuildConfig.DEBUG) return SmsResult(false, "SIM sending is available in the APK build only")
        return try {
            val mgr = if (android.os.Build.VERSION.SDK_INT >= 31)
                context.getSystemService(android.telephony.SmsManager::class.java)
            else @Suppress("DEPRECATION") android.telephony.SmsManager.getDefault()
            val parts = mgr.divideMessage(message)
            mgr.sendMultipartTextMessage(number, null, parts, null, null)
            SmsResult(true, "Handed to SIM")
        } catch (e: Exception) {
            SmsResult(false, e.message ?: "SIM error")
        }
    }

    /** Query the provider's balance URL, if configured. Returns the raw response text. */
    suspend fun checkBalance(context: Context): SmsResult {
        val prefs = AppPrefs(context)
        val url = prefs.smsBalanceUrl.replace("{apikey}", enc(prefs.smsApiKey))
        if (url.isBlank()) return SmsResult(false, "No balance URL set in SMS Settings")
        val bearer = if (prefs.smsBearer) prefs.smsApiKey else null
        return withContext(Dispatchers.IO) { httpCall(url, "GET", null, null, bearer) }
    }

    private fun httpCall(urlStr: String, method: String, body: String?, contentType: String?, bearer: String? = null): SmsResult {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 10000
            conn.readTimeout = 20000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "application/json")
            if (!bearer.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $bearer")
            if (method == "POST") {
                conn.doOutput = true
                if (contentType != null) conn.setRequestProperty("Content-Type", contentType)
                conn.outputStream.use { it.write((body ?: "").toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val out = ByteArrayOutputStream()
            stream?.use { it.copyTo(out) }
            conn.disconnect()
            val text = out.toString("UTF-8").trim()
            SmsResult(code in 200..299, if (text.isBlank()) "HTTP $code" else text)
        } catch (e: Exception) {
            SmsResult(false, e.message ?: "network error")
        }
    }
}
