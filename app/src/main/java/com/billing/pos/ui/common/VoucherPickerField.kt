package com.billing.pos.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.billing.pos.data.Bill
import com.billing.pos.data.Purchase
import com.billing.pos.data.PurchaseQuotation
import com.billing.pos.util.Format

/**
 * A searchable dropdown of past sales invoices. Type to filter by invoice no / customer;
 * picking one calls [onPick]. Shows the picked invoice number in the field.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvoicePickerField(
    bills: List<Bill>,
    selectedNo: String,
    onPick: (Bill) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, bills) {
        val q = query.trim()
        (if (q.isBlank()) bills
        else bills.filter { it.billNo.contains(q, true) || it.customerName.contains(q, true) })
            .sortedByDescending { it.dateMillis }
            .take(50)
    }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.fillMaxWidth().padding(top = 6.dp)
    ) {
        OutlinedTextField(
            value = if (expanded) query else selectedNo,
            onValueChange = { query = it; expanded = true },
            label = { Text("Return against invoice") },
            placeholder = { Text("Search invoice no or customer…") },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (filtered.isEmpty()) {
                DropdownMenuItem(text = { Text("No matching invoice") }, onClick = { expanded = false })
            } else filtered.forEach { b ->
                DropdownMenuItem(
                    text = {
                        Text(
                            "${b.billNo}   ${Format.rupee(b.grandTotal)}\n${b.customerName} • ${Format.date(b.dateMillis)}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    onClick = { onPick(b); query = ""; expanded = false }
                )
            }
        }
    }
}

/** Same as [InvoicePickerField] but for past purchases (purchase return). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurchasePickerField(
    purchases: List<Purchase>,
    selectedNo: String,
    onPick: (Purchase) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, purchases) {
        val q = query.trim()
        (if (q.isBlank()) purchases
        else purchases.filter { it.purchaseNo.contains(q, true) || it.supplierName.contains(q, true) })
            .sortedByDescending { it.dateMillis }
            .take(50)
    }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.fillMaxWidth().padding(top = 6.dp)
    ) {
        OutlinedTextField(
            value = if (expanded) query else selectedNo,
            onValueChange = { query = it; expanded = true },
            label = { Text("Return against purchase") },
            placeholder = { Text("Search purchase no or supplier…") },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (filtered.isEmpty()) {
                DropdownMenuItem(text = { Text("No matching purchase") }, onClick = { expanded = false })
            } else filtered.forEach { p ->
                DropdownMenuItem(
                    text = {
                        Text(
                            "${p.purchaseNo}   ${Format.rupee(p.grandTotal)}\n${p.supplierName} • ${Format.date(p.dateMillis)}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    onClick = { onPick(p); query = ""; expanded = false }
                )
            }
        }
    }
}

/** Searchable dropdown of purchase orders (LPO), filtered to a supplier when [supplierId] > 0. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LpoPickerField(
    lpos: List<PurchaseQuotation>,
    supplierId: Long,
    selectedNo: String,
    label: String = "Against purchase order (LPO)",
    onPick: (PurchaseQuotation) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, lpos, supplierId) {
        val q = query.trim()
        lpos.filter { (supplierId <= 0 || it.supplierId == supplierId) &&
            (q.isBlank() || it.lpoNo.contains(q, true) || it.supplierName.contains(q, true)) }
            .sortedByDescending { it.dateMillis }
            .take(50)
    }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.fillMaxWidth().padding(top = 6.dp)
    ) {
        OutlinedTextField(
            value = if (expanded) query else selectedNo,
            onValueChange = { query = it; expanded = true },
            label = { Text(label) },
            placeholder = { Text("Search LPO no or supplier…") },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (filtered.isEmpty()) {
                DropdownMenuItem(text = { Text("No matching LPO") }, onClick = { expanded = false })
            } else filtered.forEach { l ->
                DropdownMenuItem(
                    text = {
                        Text(
                            "${l.lpoNo}   ${Format.rupee(l.grandTotal)}\n${l.supplierName} • ${Format.date(l.dateMillis)}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    onClick = { onPick(l); query = ""; expanded = false }
                )
            }
        }
    }
}
