package com.billing.pos.ui.medical

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.Repository
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Shown once per app start, so the popup doesn't reappear on every dashboard visit. */
object ExpiryGate { var shown = false }

/** One batch that is expiring soon (or already expired) and still has stock. */
data class ExpiringMed(
    val itemName: String,
    val batchNo: String,
    val expiryMillis: Long,
    val daysLeft: Long,          // negative once expired
    val qty: Double,
    val rate: Double,
    val purchaseId: Long,        // 0 = no purchase voucher found for this batch
    val purchaseNo: String
) {
    val cost: Double get() = rate * qty
}

/** Batches expiring within the configured window, costed from their purchase rate. */
class ExpiryAlertViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    private val prefs = AppPrefs(app)

    val rows: StateFlow<List<ExpiringMed>> =
        combine(repo.items, repo.itemBatches, repo.batchCosts) { items, batches, costs ->
            val days = prefs.expiryAlertDays.toLong()
            val nameById = items.associate { it.id to it.name }
            val costByKey = costs
                .groupBy { it.name.lowercase() to it.batchNo.lowercase() }
                // If a batch was bought more than once, use the latest voucher.
                .mapValues { (_, l) -> l.maxByOrNull { it.dateMillis }!! }
            val now = System.currentTimeMillis()
            val cutoff = now + days * 86_400_000L
            batches
                .filter { it.expiryMillis > 0 && it.quantity > 0 && it.expiryMillis <= cutoff }
                .map { b ->
                    val name = nameById[b.itemId] ?: "?"
                    val c = costByKey[name.lowercase() to b.batchNo.lowercase()]
                    ExpiringMed(
                        itemName = name,
                        batchNo = b.batchNo,
                        expiryMillis = b.expiryMillis,
                        daysLeft = Math.floorDiv(b.expiryMillis - now, 86_400_000L),
                        qty = b.quantity,
                        rate = c?.price ?: 0.0,
                        purchaseId = c?.purchaseId ?: 0L,
                        purchaseNo = c?.purchaseNo.orEmpty()
                    )
                }
                .sortedBy { it.expiryMillis }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

/**
 * The popup shown when the app opens: every medicine nearing expiry, with days left,
 * cost, and a link to the purchase voucher it came from.
 */
@Composable
fun ExpiryAlertDialog(
    rows: List<ExpiringMed>,
    onOpenPurchase: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val total = rows.sumOf { it.cost }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Expiring medicines (${rows.size})") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                    items(rows, key = { it.itemName + "|" + it.batchNo + "|" + it.expiryMillis }) { r ->
                        val expired = r.daysLeft < 0
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    r.itemName, Modifier.weight(1f),
                                    fontWeight = FontWeight.SemiBold, maxLines = 2
                                )
                                Text(
                                    if (expired) "EXPIRED" else "${r.daysLeft} days",
                                    color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                "Batch ${r.batchNo}  •  Exp ${Format.date(r.expiryMillis)}  •  Qty ${Format.qty(r.qty)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                if (r.purchaseId > 0) {
                                    Text(
                                        "Purchase ${r.purchaseNo}",
                                        Modifier.clickable { onOpenPurchase(r.purchaseId) },
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                } else {
                                    Text(
                                        "No purchase voucher",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                                Text("Cost ${Format.money(r.cost)}", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Divider()
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("TOTAL COST", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(
                        Format.money(total),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
