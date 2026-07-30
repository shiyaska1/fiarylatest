package com.billing.pos.ui.cashbook

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.auth.Session
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.Bill
import com.billing.pos.data.Expense
import com.billing.pos.data.PayMode
import com.billing.pos.data.Receipt
import com.billing.pos.data.Repository
import com.billing.pos.pdf.TablePdf
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.ui.common.rememberPdfDownloader
import com.billing.pos.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

class CashBookViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    val bills: StateFlow<List<Bill>> =
        repo.allBills.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val receipts: StateFlow<List<Receipt>> =
        repo.allReceipts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val expenses: StateFlow<List<Expense>> =
        repo.allExpenses.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val purchases: StateFlow<List<com.billing.pos.data.Purchase>> =
        repo.allPurchases.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val journals: StateFlow<List<com.billing.pos.data.JournalEntry>> =
        repo.journalEntries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val labBills: StateFlow<List<com.billing.pos.data.LabBill>> =
        repo.labBills.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val labReceipts: StateFlow<List<com.billing.pos.data.LabReceipt>> =
        repo.labReceipts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    fun editReceipt(old: Receipt, amount: Double, mode: PayMode) {
        if (amount <= 0) { message.value = "Enter a valid amount"; return }
        viewModelScope.launch { repo.updateReceipt(old, amount, mode); message.value = "Receipt updated" }
    }

    fun editPayment(e: Expense, description: String, amount: Double, mode: PayMode) {
        if (amount <= 0) { message.value = "Enter a valid amount"; return }
        viewModelScope.launch { repo.updateExpense(e, description, amount, mode); message.value = "Payment updated" }
    }
}

private data class CashTxn(
    val date: Long,
    val kind: String,           // SALE / RECEIPT / PAYMENT / PURCHASE / JOURNAL
    val title: String,
    val mode: String,
    val amount: Double,
    val isIn: Boolean,
    val bill: Bill?,
    val receipt: Receipt?,
    val expense: Expense?,
    val purchase: com.billing.pos.data.Purchase? = null,
    val journal: com.billing.pos.data.JournalEntry? = null
)

