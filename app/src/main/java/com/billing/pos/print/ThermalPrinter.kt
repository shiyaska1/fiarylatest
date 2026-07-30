package com.billing.pos.print

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.Bill
import com.billing.pos.data.BillItem
import com.billing.pos.data.CompanyInfo
import com.billing.pos.data.Purchase
import com.billing.pos.data.PurchaseItem
import com.billing.pos.data.Receipt
import com.billing.pos.util.Format
import java.util.UUID

/**
 * Minimal ESC/POS printing over classic Bluetooth (SPP) for 58mm thermal printers (32 cols).
 * Pair the printer in Android Settings first; this picks the first paired printer, or one
 * whose name contains "print"/"pos"/"bt".
 */
object ThermalPrinter {

    private var COLS = 32
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    /** Sets the column count from the chosen receipt width before building output. */
    private fun applyWidth(context: Context) {
        COLS = AppPrefs.colsFor(AppPrefs(context).receiptWidth)
    }

    class PrinterException(message: String) : Exception(message)

    fun hasConnectPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun printBill(context: Context, company: CompanyInfo, bill: Bill, lines: List<BillItem>, imagePaths: List<String> = emptyList(), title: String = "TAX INVOICE") {
        applyWidth(context)
        val prefs = com.billing.pos.data.AppPrefs(context)
        // Render the UPI QR to a temp PNG so the ESC/POS rasteriser can print it.
        val qrPath = if (prefs.showUpiQrOnPrint && prefs.upiId.isNotBlank()) runCatching {
            val bmp = com.billing.pos.util.UpiQr.bitmap(
                com.billing.pos.util.UpiQr.link(prefs.upiId, prefs.upiName.ifBlank { company.name }, bill.grandTotal, bill.billNo), 360
            ) ?: return@runCatching null
            val f = java.io.File(context.cacheDir, "upi_qr_print.png")
            f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            f.absolutePath
        }.getOrNull() else null
        sendBytes(context, buildReceipt(company, bill, lines, imagePaths, title, qrPath))
    }

    /** Prints a diary entry: type, title, body, with the date and time. */
    @SuppressLint("MissingPermission")
    fun printDiary(
        context: Context,
        company: CompanyInfo,
        type: String,
        title: String,
        body: String,
        dateMillis: Long
    ) {
        applyWidth(context)
        sendBytes(context, buildDiary(company, type, title, body, dateMillis))
    }

    /** Prints a receipt voucher (money received) to give to the customer. */
    @SuppressLint("MissingPermission")
    fun printReceipt(context: Context, company: CompanyInfo, receipt: Receipt) {
        applyWidth(context)
        sendBytes(context, buildReceiptVoucher(company, receipt))
    }

    /** Prints a purchase voucher. */
    @SuppressLint("MissingPermission")
    fun printPurchase(context: Context, company: CompanyInfo, purchase: Purchase, lines: List<PurchaseItem>) {
        applyWidth(context)
        sendBytes(context, buildPurchase(company, purchase, lines))
    }

    /** Everything a service job card slip needs; lines are (job name, price, status). */
    data class JobCardPrint(
        val jobNo: String,
        val dateMillis: Long,
        val name: String,
        val customerName: String,
        val customerPhone: String,
        val modelName: String = "",
        val employeeName: String,
        val typeName: String,
        val status: String,
        val expectedMillis: Long,
        val lines: List<Triple<String, Double, String>>,
        val jobsTotal: Double,
        val otherCharges: Double,
        val otherChargesNote: String,
        val grandTotal: Double,
        val advance: Double,
        val remarks: String
    )

    /** Prints a service job card, same slip style as a sales bill. */
    @SuppressLint("MissingPermission")
    fun printJobCard(context: Context, company: CompanyInfo, jc: JobCardPrint) {
        applyWidth(context)
        sendBytes(context, buildJobCard(company, jc))
    }

