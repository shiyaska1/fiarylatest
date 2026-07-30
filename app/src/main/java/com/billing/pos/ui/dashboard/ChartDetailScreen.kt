package com.billing.pos.ui.dashboard

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.Repository
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar

/**
 * One line behind a dashboard figure. [route] is where tapping it goes, or blank when the
 * record has no edit screen of its own (a stock line, for instance).
 */
data class DetailRow(
    val group: String,
    val title: String,
    val subtitle: String,
    val amount: Double,
    val route: String = ""
)

class ChartDetailViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    private fun monthStart(): Long = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // One flow per metric, kept: building a new one on every recomposition restarted the
    // collection each time, so the list blinked back to empty and the screen flickered.
    private val cache = HashMap<String, StateFlow<List<DetailRow>>>()

    /** Everything a chart is made of, grouped exactly as the chart's slices are. */
    fun rowsFor(metric: String): StateFlow<List<DetailRow>> =
        cache.getOrPut(metric) { buildRows(metric) }

    private fun buildRows(metric: String): StateFlow<List<DetailRow>> = when (metric) {
        "cash" -> combine(
            repo.allBills, repo.allReceipts, repo.allExpenses, repo.allPurchases
        ) { bills, receipts, expenses, purchases ->
            buildList {
                bills.filter { it.paymentMethod != "Credit" }.forEach {
                    add(DetailRow("In · " + it.paymentMethod, it.billNo, it.customerName + " · " + Format.date(it.dateMillis), it.grandTotal, "billing/edit/" + it.id))
                }
                receipts.forEach {
                    add(DetailRow("In · " + it.paymentMode, it.receiptNo, it.payFrom.ifBlank { it.customerName } + " · " + Format.date(it.dateMillis), it.amount))
                }
                purchases.filter { it.paymentMethod != "Credit" }.forEach {
                    add(DetailRow("Out · " + it.paymentMethod, it.purchaseNo, it.supplierName + " · " + Format.date(it.dateMillis), -it.grandTotal, "purchase/edit/" + it.id))
                }
                expenses.forEach {
                    add(DetailRow("Out · " + it.paymentMode, it.voucherNo, it.payTo.ifBlank { it.description } + " · " + Format.date(it.dateMillis), -it.amount))
                }
            }.sortedBy { it.group }
        }

        "month" -> combine(repo.allBills, repo.allPurchases) { bills, purchases ->
            val start = monthStart()
            buildList {
                bills.filter { it.dateMillis >= start }.forEach {
                    add(DetailRow("Sales", it.billNo, it.customerName + " · " + Format.date(it.dateMillis), it.grandTotal, "billing/edit/" + it.id))
                }
                purchases.filter { it.dateMillis >= start }.forEach {
                    add(DetailRow("Purchase", it.purchaseNo, it.supplierName + " · " + Format.date(it.dateMillis), it.grandTotal, "purchase/edit/" + it.id))
                }
            }
        }

        "owed" -> combine(
            repo.allBills, repo.allPurchases, repo.items, repo.stockByName
        ) { bills, purchases, items, stock ->
            val rateByName = items.associate { it.name.lowercase() to if (it.purchasePrice > 0.0) it.purchasePrice else it.price }
            buildList {
                bills.filter { it.balance > 0.0 }.forEach {
                    add(DetailRow("Receivable", it.billNo, it.customerName + " · " + Format.date(it.dateMillis), it.balance, "billing/edit/" + it.id))
                }
                purchases.filter { it.balance > 0.0 }.forEach {
                    add(DetailRow("Payable", it.purchaseNo, it.supplierName + " · " + Format.date(it.dateMillis), it.balance, "purchase/edit/" + it.id))
                }
                stock.filter { it.value != 0.0 }.forEach { (name, qty) ->
                    val rate = rateByName[name.lowercase()] ?: 0.0
                    add(DetailRow("Stock value", name, Format.qty(qty) + " × " + Format.money(rate), qty * rate))
                }
            }
        }

        // Material against orders: one line per LPO line, valued at the ordered rate.
        else -> combine(
            repo.purchaseQuotations, repo.purchaseQuotationLinesFlow, repo.receivedByLpo, repo.purchaseReturns
        ) { lpos, lines, received, returns ->
            val lpoById = lpos.associateBy { it.id }
            val recvMap = received.associate { (it.lpoId to it.name.lowercase()) to it.qty }
            buildList {
                lines.forEach { line ->
                    val lpo = lpoById[line.lpoId]
                    val no = lpo?.lpoNo ?: ("LPO #" + line.lpoId)
                    val who = lpo?.supplierName.orEmpty()
                    val recv = recvMap[line.lpoId to line.name.lowercase()] ?: 0.0
                    val route = "lpo/edit/" + line.lpoId
                    add(DetailRow("Ordered", line.name, no + " · " + who + " · " + Format.qty(line.qty), line.qty * line.price, route))
                    if (recv > 0.0) add(DetailRow("Received", line.name, no + " · " + Format.qty(recv), recv * line.price, route))
                    val pending = (line.qty - recv).coerceAtLeast(0.0)
                    if (pending > 0.0) add(DetailRow("Still to receive", line.name, no + " · " + Format.qty(pending), pending * line.price, route))
                }
                returns.forEach {
                    add(DetailRow("Returned", it.returnNo, it.supplierName + " · " + Format.date(it.dateMillis), it.grandTotal, "purchasereturn/edit/" + it.id))
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

private fun titleFor(metric: String) = when (metric) {
    "cash" -> "Cash in hand"
    "month" -> "This month"
    "owed" -> "Receivable, payable and stock"
    else -> "Material against purchase orders"
}

/** The records behind one dashboard ring, grouped by slice, each opening its own entry. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartDetailScreen(
    metric: String,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    vm: ChartDetailViewModel = viewModel()
) {
    val rowsFlow = remember(metric) { vm.rowsFor(metric) }
    val rows by rowsFlow.collectAsStateSafe()
    val groups = rows.groupBy { it.group }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titleFor(metric)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("Nothing to show yet", color = MaterialTheme.colorScheme.outline)
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
            groups.forEach { (group, groupRows) ->
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            group.uppercase(),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            Format.rupee(groupRows.sumOf { it.amount }),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Divider()
                }
                items(groupRows) { row ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = row.route.isNotBlank()) { onOpen(row.route) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(row.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            if (row.subtitle.isNotBlank()) Text(
                                row.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1
                            )
                        }
                        Text(Format.rupee(row.amount), fontWeight = FontWeight.Bold)
                    }
                    Divider()
                }
            }
        }
    }
}
