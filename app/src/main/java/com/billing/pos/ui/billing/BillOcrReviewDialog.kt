package com.billing.pos.ui.billing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.billing.pos.data.Item
import com.billing.pos.ocr.ScannedItem

private class OcrRow(name: String, price: String) {
    var name by mutableStateOf(name)
    var price by mutableStateOf(price)
    var include by mutableStateOf(true)
}

/**
 * Lets the user verify + edit OCR-read items (name + price) before they are added to
 * the bill. On Update, only the ticked, non-blank rows are returned; the caller adds
 * them to the cart and creates any missing item-master entries.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillOcrReviewDialog(
    initial: List<ScannedItem>,
    masterItems: List<Item>,
    onDismiss: () -> Unit,
    onConfirm: (List<ScannedItem>) -> Unit
) {
    val rows = remember {
        mutableStateListOf<OcrRow>().apply {
            initial.forEach { add(OcrRow(it.name, if (it.price > 0.0) trimNum(it.price) else "")) }
        }
    }
    val selected = rows.count { it.include && it.name.isNotBlank() }

    Dialog(
        onDismissRequest = onDismiss,
        // decorFitsSystemWindows = false so insets reach the content; safeDrawingPadding then keeps
        // the action buttons clear of the phone's navigation bar and the keyboard.
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
                .safeDrawingPadding().imePadding().padding(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Check scanned items", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("$selected selected", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
            // Actions on TOP so they're always reachable, even with a long list + keyboard open.
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { rows.add(OcrRow("", "")) }) { Icon(Icons.Filled.Add, "Add row"); Text(" Row") }
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = {
                        val out = rows.filter { it.include && it.name.isNotBlank() }
                            .map { ScannedItem(it.name.trim(), it.price.toDoubleOrNull() ?: 0.0) }
                        onConfirm(out)
                    },
                    enabled = selected > 0,
                    modifier = Modifier.weight(1.2f)
                ) { Text("Update ($selected)") }
            }
            Text(
                "Fix any wrong name or price, untick what you don't want, then Update.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 6.dp)
            )
            Divider(Modifier.padding(vertical = 6.dp))

            if (rows.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("Nothing readable in the photo.", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(rows) { index, row ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = row.include, onCheckedChange = { row.include = it })
                            var expanded by remember { mutableStateOf(false) }
                            val suggestions = remember(row.name, masterItems) {
                                val q = row.name.trim()
                                if (q.isBlank()) emptyList()
                                else masterItems.filter { it.name.contains(q, ignoreCase = true) }
                                    .sortedBy { it.name.lowercase() }.take(6)
                            }
                            ExposedDropdownMenuBox(
                                expanded = expanded && suggestions.isNotEmpty(),
                                onExpandedChange = { expanded = it },
                                modifier = Modifier.weight(1f)
                            ) {
                                OutlinedTextField(
                                    value = row.name,
                                    onValueChange = { row.name = it; expanded = true },
                                    label = { Text("Item name") }, singleLine = true,
                                    modifier = Modifier.menuAnchor().fillMaxWidth()
                                )
                                ExposedDropdownMenu(expanded = expanded && suggestions.isNotEmpty(), onDismissRequest = { expanded = false }) {
                                    suggestions.forEach { item ->
                                        DropdownMenuItem(
                                            text = { Text("${item.name}   ₹${trimNum(item.price)}") },
                                            onClick = { row.name = item.name; row.price = trimNum(item.price); expanded = false }
                                        )
                                    }
                                }
                            }
                            androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                            OutlinedTextField(
                                value = row.price, onValueChange = { row.price = it.filter { c -> c.isDigit() || c == '.' } },
                                label = { Text("Price") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.width(92.dp)
                            )
                            IconButton(onClick = { rows.removeAt(index) }) { Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.error) }
                        }
                        Divider()
                    }
                }
            }
        }
    }
}

private fun trimNum(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
