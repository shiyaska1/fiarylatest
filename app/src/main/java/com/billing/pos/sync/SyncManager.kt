package com.billing.pos.sync

import android.content.Context
import android.text.format.Formatter
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.License
import com.billing.pos.data.Repository
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Offline two-device LAN sync (Stage 1: items, customers, sales bills, receipts, expenses).
 *
 * One phone runs as **host** ([startHost]); it exposes a tiny HTTP endpoint. The other phone is a
 * **client** ([syncWithHost]); it POSTs its own [Repository.exportJson] to the host, the host merges
 * it and replies with the host's own export, and the client merges that back — a full two-way sync in
 * one round-trip. Both sides use the app's existing idempotent import (deduped by device + number),
 * so re-syncing is always safe and never duplicates data.
 */
object SyncManager {

    /** Human-readable status shown in Settings (last result / errors). */
    val status = MutableStateFlow("Idle")

    private var server: SyncServer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var autoJob: Job? = null
    private val gate = Mutex() // never run two syncs / merges at once

    fun isHosting(): Boolean = server != null

    /** IPv4 address of this device on the current WiFi, or null if not connected. */
    @Suppress("DEPRECATION")
    fun wifiIp(context: Context): String? {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager ?: return null
        val ip = wm.connectionInfo?.ipAddress ?: return null
        if (ip == 0) return null
        return Formatter.formatIpAddress(ip)
    }

    fun startHost(context: Context) {
        if (server != null) return
        val app = context.applicationContext
        val port = AppPrefs(app).syncPort
        try {
            server = SyncServer(app, port).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
            AppPrefs(app).syncHostMode = true
            val ip = wifiIp(app)
            status.value = if (ip != null) "Hosting at $ip:$port — enter this on the other phone" else "Hosting on port $port (connect to WiFi to get an address)"
        } catch (e: Exception) {
            server = null
            status.value = "Could not start host: ${e.message}"
        }
    }

    fun stopHost(context: Context) {
        runCatching { server?.stop() }
        server = null
        AppPrefs(context.applicationContext).syncHostMode = false
        status.value = "Host stopped"
    }

    /** Client: perform one full two-way sync with the host at [ip]:[port]. */
    fun syncWithHost(context: Context, ip: String, port: Int, onDone: (Boolean) -> Unit = {}) {
        val app = context.applicationContext
        scope.launch {
            val ok = runOneSync(app, ip, port)
            withContext(Dispatchers.Main) { onDone(ok) }
        }
    }

    private suspend fun runOneSync(app: Context, ip: String, port: Int): Boolean = gate.withLock {
        status.value = "Syncing with $ip…"
        try {
            val repo = Repository(app)
            val myId = License.deviceId(app)
            val mine = repo.exportJson(myId)
            val reply = withContext(Dispatchers.IO) { httpPost("http://$ip:$port/sync", mine) }
            val res = repo.importJson(reply)
            status.value = "Synced with $ip · +${res.billsAdded} bills, +${res.receiptsAdded} receipts, +${res.expensesAdded} expenses"
            true
        } catch (e: Exception) {
            status.value = "Sync failed: ${e.message}"
            false
        }
    }

    /** Called by the host's server thread when a client pushes its data. Returns the host's own export. */
    suspend fun handleIncoming(app: Context, body: String): String = gate.withLock {
        val repo = Repository(app)
        runCatching { repo.importJson(body) }
        status.value = "A device synced with me"
        repo.exportJson(License.deviceId(app))
    }

    /** Client auto-sync loop: re-sync every [intervalMs] while enabled and the app is open. */
    fun startAuto(context: Context, ip: String, port: Int, intervalMs: Long = 20_000L) {
        stopAuto()
        val app = context.applicationContext
        autoJob = scope.launch {
            while (isActive) {
                runOneSync(app, ip, port)
                delay(intervalMs)
            }
        }
    }

    fun stopAuto() {
        autoJob?.cancel()
        autoJob = null
    }

    // --- minimal HTTP client (no dependency); returns response body as text ---
    private fun httpPost(urlStr: String, body: String): String {
        val url = java.net.URL(urlStr)
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 8000
        conn.readTimeout = 30000
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val out = ByteArrayOutputStream()
        stream?.use { it.copyTo(out) }
        conn.disconnect()
        if (code !in 200..299) throw java.io.IOException("HTTP $code")
        return out.toString("UTF-8")
    }

    /** Embedded server run on the host device. */
    private class SyncServer(private val app: Context, port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            if (session.method == Method.POST && session.uri == "/sync") {
                return try {
                    val len = session.headers["content-length"]?.toIntOrNull() ?: 0
                    val buf = ByteArray(len)
                    var read = 0
                    while (read < len) {
                        val r = session.inputStream.read(buf, read, len - read)
                        if (r < 0) break
                        read += r
                    }
                    val body = String(buf, 0, read, Charsets.UTF_8)
                    // Bridge the server thread to a coroutine to reuse the suspend merge.
                    val reply = kotlinx.coroutines.runBlocking { handleIncoming(app, body) }
                    newFixedLengthResponse(Response.Status.OK, "application/json", reply)
                } catch (e: Exception) {
                    newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message ?: "error")
                }
            }
            if (session.method == Method.GET && session.uri == "/ping") {
                return newFixedLengthResponse(Response.Status.OK, "text/plain", "posbilling")
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
        }
    }
}
