package com.billing.pos.ui.dashboard

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

/** One labelled slice of a ring. */
data class Slice(val label: String, val value: Double, val color: Color)

/** Everything the dashboard rings show, worked out once off the database. */
data class DashboardFigures(
    val byMode: List<Slice> = emptyList(),
    val cashInHand: Double = 0.0,
    val salesThisMonth: Double = 0.0,
    val purchaseThisMonth: Double = 0.0,
    val receivable: Double = 0.0,
    val payable: Double = 0.0,
    val stockValue: Double = 0.0,
    val ordered: Double = 0.0,
    val receivedAgainstOrders: Double = 0.0,
    val returnedToSupplier: Double = 0.0,
    val pendingToReceive: Double = 0.0
)

private fun startOfMonth(): Long = Calendar.getInstance().apply {
    set(Calendar.DAY_OF_MONTH, 1)
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

class DashboardChartsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    private val cashAndTrade = combine(
        repo.allBills, repo.allReceipts, repo.allExpenses, repo.allPurchases
    ) { bills, receipts, expenses, purchases ->
        val monthStart = startOfMonth()

        // Money actually in hand, split by how it moved. Credit invoices are not cash.
        val inByMode = HashMap<String, Double>()
        val outByMode = HashMap<String, Double>()
        bills.filter { it.paymentMethod != "Credit" }
            .forEach { inByMode.merge(it.paymentMethod, it.grandTotal, Double::plus) }
        receipts.forEach { inByMode.merge(it.paymentMode, it.amount, Double::plus) }
        purchases.filter { it.paymentMethod != "Credit" }
            .forEach { outByMode.merge(it.paymentMethod, it.grandTotal, Double::plus) }
        expenses.forEach { outByMode.merge(it.paymentMode, it.amount, Double::plus) }

        val modeColors = listOf(
            "Cash" to Color(0xFF2E7D32),
            "UPI" to Color(0xFF1565C0),
            "Card" to Color(0xFF6A1B9A),
            "Cheque" to Color(0xFFEF6C00)
        )
        val byMode = modeColors.map { (name, color) ->
            Slice(name, (inByMode[name] ?: 0.0) - (outByMode[name] ?: 0.0), color)
        }

        DashboardFigures(
            byMode = byMode,
            cashInHand = byMode.sumOf { it.value },
            salesThisMonth = bills.filter { it.dateMillis >= monthStart }.sumOf { it.grandTotal },
            purchaseThisMonth = purchases.filter { it.dateMillis >= monthStart }.sumOf { it.grandTotal },
            receivable = bills.sumOf { it.balance },
            payable = purchases.sumOf { it.balance }
        )
    }

    /**
     * Stock is valued from the same on-hand figures the stock report uses, at cost where a
     * cost is recorded and at the selling price otherwise.
     */
    private val withStock = combine(
        cashAndTrade, repo.items, repo.stockByName
    ) { figures, items, stock ->
        val rateByName = items.associate { it.name.lowercase() to if (it.purchasePrice > 0.0) it.purchasePrice else it.price }
        val value = stock.entries.sumOf { (name, qty) -> qty * (rateByName[name.lowercase()] ?: 0.0) }
        figures.copy(stockValue = value)
    }

    /**
     * Material against purchase orders, valued at the ordered rate throughout so the four
     * figures can be compared: what was ordered, what has arrived, what went back, and what
     * is still owed to us.
     */
    val figures: StateFlow<DashboardFigures> = combine(
        withStock, repo.purchaseQuotationLinesFlow, repo.receivedByLpo, repo.purchaseReturns
    ) { figures, lpoLines, received, returns ->
        val ordered = lpoLines.sumOf { it.qty * it.price }
        val rateFor = lpoLines.associate { (it.lpoId to it.name.lowercase()) to it.price }
        val receivedValue = received.sumOf { r -> r.qty * (rateFor[r.lpoId to r.name.lowercase()] ?: 0.0) }
        figures.copy(
            ordered = ordered,
            receivedAgainstOrders = receivedValue,
            returnedToSupplier = returns.sumOf { it.grandTotal },
            pendingToReceive = (ordered - receivedValue).coerceAtLeast(0.0)
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardFigures())
}

/**
 * Three rings under the frequently-used row: cash in hand split by payment mode, this
 * month's sales against purchases, and what is owed to and by the business.
 *
 * Hidden for a personal business, where none of these figures mean anything.
 */
@Composable
fun DashboardCharts(onOpenDetail: (String) -> Unit = {}, vm: DashboardChartsViewModel = viewModel()) {
    val f by vm.figures.collectAsStateSafe()

    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChartCard(
                title = "Cash in hand",
                centre = Format.money(f.cashInHand),
                slices = f.byMode,
                onClick = { onOpenDetail("cash") },
                modifier = Modifier.weight(1f)
            )
            ChartCard(
                title = "This month",
                centre = Format.money(f.salesThisMonth - f.purchaseThisMonth),
                slices = listOf(
                    Slice("Sales", f.salesThisMonth, Color(0xFF2E7D32)),
                    Slice("Purchase", f.purchaseThisMonth, Color(0xFFC62828))
                ),
                onClick = { onOpenDetail("month") },
                modifier = Modifier.weight(1f)
            )
        }
        ChartCard(
            title = "Receivable, payable and stock",
            centre = Format.money(f.receivable + f.stockValue - f.payable),
            slices = listOf(
                Slice("Receivable", f.receivable, Color(0xFF00838F)),
                Slice("Payable", f.payable, Color(0xFFC62828)),
                Slice("Stock value", f.stockValue, Color(0xFF5D4037))
            ),
            onClick = { onOpenDetail("owed") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
        ChartCard(
            title = "Material against purchase orders",
            centre = Format.money(f.pendingToReceive),
            slices = listOf(
                Slice("Ordered", f.ordered, Color(0xFF1565C0)),
                Slice("Received", f.receivedAgainstOrders, Color(0xFF2E7D32)),
                Slice("Returned", f.returnedToSupplier, Color(0xFFC62828)),
                Slice("Still to receive", f.pendingToReceive, Color(0xFFEF6C00))
            ),
            onClick = { onOpenDetail("material") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
    }
}

@Composable
private fun ChartCard(
    title: String,
    centre: String,
    slices: List<Slice>,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Card(modifier.clickable { onClick() }) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "tap for details",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            DonutRing(slices, centre)
            slices.forEach { s ->
                Row(
                    Modifier.fillMaxWidth().padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Canvas(Modifier.size(10.dp)) { drawCircle(s.color) }
                    Text(
                        "  " + s.label,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        Format.money(s.value),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * Draws the ring. Only positive amounts can take up arc — a negative net (more paid out
 * than taken in on that mode) still shows its real figure in the legend, but cannot be
 * drawn as a share of a circle.
 */
@Composable
private fun DonutRing(slices: List<Slice>, centre: String) {
    val positive = slices.map { it.value.coerceAtLeast(0.0) }
    val total = positive.sum()
    val empty = MaterialTheme.colorScheme.surfaceVariant

    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 6.dp)) {
        Canvas(Modifier.size(112.dp)) {
            val stroke = 20f
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            if (total <= 0.0) {
                drawArc(empty, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            } else {
                var start = -90f
                slices.forEachIndexed { i, s ->
                    val sweep = (positive[i] / total * 360.0).toFloat()
                    if (sweep > 0f) {
                        drawArc(s.color, start, sweep, false, topLeft, arcSize, style = Stroke(stroke))
                        start += sweep
                    }
                }
            }
        }
        Text(centre, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}
