package com.billing.pos.ui.vat

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.auth.Session
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.Bill
import com.billing.pos.data.DownloadSaver
import com.billing.pos.data.Purchase
import com.billing.pos.data.Repository
import com.billing.pos.data.XlsxWriter
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

data class VatRateRow(val rate: Double, val taxable: Double, val tax: Double)
data class VatSummary(
    val from: Long,
    val to: Long,
    val salesTaxable: Double,
    val salesTax: Double,
    val purchaseTaxable: Double,
    val purchaseTax: Double,
    val salesRates: List<VatRateRow>,
    val purchaseRates: List<VatRateRow>,
    val bills: List<Bill>,
    val purchases: List<Purchase>
) {
    val netPayable: Double get() = salesTax - purchaseTax
}

private fun startOfDay(m: Long): Long {
    val c = Calendar.getInstance().apply {
        timeInMillis = m
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    return c.timeInMillis
}
private fun endOfDay(m: Long): Long = startOfDay(m) + 24L * 60 * 60 * 1000 - 1
private fun firstOfMonth(now: Long): Long {
    val c = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    return c.timeInMillis
}

class VatReportViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    var from by mutableStateOf(firstOfMonth(System.currentTimeMillis())); private set
    var to by mutableStateOf(System.currentTimeMillis()); private set
    var summary by mutableStateOf<VatSummary?>(null); private set
    var busy by mutableStateOf(false); private set
    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    fun updateFrom(m: Long) { from = m; load() }
    fun updateTo(m: Long) { to = m; load() }

    init { load() }

    fun load() {
        viewModelScope.launch {
            val lo = startOfDay(from); val hi = endOfDay(to)
            val bills = withContext(Dispatchers.IO) { repo.billsAll() }.filter { it.dateMillis in lo..hi }.sortedBy { it.dateMillis }
            val purchases = withContext(Dispatchers.IO) { repo.purchasesAll() }.filter { it.dateMillis in lo..hi }.sortedBy { it.dateMillis }
            val saleLines = withContext(Dispatchers.IO) { repo.saleTaxLines() }.filter { it.dateMillis in lo..hi }
            val purLines = withContext(Dispatchers.IO) { repo.purchaseTaxLines() }.filter { it.dateMillis in lo..hi }

            fun rates(list: List<com.billing.pos.data.TaxLineInfo>) =
                list.groupBy { it.rate }.map { (r, ls) -> VatRateRow(r, ls.sumOf { it.taxable }, ls.sumOf { it.tax }) }.sortedBy { it.rate }

            summary = VatSummary(
                from, to,
                saleLines.sumOf { it.taxable }, saleLines.sumOf { it.tax },
                purLines.sumOf { it.taxable }, purLines.sumOf { it.tax },
                rates(saleLines), rates(purLines), bills, purchases
            )
        }
    }

    fun exportExcel(context: Context, onSaved: (String) -> Unit) {
        val s = summary ?: return
        busy = true
        viewModelScope.launch {
            val company = AppPrefs(context).company
            val rows = buildExcelRows(company.name, company.gstin, s)
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val file = File(dir, "vat-report.xlsx")
            withContext(Dispatchers.IO) { XlsxWriter.write(file, "VAT Report", rows) }
            val ok = withContext(Dispatchers.IO) { DownloadSaver.save(context, file, "vat-report.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") }
            busy = false
            onSaved(if (ok) "Excel saved to Downloads: vat-report.xlsx" else "Could not save Excel")
        }
    }

    fun exportJson(context: Context, onSaved: (String) -> Unit) {
        val s = summary ?: return
        busy = true
        viewModelScope.launch {
            val company = AppPrefs(context).company
            val json = buildJson(company.name, company.gstin, s)
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val file = File(dir, "vat-report.json")
            withContext(Dispatchers.IO) { file.writeText(json) }
            val ok = withContext(Dispatchers.IO) { DownloadSaver.save(context, file, "vat-report.json", "application/json") }
            busy = false
            onSaved(if (ok) "JSON saved to Downloads: vat-report.json" else "Could not save JSON")
        }
    }

    private fun buildExcelRows(companyName: String, gstin: String, s: VatSummary): List<List<XlsxWriter.Cell>> {
        val t = XlsxWriter::text; val n = XlsxWriter::num
        val rows = ArrayList<List<XlsxWriter.Cell>>()
        rows.add(XlsxWriter.row(t("VAT / GST Report")))
        rows.add(XlsxWriter.row(t(companyName), t("GSTIN: $gstin")))
        rows.add(XlsxWriter.row(t("Period"), t("${Format.date(s.from)} to ${Format.date(s.to)}")))
        rows.add(XlsxWriter.row(t("")))
        rows.add(XlsxWriter.row(t("OUTPUT VAT — SALES")))
        rows.add(XlsxWriter.row(t("Bill No"), t("Date"), t("Customer"), t("GSTIN"), t("Taxable"), t("Tax"), t("Total")))
        s.bills.forEach {
            rows.add(XlsxWriter.row(t(it.billNo), t(Format.date(it.dateMillis)), t(it.customerName), t(it.customerGstin), n(it.subTotal), n(it.taxTotal), n(it.grandTotal)))
        }
        rows.add(XlsxWriter.row(t("Total"), t(""), t(""), t(""), n(s.salesTaxable), n(s.salesTax)))
        rows.add(XlsxWriter.row(t("")))
        rows.add(XlsxWriter.row(t("Sales tax by rate")))
        rows.add(XlsxWriter.row(t("Rate %"), t("Taxable"), t("Tax")))
        s.salesRates.forEach { rows.add(XlsxWriter.row(n(it.rate), n(it.taxable), n(it.tax))) }
        rows.add(XlsxWriter.row(t("")))
        rows.add(XlsxWriter.row(t("INPUT VAT — PURCHASES")))
        rows.add(XlsxWriter.row(t("Purchase No"), t("Date"), t("Supplier"), t("GSTIN"), t("Taxable"), t("Tax"), t("Total")))
        s.purchases.forEach {
            rows.add(XlsxWriter.row(t(it.purchaseNo), t(Format.date(it.dateMillis)), t(it.supplierName), t(it.supplierGstin), n(it.subTotal), n(it.taxTotal), n(it.grandTotal)))
        }
        rows.add(XlsxWriter.row(t("Total"), t(""), t(""), t(""), n(s.purchaseTaxable), n(s.purchaseTax)))
        rows.add(XlsxWriter.row(t("")))
        rows.add(XlsxWriter.row(t("Purchase tax by rate")))
        rows.add(XlsxWriter.row(t("Rate %"), t("Taxable"), t("Tax")))
        s.purchaseRates.forEach { rows.add(XlsxWriter.row(n(it.rate), n(it.taxable), n(it.tax))) }
        rows.add(XlsxWriter.row(t("")))
        rows.add(XlsxWriter.row(t("SUMMARY")))
        rows.add(XlsxWriter.row(t("Output tax (sales)"), n(s.salesTax)))
        rows.add(XlsxWriter.row(t("Input tax (purchases)"), n(s.purchaseTax)))
        rows.add(XlsxWriter.row(t("Net VAT payable"), n(s.netPayable)))
        return rows
    }

    private fun buildJson(companyName: String, gstin: String, s: VatSummary): String {
        val root = JSONObject()
        root.put("gstin", gstin)
        root.put("companyName", companyName)
        root.put("period", JSONObject().put("from", Format.date(s.from)).put("to", Format.date(s.to)))
        root.put("sales", JSONArray().apply {
            s.bills.forEach {
                put(JSONObject().put("billNo", it.billNo).put("date", Format.date(it.dateMillis))
                    .put("customer", it.customerName).put("gstin", it.customerGstin)
                    .put("taxable", it.subTotal).put("tax", it.taxTotal).put("total", it.grandTotal))
            }
        })
        root.put("purchases", JSONArray().apply {
            s.purchases.forEach {
                put(JSONObject().put("purchaseNo", it.purchaseNo).put("date", Format.date(it.dateMillis))
                    .put("supplier", it.supplierName).put("gstin", it.supplierGstin)
                    .put("taxable", it.subTotal).put("tax", it.taxTotal).put("total", it.grandTotal))
            }
        })
        fun rateArr(rows: List<VatRateRow>) = JSONArray().apply {
            rows.forEach { put(JSONObject().put("rate", it.rate).put("taxable", it.taxable).put("tax", it.tax)) }
        }
        root.put("summary", JSONObject()
            .put("outputTax", s.salesTax).put("inputTax", s.purchaseTax).put("netPayable", s.netPayable)
            .put("salesByRate", rateArr(s.salesRates)).put("purchaseByRate", rateArr(s.purchaseRates)))
        return root.toString(2)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VatReportScreen(
    onBack: () -> Unit,
    vm: VatReportViewModel = viewModel()
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsStateSafe()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    var pendingExport by remember { mutableStateOf<String?>(null) }   // "xlsx" or "json"
    fun runExport(kind: String) {
        if (kind == "xlsx") vm.exportExcel(context) { vm.message.value = it }
        else vm.exportJson(context) { vm.message.value = it }
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val k = pendingExport; pendingExport = null
        if (granted && k != null) runExport(k) else vm.message.value = "Storage permission denied"
    }
    fun export(kind: String) {
        if (DownloadSaver.needsLegacyPermission() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) { pendingExport = kind; storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
        else runExport(kind)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("VAT / GST Report") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        if (!Session.canViewInvoice) {
            Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.Center, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Text("You don't have permission to view this report", color = MaterialTheme.colorScheme.outline)
            }
            return@Scaffold
        }
        val s = vm.summary
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickDate(context, vm.from) { vm.updateFrom(it) } }, modifier = Modifier.weight(1f)) {
                    Text("From: ${Format.date(vm.from)}")
                }
                OutlinedButton(onClick = { pickDate(context, vm.to) { vm.updateTo(it) } }, modifier = Modifier.weight(1f)) {
                    Text("To: ${Format.date(vm.to)}")
                }
            }

            Card(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    SumRow("Sales taxable value", s?.salesTaxable ?: 0.0)
                    SumRow("Output tax (sales)", s?.salesTax ?: 0.0)
                    Divider(Modifier.padding(vertical = 6.dp))
                    SumRow("Purchase taxable value", s?.purchaseTaxable ?: 0.0)
                    SumRow("Input tax (purchases)", s?.purchaseTax ?: 0.0)
                    Divider(Modifier.padding(vertical = 6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Net VAT payable", fontWeight = FontWeight.Bold)
                        Text(Format.rupee(s?.netPayable ?: 0.0), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            if (s != null && s.salesRates.isNotEmpty()) {
                Text("Sales tax by rate", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
                s.salesRates.forEach {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${Format.money(it.rate)}%  on  ${Format.rupee(it.taxable)}", style = MaterialTheme.typography.bodySmall)
                        Text(Format.rupee(it.tax), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Text(
                "${s?.bills?.size ?: 0} sales • ${s?.purchases?.size ?: 0} purchases in period",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 12.dp)
            )

            Button(onClick = { export("xlsx") }, enabled = !vm.busy && s != null, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text("Download Excel (.xlsx)")
            }
            OutlinedButton(onClick = { export("json") }, enabled = !vm.busy && s != null, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Download JSON")
            }
        }
    }
}

@Composable
private fun SumRow(label: String, value: Double) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(Format.rupee(value), fontWeight = FontWeight.SemiBold)
    }
}

private fun pickDate(context: Context, current: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = current }
    android.app.DatePickerDialog(
        context,
        { _, y, m, d -> c.set(Calendar.YEAR, y); c.set(Calendar.MONTH, m); c.set(Calendar.DAY_OF_MONTH, d); onPicked(c.timeInMillis) },
        c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
    ).show()
}
