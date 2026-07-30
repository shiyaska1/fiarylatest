package com.billing.pos.ui.outstanding

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import com.billing.pos.data.XlsxWriter
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.auth.Session
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.Bill
import com.billing.pos.data.DownloadSaver
import com.billing.pos.data.Purchase
import com.billing.pos.data.Repository
import com.billing.pos.pdf.StatementPdf
import com.billing.pos.pdf.TablePdf
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.ui.common.rememberPdfDownloader
import com.billing.pos.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class OutstandingViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    val bills: StateFlow<List<Bill>> =
        repo.allBills.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val purchases: StateFlow<List<Purchase>> =
        repo.allPurchases.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val customers: StateFlow<List<com.billing.pos.data.Customer>> =
        repo.customers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val suppliers: StateFlow<List<com.billing.pos.data.Supplier>> =
        repo.suppliers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val receipts: StateFlow<List<com.billing.pos.data.Receipt>> =
        repo.allReceipts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val salesReturns: StateFlow<List<com.billing.pos.data.SalesReturn>> =
        repo.salesReturns.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val expenses: StateFlow<List<com.billing.pos.data.Expense>> =
        repo.allExpenses.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val purchaseReturns: StateFlow<List<com.billing.pos.data.PurchaseReturn>> =
        repo.purchaseReturns.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

/** One movement in a party's account. [debit] raises the balance owed, [credit] lowers it. */
data class LedgerRow(
    val date: Long,
    val kind: String,
    val ref: String,
    val debit: Double,
    val credit: Double
)

/**
 * Builds a party's running-balance statement and trims it to the last stretch that matters.
 *
 * Every sale is a debit; its upfront/cash payment, later receipts, and returns are credits.
 * A cash sale carries an automatic receipt for the amount paid at the counter, so it nets
 * to zero and does not inflate what is owed. The running balance is walked oldest-first,
 * and everything up to and including the last moment it reached zero is dropped — so the
 * statement begins at the point the current dues started building. The closing balance is
 * unchanged by that trim, and equals the party's outstanding.
 */
fun buildLedger(
    party: String,
    isPayable: Boolean,
    bills: List<Bill>,
    receipts: List<com.billing.pos.data.Receipt>,
    salesReturns: List<com.billing.pos.data.SalesReturn>,
    purchases: List<Purchase>,
    expenses: List<com.billing.pos.data.Expense>,
    purchaseReturns: List<com.billing.pos.data.PurchaseReturn>
): Pair<List<LedgerRow>, Double> {
    val rows = ArrayList<LedgerRow>()
    if (isPayable) {
        val myPurchases = purchases.filter { it.supplierName.equals(party, true) }
        myPurchases.forEach { p ->
            rows.add(LedgerRow(p.dateMillis, "Purchase", p.purchaseNo, p.grandTotal, 0.0))
            val paymentsAgainst = expenses.filter { it.purchaseId == p.id }.sumOf { it.amount }
            val upfront = (p.paidAmount - paymentsAgainst).coerceAtLeast(0.0)
            if (upfront > 0.001) rows.add(LedgerRow(p.dateMillis, "Cash purchase (auto)", p.purchaseNo, 0.0, upfront))
        }
        val myPurchaseIds = myPurchases.map { it.id }.toSet()
        expenses.filter { it.purchaseId in myPurchaseIds }
            .forEach { rows.add(LedgerRow(it.dateMillis, "Payment", it.voucherNo, 0.0, it.amount)) }
        purchaseReturns.filter { it.supplierName.equals(party, true) }
            .forEach { rows.add(LedgerRow(it.dateMillis, "Purchase return", it.returnNo, 0.0, it.grandTotal)) }
    } else {
        val myBills = bills.filter { it.customerName.equals(party, true) }
        myBills.forEach { b ->
            rows.add(LedgerRow(b.dateMillis, "Sale", b.billNo, b.grandTotal, 0.0))
            val receiptsAgainst = receipts.filter { it.billId == b.id }.sumOf { it.amount }
            val upfront = (b.paidAmount - receiptsAgainst).coerceAtLeast(0.0)
            if (upfront > 0.001) rows.add(LedgerRow(b.dateMillis, "Cash sale (auto)", b.billNo, 0.0, upfront))
        }
        receipts.filter { r ->
            myBills.any { it.id == r.billId } || (r.billId == 0L && r.payFrom.ifBlank { r.customerName }.equals(party, true))
        }.distinctBy { it.id }
            .forEach { rows.add(LedgerRow(it.dateMillis, "Receipt", it.receiptNo, 0.0, it.amount)) }
        salesReturns.filter { it.customerName.equals(party, true) }
            .forEach { rows.add(LedgerRow(it.dateMillis, "Sales return", it.returnNo, 0.0, it.grandTotal)) }
    }

    // Oldest first for the running balance; the reset point is the last time it hit zero.
    val chrono = rows.sortedBy { it.date }
    var running = 0.0
    var resetAfter = -1
    chrono.forEachIndexed { i, r ->
        running += r.debit - r.credit
        if (kotlin.math.abs(running) < 0.001) resetAfter = i
    }
    val closing = running
    val kept = chrono.drop(resetAfter + 1)
    // Shown newest first, as asked.
    return kept.sortedByDescending { it.date } to closing
}

