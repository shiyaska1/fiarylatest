package com.billing.pos.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.billing.pos.data.AppPrefs

/**
 * Customer type dropdown with a "+" to add a new type, for every new-customer popup.
 * Defaults to General; new types are saved to the shared master (AppPrefs.customerTypes).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerTypeField(value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { AppPrefs(context) }
    var types by remember { mutableStateOf((listOf("General") + prefs.customerTypes).distinct()) }
    var menu by remember { mutableStateOf(false) }
    var addNew by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        ExposedDropdownMenuBox(expanded = menu, onExpandedChange = { menu = !menu }, modifier = Modifier.weight(1f)) {
            OutlinedTextField(
                readOnly = true, value = value.ifBlank { "General" }, onValueChange = {},
                label = { Text("Customer type") }, singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(menu) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                types.forEach { t -> DropdownMenuItem(text = { Text(t) }, onClick = { onValue(t); menu = false }) }
            }
        }
        IconButton(onClick = { addNew = true }) {
            Icon(Icons.Filled.Add, "New customer type", tint = MaterialTheme.colorScheme.primary)
        }
    }

    if (addNew) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addNew = false },
            title = { Text("New customer type") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Type name *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = name.trim()
                    if (t.isNotBlank()) {
                        prefs.addCustomerType(t)
                        types = (listOf("General") + prefs.customerTypes).distinct()
                        onValue(t)
                    }
                    addNew = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { addNew = false }) { Text("Cancel") } }
        )
    }
}
