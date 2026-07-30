package com.billing.pos.ui.expenses

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LibraryAdd
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
import androidx.compose.material.icons.filled.Calculate
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.auth.Session
import com.billing.pos.data.Expense
import androidx.compose.material.icons.filled.Close
import com.billing.pos.data.PayMode
import com.billing.pos.data.Purchase
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.Repository
import com.billing.pos.pdf.TablePdf
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.ui.common.DateSearchFilter
import com.billing.pos.ui.common.endOfDay
import com.billing.pos.ui.common.rememberPdfDownloader
import com.billing.pos.ui.common.startOfDay
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ExpensesViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    val expenses: StateFlow<List<Expense>> =
        repo.allExpenses.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val purchases: StateFlow<List<com.billing.pos.data.Purchase>> =
        repo.allPurchases.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val customers = repo.customers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val suppliers = repo.suppliers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val sundry = repo.sundryHeads.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Selectable "Paid to" parties: suppliers + customers + any Sundry ledger head. */
    val partyNames: StateFlow<List<String>> =
        kotlinx.coroutines.flow.combine(customers, suppliers, sundry) { c, s, h ->
            (s.filter { !it.isDefault }.map { it.name } + c.filter { !it.isDefault }.map { it.name } + h.map { it.name })
                .map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    fun add(
        description: String, amount: Double, mode: PayMode, dateMillis: Long,
        attachments: List<com.billing.pos.data.ExpenseAttachment> = emptyList(),
        payTo: String = ""
    ) {
        if (amount <= 0) { message.value = "Enter a valid amount"; return }
        viewModelScope.launch {
            val to = payTo.trim()
            val isCustomer = to.isNotBlank() && customers.value.any { it.name.equals(to, true) }
            val saved = if (to.isBlank()) repo.addExpense(description, amount, mode, dateMillis)
            else repo.addExpenseFull(description, amount, mode, dateMillis, to, partyIsCustomer = isCustomer)
            if (attachments.isNotEmpty()) repo.replaceExpenseAttachments(saved.id, attachments)
            message.value = "Payment added"
        }
    }

    /** Attachments already saved against a payment, for the edit dialog. */
    suspend fun attachmentsFor(expenseId: Long) = repo.expenseAttachmentsFor(expenseId)

    fun addBulk(mode: PayMode, rows: List<com.billing.pos.ui.common.BulkEntryRow>) {
        if (rows.isEmpty()) { message.value = "Nothing to save"; return }
        viewModelScope.launch {
            rows.forEach { r -> repo.addExpenseFull(r.description, r.amount, mode, r.dateMillis, r.party) }
            message.value = "${rows.size} payment(s) added"
        }
    }

    fun addAgainstPurchase(purchase: com.billing.pos.data.Purchase, amount: Double, mode: PayMode) {
        if (amount <= 0) { message.value = "Enter a valid amount"; return }
        viewModelScope.launch { repo.addPaymentForPurchase(purchase, amount, mode); message.value = "Payment added" }
    }

    fun edit(
        e: Expense, description: String, amount: Double, mode: PayMode,
        attachments: List<com.billing.pos.data.ExpenseAttachment>? = null
    ) {
        if (amount <= 0) { message.value = "Enter a valid amount"; return }
        viewModelScope.launch {
            repo.updateExpense(e, description, amount, mode)
            if (attachments != null) repo.replaceExpenseAttachments(e.id, attachments)
            message.value = "Payment updated"
        }
    }

    fun delete(e: Expense) {
        viewModelScope.launch { repo.deleteExpense(e); message.value = "Payment deleted" }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpensesScreen(
    onBack: () -> Unit,
    vm: ExpensesViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val expenses by vm.expenses.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    var showAdd by remember { mutableStateOf(false) }
    var showBulk by remember { mutableStateOf(false) }
    var editFor by remember { mutableStateOf<Expense?>(null) }
    var deleteFor by remember { mutableStateOf<Expense?>(null) }

    var query by remember { mutableStateOf("") }
    var fromMillis by remember { mutableStateOf<Long?>(null) }
    var toMillis by remember { mutableStateOf<Long?>(null) }
    val filtered = expenses.filter {
        (fromMillis == null || it.dateMillis >= startOfDay(fromMillis!!)) &&
            (toMillis == null || it.dateMillis <= endOfDay(toMillis!!)) &&
            (query.isBlank() || it.voucherNo.contains(query, true) || it.payTo.contains(query, true) ||
                it.description.contains(query, true) || it.purchaseNo.contains(query, true))
    }
    val total = filtered.sumOf { it.amount }
    val downloadPdf = rememberPdfDownloader { msg -> scope.launch { snackbar.showSnackbar(msg) } }
    fun buildPaymentsPdf(): java.io.File {
        val cols = listOf(
            TablePdf.Col("No", 1.3f), TablePdf.Col("Date", 1.3f), TablePdf.Col("To / Desc", 2.6f),
            TablePdf.Col("Mode", 1f), TablePdf.Col("Amount", 1.3f, right = true)
        )
        val data = filtered.sortedByDescending { it.dateMillis }.map {
            listOf(it.voucherNo, Format.date(it.dateMillis), it.payTo.ifBlank { it.description.ifBlank { "Expense" } }, it.paymentMode, Format.money(it.amount))
        }
        val sub = "Count: ${filtered.size}" + (fromMillis?.let { "  From: ${Format.date(it)}" } ?: "") +
            (toMillis?.let { "  To: ${Format.date(it)}" } ?: "") + (if (query.isNotBlank()) "  Search: $query" else "")
        return TablePdf.generate(context, AppPrefs(context).company, "Payments", sub, cols, data, listOf("TOTAL" to Format.money(total)))
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Payments / Expenses") },
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
                    if (Session.canCreatePayment) {
                        IconButton(onClick = { showBulk = true }) {
                            Icon(Icons.Filled.LibraryAdd, contentDescription = "Bulk add payments")
                        }
                    }
                    if (Session.canViewPayment) {
                        IconButton(onClick = { downloadPdf { buildPaymentsPdf() } }) {
                            Icon(Icons.Filled.Download, contentDescription = "Download PDF")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (Session.canCreatePayment) {
                FloatingActionButton(onClick = { showAdd = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add payment")
                }
            }
        }
    ) { pad ->
        if (!Session.canViewPayment) {
            Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("You don't have permission to view payments", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            Column(Modifier.fillMaxSize().padding(pad)) {
                DateSearchFilter(
                    query = query, onQuery = { query = it },
                    from = fromMillis, onFrom = { fromMillis = it },
                    to = toMillis, onTo = { toMillis = it },
                    searchLabel = "Search voucher / payee"
                )
                Divider()
                LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp)) {
                    items(filtered, key = { it.id }) { e ->
                    Row(
                        Modifier.fillMaxWidth().clickable { editFor = e }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${e.voucherNo}  •  ${e.payTo.ifBlank { e.description.ifBlank { "Expense" } }}", fontWeight = FontWeight.Bold)
                            Text(
                                (if (e.purchaseNo.isNotBlank()) "vs ${e.purchaseNo} • " else "") +
                                    "${e.paymentMode} • ${Format.date(e.dateMillis)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Text("- " + Format.rupee(e.amount), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                        if (Session.canDeletePayment) {
                            IconButton(onClick = { deleteFor = e }) {
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
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }

    if (showBulk) {
        com.billing.pos.ui.common.BulkEntryDialog(
            title = "Bulk payments",
            isPayment = true,
            defaultDate = System.currentTimeMillis(),
            onDismiss = { showBulk = false },
            onConfirm = { mode, rows -> vm.addBulk(mode, rows); showBulk = false }
        )
    }
    if (showAdd) {
        val purchases by vm.purchases.collectAsStateSafe()
        val outstanding = purchases.filter { it.balance > 0.001 }
        val partyNames by vm.partyNames.collectAsStateSafe()
        AddPaymentDialog(
            outstanding = outstanding,
            partyNames = partyNames,
            onDismiss = { showAdd = false },
            onGeneral = { desc, amt, mode, date, atts, payTo -> vm.add(desc, amt, mode, date, atts, payTo); showAdd = false },
            onAgainstPurchase = { pur, amt, mode -> vm.addAgainstPurchase(pur, amt, mode); showAdd = false }
        )
    }
    editFor?.let { e ->
        ExpenseEditDialog(
            initial = e,
            canSave = Session.canEditPayment,
            onDismiss = { editFor = null },
            onSave = { desc, amt, mode, atts -> vm.edit(e, desc, amt, mode, atts); editFor = null },
            loadAttachments = { id -> vm.attachmentsFor(id) }
        )
    }
    deleteFor?.let { e ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete payment ${e.voucherNo}?") },
            text = { Text("This removes ${Format.rupee(e.amount)} from expenses.") },
            confirmButton = { TextButton(onClick = { vm.delete(e); deleteFor = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddPaymentDialog(
    outstanding: List<Purchase>,
    partyNames: List<String>,
    onDismiss: () -> Unit,
    onGeneral: (String, Double, PayMode, Long, List<com.billing.pos.data.ExpenseAttachment>, String) -> Unit,
    onAgainstPurchase: (Purchase, Double, PayMode) -> Unit
) {
    val context = LocalContext.current
    var againstPurchase by remember { mutableStateOf(false) }
    var payTo by remember { mutableStateOf("") }
    var payToQuery by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(outstanding.firstOrNull()) }
    var description by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    val attachments = remember { androidx.compose.runtime.mutableStateListOf<com.billing.pos.data.ExpenseAttachment>() }
    // Fill the description by hand or from a photo, and the amount from a calculator.
    var drawDesc by remember { mutableStateOf(false) }
    var descOcrUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var showCalc by remember { mutableStateOf(false) }
    val descCamera = com.billing.pos.ocr.rememberImageCamera { u -> descOcrUri = u }
    val descGallery = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { u -> if (u != null) descOcrUri = u }
    if (drawDesc) {
        com.billing.pos.ui.common.HandwriteTextDialog(
            onResult = { t -> if (t.isNotBlank()) description = (description.trimEnd() + " " + t).trim(); drawDesc = false },
            onDismiss = { drawDesc = false }
        )
    }
    descOcrUri?.let { u ->
        com.billing.pos.ui.common.RegionOcrDialog(
            uri = u,
            onResult = { t -> if (t.isNotBlank()) description = (description.trimEnd() + " " + t).trim(); descOcrUri = null },
            onDismiss = { descOcrUri = null }
        )
    }
    if (showCalc) {
        com.billing.pos.ui.common.CalculatorDialog(
            initial = amount.toDoubleOrNull() ?: 0.0,
            onOk = { total -> amount = Format.money(total); showCalc = false },
            onDismiss = { showCalc = false }
        )
    }
    var mode by remember { mutableStateOf(PayMode.CASH) }
    var dateMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New payment") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !againstPurchase, onClick = { againstPurchase = false }, label = { Text("General expense") })
                    FilterChip(selected = againstPurchase, onClick = { againstPurchase = true }, enabled = outstanding.isNotEmpty(), label = { Text("Against purchase") })
                }
                if (againstPurchase) {
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedTextField(
                            readOnly = true,
                            value = selected?.let { "${it.purchaseNo} • ${it.supplierName} • bal ${Format.money(it.balance)}" } ?: "",
                            onValueChange = {}, label = { Text("Purchase") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            outstanding.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text("${p.purchaseNo} • ${p.supplierName} • bal ${Format.money(p.balance)}") },
                                    onClick = { selected = p; amount = Format.money(p.balance); expanded = false }
                                )
                            }
                        }
                    }
                } else {
                    // Paid to (party account) — type to search, up to 5 suggestions. Blank = no party.
                    OutlinedTextField(
                        value = payTo.ifBlank { payToQuery },
                        onValueChange = { payToQuery = it; payTo = "" },
                        label = { Text("Paid to (customer / supplier / account)") },
                        singleLine = true,
                        trailingIcon = { if (payTo.isNotBlank()) IconButton(onClick = { payTo = ""; payToQuery = "" }) { Icon(Icons.Filled.Close, "Clear") } },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    if (payTo.isBlank() && payToQuery.isNotBlank()) {
                        partyNames.filter { it.contains(payToQuery, true) }.take(5).forEach { nm ->
                            Text(nm, modifier = Modifier.fillMaxWidth().clickable { payTo = nm; payToQuery = "" }.padding(vertical = 8.dp, horizontal = 8.dp),
                                style = MaterialTheme.typography.bodyMedium)
                            Divider()
                        }
                    }
                    OutlinedTextField(
                        value = description, onValueChange = { description = it },
                        label = { Text("Description / narration") },
                        minLines = 2, maxLines = 6,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
                // Handwrite the description, or read it off a photo.
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(onClick = { drawDesc = true }, modifier = Modifier.weight(1f)) {
                        Text("Draw", style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(onClick = { descCamera() }, modifier = Modifier.weight(1f)) {
                        Text("Camera", style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(
                        onClick = {
                            descGallery.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Gallery", style = MaterialTheme.typography.labelMedium) }
                }
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Amount") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    trailingIcon = {
                        IconButton(onClick = { showCalc = true }) {
                            Icon(Icons.Filled.Calculate, contentDescription = "Calculator")
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PayMode.values().forEach { m -> FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label) }) }
                }
                PaymentAttachments(attachments, enabled = true)
                OutlinedButton(
                    onClick = { pickPaymentDate(context, dateMillis) { dateMillis = it } },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("Date: ${Format.date(dateMillis)}") }
            }
        },
        confirmButton = {
            Button(onClick = {
                val amt = amount.toDoubleOrNull() ?: 0.0
                if (againstPurchase) selected?.let { onAgainstPurchase(it, amt.coerceAtMost(it.balance), mode) }
                else onGeneral(description, amt, mode, dateMillis, attachments.toList(), payTo.ifBlank { payToQuery })
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ExpenseEditDialog(
    initial: Expense?,
    canSave: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, Double, PayMode, List<com.billing.pos.data.ExpenseAttachment>) -> Unit,
    loadAttachments: suspend (Long) -> List<com.billing.pos.data.ExpenseAttachment> = { emptyList() }
) {
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var amount by remember { mutableStateOf(initial?.amount?.let { Format.money(it) } ?: "") }
    val attachments = remember { androidx.compose.runtime.mutableStateListOf<com.billing.pos.data.ExpenseAttachment>() }
    // Load what is already attached to this payment.
    androidx.compose.runtime.LaunchedEffect(initial?.id) {
        val id = initial?.id ?: return@LaunchedEffect
        attachments.clear()
        attachments.addAll(loadAttachments(id))
    }
    // Fill the description by hand or from a photo, and the amount from a calculator.
    var drawDesc by remember { mutableStateOf(false) }
    var descOcrUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var showCalc by remember { mutableStateOf(false) }
    val descCamera = com.billing.pos.ocr.rememberImageCamera { u -> descOcrUri = u }
    val descGallery = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { u -> if (u != null) descOcrUri = u }
    if (drawDesc) {
        com.billing.pos.ui.common.HandwriteTextDialog(
            onResult = { t -> if (t.isNotBlank()) description = (description.trimEnd() + " " + t).trim(); drawDesc = false },
            onDismiss = { drawDesc = false }
        )
    }
    descOcrUri?.let { u ->
        com.billing.pos.ui.common.RegionOcrDialog(
            uri = u,
            onResult = { t -> if (t.isNotBlank()) description = (description.trimEnd() + " " + t).trim(); descOcrUri = null },
            onDismiss = { descOcrUri = null }
        )
    }
    if (showCalc) {
        com.billing.pos.ui.common.CalculatorDialog(
            initial = amount.toDoubleOrNull() ?: 0.0,
            onOk = { total -> amount = Format.money(total); showCalc = false },
            onDismiss = { showCalc = false }
        )
    }
    var mode by remember { mutableStateOf(PayMode.values().firstOrNull { it.label == initial?.paymentMode } ?: PayMode.CASH) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New payment" else if (canSave) "Edit payment" else "Payment ${initial.voucherNo}") },
        text = {
            Column {
                OutlinedTextField(
                    value = description, onValueChange = { description = it },
                    label = { Text("Description") }, enabled = canSave,
                    minLines = 3, maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
                if (canSave) {
                // Handwrite the description, or read it off a photo.
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(onClick = { drawDesc = true }, modifier = Modifier.weight(1f)) {
                        Text("Draw", style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(onClick = { descCamera() }, modifier = Modifier.weight(1f)) {
                        Text("Camera", style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(
                        onClick = {
                            descGallery.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Gallery", style = MaterialTheme.typography.labelMedium) }
                }
                }
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Amount") }, singleLine = true, enabled = canSave,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    trailingIcon = {
                        if (canSave) IconButton(onClick = { showCalc = true }) {
                            Icon(Icons.Filled.Calculate, contentDescription = "Calculator")
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PayMode.values().forEach { m ->
                        FilterChip(selected = mode == m, onClick = { if (canSave) mode = m }, label = { Text(m.label) })
                    }
                }
                PaymentAttachments(attachments, enabled = canSave)
            }
        },
        confirmButton = {
            Button(onClick = { onSave(description, amount.toDoubleOrNull() ?: 0.0, mode, attachments.toList()) }, enabled = canSave) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

private fun pickPaymentDate(context: android.content.Context, current: Long, onPicked: (Long) -> Unit) {
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