private data class DocDue(val no: String, val date: Long, val balance: Double)
private data class PartyDue(val name: String, val total: Double, val docs: List<DocDue>)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun OutstandingScreen(
    onBack: () -> Unit,
    vm: OutstandingViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val bills by vm.bills.collectAsStateSafe()
    val purchases by vm.purchases.collectAsStateSafe()
    val customers by vm.customers.collectAsStateSafe()
    val suppliers by vm.suppliers.collectAsStateSafe()
    val receipts by vm.receipts.collectAsStateSafe()
    val salesReturns by vm.salesReturns.collectAsStateSafe()
    val expenses by vm.expenses.collectAsStateSafe()
    val purchaseReturns by vm.purchaseReturns.collectAsStateSafe()
    var payable by remember { mutableStateOf(false) }   // false = receivable (customers)
    var nameQuery by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var ledgerFor by remember { mutableStateOf<String?>(null) }

    // The party's real closing balance comes from its ledger (returns and advances netted
    // in), so the list, the totals and each statement all agree.
    fun closingFor(name: String, isPayable: Boolean): Double =
        buildLedger(name, isPayable, bills, receipts, salesReturns, purchases, expenses, purchaseReturns).second

    val receivables = remember(bills, receipts, salesReturns) {
        bills.map { it.customerName }.filter { it.isNotBlank() }.distinct().map { name ->
            val (rows, closing) = buildLedger(name, false, bills, receipts, salesReturns, purchases, expenses, purchaseReturns)
            PartyDue(name, closing, rows.map { DocDue(it.ref, it.date, it.debit - it.credit) })
        }.filter { it.total > 0.001 }.sortedByDescending { it.total }
    }
    val payables = remember(purchases, expenses, purchaseReturns) {
        purchases.map { it.supplierName }.filter { it.isNotBlank() }.distinct().map { name ->
            val (rows, closing) = buildLedger(name, true, bills, receipts, salesReturns, purchases, expenses, purchaseReturns)
            PartyDue(name, closing, rows.map { DocDue(it.ref, it.date, it.debit - it.credit) })
        }.filter { it.total > 0.001 }.sortedByDescending { it.total }
    }

    val list = (if (payable) payables else receivables)
        .filter { nameQuery.isBlank() || it.name.contains(nameQuery, ignoreCase = true) }
    val grandTotal = list.sumOf { it.total }

    fun phoneFor(name: String): String =
        (if (payable) suppliers.firstOrNull { it.name.equals(name, true) }?.phone
        else customers.firstOrNull { it.name.equals(name, true) }?.phone) ?: ""

    fun buildStatement(party: PartyDue): File {
        val heading = if (payable) "Payable Statement" else "Outstanding Statement"
        val lines = party.docs.map { StatementPdf.Line(it.no, it.date, it.balance) }
        return StatementPdf.generate(
            context, AppPrefs(context).company, party.name.ifBlank { "(no name)" },
            heading, phoneFor(party.name), lines, party.total
        )
    }

    fun doDownload(party: PartyDue) {
        scope.launch {
            val file = withContext(Dispatchers.IO) { buildStatement(party) }
            val ok = withContext(Dispatchers.IO) { DownloadSaver.save(context, file, file.name, "application/pdf") }
            snackbar.showSnackbar(if (ok) "Saved to Downloads: ${file.name}" else "Could not save")
        }
    }

    var pendingDownload by remember { mutableStateOf<PartyDue?>(null) }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val party = pendingDownload; pendingDownload = null
        if (granted && party != null) doDownload(party) else scope.launch { snackbar.showSnackbar("Storage permission denied") }
    }
    fun requestDownload(party: PartyDue) {
        if (DownloadSaver.needsLegacyPermission() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) { pendingDownload = party; storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
        else doDownload(party)
    }

    val downloadPdf = rememberPdfDownloader { msg -> scope.launch { snackbar.showSnackbar(msg) } }
    fun buildListPdf(): File {
        val title = if (payable) "Payable (Suppliers)" else "Outstanding (Customers)"
        val cols = listOf(
            TablePdf.Col("Party", 3f), TablePdf.Col("Documents", 1.5f, right = true),
            TablePdf.Col("Total Due", 1.8f, right = true)
        )
        val data = list.map { listOf(it.name.ifBlank { "(no name)" }, it.docs.size.toString(), Format.money(it.total)) }
        val footer = listOf("TOTAL" to Format.money(grandTotal))
        return TablePdf.generate(context, AppPrefs(context).company, title, "Parties: ${list.size}", cols, data, footer)
    }

    fun sendWhatsApp(party: PartyDue) {
        scope.launch {
            val file = withContext(Dispatchers.IO) { buildStatement(party) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val phone = phoneFor(party.name).filter { it.isDigit() }
            val caption = (if (payable) "Payable" else "Outstanding") + ": ${party.name} — Total ${Format.rupee(party.total)}"
            fun tryPkg(pkg: String?): Boolean {
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, caption)
                    if (phone.isNotEmpty()) putExtra("jid", "$phone@s.whatsapp.net")
                    if (pkg != null) setPackage(pkg)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                return runCatching { context.startActivity(i) }.isSuccess
            }
            if (tryPkg("com.whatsapp") || tryPkg("com.whatsapp.w4b")) return@launch
            val chooser = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_TEXT, caption)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(Intent.createChooser(chooser, "Send statement").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Outstanding") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    IconButton(onClick = { downloadPdf { buildListPdf() } }) {
                        Icon(Icons.Filled.PictureAsPdf, contentDescription = "Download list PDF")
                    }
                }
            )
        }
    ) { pad ->
        if (!Session.canViewInvoice) {
            Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("You don't have permission to view this report", color = MaterialTheme.colorScheme.outline)
            }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !payable, onClick = { payable = false }, label = { Text("Receivable (Customers)") })
                FilterChip(selected = payable, onClick = { payable = true }, label = { Text("Payable (Suppliers)") })
            }
            OutlinedTextField(
                value = nameQuery, onValueChange = { nameQuery = it },
                label = { Text(if (payable) "Search supplier" else "Search customer") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )

            if (list.isEmpty()) {
                Text("Nothing outstanding.", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 16.dp))
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)) {
                    items(list, key = { it.name }) { party ->
                        val isOpen = party.name in expanded
                        Row(
                            Modifier.fillMaxWidth()
                                .combinedClickable(
                                    onClick = { expanded = if (isOpen) expanded - party.name else expanded + party.name },
                                    onLongClick = { ledgerFor = party.name }
                                )
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(party.name.ifBlank { "(no name)" }, fontWeight = FontWeight.Bold)
                                Text("${party.docs.size} document(s)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                            Text(Format.rupee(party.total), fontWeight = FontWeight.Bold)
                            IconButton(onClick = { requestDownload(party) }) {
                                Icon(Icons.Filled.Download, contentDescription = "Download PDF")
                            }
                            IconButton(onClick = { sendWhatsApp(party) }) {
                                Icon(Icons.Filled.Share, contentDescription = "Send to WhatsApp", tint = MaterialTheme.colorScheme.primary)
                            }
                            Icon(if (isOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
                        }
                        if (isOpen) {
                            party.docs.sortedBy { it.date }.forEach { d ->
                                Row(Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${d.no}  •  ${Format.date(d.date)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                    Text(Format.rupee(d.balance), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Text(
                                "Long-press for full statement",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)
                            )
                        }
                        Divider()
                    }
                }
            }

            // Grand total: the big figure, bottom-left, matching the parties above.
            Divider(thickness = 2.dp)
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text(
                        if (payable) "TOTAL PAYABLE" else "TOTAL RECEIVABLE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        Format.money(grandTotal),
                        fontSize = 34.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    "${list.size} ${if (payable) "supplier(s)" else "customer(s)"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }

    ledgerFor?.let { name ->
        val (rows, closing) = buildLedger(name, payable, bills, receipts, salesReturns, purchases, expenses, purchaseReturns)
        PartyStatementDialog(
            party = name,
            isPayable = payable,
            rows = rows,
            closing = closing,
            phone = phoneFor(name),
            onDismiss = { ledgerFor = null }
        )
    }
}

/**
 * A party's full statement: every movement since dues last cleared, newest first, with a
 * running balance and the closing figure. Print/PDF, Excel and WhatsApp from the top bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PartyStatementDialog(
    party: String,
    isPayable: Boolean,
    rows: List<LedgerRow>,
    closing: Double,
    phone: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Running balance per row, computed oldest-first then shown newest-first.
    val oldestFirst = rows.sortedBy { it.date }
    val balanceByIndex = HashMap<Int, Double>()
    var run = 0.0
    oldestFirst.forEachIndexed { i, r -> run += r.debit - r.credit; balanceByIndex[i] = run }
    val display = oldestFirst.indices.sortedByDescending { oldestFirst[it].date }

    fun exportPdf(): File {
        val heading = (if (isPayable) "Payable" else "Receivable") + " Statement"
        val lines = oldestFirst.map { StatementPdf.Line(it.kind + " " + it.ref, it.date, it.debit - it.credit) }
        return StatementPdf.generate(
            context, AppPrefs(context).company, party.ifBlank { "(no name)" },
            heading, phone, lines, closing
        )
    }

    fun exportExcel(): File {
        val rowsX = ArrayList<List<XlsxWriter.Cell>>()
        rowsX.add(XlsxWriter.row(XlsxWriter.text("Date"), XlsxWriter.text("Type"), XlsxWriter.text("Ref"),
            XlsxWriter.text("Debit"), XlsxWriter.text("Credit"), XlsxWriter.text("Balance")))
        display.forEach { i ->
            val r = oldestFirst[i]
            rowsX.add(XlsxWriter.row(
                XlsxWriter.text(Format.date(r.date)), XlsxWriter.text(r.kind), XlsxWriter.text(r.ref),
                XlsxWriter.num(r.debit), XlsxWriter.num(r.credit), XlsxWriter.num(balanceByIndex[i] ?: 0.0)
            ))
        }
        rowsX.add(XlsxWriter.row(XlsxWriter.text(""), XlsxWriter.text(""), XlsxWriter.text("CLOSING"),
            XlsxWriter.text(""), XlsxWriter.text(""), XlsxWriter.num(closing)))
        val safe = party.ifBlank { "party" }.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(File(context.cacheDir, "shared").apply { mkdirs() }, "statement_$safe.xlsx")
        XlsxWriter.write(file, "Statement", rowsX)
        return file
    }

    fun shareFile(file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, (if (isPayable) "Payable" else "Outstanding") + ": $party — ${Format.rupee(closing)}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(Intent.createChooser(send, "Share statement").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Column(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .safeDrawingPadding()
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(party.ifBlank { "(no name)" }, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(
                        (if (isPayable) "Payable statement" else "Receivable statement"),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline
                    )
                }
                IconButton(onClick = { scope.launch { withContext(Dispatchers.IO) { shareFile(exportPdf(), "application/pdf") } } }) {
                    Icon(Icons.Filled.Share, "Share PDF", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = {
                    scope.launch {
                        val f = withContext(Dispatchers.IO) { exportExcel() }
                        withContext(Dispatchers.IO) { DownloadSaver.save(context, f, f.name, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") }
                        shareFile(f, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    }
                }) {
                    Icon(Icons.Filled.Download, "Excel")
                }
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Close") }
            }
            Divider()

            if (display.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Account is clear.", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
                    items(display) { i ->
                        val r = oldestFirst[i]
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.kind + "  " + r.ref, fontWeight = FontWeight.SemiBold)
                                Text(Format.date(r.date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                            Text(
                                if (r.debit > 0) Format.money(r.debit) else "- " + Format.money(r.credit),
                                color = if (r.debit > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                Format.money(balanceByIndex[i] ?: 0.0),
                                modifier = Modifier.padding(start = 12.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Divider()
                    }
                }
            }

            Divider(thickness = 2.dp)
            Row(
                Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text(
                        if (isPayable) "CLOSING PAYABLE" else "CLOSING BALANCE",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline
                    )
                    Text(Format.money(closing), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

