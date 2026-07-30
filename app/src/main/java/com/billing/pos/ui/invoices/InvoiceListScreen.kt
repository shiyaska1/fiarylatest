package com.billing.pos.ui.invoices

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.FileProvider
import com.billing.pos.auth.PendingImport
import com.billing.pos.auth.Session
import com.billing.pos.data.Backup
import com.billing.pos.data.Bill
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.Repository
import com.billing.pos.pdf.TablePdf
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.ui.common.DateSearchFilter
import com.billing.pos.ui.common.endOfDay
import com.billing.pos.ui.common.rememberPdfDownloader
import com.billing.pos.ui.common.startOfDay
import com.billing.pos.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class InvoiceListViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    val bills: StateFlow<List<Bill>> =
        repo.allBills.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Phone/WhatsApp number keyed by customer id (0 for walk-in). */
    val phoneByCustomer: StateFlow<Map<Long, String>> =
        repo.customers.map { list -> list.associate { it.id to it.phone } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Lower-cased, space-joined item names keyed by bill id. */
    val itemNamesByBill: StateFlow<Map<Long, String>> =
        repo.billLines.map { lines ->
            lines.groupBy { it.billId }
                .mapValues { (_, l) -> l.joinToString(" ") { it.name }.lowercase() }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    fun delete(bill: Bill) {
        viewModelScope.launch { repo.deleteBill(bill); message.value = "Invoice ${bill.billNo} deleted" }
    }

    fun exportAndShare(context: Context) {
        viewModelScope.launch {
            val json = withContext(Dispatchers.IO) { repo.exportJson(Session.sourceLabel) }
            val safeName = Session.sourceLabel.ifBlank { "data" }.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val zip = withContext(Dispatchers.IO) { Backup.writeZip(context, "pos-backup-$safeName", json) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", zip)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(intent, "Send backup").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        }
    }

    fun importFrom(context: Context, uri: Uri) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { Backup.readBackup(context, uri) }
            if (text.isNullOrBlank()) { message.value = "Could not read backup file"; return@launch }
            val result = runCatching { withContext(Dispatchers.IO) { repo.importJson(text) } }.getOrNull()
            message.value = if (result == null) "Invalid backup file"
            else "Imported from ${result.source}: ${result.billsAdded} bills, " +
                "${result.receiptsAdded} receipts, ${result.expensesAdded} payments " +
                "(${result.billsSkipped} duplicates skipped)"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvoiceListScreen(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    vm: InvoiceListViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val bills by vm.bills.collectAsStateSafe()
    val phoneByCustomer by vm.phoneByCustomer.collectAsStateSafe()
    val itemNamesByBill by vm.itemNamesByBill.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    var pendingDelete by remember { mutableStateOf<Bill?>(null) }
    var searchQ by remember { mutableStateOf("") }
    var fromMillis by remember { mutableStateOf<Long?>(null) }
    var toMillis by remember { mutableStateOf<Long?>(null) }
    val filtered = bills.filter {
        val q = searchQ.trim()
        val matches = q.isBlank() ||
            it.billNo.contains(q, true) ||
            it.customerName.contains(q, true) ||
            it.remarks.contains(q, true) ||
            (phoneByCustomer[it.customerId]?.contains(q, true) == true) ||
            (itemNamesByBill[it.id]?.contains(q, true) == true)
        matches &&
            (fromMillis == null || it.dateMillis >= startOfDay(fromMillis!!)) &&
            (toMillis == null || it.dateMillis <= endOfDay(toMillis!!))
    }
    val downloadPdf = rememberPdfDownloader { msg -> scope.launch { snackbar.showSnackbar(msg) } }
    fun buildInvoicesPdf(): java.io.File {
        val cols = listOf(
            TablePdf.Col("Bill No", 1.4f), TablePdf.Col("Date", 1.3f), TablePdf.Col("Customer", 2.4f),
            TablePdf.Col("Pay", 1f), TablePdf.Col("Status", 1f), TablePdf.Col("Total", 1.3f, right = true)
        )
        val data = filtered.sortedByDescending { it.dateMillis }.map {
            listOf(it.billNo, Format.date(it.dateMillis), it.customerName, it.paymentMethod, it.paymentStatus, Format.money(it.grandTotal))
        }
        val footer = listOf("TOTAL" to Format.money(filtered.sumOf { it.grandTotal }))
        return TablePdf.generate(context, AppPrefs(context).company, "Invoices", "Count: ${filtered.size}", cols, data, footer)
    }

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    // Auto-import a backup that was shared into the app from another app.
    LaunchedEffect(Unit) {
        PendingImport.consume()?.let { uri ->
            if (Session.canImport) vm.importFrom(context, uri)
            else snackbar.showSnackbar("You don't have permission to import")
        }
    }

    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) vm.importFrom(context, uri) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Invoices") },
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
                    IconButton(onClick = { downloadPdf { buildInvoicesPdf() } }) {
                        Icon(Icons.Filled.PictureAsPdf, contentDescription = "Download list PDF")
                    }
                    if (Session.canExport) {
                        IconButton(onClick = { vm.exportAndShare(context) }) {
                            Icon(Icons.Filled.Share, contentDescription = "Export & share")
                        }
                    }
                    if (Session.canImport) {
                        IconButton(onClick = { importPicker.launch("*/*") }) {
                            Icon(Icons.Filled.Download, contentDescription = "Import data")
                        }
                    }
                }
            )
        }
    ) { pad ->
        if (!Session.canViewInvoice) {
            Column(
                Modifier.fillMaxSize().padding(pad),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) { Text("You don't have permission to view invoices", color = MaterialTheme.colorScheme.outline) }
        } else {
            Column(Modifier.fillMaxSize().padding(pad)) {
                DateSearchFilter(
                    query = searchQ, onQuery = { searchQ = it },
                    from = fromMillis, onFrom = { fromMillis = it },
                    to = toMillis, onTo = { toMillis = it },
                    searchLabel = "Bill no, customer, phone, item, remark"
                )
                if (filtered.isEmpty()) {
                    Text(
                        if (bills.isEmpty()) "No invoices yet" else "No invoices match the filter",
                        color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(16.dp)
                    )
                } else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    items(filtered, key = { it.id }) { bill ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onEdit(bill.id) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(bill.billNo, fontWeight = FontWeight.Bold)
                                if (bill.source.isNotEmpty()) {
                                    Text(
                                        "from ${bill.source}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Text(
                                "${bill.customerName} • ${bill.paymentMethod} • ${Format.date(bill.dateMillis)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Text(Format.rupee(bill.grandTotal), fontWeight = FontWeight.Bold)
                        if (Session.canEdit) {
                            IconButton(onClick = { onEdit(bill.id) }) {
                                Icon(Icons.Filled.Edit, "Edit")
                            }
                        }
                        if (Session.canDelete) {
                            IconButton(onClick = { pendingDelete = bill }) {
                                Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    Divider()
                    }
                }
            }
        }
    }

    pendingDelete?.let { bill ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete invoice?") },
            text = { Text("Delete ${bill.billNo} (${Format.rupee(bill.grandTotal)})? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { vm.delete(bill); pendingDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}
