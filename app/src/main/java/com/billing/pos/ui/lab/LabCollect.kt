package com.billing.pos.ui.lab

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.LabBill
import com.billing.pos.data.LabReceipt
import com.billing.pos.data.Repository
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A lab bill with its still-outstanding balance. */
data class LabOutstanding(val bill: LabBill, val balance: Double)

class LabBalanceViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    val outstanding: StateFlow<List<LabOutstanding>> =
        combine(repo.labBills, repo.labReceiptSumByBill) { bills, sums ->
            val sumById = sums.associate { it.id to it.total }
            bills.mapNotNull { b ->
                val bal = b.grandTotal - b.paidAmount - (sumById[b.id] ?: 0.0)
                if (bal > 0.005) LabOutstanding(b, bal) else null
            }.sortedBy { it.bill.patientName.lowercase() }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun collect(bill: LabBill, amount: Double, mode: String, onDone: () -> Unit) {
        if (amount <= 0.0) return
        viewModelScope.launch {
            repo.addLabReceipt(LabReceipt(labBillId = bill.id, billNo = bill.billNo, patientName = bill.patientName, dateMillis = System.currentTimeMillis(), amount = amount, mode = mode))
            onDone()
        }
    }
}

/**
 * A "collect lab balance" toolbar button: lists lab bills with an outstanding balance and
 * records a receipt against the chosen one. [nowMillis] stamps the receipt date.
 */
@Composable
fun LabCollectButton(onMessage: (String) -> Unit, vm: LabBalanceViewModel = viewModel()) {
    val outstanding by vm.outstanding.collectAsStateSafe()
    var open by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<LabOutstanding?>(null) }

    IconButton(onClick = { open = true }) { Icon(Icons.Filled.Payments, contentDescription = "Collect lab balance") }

    if (open) {
        val t = target
        if (t == null) {
            AlertDialog(
                onDismissRequest = { open = false },
                title = { Text("Collect lab balance") },
                text = {
                    if (outstanding.isEmpty()) Text("No lab bills with a pending balance.", color = MaterialTheme.colorScheme.outline)
                    else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                        items(outstanding, key = { it.bill.id }) { o ->
                            Row(Modifier.fillMaxWidth().clickable { target = o }.padding(vertical = 10.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text("${o.bill.billNo} • ${o.bill.patientName}", fontWeight = FontWeight.SemiBold)
                                    Text("Total ${Format.rupee(o.bill.grandTotal)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                }
                                Text("Bal ${Format.rupee(o.balance)}", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                            }
                            Divider()
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } }
            )
        } else {
            var amount by remember(t.bill.id) { mutableStateOf(Format.money(t.balance)) }
            var mode by remember(t.bill.id) { mutableStateOf("Cash") }
            AlertDialog(
                onDismissRequest = { target = null },
                title = { Text("Receive — ${t.bill.patientName}") },
                text = {
                    Column(Modifier.fillMaxWidth()) {
                        Text("${t.bill.billNo} • balance ${Format.rupee(t.balance)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        OutlinedTextField(
                            value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                            label = { Text("Amount received") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                        )
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Cash", "UPI", "Card", "Cheque").forEach { m ->
                                FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m) })
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val amt = (amount.toDoubleOrNull() ?: 0.0).coerceAtMost(t.balance)
                        vm.collect(t.bill, amt, mode) {
                            onMessage("Received ${Format.rupee(amt)} for ${t.bill.billNo}")
                        }
                        target = null; open = false
                    }) { Text("Receive") }
                },
                dismissButton = { TextButton(onClick = { target = null }) { Text("Back") } }
            )
        }
    }
}
