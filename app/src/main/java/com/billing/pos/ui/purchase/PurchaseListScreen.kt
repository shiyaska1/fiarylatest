package com.billing.pos.ui.purchase

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PictureAsPdf
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
import com.billing.pos.auth.Session
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.Purchase
import com.billing.pos.data.Repository
import com.billing.pos.pdf.TablePdf
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.ui.common.ListFilters
import com.billing.pos.ui.common.endOfDay
import com.billing.pos.ui.common.rememberPdfDownloader
import com.billing.pos.ui.common.startOfDay
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PurchaseListViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    val purchases: StateFlow<List<Purchase>> =
        repo.allPurchases.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }
    fun delete(p: Purchase) {
        viewModelScope.launch { repo.deletePurchase(p); message.value = "Purchase ${p.purchaseNo} deleted" }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurchaseListScreen(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    vm: PurchaseListViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val purchases by vm.purchases.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    var pendingDelete by remember { mutableStateOf<Purchase?>(null) }
    var voucherQ by remember { mutableStateOf("") }
    var nameQ by remember { mutableStateOf("") }
    var fromMillis by remember { mutableStateOf<Long?>(null) }
    var toMillis by remember { mutableStateOf<Long?>(null) }
    val filtered = purchases.filter {
        (voucherQ.isBlank() || it.purchaseNo.contains(voucherQ, true)) &&
            (nameQ.isBlank() || it.supplierName.contains(nameQ, true)) &&
            (fromMillis == null || it.dateMillis >= startOfDay(fromMillis!!)) &&
            (toMillis == null || it.dateMillis <= endOfDay(toMillis!!))
    }
    val downloadPdf = rememberPdfDownloader { msg -> scope.launch { snackbar.showSnackbar(msg) } }
    fun buildPurchasesPdf(): java.io.File {
        val cols = listOf(
            TablePdf.Col("No", 1.4f), TablePdf.Col("Date", 1.3f), TablePdf.Col("Supplier", 2.4f),
            TablePdf.Col("Pay", 1f), TablePdf.Col("Status", 1f), TablePdf.Col("Total", 1.3f, right = true)
        )
        val data = filtered.sortedByDescending { it.dateMillis }.map {
            listOf(it.purchaseNo, Format.date(it.dateMillis), it.supplierName, it.paymentMethod, it.paymentStatus, Format.money(it.grandTotal))
        }
        val footer = listOf("TOTAL" to Format.money(filtered.sumOf { it.grandTotal }))
        return TablePdf.generate(context, AppPrefs(context).company, "Purchases", "Count: ${filtered.size}", cols, data, footer)
    }

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Purchases") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    IconButton(onClick = { downloadPdf { buildPurchasesPdf() } }) {
                        Icon(Icons.Filled.PictureAsPdf, contentDescription = "Download list PDF")
                    }
                }
            )
        }
    ) { pad ->
        if (!Session.canViewInvoice) {
            Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("You don't have permission to view purchases", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            Column(Modifier.fillMaxSize().padding(pad)) {
                ListFilters(
                    voucher = voucherQ, onVoucher = { voucherQ = it },
                    name = nameQ, onName = { nameQ = it }, nameLabel = "Supplier",
                    from = fromMillis, onFrom = { fromMillis = it },
                    to = toMillis, onTo = { toMillis = it }
                )
                if (filtered.isEmpty()) {
                    Text(
                        if (purchases.isEmpty()) "No purchases yet" else "No purchases match the filter",
                        color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(16.dp)
                    )
                } else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    items(filtered, key = { it.id }) { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onEdit(p.id) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.purchaseNo, fontWeight = FontWeight.Bold)
                            Text(
                                "${p.supplierName} • ${p.paymentMethod} • ${p.paymentStatus} • ${Format.date(p.dateMillis)}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Text(Format.rupee(p.grandTotal), fontWeight = FontWeight.Bold)
                        if (Session.canDelete) {
                            IconButton(onClick = { pendingDelete = p }) {
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

    pendingDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete purchase?") },
            text = { Text("Delete ${p.purchaseNo} (${Format.rupee(p.grandTotal)})? This cannot be undone.") },
            confirmButton = { TextButton(onClick = { vm.delete(p); pendingDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}
