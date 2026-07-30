package com.billing.pos.ui.receipts

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.auth.Session
import com.billing.pos.data.AppPrefs
import com.billing.pos.pdf.TablePdf
import com.billing.pos.ui.common.DateSearchFilter
import com.billing.pos.ui.common.endOfDay
import com.billing.pos.ui.common.rememberPdfDownloader
import com.billing.pos.ui.common.startOfDay
import com.billing.pos.data.Bill
import com.billing.pos.data.PayMode
import com.billing.pos.data.Receipt
import com.billing.pos.data.Repository
import com.billing.pos.pdf.ThermalPdf
import com.billing.pos.print.ThermalPrinter
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import com.billing.pos.util.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReceiptsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    val receipts: StateFlow<List<Receipt>> =
        repo.allReceipts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val bills: StateFlow<List<Bill>> =
        repo.allBills.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val customers: StateFlow<List<com.billing.pos.data.Customer>> =
        repo.customers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val payFromOptions = MutableStateFlow<List<String>>(emptyList())
    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    /**
     * Names offered under "Pay from": everyone in the customer master, plus any name typed
     * on an earlier receipt. A name typed here is never written back to the master, but it
     * is remembered for the next search because past receipts are part of this list.
     */
    private suspend fun refreshPayFrom() {
        val used = repo.payFromNames()
        val masters = repo.customers.first().map { it.name }
        payFromOptions.value = (masters + used)
            .map { it.trim() }.filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }
    }

    init { viewModelScope.launch { refreshPayFrom() } }

    fun addAgainstInvoice(bill: Bill, amount: Double, mode: PayMode, dateMillis: Long, attachments: List<com.billing.pos.data.ReceiptAttachment> = emptyList()) {
        if (amount <= 0) { message.value = "Enter a valid amount"; return }
        viewModelScope.launch {
            val r = repo.addReceipt(bill, amount, mode, dateMillis)
            if (attachments.isNotEmpty()) repo.replaceReceiptAttachments(r.id, attachments)
            message.value = "Receipt added"
        }
    }

    fun addStandalone(payFrom: String, amount: Double, mode: PayMode, dateMillis: Long, attachments: List<com.billing.pos.data.ReceiptAttachment> = emptyList()) {
        if (amount <= 0) { message.value = "Enter a valid amount"; return }
        viewModelScope.launch {
            val r = repo.addStandaloneReceipt(payFrom.trim().ifBlank { "Cash receipt" }, amount, mode, dateMillis)
            if (attachments.isNotEmpty()) repo.replaceReceiptAttachments(r.id, attachments)
            refreshPayFrom()
            message.value = "Receipt added"
        }
    }

    suspend fun attachmentsFor(receiptId: Long) = repo.receiptAttachmentsFor(receiptId)

    fun addBulk(mode: PayMode, rows: List<com.billing.pos.ui.common.BulkEntryRow>) {
        if (rows.isEmpty()) { message.value = "Nothing to save"; return }
        viewModelScope.launch {
            rows.forEach { r -> repo.addStandaloneReceipt(r.party.ifBlank { "Cash receipt" }, r.amount, mode, r.dateMillis) }
            refreshPayFrom()
            message.value = "${rows.size} receipt(s) added"
        }
    }

    fun edit(old: Receipt, amount: Double, mode: PayMode) {
        if (amount <= 0) { message.value = "Enter a valid amount"; return }
        viewModelScope.launch { repo.updateReceipt(old, amount, mode); message.value = "Receipt updated" }
    }

    fun delete(r: Receipt) {
        viewModelScope.launch { repo.deleteReceipt(r); message.value = "Receipt deleted" }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptsScreen(
    onBack: () -> Unit,
    vm: ReceiptsViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val receipts by vm.receipts.collectAsStateSafe()
    val bills by vm.bills.collectAsStateSafe()
    val payFromOptions by vm.payFromOptions.collectAsStateSafe()
    val customers by vm.customers.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    var showAdd by remember { mutableStateOf(false) }
    var showBulk by remember { mutableStateOf(false) }
    var editFor by remember { mutableStateOf<Receipt?>(null) }
    var deleteFor by remember { mutableStateOf<Receipt?>(null) }
    var printFor by remember { mutableStateOf<Receipt?>(null) }

    val printPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val r = printFor
        if (granted && r != null) scope.launch { doPrintReceipt(context, r, snackbar) }
        else if (!granted) scope.launch {
            val res = snackbar.showSnackbar(
                "Allow 'Nearby devices' permission to print",
                actionLabel = "Settings",
                duration = SnackbarDuration.Long
            )
            if (res == SnackbarResult.ActionPerformed) Permissions.openAppSettings(context)
        }
    }

    fun requestPrint(r: Receipt) {
        printFor = r
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !ThermalPrinter.hasConnectPermission(context)) {
            printPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else scope.launch { doPrintReceipt(context, r, snackbar) }
    }

    val outstanding = bills.filter { it.balance > 0.001 }

    var query by remember { mutableStateOf("") }
    var fromMillis by remember { mutableStateOf<Long?>(null) }
    var toMillis by remember { mutableStateOf<Long?>(null) }
    val filtered = receipts.filter {
        (fromMillis == null || it.dateMillis >= startOfDay(fromMillis!!)) &&
            (toMillis == null || it.dateMillis <= endOfDay(toMillis!!)) &&
            (query.isBlank() || it.receiptNo.contains(query, true) || it.payFrom.contains(query, true) ||
                it.customerName.contains(query, true) || it.billNo.contains(query, true))
    }
    val total = filtered.sumOf { it.amount }
    val downloadPdf = rememberPdfDownloader { msg -> scope.launch { snackbar.showSnackbar(msg) } }
    fun buildReceiptsPdf(): java.io.File {
        val cols = listOf(
            TablePdf.Col("No", 1.3f), TablePdf.Col("Date", 1.3f), TablePdf.Col("From", 2.6f),
            TablePdf.Col("Mode", 1f), TablePdf.Col("Amount", 1.3f, right = true)
        )
        val data = filtered.sortedByDescending { it.dateMillis }.map {
            listOf(it.receiptNo, Format.date(it.dateMillis), it.payFrom.ifBlank { it.customerName }, it.paymentMode, Format.money(it.amount))
        }
        val sub = "Count: ${filtered.size}" + (fromMillis?.let { "  From: ${Format.date(it)}" } ?: "") +
            (toMillis?.let { "  To: ${Format.date(it)}" } ?: "") + (if (query.isNotBlank()) "  Search: $query" else "")
        return TablePdf.generate(context, AppPrefs(context).company, "Receipts", sub, cols, data, listOf("TOTAL" to Format.money(total)))
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Receipts") },
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
                    // Receive against a lab bill's outstanding balance (Medical lab).
                    if (Session.canCreateReceipt && remember { com.billing.pos.data.AppPrefs(context).businessType == "Medical lab" }) {
                        com.billing.pos.ui.lab.LabCollectButton(onMessage = { scope.launch { snackbar.showSnackbar(it) } })
                    }
                    if (Session.canCreateReceipt) {
                        IconButton(onClick = { showBulk = true }) {
                            Icon(Icons.Filled.LibraryAdd, contentDescription = "Bulk add receipts")
                        }
                    }
                    if (Session.canViewReceipt) {
                        IconButton(onClick = { downloadPdf { buildReceiptsPdf() } }) {
                            Icon(Icons.Filled.Download, contentDescription = "Download PDF")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (Session.canCreateReceipt) {
                FloatingActionButton(onClick = { showAdd = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add receipt")
                }
            }
        }
    ) { pad ->
        if (!Session.canViewReceipt) {
            Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("You don't have permission to view receipts", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            Column(Modifier.fillMaxSize().padding(pad)) {
                DateSearchFilter(
                    query = query, onQuery = { query = it },
                    from = fromMillis, onFrom = { fromMillis = it },
                    to = toMillis, onTo = { toMillis = it },
                    searchLabel = "Search receipt no / name"
                )
                Divider()
                LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp)) {
                    items(filtered, key = { it.id }) { r ->
                    Row(
                        Modifier.fillMaxWidth().clickable { editFor = r }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${r.receiptNo}  •  ${r.payFrom.ifBlank { r.customerName }}", fontWeight = FontWeight.Bold)
                            Text(
                                (if (r.billNo.isNotBlank()) "vs ${r.billNo} • " else "Other • ") +
                                    "${r.paymentMode} • ${Format.date(r.dateMillis)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Text("+ " + Format.rupee(r.amount), fontWeight = FontWeight.Bold)
                        IconButton(onClick = { sharePdf(context, r) }) {
                            Icon(Icons.Filled.PictureAsPdf, "Share PDF")
                        }
                        IconButton(onClick = { requestPrint(r) }) {
                            Icon(Icons.Filled.Print, "Print")
                        }
                        if (Session.canDeleteReceipt) {
                            IconButton(onClick = { deleteFor = r }) {
                                Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    Divider()
                    }
                }
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Total (${filtered.size}):  ", fontWeight = FontWeight.Bold)
                        Text(
                            Format.rupee(total),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }

    if (showBulk) {
        com.billing.pos.ui.common.BulkEntryDialog(
            title = "Bulk receipts",
            isPayment = false,
            defaultDate = System.currentTimeMillis(),
            onDismiss = { showBulk = false },
            onConfirm = { mode, rows -> vm.addBulk(mode, rows); showBulk = false }
        )
    }
    if (showAdd) {
        AddReceiptDialog(
            outstanding = outstanding,
            customers = customers,
            payFromOptions = payFromOptions,
            onDismiss = { showAdd = false },
            onAddInvoice = { bill, amt, mode, date, atts -> vm.addAgainstInvoice(bill, amt, mode, date, atts); showAdd = false },
            onAddOther = { payFrom, amt, mode, date, atts -> vm.addStandalone(payFrom, amt, mode, date, atts); showAdd = false }
        )
    }
    editFor?.let { r ->
        ReceiptDialog(
            receipt = r,
            canSave = Session.canEditReceipt,
            onDismiss = { editFor = null },
            onSave = { amt, mode -> vm.edit(r, amt, mode); editFor = null }
        )
    }
    deleteFor?.let { r ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete receipt ${r.receiptNo}?") },
            text = { Text(if (r.billNo.isNotBlank()) "This reduces the invoice's paid amount by ${Format.rupee(r.amount)}." else "Remove ${Format.rupee(r.amount)} receipt.") },
            confirmButton = { TextButton(onClick = { vm.delete(r); deleteFor = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } }
        )
    }
}

private fun pickReceiptDate(context: Context, current: Long, onPicked: (Long) -> Unit) {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = current }
    android.app.DatePickerDialog(
        context,
        { _, y, m, d ->
            c.set(java.util.Calendar.YEAR, y); c.set(java.util.Calendar.MONTH, m); c.set(java.util.Calendar.DAY_OF_MONTH, d)
            onPicked(c.timeInMillis)
        },
        c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH)
    ).show()
}

private fun sharePdf(context: Context, r: Receipt) {
    val company = AppPrefs(context).company
    val uri = ThermalPdf.receipt(context, company, r)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(intent, "Share receipt").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

private suspend fun doPrintReceipt(context: Context, r: Receipt, snackbar: SnackbarHostState) {
    val company = AppPrefs(context).company
    val result = withContext(Dispatchers.IO) { runCatching { ThermalPrinter.printReceipt(context, company, r) } }
    result.onSuccess { snackbar.showSnackbar("Sent to printer") }
        .onFailure { snackbar.showSnackbar(it.message ?: "Print failed") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddReceiptDialog(
    outstanding: List<Bill>,
    customers: List<com.billing.pos.data.Customer>,
    payFromOptions: List<String>,
    onDismiss: () -> Unit,
    onAddInvoice: (Bill, Double, PayMode, Long, List<com.billing.pos.data.ReceiptAttachment>) -> Unit,
    onAddOther: (String, Double, PayMode, Long, List<com.billing.pos.data.ReceiptAttachment>) -> Unit
) {
    val context = LocalContext.current
    var againstInvoice by remember { mutableStateOf(outstanding.isNotEmpty()) }
    var selected by remember { mutableStateOf(outstanding.firstOrNull()) }
    var payFrom by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf(outstanding.firstOrNull()?.balance?.let { Format.money(it) } ?: "") }
    var mode by remember { mutableStateOf(PayMode.CASH) }
    var dateMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var billExpanded by remember { mutableStateOf(false) }
    var payFromExpanded by remember { mutableStateOf(false) }
    val attachments = remember { androidx.compose.runtime.mutableStateListOf<com.billing.pos.data.ReceiptAttachment>() }
    fun addAttachment(uri: android.net.Uri?) {
        if (uri == null) return
        com.billing.pos.data.ReceiptAttachmentStore.copyIn(context, uri)?.let { attachments.add(it) }
    }
    val attCamera = com.billing.pos.ocr.rememberImageCamera { u -> addAttachment(u) }
    val attGallery = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { addAttachment(it) }
    val attFile = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { addAttachment(it) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New receipt") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = againstInvoice,
                        onClick = { againstInvoice = true },
                        enabled = outstanding.isNotEmpty(),
                        label = { Text("Against invoice") }
                    )
                    FilterChip(
                        selected = !againstInvoice,
                        onClick = { againstInvoice = false },
                        label = { Text("Other source") }
                    )
                }

                if (againstInvoice) {
                    ExposedDropdownMenuBox(expanded = billExpanded, onExpandedChange = { billExpanded = it }, modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedTextField(
                            readOnly = true,
                            value = selected?.let { "${it.billNo} • ${it.customerName} • bal ${Format.money(it.balance)}" } ?: "",
                            onValueChange = {},
                            label = { Text("Invoice") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(billExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = billExpanded, onDismissRequest = { billExpanded = false }) {
                            outstanding.forEach { b ->
                                DropdownMenuItem(
                                    text = { Text("${b.billNo} • ${b.customerName} • bal ${Format.money(b.balance)}") },
                                    onClick = { selected = b; amount = Format.money(b.balance); billExpanded = false }
                                )
                            }
                        }
                    }
                } else {
                    com.billing.pos.ui.common.CustomerPickField(
                        customers = customers,
                        selectedName = payFrom,
                        onPick = { c -> payFrom = c.name },
                        label = "Pay from (optional)",
                        allowFreeText = true,
                        onTyped = { payFrom = it },
                        extraOptions = payFromOptions,
                        onPickExtra = { payFrom = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    // Anything still owed on this payer's open job cards (Service Center).
                    com.billing.pos.ui.service.JobCardBalanceNote(customerId = 0L, customerName = payFrom)
                }

                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Amount received") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PayMode.values().forEach { m ->
                        FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label) })
                    }
                }
                OutlinedButton(
                    onClick = { pickReceiptDate(context, dateMillis) { dateMillis = it } },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("Date: ${Format.date(dateMillis)}") }

                // Proof of payment: photos or any file, filed against the receipt.
                Text("Attachments", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { attCamera() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.PhotoCamera, "Camera", Modifier.size(18.dp))
                    }
                    OutlinedButton(
                        onClick = {
                            attGallery.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) { Icon(Icons.Filled.PhotoLibrary, "Gallery", Modifier.size(18.dp)) }
                    OutlinedButton(onClick = { attFile.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.UploadFile, "File", Modifier.size(18.dp))
                    }
                }
                attachments.forEachIndexed { i, att ->
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(att.name, Modifier.weight(1f), maxLines = 1, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = { attachments.removeAt(i) }) {
                            Icon(Icons.Filled.Delete, "Remove", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val amt = amount.toDoubleOrNull() ?: 0.0
                if (againstInvoice) {
                    selected?.let { onAddInvoice(it, amt.coerceAtMost(it.balance), mode, dateMillis, attachments.toList()) }
                } else {
                    onAddOther(payFrom.trim(), amt, mode, dateMillis, attachments.toList())
                }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ReceiptDialog(
    receipt: Receipt,
    canSave: Boolean,
    onDismiss: () -> Unit,
    onSave: (Double, PayMode) -> Unit
) {
    var amount by remember { mutableStateOf(Format.money(receipt.amount)) }
    var mode by remember { mutableStateOf(PayMode.values().firstOrNull { it.label == receipt.paymentMode } ?: PayMode.CASH) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (canSave) "Edit receipt ${receipt.receiptNo}" else "Receipt ${receipt.receiptNo}") },
        text = {
            Column {
                Text(
                    "From: ${receipt.payFrom.ifBlank { receipt.customerName }}" +
                        if (receipt.billNo.isNotBlank()) "  •  vs ${receipt.billNo}" else "  •  other source",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                )
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Amount") }, singleLine = true, enabled = canSave,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PayMode.values().forEach { m ->
                        FilterChip(selected = mode == m, onClick = { if (canSave) mode = m }, label = { Text(m.label) })
                    }
                }

                // Files attached when the receipt was made; tap to open.
                val attCtx = LocalContext.current
                var atts by remember(receipt.id) { mutableStateOf<List<com.billing.pos.data.ReceiptAttachment>>(emptyList()) }
                LaunchedEffect(receipt.id) {
                    atts = com.billing.pos.data.Repository(attCtx).receiptAttachmentsFor(receipt.id)
                }
                if (atts.isNotEmpty()) {
                    Text("Attachments", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
                    atts.forEach { a ->
                        Text(
                            a.name,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { com.billing.pos.data.ReceiptAttachmentStore.open(attCtx, a) }
                                .padding(top = 6.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(amount.toDoubleOrNull() ?: 0.0, mode) }, enabled = canSave) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
