package com.billing.pos.ui.order

import android.app.Application
import android.content.Intent
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.CustOrder
import com.billing.pos.data.CustOrderItem
import com.billing.pos.data.Repository
import com.billing.pos.data.XlsxWriter
import com.billing.pos.pdf.TablePdf
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.io.File
import java.util.Calendar

class OrderReportViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    val orders: StateFlow<List<CustOrder>> = repo.custOrders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val lines: StateFlow<List<CustOrderItem>> = repo.custOrderLinesFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

/** One item's total ordered qty, plus the per-customer breakdown behind it. */
private data class ItemAgg(val name: String, val totalQty: Double, val byCustomer: List<Pair<String, Double>>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderReportScreen(onBack: () -> Unit, vm: OrderReportViewModel = viewModel()) {
    val context = LocalContext.current
    val orders by vm.orders.collectAsStateSafe()
    val lines by vm.lines.collectAsStateSafe()

    var itemFilter by remember { mutableStateOf("All") }
    var custFilter by remember { mutableStateOf("All") }
    var dateOn by remember { mutableStateOf(true) }
    var dayMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var detailFor by remember { mutableStateOf<ItemAgg?>(null) }

    fun sameDay(m: Long): Boolean {
        val a = Calendar.getInstance().apply { timeInMillis = m }
        val b = Calendar.getInstance().apply { timeInMillis = dayMillis }
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }

    val orderById = orders.associateBy { it.id }
    // Lines that pass the order-level filters (customer + date), then the item filter.
    val filteredLines = lines.filter { l ->
        val o = orderById[l.orderId] ?: return@filter false
        (custFilter == "All" || o.customerName.equals(custFilter, true)) &&
            (!dateOn || sameDay(o.dateMillis)) &&
            (itemFilter == "All" || l.name.equals(itemFilter, true))
    }
    val aggregates = filteredLines.groupBy { it.name.lowercase() }.map { (_, ls) ->
        val name = ls.first().name
        val byCust = ls.groupBy { orderById[it.orderId]?.customerName ?: "—" }
            .map { (c, cl) -> c to cl.sumOf { it.qty } }.sortedByDescending { it.second }
        ItemAgg(name, ls.sumOf { it.qty }, byCust)
    }.sortedByDescending { it.totalQty }

    val itemNames = listOf("All") + lines.map { it.name }.distinct().sorted()
    val custNames = listOf("All") + orders.map { it.customerName }.distinct().sorted()

    fun buildPdf(): File {
        val cols = listOf(TablePdf.Col("Item", 3f), TablePdf.Col("Total Qty", 1.5f, right = true))
        val data = aggregates.map { listOf(it.name, Format.qty(it.totalQty)) }
        val crit = buildString {
            append(if (custFilter == "All") "All customers" else custFilter)
            append(" · "); append(if (itemFilter == "All") "All items" else itemFilter)
            if (dateOn) { append(" · "); append(Format.date(dayMillis)) }
        }
        return TablePdf.generate(context, AppPrefs(context).company, "Consolidated Orders", crit, cols, data,
            listOf("Lines" to aggregates.size.toString()))
    }

    fun buildExcel(): File {
        val rows = ArrayList<List<XlsxWriter.Cell>>()
        rows.add(XlsxWriter.row(XlsxWriter.text("Item"), XlsxWriter.text("Customer"), XlsxWriter.text("Qty")))
        aggregates.forEach { a ->
            a.byCustomer.forEach { (c, q) -> rows.add(XlsxWriter.row(XlsxWriter.text(a.name), XlsxWriter.text(c), XlsxWriter.num(q))) }
            rows.add(XlsxWriter.row(XlsxWriter.text(a.name + " — TOTAL"), XlsxWriter.text(""), XlsxWriter.num(a.totalQty)))
        }
        val f = File(File(context.cacheDir, "shared").apply { mkdirs() }, "consolidated_orders.xlsx")
        XlsxWriter.write(f, "Orders", rows)
        return f
    }

    fun share(file: File, mime: String) {
        runCatching {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".provider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime; putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "Consolidated orders")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
                val d = Intent(send).setPackage(pkg)
                if (d.resolveActivity(context.packageManager) != null) { runCatching { context.startActivity(d) }.onSuccess { return } }
            }
            context.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Consolidated Orders") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { share(buildPdf(), "application/pdf") }) { Icon(Icons.Filled.PictureAsPdf, "Share PDF") }
                    IconButton(onClick = { share(buildExcel(), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") }) { Icon(Icons.Filled.Share, "Share Excel") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterDrop("Item", itemFilter, itemNames, { itemFilter = it }, Modifier.weight(1f))
                FilterDrop("Customer", custFilter, custNames, { custFilter = it }, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(checked = dateOn, onCheckedChange = { dateOn = it })
                Text("Date", style = MaterialTheme.typography.labelLarge)
                if (dateOn) {
                    androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { pickDate(context, dayMillis) { dayMillis = it } }) { Text(Format.date(dayMillis)) }
                }
            }
            Divider(Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text("ITEM", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.outline)
                Text("QTY ORDERED", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.outline)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(aggregates, key = { it.name }) { a ->
                    Row(Modifier.fillMaxWidth().clickable { detailFor = a }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(a.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Text(Format.qty(a.totalQty), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    }
                    Divider()
                }
                if (aggregates.isEmpty()) item { Text("Nothing ordered for this filter.", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(16.dp)) }
            }
        }
    }

    detailFor?.let { a ->
        AlertDialog(
            onDismissRequest = { detailFor = null },
            title = { Text(a.name) },
            text = {
                Column {
                    a.byCustomer.forEach { (c, q) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(c, Modifier.weight(1f))
                            Text(Format.qty(q), fontWeight = FontWeight.Bold)
                        }
                    }
                    Divider(Modifier.padding(vertical = 4.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Text("Total", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        Text(Format.qty(a.totalQty), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { detailFor = null }) { Text("Close") } }
        )
    }
}

private fun pickDate(context: android.content.Context, current: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = current }
    android.app.DatePickerDialog(context, { _, y, m, d -> c.set(Calendar.YEAR, y); c.set(Calendar.MONTH, m); c.set(Calendar.DAY_OF_MONTH, d); onPicked(c.timeInMillis) },
        c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
}

@Composable
private fun FilterDrop(label: String, value: String, options: List<String>, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(label + ": " + value.take(12), maxLines = 1)
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { onPick(o); open = false }) }
        }
    }
}
