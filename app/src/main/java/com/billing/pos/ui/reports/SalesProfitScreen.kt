package com.billing.pos.ui.reports

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.billing.pos.data.Bill
import com.billing.pos.data.BillItem
import com.billing.pos.data.PurchaseLineInfo
import com.billing.pos.data.Repository
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

data class ProfitRow(val billId: Long, val billNo: String, val customer: String, val date: Long, val revenue: Double, val cost: Double) {
    val profit: Double get() = revenue - cost
}

class SalesProfitViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    val bills: StateFlow<List<Bill>> = repo.allBills.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val lines: StateFlow<List<BillItem>> = repo.billLines.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val purchaseLines: StateFlow<List<PurchaseLineInfo>> = repo.purchaseLines.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SalesProfitScreen(onBack: () -> Unit, onOpenInvoice: (Long) -> Unit, vm: SalesProfitViewModel = viewModel()) {
    val context = LocalContext.current
    val bills by vm.bills.collectAsStateSafe()
    val lines by vm.lines.collectAsStateSafe()
    val purchases by vm.purchaseLines.collectAsStateSafe()

    var fromMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var toMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var customer by remember { mutableStateOf("") }

    // Cost basis = latest purchase rate per item name (primary unit).
    val lastRate = remember(purchases) {
        purchases.groupBy { it.name.lowercase() }.mapValues { (_, l) -> l.maxByOrNull { it.dateMillis }?.price ?: 0.0 }
    }
    val linesByBill = remember(lines) { lines.groupBy { it.billId } }
    val rows: List<ProfitRow> = remember(bills, linesByBill, lastRate, fromMillis, toMillis, customer) {
        val lo = startOfDay(fromMillis); val hi = endOfDay(toMillis)
        bills.filter { it.dateMillis in lo..hi && (customer.isBlank() || it.customerName.equals(customer, true)) }
            .map { b ->
                val bls = linesByBill[b.id].orEmpty()
                val revenue = bls.sumOf { it.lineTotal }
                val cost = bls.sumOf { (lastRate[it.name.lowercase()] ?: 0.0) * (if (it.primaryQty > 0) it.primaryQty else it.qty) }
                ProfitRow(b.id, b.billNo, b.customerName, b.dateMillis, revenue, cost)
            }.sortedByDescending { it.date }
    }
    val totalProfit = rows.sumOf { it.profit }
    val totalRevenue = rows.sumOf { it.revenue }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sales Profit") },
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
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickDate(context, fromMillis) { fromMillis = it } }, modifier = Modifier.weight(1f)) { Text("From ${Format.date(fromMillis)}", maxLines = 1) }
                OutlinedButton(onClick = { pickDate(context, toMillis) { toMillis = it } }, modifier = Modifier.weight(1f)) { Text("To ${Format.date(toMillis)}", maxLines = 1) }
            }
            var custMenu by remember { mutableStateOf(false) }
            val customerNames = remember(bills) { bills.map { it.customerName }.filter { it.isNotBlank() }.distinct().sorted() }
            ExposedDropdownMenuBox(expanded = custMenu, onExpandedChange = { custMenu = !custMenu }, modifier = Modifier.padding(horizontal = 12.dp)) {
                OutlinedTextField(
                    readOnly = true, value = customer.ifBlank { "All customers" }, onValueChange = {},
                    label = { Text("Customer") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(custMenu) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = custMenu, onDismissRequest = { custMenu = false }) {
                    DropdownMenuItem(text = { Text("All customers") }, onClick = { customer = ""; custMenu = false })
                    customerNames.forEach { n -> DropdownMenuItem(text = { Text(n) }, onClick = { customer = n; custMenu = false }) }
                }
            }
            Divider(Modifier.padding(top = 6.dp))
            if (rows.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No invoices in this range", color = MaterialTheme.colorScheme.outline) }
            else LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(rows, key = { it.billId }) { r ->
                    Row(Modifier.fillMaxWidth().clickable { onOpenInvoice(r.billId) }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.billNo + "  •  " + r.customer, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(Format.date(r.date) + "  •  Sale ${Format.rupee(r.revenue)}  •  Cost ${Format.rupee(r.cost)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                        Text(Format.rupee(r.profit), fontWeight = FontWeight.Bold, color = if (r.profit >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    }
                    Divider()
                }
            }
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Total sales", color = MaterialTheme.colorScheme.outline); Text(Format.rupee(totalRevenue))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Total profit", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(Format.rupee(totalProfit), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = if (totalProfit >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun pickDate(context: android.content.Context, current: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = current }
    DatePickerDialog(context, { _, y, m, d -> onPicked(Calendar.getInstance().apply { set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis) }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
}
private fun startOfDay(m: Long) = Calendar.getInstance().apply { timeInMillis = m; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
private fun endOfDay(m: Long) = Calendar.getInstance().apply { timeInMillis = m; set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999) }.timeInMillis
