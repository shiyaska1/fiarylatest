package com.billing.pos.ui.customers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.billing.pos.data.Customer
import com.billing.pos.data.CustomerAttachment
import com.billing.pos.data.DiaryRepository
import com.billing.pos.data.Repository
import com.billing.pos.data.ServiceRepository
import com.billing.pos.util.Format

private data class HistRow(val title: String, val sub: String)

/**
 * Everything ever filed against one customer, grouped as an expandable tree:
 * sales, quotations, estimates, orders, job cards, receipts, diary notes, and
 * attachments (which is where sticky notes land). Opened by long-pressing the
 * customer in the list.
 */
@Composable
fun CustomerHistoryDialog(customer: Customer, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { Repository(context) }
    val diaryRepo = remember { DiaryRepository(context) }
    val serviceRepo = remember { ServiceRepository(context) }

    val bills by repo.allBills.collectAsState(initial = emptyList())
    val quotations by repo.quotations.collectAsState(initial = emptyList())
    val estimates by repo.estimates.collectAsState(initial = emptyList())
    val orders by repo.custOrders.collectAsState(initial = emptyList())
    val receipts by repo.allReceipts.collectAsState(initial = emptyList())
    val diaries by diaryRepo.searchAll("").collectAsState(initial = emptyList())
    val cards by serviceRepo.cards.collectAsState(initial = emptyList())
    var attachments by remember { mutableStateOf<List<CustomerAttachment>>(emptyList()) }
    LaunchedEffect(customer.id) { attachments = repo.customerAttachmentsFor(customer.id) }

    fun match(id: Long, name: String) =
        (customer.id != 0L && id == customer.id) || name.equals(customer.name, true)

    val groups: List<Pair<String, List<HistRow>>> = listOf(
        "Sales" to bills.filter { match(it.customerId, it.customerName) }
            .map { HistRow("${it.billNo}  •  ${Format.rupee(it.grandTotal)}", Format.date(it.dateMillis)) },
        "Quotations" to quotations.filter { match(it.customerId, it.customerName) }
            .map { HistRow("${it.quotationNo}  •  ${Format.rupee(it.grandTotal)}", Format.date(it.dateMillis)) },
        "Estimates" to estimates.filter { match(it.customerId, it.customerName) }
            .map { HistRow("${it.estimateNo}  •  ${Format.rupee(it.grandTotal)}", Format.date(it.dateMillis)) },
        "Orders" to orders.filter { match(it.customerId, it.customerName) }
            .map { HistRow("${it.orderNo}  •  ${Format.rupee(it.grandTotal)}", Format.date(it.dateMillis)) },
        "Job cards" to cards.filter { match(it.customerId, it.customerName) }
            .map { HistRow("${it.jobNo}  •  ${Format.rupee(it.grandTotal)}", "${Format.date(it.dateMillis)}  •  ${it.status}") },
        "Receipts" to receipts.filter { it.payFrom.ifBlank { it.customerName }.equals(customer.name, true) }
            .map { HistRow("${it.receiptNo}  •  ${Format.rupee(it.amount)}", Format.date(it.dateMillis)) },
        "Diary" to diaries.filter { match(it.customerId, it.customerName) }
            .map { HistRow(it.title.ifBlank { it.remarks.take(40) }, Format.date(it.createdAt)) },
        "Sticky notes / files" to attachments.map { HistRow(it.name, "") }
    )

    var expanded by remember { mutableStateOf(setOf<String>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(customer.name, maxLines = 1) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                groups.forEach { (label, rows) ->
                    item(key = label) {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    expanded = if (label in expanded) expanded - label else expanded + label
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("$label (${rows.size})", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                            Icon(
                                if (label in expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = null
                            )
                        }
                        Divider()
                    }
                    if (label in expanded) {
                        if (rows.isEmpty()) item(key = "$label-empty") {
                            Box(Modifier.fillMaxWidth().padding(8.dp)) {
                                Text("Nothing here", color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        rows.forEachIndexed { i, r ->
                            item(key = "$label-$i") {
                                Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 6.dp, bottom = 6.dp)) {
                                    Text(r.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                    if (r.sub.isNotBlank()) Text(r.sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
