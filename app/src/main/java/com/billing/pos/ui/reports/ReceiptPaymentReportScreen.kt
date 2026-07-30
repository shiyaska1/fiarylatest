package com.billing.pos.ui.reports

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.PayMode
import com.billing.pos.data.Repository
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar

class RpReportViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    val receipts = repo.allReceipts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val expenses = repo.allExpenses.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

/** One voucher inside a group, for the drill-down popup. */
private data class RpVoucher(val no: String, val dateMillis: Long, val mode: String, val amount: Double)
private data class RpGroup(val name: String, val total: Double, val vouchers: List<RpVoucher>)

/**
 * Receipts and Payments grouped by account name. Tabs on top, then filters
 * (date range — today by default — account head, payment method), grouped rows
 * that open a detail popup, and the running total big at the bottom left.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptPaymentReportScreen(onBack: () -> Unit, vm: RpReportViewModel = viewModel()) {
    val context = LocalContext.current
    val receipts by vm.receipts.collectAsStateSafe()
    val expenses by vm.expenses.collectAsStateSafe()

    var showReceipts by remember { mutableStateOf(true) }
    var fromMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var toMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var accountFilter by remember { mutableStateOf("") }
    var modeFilter by remember { mutableStateOf("") }
    var drill by remember { mutableStateOf<RpGroup?>(null) }

    fun dayStart(m: Long) = Calendar.getInstance().apply { timeInMillis = m; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    fun dayEnd(m: Long) = Calendar.getInstance().apply { timeInMillis = m; set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999) }.timeInMillis
    fun pickDate(current: Long, onPicked: (Long) -> Unit) {
        val c = Calendar.getInstance().apply { timeInMillis = current }
        android.app.DatePickerDialog(
            context,
            { _, y, m, d -> onPicked(Calendar.getInstance().apply { set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis) },
            c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    // The current tab as (account name, voucher) pairs, date- and mode-filtered.
    val vouchers: List<Pair<String, RpVoucher>> =
        if (showReceipts) receipts
            .filter { it.dateMillis >= dayStart(fromMillis) && it.dateMillis <= dayEnd(toMillis) }
            .filter { modeFilter.isBlank() || it.paymentMode.equals(modeFilter, true) }
            .map { (it.payFrom.ifBlank { it.customerName }.ifBlank { "Cash receipt" }) to RpVoucher(it.receiptNo, it.dateMillis, it.paymentMode, it.amount) }
        else expenses
            .filter { it.dateMillis >= dayStart(fromMillis) && it.dateMillis <= dayEnd(toMillis) }
            .filter { modeFilter.isBlank() || it.paymentMode.equals(modeFilter, true) }
            .map { (it.payTo.ifBlank { it.description }.ifBlank { "Payment" }) to RpVoucher(it.voucherNo, it.dateMillis, it.paymentMode, it.amount) }

    val accountNames = vouchers.map { it.first }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
    val groups = vouchers
        .filter { accountFilter.isBlank() || it.first.equals(accountFilter, true) }
        .groupBy { it.first.lowercase() }
        .map { (_, list) -> RpGroup(list.first().first, list.sumOf { it.second.amount }, list.map { it.second }.sortedByDescending { it.dateMillis }) }
        .sortedByDescending { it.total }
    val total = groups.sumOf { it.total }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Receipt & Payment") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        bottomBar = {
            // The running total, big, bottom left.
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "TOTAL  ${Format.rupee(total)}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (showReceipts) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = showReceipts, onClick = { showReceipts = true; accountFilter = "" }, label = { Text("Receipts") })
                FilterChip(selected = !showReceipts, onClick = { showReceipts = false; accountFilter = "" }, label = { Text("Payments") })
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickDate(fromMillis) { fromMillis = it } }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.CalendarMonth, null, Modifier.size(16.dp)); Text(" ${Format.date(fromMillis)}", maxLines = 1)
                }
                OutlinedButton(onClick = { pickDate(toMillis) { toMillis = it } }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.CalendarMonth, null, Modifier.size(16.dp)); Text(" ${Format.date(toMillis)}", maxLines = 1)
                }
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                var accMenu by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = accMenu, onExpandedChange = { accMenu = !accMenu }, modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        readOnly = true, value = accountFilter.ifBlank { "All accounts" }, onValueChange = {},
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(accMenu) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = accMenu, onDismissRequest = { accMenu = false }) {
                        DropdownMenuItem(text = { Text("All accounts") }, onClick = { accountFilter = ""; accMenu = false })
                        accountNames.forEach { n ->
                            DropdownMenuItem(text = { Text(n) }, onClick = { accountFilter = n; accMenu = false })
                        }
                    }
                }
                var modeMenu by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = modeMenu, onExpandedChange = { modeMenu = !modeMenu }, modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        readOnly = true, value = modeFilter.ifBlank { "All methods" }, onValueChange = {},
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modeMenu) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                        DropdownMenuItem(text = { Text("All methods") }, onClick = { modeFilter = ""; modeMenu = false })
                        PayMode.values().forEach { m ->
                            DropdownMenuItem(text = { Text(m.label) }, onClick = { modeFilter = m.label; modeMenu = false })
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text("Account", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text("Amount", fontWeight = FontWeight.Bold)
            }
            Divider()
            if (groups.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (showReceipts) "No receipts in this range" else "No payments in this range", color = MaterialTheme.colorScheme.outline)
            }
            else LazyColumn(Modifier.fillMaxSize()) {
                items(groups, key = { it.name }) { g ->
                    Row(
                        Modifier.fillMaxWidth().clickable { drill = g }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(g.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text("${g.vouchers.size} voucher(s)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                        Text(Format.rupee(g.total), fontWeight = FontWeight.Bold)
                    }
                    Divider()
                }
            }
        }
    }

    drill?.let { g ->
        AlertDialog(
            onDismissRequest = { drill = null },
            title = { Text(g.name, maxLines = 1) },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(g.vouchers.size) { i ->
                        val v = g.vouchers[i]
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(v.no, fontWeight = FontWeight.SemiBold)
                                Text("${Format.date(v.dateMillis)}  •  ${v.mode}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                            Text(Format.rupee(v.amount), fontWeight = FontWeight.Bold)
                        }
                        Divider()
                    }
                }
            },
            confirmButton = {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Total ${Format.rupee(g.total)}",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = { drill = null }) { Text("Close") }
                }
            }
        )
    }
}
