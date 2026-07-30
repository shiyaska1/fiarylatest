package com.billing.pos.ui.accounts

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.AccountGroup
import com.billing.pos.data.AccountHead
import com.billing.pos.data.AccountNature
import com.billing.pos.data.Repository
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChartOfAccountsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    val groups: StateFlow<List<AccountGroup>> =
        repo.accountGroups.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val heads: StateFlow<List<AccountHead>> =
        repo.accountHeads.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    fun saveGroup(existing: AccountGroup?, name: String, nature: AccountNature, onDone: () -> Unit) {
        if (name.isBlank()) { message.value = "Enter a name"; return }
        viewModelScope.launch {
            if (existing == null) repo.addAccountGroup(name, nature)
            else repo.updateAccountGroup(existing.copy(name = name.trim(), nature = nature))
            message.value = "Saved"; onDone()
        }
    }

    fun deleteGroup(g: AccountGroup) {
        viewModelScope.launch {
            val r = repo.deleteAccountGroup(g)
            message.value = if (r.isSuccess) "Group deleted" else r.exceptionOrNull()?.message ?: "Cannot delete"
        }
    }

    fun saveHead(existing: AccountHead?, name: String, groupId: Long, opening: Double, isDebit: Boolean, onDone: () -> Unit) {
        if (name.isBlank()) { message.value = "Enter a name"; return }
        if (groupId <= 0) { message.value = "Pick a group"; return }
        viewModelScope.launch {
            if (existing == null) repo.addAccountHead(name, groupId, opening, isDebit)
            else repo.updateAccountHead(existing.copy(name = name.trim(), groupId = groupId, openingBalance = opening, openingIsDebit = isDebit))
            message.value = "Saved"; onDone()
        }
    }

    fun deleteHead(h: AccountHead) {
        viewModelScope.launch {
            val r = repo.deleteAccountHead(h)
            message.value = if (r.isSuccess) "Head deleted" else r.exceptionOrNull()?.message ?: "Cannot delete"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartOfAccountsScreen(
    onBack: () -> Unit,
    vm: ChartOfAccountsViewModel = viewModel()
) {
    val snackbar = remember { SnackbarHostState() }
    val groups by vm.groups.collectAsStateSafe()
    val heads by vm.heads.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    var showHeads by remember { mutableStateOf(false) }
    var editGroup by remember { mutableStateOf<AccountGroup?>(null) }
    var showGroupDialog by remember { mutableStateOf(false) }
    var editHead by remember { mutableStateOf<AccountHead?>(null) }
    var showHeadDialog by remember { mutableStateOf(false) }
    var deleteGroupFor by remember { mutableStateOf<AccountGroup?>(null) }
    var deleteHeadFor by remember { mutableStateOf<AccountHead?>(null) }

    val groupName = remember(groups) { groups.associate { it.id to it.name } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Chart of Accounts") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                if (showHeads) { editHead = null; showHeadDialog = true } else { editGroup = null; showGroupDialog = true }
            }) { Icon(Icons.Filled.Add, contentDescription = "Add") }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !showHeads, onClick = { showHeads = false }, label = { Text("Groups") })
                FilterChip(selected = showHeads, onClick = { showHeads = true }, label = { Text("Account Heads") })
            }

            if (!showHeads) {
                LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    items(groups, key = { it.id }) { g ->
                        Row(Modifier.fillMaxWidth().clickable { editGroup = g; showGroupDialog = true }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(g.name, fontWeight = FontWeight.Bold)
                                Text(g.nature.label + if (g.isSystem) "  •  system" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                            if (!g.isSystem) IconButton(onClick = { deleteGroupFor = g }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                        }
                        Divider()
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    items(heads, key = { it.id }) { h ->
                        Row(Modifier.fillMaxWidth().clickable { editHead = h; showHeadDialog = true }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(h.name, fontWeight = FontWeight.Bold)
                                Text(
                                    (groupName[h.groupId] ?: "?") +
                                        (if (h.openingBalance != 0.0) "  •  Op ${Format.rupee(h.openingBalance)} ${if (h.openingIsDebit) "Dr" else "Cr"}" else ""),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline
                                )
                            }
                            if (!h.isSystem) IconButton(onClick = { deleteHeadFor = h }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                        }
                        Divider()
                    }
                }
            }
        }
    }

    if (showGroupDialog) {
        GroupDialog(existing = editGroup, onDismiss = { showGroupDialog = false }, onSave = { n, nat -> vm.saveGroup(editGroup, n, nat) { showGroupDialog = false } })
    }
    if (showHeadDialog) {
        HeadDialog(existing = editHead, groups = groups, onDismiss = { showHeadDialog = false }, onSave = { n, gid, op, dr -> vm.saveHead(editHead, n, gid, op, dr) { showHeadDialog = false } })
    }
    deleteGroupFor?.let { g ->
        AlertDialog(onDismissRequest = { deleteGroupFor = null }, title = { Text("Delete ${g.name}?") },
            text = { Text("Only empty, non-system groups can be deleted.") },
            confirmButton = { TextButton(onClick = { vm.deleteGroup(g); deleteGroupFor = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteGroupFor = null }) { Text("Cancel") } })
    }
    deleteHeadFor?.let { h ->
        AlertDialog(onDismissRequest = { deleteHeadFor = null }, title = { Text("Delete ${h.name}?") },
            text = { Text("Remove this account head.") },
            confirmButton = { TextButton(onClick = { vm.deleteHead(h); deleteHeadFor = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteHeadFor = null }) { Text("Cancel") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupDialog(existing: AccountGroup?, onDismiss: () -> Unit, onSave: (String, AccountNature) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var nature by remember { mutableStateOf(existing?.nature ?: AccountNature.ASSET) }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New group" else "Edit group") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Group name *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(readOnly = true, value = nature.label, onValueChange = {}, label = { Text("Nature") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        AccountNature.values().forEach { n -> DropdownMenuItem(text = { Text(n.label) }, onClick = { nature = n; expanded = false }) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, nature) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeadDialog(existing: AccountHead?, groups: List<AccountGroup>, onDismiss: () -> Unit, onSave: (String, Long, Double, Boolean) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var group by remember { mutableStateOf(groups.firstOrNull { it.id == existing?.groupId } ?: groups.firstOrNull()) }
    var opening by remember { mutableStateOf(if ((existing?.openingBalance ?: 0.0) != 0.0) Format.money(existing!!.openingBalance) else "") }
    var isDebit by remember { mutableStateOf(existing?.openingIsDebit ?: true) }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New account head" else "Edit account head") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Head name *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(readOnly = true, value = group?.name ?: "", onValueChange = {}, label = { Text("Group") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        groups.forEach { g -> DropdownMenuItem(text = { Text("${g.name}  (${g.nature.label})") }, onClick = { group = g; expanded = false }) }
                    }
                }
                OutlinedTextField(value = opening, onValueChange = { opening = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Opening balance") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = isDebit, onClick = { isDebit = true }, label = { Text("Debit") })
                    FilterChip(selected = !isDebit, onClick = { isDebit = false }, label = { Text("Credit") })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, group?.id ?: 0L, opening.toDoubleOrNull() ?: 0.0, isDebit) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
