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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.LpoReceivedRow
import com.billing.pos.data.PurchaseQuotation
import com.billing.pos.data.Repository
import com.billing.pos.data.Supplier
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.ui.common.LpoPickerField
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One line of the LPO material report. */
data class LpoReportRow(
    val lpoId: Long, val lpoNo: String, val date: Long, val name: String,
    val ordered: Double, val received: Double, val returned: Double, val rate: Double
) {
    /** Quantity still to receive against the order (informational). */
    val balance: Double get() = (ordered - received).coerceAtLeast(0.0)
    /** Net goods kept = received minus returned-to-supplier — what you actually owe for. */
    val payableQty: Double get() = (received - returned).coerceAtLeast(0.0)
    /** Payable value for this line. A purchase return lowers it; re-receiving (a new MRN) raises it back. */
    val value: Double get() = payableQty * rate
}

class LpoMaterialReportViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    val suppliers: StateFlow<List<Supplier>> = repo.suppliers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val lpos: StateFlow<List<PurchaseQuotation>> = repo.purchaseQuotations.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val receivedByLpo: StateFlow<List<LpoReceivedRow>> = repo.receivedByLpo.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Builds report rows for a supplier (optionally one LPO + a date window). */
    suspend fun build(
        supplierId: Long, lpoId: Long, from: Long, to: Long,
        received: List<LpoReceivedRow>, lpos: List<PurchaseQuotation>
    ): List<LpoReportRow> {
        // The supplier's total returns per item, allocated greedily across the LPO lines below so a
        // return reduces payable once (and a re-receipt via a new MRN raises `received` back).
        val remainingReturn = repo.returnedQtyForSupplier(supplierId)
            .associate { it.name.lowercase() to it.qty }.toMutableMap()
        val recvMap = received.associate { (it.lpoId to it.name.lowercase()) to it.qty }
        val chosen = lpos.filter {
            it.supplierId == supplierId &&
                (lpoId <= 0L || it.id == lpoId) &&
                it.dateMillis in from..to
        }.sortedByDescending { it.dateMillis }
        val out = ArrayList<LpoReportRow>()
        chosen.forEach { lpo ->
            repo.purchaseQuotationLines(lpo.id).forEach { line ->
                val key = line.name.lowercase()
                val recv = recvMap[lpo.id to key] ?: 0.0
                val avail = remainingReturn[key] ?: 0.0
                val applied = minOf(avail, recv)          // a line can't return more than it received
                remainingReturn[key] = avail - applied
                out.add(
                    LpoReportRow(
                        lpoId = lpo.id, lpoNo = lpo.lpoNo, date = lpo.dateMillis, name = line.name,
                        ordered = line.qty, received = recv, returned = applied, rate = line.price
                    )
                )
            }
        }
        return out
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LpoMaterialReportScreen(onBack: () -> Unit, onOpenLpo: (Long) -> Unit, vm: LpoMaterialReportViewModel = viewModel()) {
    val context = LocalContext.current
    val suppliers by vm.suppliers.collectAsStateSafe()
    val lpos by vm.lpos.collectAsStateSafe()
    val received by vm.receivedByLpo.collectAsStateSafe()

    var supplier by remember { mutableStateOf<Supplier?>(null) }
    var lpoNo by remember { mutableStateOf("") }
    var lpoId by remember { mutableStateOf(0L) }
    var fromMillis by remember { mutableStateOf(Calendar.getInstance().apply { add(Calendar.MONTH, -3) }.timeInMillis) }
    var toMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var rows by remember { mutableStateOf<List<LpoReportRow>>(emptyList()) }
    var viewed by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val payable = rows.sumOf { it.value }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LPO Material Report") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
            var supMenu by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = supMenu, onExpandedChange = { supMenu = !supMenu }) {
                OutlinedTextField(
                    readOnly = true, value = supplier?.name ?: "", onValueChange = {},
                    label = { Text("Supplier *  (required)") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(supMenu) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = supMenu, onDismissRequest = { supMenu = false }) {
                    suppliers.forEach { s -> DropdownMenuItem(text = { Text(s.name) }, onClick = { supplier = s; lpoId = 0; lpoNo = ""; supMenu = false }) }
                }
            }
            LpoPickerField(
                lpos = lpos, supplierId = supplier?.id ?: 0L, selectedNo = lpoNo,
                label = "LPO (optional — all if blank)",
                onPick = { lpoId = it.id; lpoNo = it.lpoNo }
            )
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickDate(context, fromMillis) { fromMillis = it } }, modifier = Modifier.weight(1f)) { Text("From ${Format.date(fromMillis)}", maxLines = 1) }
                OutlinedButton(onClick = { pickDate(context, toMillis) { toMillis = it } }, modifier = Modifier.weight(1f)) { Text("To ${Format.date(toMillis)}", maxLines = 1) }
            }
            Button(
                onClick = {
                    val s = supplier ?: return@Button
                    scope.launch {
                        rows = vm.build(s.id, lpoId, startOfDay(fromMillis), endOfDay(toMillis), received, lpos)
                        viewed = true
                    }
                },
                enabled = supplier != null,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) { Text("View") }

            // Column header
            Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text("Item / LPO", Modifier.weight(2f), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelSmall)
                Text("Ord", Modifier.weight(0.8f), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End)
                Text("Rec", Modifier.weight(0.8f), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End)
                Text("Ret", Modifier.weight(0.8f), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End)
                Text("Bal", Modifier.weight(0.8f), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End)
                Text("Value", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End)
            }
            Divider()
            if (viewed && rows.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("No LPO lines for this filter", color = MaterialTheme.colorScheme.outline) }
            else LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(rows, key = { it.lpoId.toString() + it.name }) { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(2f)) {
                            Text(r.name, fontWeight = FontWeight.Medium, maxLines = 1)
                            Text(
                                r.lpoNo,
                                Modifier.clickable { onOpenLpo(r.lpoId) },
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(Format.qty(r.ordered), Modifier.weight(0.8f), textAlign = TextAlign.End, style = MaterialTheme.typography.bodySmall)
                        Text(Format.qty(r.received), Modifier.weight(0.8f), textAlign = TextAlign.End, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                        Text(Format.qty(r.returned), Modifier.weight(0.8f), textAlign = TextAlign.End, style = MaterialTheme.typography.bodySmall)
                        Text(Format.qty(r.balance), Modifier.weight(0.8f), textAlign = TextAlign.End, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        Text(Format.money(r.value), Modifier.weight(1f), textAlign = TextAlign.End, style = MaterialTheme.typography.bodySmall)
                    }
                    Divider()
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("PAYABLE (received − returned)", fontWeight = FontWeight.Bold)
                Text(Format.money(payable), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
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
