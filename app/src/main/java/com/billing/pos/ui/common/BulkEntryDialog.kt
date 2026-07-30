package com.billing.pos.ui.common

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.billing.pos.data.PayMode
import com.billing.pos.util.Format
import java.util.Calendar

/** One saved row from the bulk entry dialog. */
data class BulkEntryRow(val party: String, val description: String, val amount: Double, val dateMillis: Long)

private class BulkRowState(dateMillis: Long) {
    var party by mutableStateOf("")
    var description by mutableStateOf("")
    var amount by mutableStateOf("")
    var dateMillis by mutableStateOf(dateMillis)
}

/**
 * Enter many payments / receipts in one window. Each row has an amount, date and an
 * optional party; payment rows also have a details field. One payment mode applies to all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulkEntryDialog(
    title: String,
    isPayment: Boolean,
    defaultDate: Long,
    onDismiss: () -> Unit,
    onConfirm: (PayMode, List<BulkEntryRow>) -> Unit
) {
    val context = LocalContext.current
    val rows = remember { mutableStateListOf<BulkRowState>().apply { repeat(3) { add(BulkRowState(defaultDate)) } } }
    var mode by remember { mutableStateOf(PayMode.CASH) }
    var modeMenu by remember { mutableStateOf(false) }
    val ready = rows.count { (it.amount.toDoubleOrNull() ?: 0.0) > 0.0 }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
                .safeDrawingPadding().imePadding().padding(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            // Actions on top so they stay reachable above the nav bar / keyboard.
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { rows.add(BulkRowState(defaultDate)) }) { Icon(Icons.Filled.Add, "Add row"); Text(" Row") }
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = {
                        val out = rows.mapNotNull { r ->
                            val amt = r.amount.toDoubleOrNull() ?: 0.0
                            if (amt <= 0.0) null
                            else BulkEntryRow(r.party.trim(), r.description.trim(), amt, r.dateMillis)
                        }
                        onConfirm(mode, out)
                    },
                    enabled = ready > 0,
                    modifier = Modifier.weight(1.2f)
                ) { Text("Save ($ready)") }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExposedDropdownMenuBox(expanded = modeMenu, onExpandedChange = { modeMenu = !modeMenu }, modifier = Modifier.width(160.dp)) {
                    OutlinedTextField(
                        readOnly = true, value = mode.label, onValueChange = {},
                        label = { Text("Mode (all)") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modeMenu) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                        PayMode.values().forEach { m ->
                            DropdownMenuItem(text = { Text(m.label) }, onClick = { mode = m; modeMenu = false })
                        }
                    }
                }
                Text("$ready to save", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
            Divider(Modifier.padding(vertical = 6.dp))

            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(rows) { index, row ->
                    Column(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = row.amount,
                                onValueChange = { row.amount = it.filter { c -> c.isDigit() || c == '.' } },
                                label = { Text("Amount") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.width(120.dp)
                            )
                            androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                            OutlinedButton(onClick = { pickDate(context, row.dateMillis) { row.dateMillis = it } }, modifier = Modifier.weight(1f)) {
                                Text(Format.date(row.dateMillis))
                            }
                            IconButton(onClick = { rows.removeAt(index) }) { Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.error) }
                        }
                        OutlinedTextField(
                            value = row.party, onValueChange = { row.party = it },
                            label = { Text(if (isPayment) "Party / paid to (optional)" else "Party / from (optional)") },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        if (isPayment) {
                            OutlinedTextField(
                                value = row.description, onValueChange = { row.description = it },
                                label = { Text("Details (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Divider(Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

private fun pickDate(context: Context, current: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = current }
    android.app.DatePickerDialog(
        context,
        { _, y, m, d -> c.set(Calendar.YEAR, y); c.set(Calendar.MONTH, m); c.set(Calendar.DAY_OF_MONTH, d); onPicked(c.timeInMillis) },
        c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
    ).show()
}
