package com.billing.pos.ui.items

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.billing.pos.ocr.ScannedItem

/** An editable, ready-to-save item parsed from a scan. */
data class ImportItem(
    val name: String,
    val price: Double,
    val category: String,
    val openingStock: Double,
    val unit: String
)

/** Mutable per-row state backing one line in the review list. */
private class RowState(
    name: String,
    price: String,
    category: String,
    val duplicate: Boolean
) {
    var name by mutableStateOf(name)
    var price by mutableStateOf(price)
    var category by mutableStateOf(category)
    var stock by mutableStateOf("")
    var unit by mutableStateOf("PCS")
    var include by mutableStateOf(!duplicate)
}

/**
 * Review screen for a scanned printed item list. Duplicates (already in the master)
 * are shown greyed and unticked. Everything is editable; Save adds only ticked,
 * non-blank rows that don't already exist.
 */
@Composable
fun ScanImportDialog(
    initial: List<ScannedItem>,
    existingNames: Set<String>,
    categories: List<String>,
    onDismiss: () -> Unit,
    onImport: (List<ImportItem>) -> Unit
) {
    val rows = remember {
        mutableStateListOf<RowState>().apply {
            initial.forEach { s ->
                add(RowState(
                    name = s.name,
                    price = if (s.price > 0.0) trimNum(s.price) else "",
                    category = "",
                    duplicate = existingNames.contains(s.name.trim().lowercase())
                ))
            }
        }
    }
    var applyCategory by remember { mutableStateOf("") }
    var catMenu by remember { mutableStateOf(false) }

    val selectedCount = rows.count { it.include && it.name.isNotBlank() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
                .safeDrawingPadding().imePadding().padding(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Review scanned items", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("$selectedCount selected", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
            // Actions on top so they stay reachable above the nav bar / keyboard.
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = {
                        val out = rows.filter { it.include && it.name.isNotBlank() }.map {
                            ImportItem(
                                name = it.name.trim(),
                                price = it.price.toDoubleOrNull() ?: 0.0,
                                category = it.category.trim(),
                                openingStock = it.stock.toDoubleOrNull() ?: 0.0,
                                unit = it.unit.trim().ifBlank { "PCS" }
                            )
                        }
                        onImport(out)
                    },
                    enabled = selectedCount > 0,
                    modifier = Modifier.weight(1.4f)
                ) { Text("Save $selectedCount items") }
            }
            Text(
                "Tick the ones to add. Duplicates already in your items are unticked. Edit anything below.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
            )

            // Apply one category to every row (type a new one, or pick an existing).
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = applyCategory, onValueChange = { applyCategory = it },
                        label = { Text("Category for all") }, singleLine = true,
                        trailingIcon = {
                            if (categories.isNotEmpty()) IconButton(onClick = { catMenu = true }) { Icon(Icons.Filled.Add, "Pick category") }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    DropdownMenu(expanded = catMenu, onDismissRequest = { catMenu = false }) {
                        categories.forEach { c ->
                            DropdownMenuItem(text = { Text(c) }, onClick = { applyCategory = c; catMenu = false })
                        }
                    }
                }
                OutlinedButton(onClick = { rows.forEach { it.category = applyCategory } }, enabled = applyCategory.isNotBlank()) {
                    Text("Apply")
                }
            }

            Divider(Modifier.padding(vertical = 6.dp))

            if (rows.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("No text found. Try again with a clearer photo.", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(rows) { index, row ->
                        Column(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = row.include, onCheckedChange = { row.include = it })
                                OutlinedTextField(
                                    value = row.name, onValueChange = { row.name = it },
                                    label = { Text(if (row.duplicate) "Name (already exists)" else "Name") },
                                    singleLine = true, modifier = Modifier.weight(1f)
                                )
                                Spacer6()
                                OutlinedTextField(
                                    value = row.price, onValueChange = { row.price = it.filter { c -> c.isDigit() || c == '.' } },
                                    label = { Text("Sell") }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.width(84.dp)
                                )
                                IconButton(onClick = { rows.removeAt(index) }) { Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.error) }
                            }
                            Row(Modifier.padding(start = 40.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = row.category, onValueChange = { row.category = it },
                                    label = { Text("Category") }, singleLine = true, modifier = Modifier.weight(1.4f)
                                )
                                Spacer6()
                                OutlinedTextField(
                                    value = row.stock, onValueChange = { row.stock = it.filter { c -> c.isDigit() || c == '.' } },
                                    label = { Text("Op.stock") }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.width(96.dp)
                                )
                                Spacer6()
                                OutlinedTextField(
                                    value = row.unit, onValueChange = { row.unit = it },
                                    label = { Text("Unit") }, singleLine = true, modifier = Modifier.width(84.dp)
                                )
                            }
                            Divider()
                        }
                    }
                }
            }

        }
    }
}

@Composable
private fun Spacer6() = androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))

private fun trimNum(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
