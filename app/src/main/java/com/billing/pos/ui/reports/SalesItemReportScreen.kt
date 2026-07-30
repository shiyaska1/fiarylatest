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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.Item
import com.billing.pos.data.MoveRow
import com.billing.pos.data.NameQty
import com.billing.pos.data.PurchaseLineInfo
import com.billing.pos.data.Repository
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** One invoice (sale voucher) that included a given item within the period. */
data class ItemInvoiceRef(val billId: Long, val billNo: String, val date: Long, val qty: Double)

/** One row of the item-wise sales report. */
data class ItemSalesRow(
    val itemId: Long,
    val name: String,
    val soldQty: Double,        // in the selected period
    val purchasedQty: Double,   // in the selected period
    val stock: Double,          // current (live) stock
    val invoices: List<ItemInvoiceRef>
)

class SalesItemReportViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    val items: StateFlow<List<Item>> = repo.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val sales: StateFlow<List<MoveRow>> = repo.saleMovements.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val purchases: StateFlow<List<PurchaseLineInfo>> = repo.purchaseLines.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val materialOut: StateFlow<List<NameQty>> = repo.materialOutByItem.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SalesItemReportScreen(
    onBack: () -> Unit,
    onOpenItem: (Long) -> Unit,
    onOpenInvoice: (Long) -> Unit,
    vm: SalesItemReportViewModel = viewModel()
) {
    val context = LocalContext.current
    val items by vm.items.collectAsStateSafe()
    val sales by vm.sales.collectAsStateSafe()
    val purchases by vm.purchases.collectAsStateSafe()
    val materialOut by vm.materialOut.collectAsStateSafe()

    // Default range: last one month up to today.
    var fromMillis by remember { mutableStateOf(Calendar.getInstance().apply { add(Calendar.MONTH, -1) }.timeInMillis) }
    var toMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var nameQuery by remember { mutableStateOf("") }
    var invoicesFor by remember { mutableStateOf<ItemSalesRow?>(null) }

    val rows: List<ItemSalesRow> = remember(items, sales, purchases, materialOut, fromMillis, toMillis, nameQuery) {
        val lo = startOfDay(fromMillis); val hi = endOfDay(toMillis)
        val salesPeriodByName = sales.filter { it.dateMillis in lo..hi }.groupBy { it.name.lowercase() }
        val purchPeriodByName = purchases.filter { it.dateMillis in lo..hi }.groupBy { it.name.lowercase() }
        // All-time totals for the live-stock formula (opening + purchased - sold - material out).
        val soldAllByName = sales.groupBy { it.name.lowercase() }.mapValues { (_, l) -> l.sumOf { it.qty } }
        val purchAllByName = purchases.groupBy { it.name.lowercase() }.mapValues { (_, l) -> l.sumOf { it.qty } }
        val outByName = materialOut.associate { it.name.lowercase() to it.qty }
        items.map { item ->
            val key = item.name.lowercase()
            val periodSales = salesPeriodByName[key].orEmpty()
            val sold = periodSales.sumOf { it.qty }
            val purchased = purchPeriodByName[key].orEmpty().sumOf { it.qty }
            val stock = item.openingStock + (purchAllByName[key] ?: 0.0) - (soldAllByName[key] ?: 0.0) - (outByName[key] ?: 0.0)
            // One entry per invoice (a sale may have several lines of the same item).
            val invoices = periodSales.groupBy { it.voucherId }.map { (id, l) ->
                ItemInvoiceRef(id, l.first().voucherNo, l.first().dateMillis, l.sumOf { it.qty })
            }.sortedByDescending { it.date }
            ItemSalesRow(item.id, item.name, sold, purchased, stock, invoices)
        }.filter {
            (it.soldQty > 0.0 || it.purchasedQty > 0.0) &&
                (nameQuery.isBlank() || it.name.contains(nameQuery, true))
        }.sortedByDescending { it.soldQty }
    }
    val totalSold = rows.sumOf { it.soldQty }
    val totalPurch = rows.sumOf { it.purchasedQty }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sales (item-wise)") },
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
            OutlinedTextField(
                value = nameQuery, onValueChange = { nameQuery = it },
                label = { Text("Item name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            )
            // Column header
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text("Item", Modifier.weight(2.3f), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                Text("Sold", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
                Text("Purch", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
                Text("Stock", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
            }
            Divider()
            if (rows.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("No item sales in this range", color = MaterialTheme.colorScheme.outline) }
            else LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(rows, key = { it.itemId }) { r ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Item name → open the item in edit mode.
                        Text(
                            r.name, Modifier.weight(2.3f).clickable { onOpenItem(r.itemId) },
                            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium
                        )
                        // Sold qty → tap to see the invoices behind it.
                        Text(
                            Format.qty(r.soldQty),
                            Modifier.weight(1f).clickable(enabled = r.invoices.isNotEmpty()) { invoicesFor = r },
                            textAlign = TextAlign.End,
                            color = if (r.invoices.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (r.invoices.isNotEmpty()) FontWeight.SemiBold else FontWeight.Normal
                        )
                        Text(Format.qty(r.purchasedQty), Modifier.weight(1f), textAlign = TextAlign.End)
                        Text(Format.qty(r.stock), Modifier.weight(1f), textAlign = TextAlign.End,
                            color = if (r.stock < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    }
                    Divider()
                }
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Items: ${rows.size}", color = MaterialTheme.colorScheme.outline)
                Text("Sold ${Format.qty(totalSold)}  •  Purch ${Format.qty(totalPurch)}", fontWeight = FontWeight.Bold)
            }
        }
    }

    // Invoices for the tapped item — click one to open it in edit mode.
    invoicesFor?.let { row ->
        AlertDialog(
            onDismissRequest = { invoicesFor = null },
            title = { Text("${row.name} — sales") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(row.invoices, key = { it.billId }) { inv ->
                        Row(
                            Modifier.fillMaxWidth().clickable { invoicesFor = null; onOpenInvoice(inv.billId) }.padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(inv.billNo.ifBlank { "#${inv.billId}" }, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                                Text(Format.date(inv.date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                            Text("Qty ${Format.qty(inv.qty)}")
                        }
                        Divider()
                    }
                }
            },
            confirmButton = { TextButton(onClick = { invoicesFor = null }) { Text("Close") } }
        )
    }
}

private fun pickDate(context: android.content.Context, current: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = current }
    DatePickerDialog(context, { _, y, m, d -> onPicked(Calendar.getInstance().apply { set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis) }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
}
private fun startOfDay(m: Long) = Calendar.getInstance().apply { timeInMillis = m; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
private fun endOfDay(m: Long) = Calendar.getInstance().apply { timeInMillis = m; set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999) }.timeInMillis
