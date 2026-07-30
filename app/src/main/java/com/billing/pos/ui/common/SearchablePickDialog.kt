package com.billing.pos.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * A searchable list picker with an "add new" fallback — used for master data like the
 * diary type, where the user should be able to pick an existing value or create one
 * without leaving the screen.
 */
@Composable
fun SearchablePickDialog(
    title: String,
    options: List<Pair<Long, String>>,
    selectedId: Long,
    onPick: (Long) -> Unit,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
    noneLabel: String = "— none —"
) {
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val filtered = if (q.isBlank()) options
    else options.filter { it.second.contains(q, ignoreCase = true) }
    val exact = options.any { it.second.equals(q, ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search or type a new one") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp).padding(top = 8.dp)) {
                    item {
                        Text(
                            noneLabel,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onPick(0L) }
                                .padding(vertical = 10.dp),
                            color = MaterialTheme.colorScheme.outline
                        )
                        Divider()
                    }
                    items(filtered, key = { it.first }) { (id, name) ->
                        Text(
                            name,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onPick(id) }
                                .padding(vertical = 10.dp),
                            fontWeight = if (id == selectedId) FontWeight.Bold else FontWeight.Normal,
                            color = if (id == selectedId) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                        Divider()
                    }
                }
                // Offer to create only when the typed name isn't already there.
                if (q.isNotBlank() && !exact) {
                    Row(
                        Modifier.fillMaxWidth().clickable { onAdd(q) }.padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("  Add \"$q\"", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