private fun startOfDay(m: Long): Long {
    val c = Calendar.getInstance().apply {
        timeInMillis = m
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    return c.timeInMillis
}
private fun endOfDay(m: Long): Long = startOfDay(m) + 24L * 60 * 60 * 1000 - 1

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashBookScreen(
    onBack: () -> Unit,
    onEditInvoice: (Long) -> Unit,
    onEditPurchase: (Long) -> Unit,
    onEditJournal: (Long) -> Unit,
    vm: CashBookViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val bills by vm.bills.collectAsStateSafe()
    val receipts by vm.receipts.collectAsStateSafe()
    val expenses by vm.expenses.collectAsStateSafe()
    val purchases by vm.purchases.collectAsStateSafe()
    val journals by vm.journals.collectAsStateSafe()
    val labBills by vm.labBills.collectAsStateSafe()
    val labReceipts by vm.labReceipts.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    // Default the range to today; the Clear button still lets you see all dates.
    var fromMillis by remember { mutableStateOf<Long?>(System.currentTimeMillis()) }
    var toMillis by remember { mutableStateOf<Long?>(System.currentTimeMillis()) }
    var modeFilter by remember { mutableStateOf("All") }   // All/Cash/UPI/Card/Cheque
    var typeFilter by remember { mutableStateOf("All") }   // All/Sale/Receipt/Payment/Purchase/Journal
    var searchQuery by remember { mutableStateOf("") }

    var editReceiptFor by remember { mutableStateOf<Receipt?>(null) }
    var editPaymentFor by remember { mutableStateOf<Expense?>(null) }

    // Build all cash-affecting transactions.
    val allTxns = remember(bills, receipts, expenses, purchases, journals, labBills, labReceipts) {
        buildList {
            bills.filter { it.paymentMethod != "Credit" }.forEach {
                add(CashTxn(it.dateMillis, "SALE", "${it.billNo} • ${it.customerName}", it.paymentMethod, it.grandTotal, true, it, null, null))
            }
            // Lab bills: advance/collected at billing shows as received income.
            labBills.filter { it.paidAmount > 0.0 }.forEach {
                add(CashTxn(it.dateMillis, "LAB", "${it.billNo} • ${it.patientName}", it.paymentMethod, it.paidAmount, true, null, null, null))
            }
            // Lab balance receipts collected later.
            labReceipts.forEach {
                add(CashTxn(it.dateMillis, "LAB", "${it.billNo} • ${it.patientName} (balance)", it.mode, it.amount, true, null, null, null))
            }
            receipts.forEach {
                add(CashTxn(it.dateMillis, "RECEIPT", "${it.receiptNo} • ${it.payFrom.ifBlank { it.customerName }}", it.paymentMode, it.amount, true, null, it, null))
            }
            purchases.filter { it.paymentMethod != "Credit" }.forEach {
                add(CashTxn(it.dateMillis, "PURCHASE", "${it.purchaseNo} • ${it.supplierName}", it.paymentMethod, it.grandTotal, false, null, null, null, it))
            }
            expenses.forEach {
                add(CashTxn(it.dateMillis, "PAYMENT", "${it.voucherNo} • ${it.payTo.ifBlank { it.description.ifBlank { "Expense" } }}", it.paymentMode, it.amount, false, null, null, it))
            }
            journals.filter { it.cashMode.isNotBlank() && it.cashAmount > 0.0 }.forEach {
                add(CashTxn(it.dateMillis, "JOURNAL", "${it.voucherNo} • ${it.narration.ifBlank { "Journal" }}", it.cashMode, it.cashAmount, it.cashIsIn, null, null, null, null, it))
            }
        }
    }

    val byMode = if (modeFilter == "All") allTxns else allTxns.filter { it.mode == modeFilter }
    val lo = fromMillis?.let { startOfDay(it) }
    val hi = toMillis?.let { endOfDay(it) }

    fun signed(t: CashTxn) = if (t.isIn) t.amount else -t.amount

    val opening = if (lo == null) 0.0 else byMode.filter { it.date < lo }.sumOf { signed(it) }
    val ranged = byMode.filter { (lo == null || it.date >= lo) && (hi == null || it.date <= hi) }
        .sortedBy { it.date }

    // running balance per row
    var running = opening
    val rows = ranged.map { t -> running += signed(t); t to running }
    val closing = running
    val totalIn = ranged.filter { it.isIn }.sumOf { it.amount }
    val totalOut = ranged.filter { !it.isIn }.sumOf { it.amount }

    // Display filters (do not affect the running balance / totals above).
    val q = searchQuery.trim()
    val visibleRows = rows.filter { (t, _) ->
        (typeFilter == "All" || t.kind.equals(typeFilter, ignoreCase = true)) &&
            (q.isBlank() || t.title.contains(q, true) || t.mode.contains(q, true))
    }
    // Totals of only the entries currently shown (change with the type/search filter).
    val visIn = visibleRows.filter { it.first.isIn }.sumOf { it.first.amount }
    val visOut = visibleRows.filter { !it.first.isIn }.sumOf { it.first.amount }
    val visNet = visIn - visOut

    // Build a PDF of exactly what's shown, with the filter criteria as the heading.
    fun buildCashBookPdf(): File {
        val crit = buildString {
            append("From: ").append(fromMillis?.let { Format.date(it) } ?: "any")
            append("   To: ").append(toMillis?.let { Format.date(it) } ?: "any")
            if (modeFilter != "All") append("   Mode: $modeFilter")
            if (typeFilter != "All") append("   Type: $typeFilter")
            if (q.isNotBlank()) append("   Search: \"$q\"")
        }
        val cols = listOf(
            TablePdf.Col("Date", 1.2f), TablePdf.Col("Type", 1f), TablePdf.Col("Details", 2.8f),
            TablePdf.Col("Mode", 0.9f), TablePdf.Col("Amount", 1.2f, right = true),
            TablePdf.Col("Balance", 1.2f, right = true)
        )
        val data = visibleRows.map { (t, bal) ->
            listOf(
                Format.date(t.date), t.kind.lowercase().replaceFirstChar { it.uppercase() }, t.title, t.mode,
                (if (t.isIn) "+ " else "- ") + Format.money(t.amount), Format.money(bal)
            )
        }
        val footer = listOf(
            "Total In" to Format.money(visIn),
            "Total Out" to Format.money(visOut),
            "Net" to Format.money(visNet)
        )
        return TablePdf.generate(context, AppPrefs(context).company, "Cash Book", crit, cols, data, footer)
    }
    val downloadPdf = rememberPdfDownloader { msg -> scope.launch { snackbar.showSnackbar(msg) } }
    fun shareCashBook() {
        scope.launch {
            val file = withContext(Dispatchers.IO) { buildCashBookPdf() }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching {
                context.startActivity(Intent.createChooser(intent, "Share cash book").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { snackbar.showSnackbar("Could not share") }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Cash Book") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    // Collect an outstanding lab-bill balance (shows for Medical lab).
                    if (remember { com.billing.pos.data.AppPrefs(context).businessType == "Medical lab" }) {
                        com.billing.pos.ui.lab.LabCollectButton(onMessage = { scope.launch { snackbar.showSnackbar(it) } })
                    }
                    if (Session.canViewCashbook) {
                        IconButton(onClick = { downloadPdf { buildCashBookPdf() } }) {
                            Icon(Icons.Filled.PictureAsPdf, contentDescription = "Download PDF")
                        }
                        IconButton(onClick = { shareCashBook() }) {
                            Icon(Icons.Filled.Share, contentDescription = "Share")
                        }
                    }
                }
            )
        }
    ) { pad ->
        if (!Session.canViewCashbook) {
            Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("You don't have permission to view the cash book", color = MaterialTheme.colorScheme.outline)
            }
            return@Scaffold
        }

        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
            // Date range (optional)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickDate(context, fromMillis) { fromMillis = it } }, modifier = Modifier.weight(1f)) {
                    Text("From: " + (fromMillis?.let { Format.date(it) } ?: "any"))
                }
                OutlinedButton(onClick = { pickDate(context, toMillis) { toMillis = it } }, modifier = Modifier.weight(1f)) {
                    Text("To: " + (toMillis?.let { Format.date(it) } ?: "any"))
                }
                if (fromMillis != null || toMillis != null) {
                    TextButton(onClick = { fromMillis = null; toMillis = null }) { Text("Clear") }
                }
            }

            // Payment mode + voucher type as two dropdowns on one row, so the
            // entry list below gets the space the two chip rows used to take.
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                var modeMenu by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = modeMenu, onExpandedChange = { modeMenu = !modeMenu }, modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        readOnly = true, value = if (modeFilter == "All") "All modes" else modeFilter, onValueChange = {},
                        singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modeMenu) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                        listOf("All", "Cash", "UPI", "Card", "Cheque").forEach { m ->
                            DropdownMenuItem(text = { Text(if (m == "All") "All modes" else m) }, onClick = { modeFilter = m; modeMenu = false })
                        }
                    }
                }
                var typeMenu by remember { mutableStateOf(false) }
                val isLabType = remember { com.billing.pos.data.AppPrefs(context).businessType == "Medical lab" }
                val typeOptions = remember(isLabType) {
                    listOf("All", "Sale") + (if (isLabType) listOf("Lab") else emptyList()) +
                        listOf("Receipt", "Payment", "Purchase", "Journal")
                }
                ExposedDropdownMenuBox(expanded = typeMenu, onExpandedChange = { typeMenu = !typeMenu }, modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        readOnly = true, value = if (typeFilter == "All") "All types" else typeFilter, onValueChange = {},
                        singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeMenu) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                        typeOptions.forEach { t ->
                            DropdownMenuItem(text = { Text(if (t == "All") "All types" else t) }, onClick = { typeFilter = t; typeMenu = false })
                        }
                    }
                }
            }

            // Search
            OutlinedTextField(
                value = searchQuery, onValueChange = { searchQuery = it },
                label = { Text("Search entries") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )

            // Balances
            Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    BalRow("Opening balance", opening)
                    BalRow("Total in", totalIn, Color(0xFF2E7D32))
                    BalRow("Total out", -totalOut, MaterialTheme.colorScheme.error)
                    Divider(Modifier.padding(vertical = 8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Closing balance", fontWeight = FontWeight.Bold)
                        Text(Format.rupee(closing), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            if (visibleRows.isEmpty()) {
                Text(
                    if (rows.isEmpty()) "No cash transactions in this period." else "No entries match the filter.",
                    color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 16.dp)
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)) {
                    items(visibleRows) { (t, balance) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    when (t.kind) {
                                        "SALE" -> t.bill?.let { onEditInvoice(it.id) }
                                        "RECEIPT" -> editReceiptFor = t.receipt
                                        "PAYMENT" -> editPaymentFor = t.expense
                                        "PURCHASE" -> t.purchase?.let { onEditPurchase(it.id) }
                                        "JOURNAL" -> t.journal?.let { onEditJournal(it.id) }
                                    }
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(t.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(
                                    "${t.kind.lowercase().replaceFirstChar { it.uppercase() }} • ${t.mode} • ${Format.date(t.date)}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                                )
                            }
                            Text(
                                (if (t.isIn) "+ " else "- ") + Format.money(t.amount),
                                color = if (t.isIn) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                Format.money(balance),
                                modifier = Modifier.padding(start = 12.dp).width(78.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Divider()
                    }
                }
            }

            // Bottom total — reflects only the entries currently displayed.
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FooterCell("Shown", "${visibleRows.size}")
                    FooterCell("In", "+ " + Format.money(visIn), Color(0xFF2E7D32))
                    FooterCell("Out", "- " + Format.money(visOut), MaterialTheme.colorScheme.error)
                    FooterCell("Net", Format.rupee(visNet), fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    editReceiptFor?.let { r ->
        ReceiptEditDialog(r, canSave = Session.canEditReceipt, onDismiss = { editReceiptFor = null }) { amt, mode ->
            vm.editReceipt(r, amt, mode); editReceiptFor = null
        }
    }
    editPaymentFor?.let { e ->
        PaymentEditDialog(e, canSave = Session.canEditPayment, onDismiss = { editPaymentFor = null }) { desc, amt, mode ->
            vm.editPayment(e, desc, amt, mode); editPaymentFor = null
        }
    }
}

@Composable
private fun BalRow(label: String, value: Double, color: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(Format.rupee(value), color = color, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun FooterCell(label: String, value: String, color: Color = Color.Unspecified, fontWeight: FontWeight? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Text(value, color = color, fontWeight = fontWeight, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun pickDate(context: Context, current: Long?, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { if (current != null) timeInMillis = current }
    android.app.DatePickerDialog(
        context,
        { _, y, m, d -> c.set(Calendar.YEAR, y); c.set(Calendar.MONTH, m); c.set(Calendar.DAY_OF_MONTH, d); onPicked(c.timeInMillis) },
        c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
    ).show()
}

@Composable
private fun ReceiptEditDialog(r: Receipt, canSave: Boolean, onDismiss: () -> Unit, onSave: (Double, PayMode) -> Unit) {
    var amount by remember { mutableStateOf(Format.money(r.amount)) }
    var mode by remember { mutableStateOf(PayMode.values().firstOrNull { it.label == r.paymentMode } ?: PayMode.CASH) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (canSave) "Edit receipt ${r.receiptNo}" else "Receipt ${r.receiptNo}") },
        text = {
            Column {
                Text("From: ${r.payFrom.ifBlank { r.customerName }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Amount") }, singleLine = true, enabled = canSave,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PayMode.values().forEach { m -> FilterChip(selected = mode == m, onClick = { if (canSave) mode = m }, label = { Text(m.label) }) }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(amount.toDoubleOrNull() ?: 0.0, mode) }, enabled = canSave) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun PaymentEditDialog(e: Expense, canSave: Boolean, onDismiss: () -> Unit, onSave: (String, Double, PayMode) -> Unit) {
    var description by remember { mutableStateOf(e.description) }
    var amount by remember { mutableStateOf(Format.money(e.amount)) }
    var mode by remember { mutableStateOf(PayMode.values().firstOrNull { it.label == e.paymentMode } ?: PayMode.CASH) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (canSave) "Edit payment ${e.voucherNo}" else "Payment ${e.voucherNo}") },
        text = {
            Column {
                OutlinedTextField(
                    value = description, onValueChange = { description = it },
                    label = { Text("Description") }, singleLine = true, enabled = canSave,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Amount") }, singleLine = true, enabled = canSave,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PayMode.values().forEach { m -> FilterChip(selected = mode == m, onClick = { if (canSave) mode = m }, label = { Text(m.label) }) }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(description, amount.toDoubleOrNull() ?: 0.0, mode) }, enabled = canSave) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
