package com.billing.pos.ui.hire

import android.app.Application
import android.app.DatePickerDialog
import java.util.Calendar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.billing.pos.data.HireInvoice
import com.billing.pos.data.HireInvoiceItem
import com.billing.pos.data.HireNameQty
import com.billing.pos.data.Repository
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** One row of the item-wise hire report. */
data class HireItemRow(val name: String, val out: Double, val returned: Double) {
    val outside: Double get() = out - returned
}

class HireReportViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    val rows: StateFlow<List<HireItemRow>> =
        combine(repo.hireOutByItem, repo.hireReturnedByItem) { out, ret ->
            val retByName = ret.associate { it.name.lowercase() to it.qty }
            out.map { HireItemRow(it.name, it.qty, retByName[it.name.lowercase()] ?: 0.0) }
                .filter { it.out != 0.0 }
                .sortedByDescending { it.outside }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val hires: StateFlow<List<HireInvoice>> = repo.hireInvoices.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val hireLines: StateFlow<List<HireInvoiceItem>> = repo.hireLinesFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Hire invoices that still have items outstanding (not fully returned), by hire id. */
    val openHires: StateFlow<List<HireInvoice>> =
        combine(repo.hireInvoices, repo.hireLinesFlow, repo.hireReturnedByHire) { hires, lines, returned ->
            val hiredById = lines.groupBy { it.hireId }.mapValues { (_, l) -> l.sumOf { it.qty } }
            val retById = returned.associate { it.id to it.qty }
            hires.filter { (hiredById[it.id] ?: 0.0) - (retById[it.id] ?: 0.0) > 0.0001 }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HireItemReportScreen(onBack: () -> Unit, onOpenHire: (Long) -> Unit, vm: HireReportViewModel = viewModel()) {
    val rows by vm.rows.collectAsStateSafe()
    val hires by vm.hires.collectAsStateSafe()
    val hireLines by vm.hireLines.collectAsStateSafe()
    var drillItem by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hire — Item Report") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text("Item", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text("Out", Modifier.width(70.dp), fontWeight = FontWeight.Bold)
                Text("Ret.", Modifier.width(60.dp), fontWeight = FontWeight.Bold)
                Text("Outside", Modifier.width(74.dp), fontWeight = FontWeight.Bold)
            }
            Divider()
            if (rows.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No hire activity yet", color = MaterialTheme.colorScheme.outline) }
            else LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.name }) { r ->
                    Row(
                        Modifier.fillMaxWidth().clickable { if (r.outside != 0.0) drillItem = r.name }.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(r.name, Modifier.weight(1f), maxLines = 1)
                        Text(Format.qty(r.out), Modifier.width(70.dp))
                        Text(Format.qty(r.returned), Modifier.width(60.dp))
                        Text(
                            Format.qty(r.outside), Modifier.width(74.dp),
                            fontWeight = FontWeight.Bold,
                            color = if (r.outside > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        )
                    }
                    Divider()
                }
            }
        }
    }

    drillItem?.let { name ->
        // Hire invoices that contain this item (for the "stock outside" drill-down).
        val idsWithItem = hireLines.filter { it.name.equals(name, ignoreCase = true) }.map { it.hireId }.toSet()
        val matching = hires.filter { it.id in idsWithItem }
        HireDrillDownDialog(
            itemName = name,
            invoices = matching,
            onOpen = { drillItem = null; onOpenHire(it) },
            onDismiss = { drillItem = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HireDrillDownDialog(
    itemName: String,
    invoices: List<HireInvoice>,
    onOpen: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Out on hire — $itemName") },
        text = {
            var customerFilter by remember { mutableStateOf("") }
            val shown = if (customerFilter.isBlank()) invoices
                else invoices.filter { it.customerName.contains(customerFilter, ignoreCase = true) }
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = customerFilter, onValueChange = { customerFilter = it },
                    label = { Text("Filter by customer") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Divider(Modifier.padding(vertical = 6.dp))
                if (shown.isEmpty()) Text("No matching hire invoices", color = MaterialTheme.colorScheme.outline)
                else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(shown, key = { it.id }) { h ->
                        Column(Modifier.fillMaxWidth().clickable { onOpen(h.id) }.padding(vertical = 10.dp)) {
                            Text(h.hireNo, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${h.customerName} • ${Format.date(h.startDateMillis)} → ${Format.date(h.endDateMillis)}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                            )
                            Divider(Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HireExpiryReportScreen(onBack: () -> Unit, onOpenHire: (Long) -> Unit, vm: HireReportViewModel = viewModel()) {
    val context = LocalContext.current
    // Only hires with items still outstanding — fully-returned hires are excluded.
    val openHires by vm.openHires.collectAsStateSafe()
    var asOf by remember { mutableStateOf(System.currentTimeMillis()) }
    // Expired = still-open hire whose end date is on or before the chosen date.
    val expired = openHires.filter { it.endDateMillis <= endOfDay(asOf) }.sortedBy { it.endDateMillis }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hire — Expiry Report") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            OutlinedButton(
                onClick = {
                    val c = Calendar.getInstance().apply { timeInMillis = asOf }
                    DatePickerDialog(context, { _, y, m, d ->
                        asOf = Calendar.getInstance().apply { set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
                    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
                },
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            ) { Icon(Icons.Filled.CalendarMonth, null); Text("  Expired on or before: ${Format.date(asOf)}") }
            Divider()
            if (expired.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No hires expired by this date", color = MaterialTheme.colorScheme.outline) }
            else LazyColumn(Modifier.fillMaxSize()) {
                items(expired, key = { it.id }) { h ->
                    Row(Modifier.fillMaxWidth().clickable { onOpenHire(h.id) }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(h.hireNo, fontWeight = FontWeight.Bold)
                            Text("${h.customerName} • ended ${Format.date(h.endDateMillis)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        Text(Format.rupee(h.grandTotal), fontWeight = FontWeight.SemiBold)
                    }
                    Divider()
                }
            }
        }
    }
}

private fun endOfDay(millis: Long): Long {
    val c = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
    }
    return c.timeInMillis
}