    @SuppressLint("MissingPermission")
    private fun sendBytes(context: Context, bytes: ByteArray) {
        if (!hasConnectPermission(context)) throw PrinterException("Bluetooth permission not granted")

        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: throw PrinterException("No Bluetooth on this device")
        if (!adapter.isEnabled) throw PrinterException("Bluetooth is turned off")

        val device = pickPrinter(adapter, AppPrefs(context).printerAddress)
            ?: throw PrinterException("No paired printer found. Pair it in Settings first.")

        // cancelDiscovery() needs BLUETOOTH_SCAN on API 31+; it's only an optimisation
        // for a bonded device, so never let a missing SCAN permission block printing.
        runCatching { adapter.cancelDiscovery() }

        var socket: BluetoothSocket? = null
        try {
            socket = connect(device)
            val out = socket.outputStream
            out.write(bytes)
            out.flush()
            Thread.sleep(400) // let the buffer flush before closing
        } catch (e: Exception) {
            throw PrinterException(friendlyError(e))
        } finally {
            runCatching { socket?.close() }
        }
    }

    /**
     * Connects to [device] over RFCOMM, trying several strategies because cheap thermal
     * printers often fail the standard SDP connect with "read failed, socket might closed".
     */
    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice): BluetoothSocket {
        val strategies: List<() -> BluetoothSocket> = listOf(
            { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            { device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                .invoke(device, 1) as BluetoothSocket }
        )
        var last: Exception? = null
        for (make in strategies) {
            var s: BluetoothSocket? = null
            try {
                s = make()
                s.connect()
                return s
            } catch (e: Exception) {
                last = e
                runCatching { s?.close() }
                runCatching { Thread.sleep(150) }
            }
        }
        throw last ?: PrinterException("Could not connect to the printer")
    }

    private fun friendlyError(e: Exception): String {
        val m = e.message ?: ""
        return when {
            m.contains("read failed", true) || m.contains("socket", true) ||
                m.contains("timeout", true) || m.contains("closed", true) ->
                "Couldn't connect to the printer. Switch it off and on, keep it close, " +
                    "make sure it's charged and not already connected to another phone/app, then try again."
            m.contains("Service discovery failed", true) ->
                "Printer not responding. Re-pair it in Bluetooth settings, then try again."
            m.isBlank() -> "Failed to print. Try again."
            else -> m
        }
    }

    @SuppressLint("MissingPermission")
    private fun pickPrinter(adapter: BluetoothAdapter, preferredAddress: String): BluetoothDevice? {
        val bonded = adapter.bondedDevices ?: emptySet()
        if (preferredAddress.isNotBlank()) {
            bonded.firstOrNull { it.address == preferredAddress }?.let { return it }
        }
        return bonded.firstOrNull {
            val n = (it.name ?: "").lowercase()
            n.contains("print") || n.contains("pos") || n.contains("bt") || n.contains("thermal")
        } ?: bonded.firstOrNull()
    }

    /** A paired Bluetooth device the user can choose as their printer. */
    data class BtDevice(val name: String, val address: String)

    /** Lists paired Bluetooth devices, or empty if permission/adapter unavailable. */
    @SuppressLint("MissingPermission")
    fun bondedPrinters(context: Context): List<BtDevice> {
        if (!hasConnectPermission(context)) return emptyList()
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
        val bonded = adapter.bondedDevices ?: return emptyList()
        return bonded.map { BtDevice(it.name ?: "(unknown)", it.address) }.sortedBy { it.name.lowercase() }
    }

    /** Returns a human message if Bluetooth isn't ready to print, else null. */
    fun bluetoothProblem(context: Context): String? {
        if (!hasConnectPermission(context)) return "Bluetooth permission not granted"
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return "No Bluetooth on this device"
        if (!adapter.isEnabled) return "Bluetooth is turned off"
        return null
    }

    /** Prints a short test slip to confirm the connection works. */
    @SuppressLint("MissingPermission")
    fun testPrint(context: Context, company: CompanyInfo) {
        applyWidth(context)
        sendBytes(context, buildTest(company))
    }

    private fun buildTest(company: CompanyInfo): ByteArray {
        val sb = StringBuilder()
        sb.append(center(company.name.ifBlank { "My Shop" })).append('\n')
        sb.append(center("PRINTER TEST")).append('\n')
        sb.append(line()).append('\n')
        sb.append("Connection OK!\n")
        sb.append("$COLS-column test:\n")
        sb.append("1".repeat(COLS)).append('\n')
        sb.append(Format.dateTime(System.currentTimeMillis())).append('\n')
        sb.append(line()).append('\n')
        sb.append("\n\n\n")
        val text = sb.toString().toByteArray(Charsets.US_ASCII)
        val init = byteArrayOf(ESC.toByte(), '@'.code.toByte())
        val cut = byteArrayOf(GS.toByte(), 'V'.code.toByte(), 66, 0)
        return init + BOLD_ON + text + cut
    }

    private fun buildDiary(
        company: CompanyInfo,
        type: String,
        title: String,
        body: String,
        dateMillis: Long
    ): ByteArray {
        val sb = StringBuilder()
        if (company.name.isNotBlank()) sb.append(center(company.name)).append('\n')
        if (company.phone.isNotBlank()) sb.append(center("Ph: " + company.phone)).append('\n')
        sb.append(line()).append('\n')
        sb.append(com.billing.pos.util.Format.dateTime(dateMillis)).append('\n')
        if (type.isNotBlank()) sb.append("Type: ").append(type).append('\n')
        sb.append(line()).append('\n')
        if (title.isNotBlank()) {
            wrapCols(title).forEach { sb.append(it).append('\n') }
            sb.append(line()).append('\n')
        }
        body.split('\n').forEach { para ->
            if (para.isBlank()) sb.append('\n')
            else wrapCols(para).forEach { sb.append(it).append('\n') }
        }
        sb.append("\n\n\n")
        val text = sb.toString().toByteArray(Charsets.US_ASCII)
        val init = byteArrayOf(ESC.toByte(), '@'.code.toByte())
        val cut = byteArrayOf(GS.toByte(), 'V'.code.toByte(), 66, 0)
        return init + BOLD_ON + text + cut
    }

    /** Hard-wraps to the printer's column count so nothing is chopped off the edge. */
    private fun wrapCols(text: String): List<String> {
        val words = text.trim().split(Regex("\\s+"))
        val out = ArrayList<String>()
        var line = StringBuilder()
        words.forEach { w ->
            val candidate = if (line.isEmpty()) w else line.toString() + " " + w
            if (candidate.length <= COLS || line.isEmpty()) line = StringBuilder(candidate)
            else { out.add(line.toString()); line = StringBuilder(w) }
        }
        if (line.isNotEmpty()) out.add(line.toString())
        return out
    }

    // ---- ESC/POS byte building -------------------------------------------------

    private const val ESC = 0x1B
    private const val GS = 0x1D

    /**
     * Makes the whole slip bold, using every widely-supported ESC/POS bold flag so at least
     * one is honoured by cheap printers:
     *  - ESC ! 0x08  print-mode emphasized bit
     *  - ESC E 1     emphasized on
     *  - ESC G 1     double-strike (overprints each dot → darker)
     */
    private val BOLD_ON = byteArrayOf(
        ESC.toByte(), '!'.code.toByte(), 0x08,
        ESC.toByte(), 'E'.code.toByte(), 1,
        ESC.toByte(), 'G'.code.toByte(), 1
    )

    private fun buildReceipt(company: CompanyInfo, bill: Bill, lines: List<BillItem>, imagePaths: List<String> = emptyList(), title: String = "TAX INVOICE", qrPath: String? = null): ByteArray {
        val sb = StringBuilder()
        sb.append(center(company.name)).append('\n')
        if (company.address.isNotBlank()) sb.append(center(company.address)).append('\n')
        if (company.phone.isNotBlank()) sb.append(center("Ph: ${company.phone}")).append('\n')
        sb.append(center(title)).append('\n')
        sb.append(line()).append('\n')
        sb.append("Bill: ${bill.billNo}\n")
        sb.append("Date: ${Format.dateTime(bill.dateMillis)}\n")
        sb.append("Cust: ${bill.customerName}\n")
        sb.append("Pay : ${bill.paymentMethod}\n")
        sb.append(line()).append('\n')
        sb.append(row("Item", "Qty", "Amount")).append('\n')
        sb.append(line()).append('\n')
        for (l in lines) {
            sb.append(clip(l.name, COLS)).append('\n')
            sb.append(row("  @${Format.money(l.price)}" + (if (l.unit.isNotBlank()) "/${l.unit}" else ""), Format.qty(l.qty), Format.money(l.lineTotal)))
                .append('\n')
        }
        sb.append(line()).append('\n')
        sb.append(kv("Sub Total", Format.money(bill.subTotal))).append('\n')
        sb.append(kv("Tax", Format.money(bill.taxTotal))).append('\n')
        if (bill.additionalCharge != 0.0)
            sb.append(kv("Additional", Format.money(bill.additionalCharge))).append('\n')
        if (bill.discount != 0.0)
            sb.append(kv("Discount", "-" + Format.money(bill.discount))).append('\n')
        sb.append(line()).append('\n')
        sb.append(kv("GRAND TOTAL", Format.money(bill.grandTotal))).append('\n')
        sb.append(line()).append('\n')
        if (bill.remarks.isNotBlank()) {
            sb.append("Note:\n")
            bill.remarks.chunked(COLS).forEach { sb.append(it).append('\n') }
            sb.append(line()).append('\n')
        }
        if (qrPath != null) sb.append(center("Scan to pay " + Format.money(bill.grandTotal))).append('\n')
        sb.append(center("Thank you! Visit again.")).append('\n')

        val text = sb.toString().toByteArray(Charsets.US_ASCII)
        val maxDots = COLS * 12
        // The UPI QR (60% width, centred) then any attached photos, as raster.
        val qrBytes = if (qrPath != null) escposImage(qrPath, maxDots * 3 / 5) else ByteArray(0)
        val imgBytes = imagePaths.fold(ByteArray(0)) { acc, p -> acc + escposImage(p, maxDots) }
        val feed = "\n\n\n".toByteArray(Charsets.US_ASCII)
        val init = byteArrayOf(ESC.toByte(), '@'.code.toByte())          // initialize
        val cut = byteArrayOf(GS.toByte(), 'V'.code.toByte(), 66, 0)      // partial cut (ignored if unsupported)
        return init + BOLD_ON + text + qrBytes + imgBytes + feed + cut
    }

    /** Converts an image file to a centred ESC/POS raster (GS v 0), banded so cheap printers cope. */
    private fun escposImage(path: String, maxDots: Int): ByteArray {
        val src = runCatching { android.graphics.BitmapFactory.decodeFile(path) }.getOrNull() ?: return ByteArray(0)
        val w = maxDots.coerceAtLeast(8)
        val h = (src.height.toFloat() * w / src.width).toInt().coerceAtLeast(1)
        val bmp = android.graphics.Bitmap.createScaledBitmap(src, w, h, true)
        src.recycle()
        val pixels = IntArray(w * h); bmp.getPixels(pixels, 0, w, 0, 0, w, h); bmp.recycle()
        val bytesPerRow = (w + 7) / 8
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(ESC.toByte(), 'a'.code.toByte(), 1))       // centre
        var yStart = 0
        val band = 128
        while (yStart < h) {
            val rows = minOf(band, h - yStart)
            out.write(byteArrayOf(GS.toByte(), 'v'.code.toByte(), '0'.code.toByte(), 0,
                (bytesPerRow and 0xFF).toByte(), ((bytesPerRow shr 8) and 0xFF).toByte(),
                (rows and 0xFF).toByte(), ((rows shr 8) and 0xFF).toByte()))
            val data = ByteArray(bytesPerRow * rows)
            for (ry in 0 until rows) {
                val yy = yStart + ry
                for (xx in 0 until w) {
                    val px = pixels[yy * w + xx]
                    val a = (px ushr 24) and 0xFF
                    val r = (px shr 16) and 0xFF; val g = (px shr 8) and 0xFF; val b = px and 0xFF
                    val lum = (r * 299 + g * 587 + b * 114) / 1000
                    if (a > 128 && lum < 140) {
                        val bi = ry * bytesPerRow + (xx / 8)
                        data[bi] = (data[bi].toInt() or (0x80 shr (xx % 8))).toByte()
                    }
                }
            }
            out.write(data)
            yStart += rows
        }
        out.write(byteArrayOf(ESC.toByte(), 'a'.code.toByte(), 0))       // left
        return out.toByteArray()
    }

    private fun buildPurchase(company: CompanyInfo, pur: Purchase, lines: List<PurchaseItem>): ByteArray {
        val sb = StringBuilder()
        sb.append(center(company.name)).append('\n')
        if (company.address.isNotBlank()) sb.append(center(company.address)).append('\n')
        if (company.phone.isNotBlank()) sb.append(center("Ph: ${company.phone}")).append('\n')
        sb.append(center("PURCHASE VOUCHER")).append('\n')
        sb.append(line()).append('\n')
        sb.append("No  : ${pur.purchaseNo}\n")
        sb.append("Date: ${Format.dateTime(pur.dateMillis)}\n")
        sb.append("Supp: ${pur.supplierName}\n")
        sb.append("Pay : ${pur.paymentMethod}\n")
        sb.append(line()).append('\n')
        sb.append(row("Item", "Qty", "Amount")).append('\n')
        sb.append(line()).append('\n')
        for (l in lines) {
            sb.append(clip(l.name, COLS)).append('\n')
            sb.append(row("  @${Format.money(l.price)}" + (if (l.unit.isNotBlank()) "/${l.unit}" else ""), Format.qty(l.qty), Format.money(l.lineTotal))).append('\n')
        }
        sb.append(line()).append('\n')
        sb.append(kv("Sub Total", Format.money(pur.subTotal))).append('\n')
        sb.append(kv("Tax", Format.money(pur.taxTotal))).append('\n')
        if (pur.additionalCharge != 0.0) sb.append(kv("Additional", Format.money(pur.additionalCharge))).append('\n')
        if (pur.discount != 0.0) sb.append(kv("Discount", "-" + Format.money(pur.discount))).append('\n')
        sb.append(line()).append('\n')
        sb.append(kv("GRAND TOTAL", Format.money(pur.grandTotal))).append('\n')
        sb.append(line()).append('\n')
        sb.append("\n\n\n")

        val text = sb.toString().toByteArray(Charsets.US_ASCII)
        val init = byteArrayOf(ESC.toByte(), '@'.code.toByte())
        val cut = byteArrayOf(GS.toByte(), 'V'.code.toByte(), 66, 0)
        return init + BOLD_ON + text + cut
    }

    private fun buildJobCard(company: CompanyInfo, jc: JobCardPrint): ByteArray {
        val sb = StringBuilder()
        sb.append(center(company.name)).append('\n')
        if (company.address.isNotBlank()) sb.append(center(company.address)).append('\n')
        if (company.phone.isNotBlank()) sb.append(center("Ph: ${company.phone}")).append('\n')
        sb.append(center("JOB CARD")).append('\n')
        sb.append(line()).append('\n')
        sb.append("No  : ${jc.jobNo}\n")
        sb.append("Date: ${Format.dateTime(jc.dateMillis)}\n")
        if (jc.name.isNotBlank()) sb.append("Card: ${clip(jc.name, COLS - 6)}\n")
        sb.append("Cust: ${clip(jc.customerName, COLS - 6)}\n")
        if (jc.customerPhone.isNotBlank()) sb.append("Ph  : ${jc.customerPhone}\n")
        if (jc.modelName.isNotBlank()) sb.append("Modl: ${clip(jc.modelName, COLS - 6)}\n")
        if (jc.employeeName.isNotBlank()) sb.append("Emp : ${clip(jc.employeeName, COLS - 6)}\n")
        if (jc.typeName.isNotBlank()) sb.append("Type: ${clip(jc.typeName, COLS - 6)}\n")
        if (jc.expectedMillis > 0) sb.append("Exp : ${Format.date(jc.expectedMillis)}\n")
        sb.append(line()).append('\n')
        sb.append(kv("Job", "Amount")).append('\n')
        sb.append(line()).append('\n')
        // One job per row: name left, amount right. No statuses on the slip.
        for ((name, price, _) in jc.lines) {
            sb.append(kv(clip(name, COLS - 10), Format.money(price))).append('\n')
        }
        sb.append(line()).append('\n')
        sb.append(kv("Jobs Total", Format.money(jc.jobsTotal))).append('\n')
        if (jc.otherCharges != 0.0)
            sb.append(kv(jc.otherChargesNote.ifBlank { "Other charges" }.take(COLS - 12), Format.money(jc.otherCharges))).append('\n')
        sb.append(line()).append('\n')
        sb.append(kv("TOTAL", Format.money(jc.grandTotal))).append('\n')
        if (jc.advance != 0.0) {
            sb.append(kv("Advance", Format.money(jc.advance))).append('\n')
            sb.append(kv("BALANCE", Format.money(jc.grandTotal - jc.advance))).append('\n')
        }
        sb.append(line()).append('\n')
        if (jc.remarks.isNotBlank()) {
            sb.append("Note:\n")
            jc.remarks.chunked(COLS).forEach { sb.append(it).append('\n') }
            sb.append(line()).append('\n')
        }
        sb.append(center("Thank you!")).append('\n')
        sb.append("\n\n\n")

        val text = sb.toString().toByteArray(Charsets.US_ASCII)
        val init = byteArrayOf(ESC.toByte(), '@'.code.toByte())
        val cut = byteArrayOf(GS.toByte(), 'V'.code.toByte(), 66, 0)
        return init + BOLD_ON + text + cut
    }

    private fun buildReceiptVoucher(company: CompanyInfo, r: Receipt): ByteArray {
        val sb = StringBuilder()
        sb.append(center(company.name)).append('\n')
        if (company.address.isNotBlank()) sb.append(center(company.address)).append('\n')
        if (company.phone.isNotBlank()) sb.append(center("Ph: ${company.phone}")).append('\n')
        sb.append(center("RECEIPT VOUCHER")).append('\n')
        sb.append(line()).append('\n')
        sb.append("No  : ${r.receiptNo}\n")
        sb.append("Date: ${Format.dateTime(r.dateMillis)}\n")
        sb.append("From: ${r.payFrom.ifBlank { r.customerName }}\n")
        if (r.billNo.isNotBlank()) sb.append("Ref : ${r.billNo}\n")
        sb.append("Mode: ${r.paymentMode}\n")
        sb.append(line()).append('\n')
        sb.append(kv("RECEIVED", Format.money(r.amount))).append('\n')
        sb.append(line()).append('\n')
        sb.append(center("Thank you")).append('\n')
        sb.append("\n\n\n")

        val text = sb.toString().toByteArray(Charsets.US_ASCII)
        val init = byteArrayOf(ESC.toByte(), '@'.code.toByte())
        val cut = byteArrayOf(GS.toByte(), 'V'.code.toByte(), 66, 0)
        return init + BOLD_ON + text + cut
    }

    private fun line() = "-".repeat(COLS)

    private fun center(s: String): String {
        val t = clip(s, COLS)
        val pad = (COLS - t.length) / 2
        return " ".repeat(pad.coerceAtLeast(0)) + t
    }

    /** Left key, right value on the same 32-col line. */
    private fun kv(key: String, value: String): String {
        val space = (COLS - key.length - value.length).coerceAtLeast(1)
        return key + " ".repeat(space) + value
    }

    /** Three columns: left(16) center(6) right(10). */
    private fun row(a: String, b: String, cRight: String): String {
        val left = a.take(16).padEnd(16)
        val mid = b.take(6).padStart(6)
        val right = cRight.take(10).padStart(10)
        return (left + mid + right).take(COLS)
    }

    private fun clip(s: String, max: Int) = if (s.length <= max) s else s.take(max)
}
