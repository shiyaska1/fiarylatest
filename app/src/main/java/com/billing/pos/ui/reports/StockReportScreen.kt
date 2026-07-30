package com.billing.pos.ui.reports

import android.app.Application
import android.app.DatePickerDialog
import java.util.Calendar
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
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.billing.pos.data.Item
import com.billing.pos.data.MoveRow
import com.billing.pos.data.PurchaseLineInfo
import com.billing.pos.data.Repository
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

data class StockRow(val name: String, val unit: String, val qty: Double, val value: Double)

class StockReportViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    val items: StateFlow<List<Item>> = repo.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val purchaseLines: StateFlow<List<PurchaseLineInfo>> = repo.purchaseLines.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val sales: StateFlow<List<MoveRow>> = repo.saleMovements.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val materials: StateFlow<List<MoveRow>> = repo.materialMovements.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

/** FIFO ending-inventory value as of [asOf]: consume issued qty oldest-first, value what remains. */
private fun fifoStock(item: Item, purchases: List<PurchaseLineInfo>, issued: Double, asOf: Long): StockRow {
    val lots = ArrayList<Pair<Double, Double>>() // qty, unitCost  (oldest first)
    val bought = purchases.filter { it.name.equals(item.name, true) && it.dateMillis <= asOf }.sortedBy { it.dateMillis }
    val fallbackRate = bought.lastOrNull()?.price ?: item.price
    if (item.openingStock > 0) lots.add(item.openingStock to fallbackRate)
    bought.forEach { lots.add(it.qty to it.price) }
    var toIssue = issued
    var qty = 0.0; var value = 0.0
    for ((lotQty, cost) in lots) {
        var remain = lotQty
        if (toIssue > 0) { val take = minOf(toIssue, remain); remain -= take; toIssue -= take }
        if (remain > 0) { qty += remain; value += remain * cost }
    }
    return StockRow(item.name, item.unit, qty, value)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockReportScreen(onBack: () -> Unit, vm: StockReportViewModel = viewModel()) {
    val context = LocalContext.current
    val items by vm.items.collectAsStateSafe()
    val purchases by vm.purchaseLines.collectAsStateSafe()
    val sales by vm.sales.collectAsStateSafe()
    val materials by vm.materials.collectAsStateSafe()
    var asOf by remember { mutableStateOf(System.currentTimeMillis()) }

    val rows: List<StockRow> = remember(items, purchases, sales, materials, asOf) {
        val end = endOfDay(asOf)
        val soldByName = sales.filter { it.dateMillis <= end }.groupBy { it.name.lowercase() }.mapValues { (_, l) -> l.sumOf { it.qty } }
        val outByName = materials.filter { it.dateMillis <= end }.groupBy { it.name.lowercase() }.mapValues { (_, l) -> l.sumOf { it.qty } }
        items.map { item ->
            val issued = (soldByName[item.name.lowercase()] ?: 0.0) + (outByName[item.name.lowercase()] ?: 0.0)
            fifoStock(item, purchases, issued, end)
        }.filter { it.qty != 0.0 || it.value != 0.0 }.sortedBy { it.name.lowercase() }
    }
    val totalValue = rows.sumOf { it.value }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stock on Date") },
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
                    DatePickerDialog(context, { _, y, m, d -> asOf = Calendar.getInstance().apply { set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
                },
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            ) { Icon(Icons.Filled.CalendarMonth, null); Text("  Stock as on: ${Format.date(asOf)}") }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Item", Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                Text("Qty", Modifier.width(80.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                Text("Value (FIFO)", Modifier.width(96.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            }
            Divider()
            if (rows.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No stock on this date", color = MaterialTheme.colorScheme.outline) }
            else LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(rows, key = { it.name }) { r ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(r.name, Modifier.weight(1f), maxLines = 1)
                        Text(Format.qty(r.qty) + " " + r.unit, Modifier.width(80.dp), style = MaterialTheme.typography.bodySmall)
                        Text(Format.rupee(r.value), Modifier.width(96.dp), fontWeight = FontWeight.SemiBold)
                    }
                    Divider()
                }
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Total stock value", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(Format.rupee(totalValue), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

private fun endOfDay(m: Long) = Calendar.getInstance().apply {
    timeInMillis = m; set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
}.timeInMillis
