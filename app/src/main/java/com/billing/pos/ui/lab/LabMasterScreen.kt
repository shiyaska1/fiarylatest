package com.billing.pos.ui.lab

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.LabEvalMaster
import com.billing.pos.data.LabGroup
import com.billing.pos.data.Repository
import com.billing.pos.ui.billing.collectAsStateSafe
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LabMasterViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    val groups: StateFlow<List<LabGroup>> = repo.labGroups.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val evals: StateFlow<List<LabEvalMaster>> = repo.labEvalMasters.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val headings: StateFlow<List<com.billing.pos.data.LabHeading>> = repo.labHeadings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun addGroup(name: String) { if (name.isNotBlank()) viewModelScope.launch { repo.ensureLabGroup(name) } }
    fun deleteGroup(g: LabGroup) { viewModelScope.launch { repo.deleteLabGroup(g) } }
    fun saveEval(e: LabEvalMaster) { viewModelScope.launch { repo.saveLabEvalMaster(e); if (e.groupName.isNotBlank()) repo.ensureLabGroup(e.groupName) } }
    fun deleteEval(e: LabEvalMaster) { viewModelScope.launch { repo.deleteLabEvalMaster(e) } }
    fun addHeading(name: String) { if (name.isNotBlank()) viewModelScope.launch { repo.addHeadingToMaster(name) } }
    fun deleteHeading(h: com.billing.pos.data.LabHeading) { viewModelScope.launch { repo.deleteLabHeading(h) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabMasterScreen(onBack: () -> Unit, vm: LabMasterViewModel = viewModel()) {
    val groups by vm.groups.collectAsStateSafe()
    val evals by vm.evals.collectAsStateSafe()
    val headings by vm.headings.collectAsStateSafe()
    var newGroup by remember { mutableStateOf("") }
    var newHeading by remember { mutableStateOf("") }
    var editEval by remember { mutableStateOf<LabEvalMaster?>(null) }
    var showNewEval by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lab Masters") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
            item {
                Text("Groups", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = newGroup, onValueChange = { newGroup = it }, label = { Text("New group") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { vm.addGroup(newGroup); newGroup = "" }) { Text("Add") }
                }
            }
            items(groups, key = { "g" + it.id }) { g ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("▸ ${g.name}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { vm.deleteGroup(g) }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                }
                Divider()
            }
            item {
                Text("Headings", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = newHeading, onValueChange = { newHeading = it }, label = { Text("New heading") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { vm.addHeading(newHeading); newHeading = "" }) { Text("Add") }
                }
            }
            items(headings, key = { "h" + it.id }) { h ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(h.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { vm.deleteHeading(h) }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                }
                Divider()
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Evaluations", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = { showNewEval = true }) { Icon(Icons.Filled.Add, null); Text(" Add") }
                }
            }
            items(evals, key = { "e" + it.id }) { e ->
                Row(Modifier.fillMaxWidth().clickable { editEval = e }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.name, fontWeight = FontWeight.SemiBold)
                        Text(listOfNotNull(e.unit.ifBlank { null }, e.normalValue.ifBlank { null }, e.groupName.ifBlank { null }).joinToString("  •  "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                    IconButton(onClick = { vm.deleteEval(e) }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                }
                Divider()
            }
        }
    }

    if (showNewEval) EvalMasterDialog(null, groups, onDismiss = { showNewEval = false }, onSave = { vm.saveEval(it); showNewEval = false })
    editEval?.let { e -> EvalMasterDialog(e, groups, onDismiss = { editEval = null }, onSave = { vm.saveEval(it); editEval = null }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EvalMasterDialog(existing: LabEvalMaster?, groups: List<LabGroup>, onDismiss: () -> Unit, onSave: (LabEvalMaster) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var unit by remember { mutableStateOf(existing?.unit ?: "") }
    var normal by remember { mutableStateOf(existing?.normalValue ?: "") }
    var group by remember { mutableStateOf(existing?.groupName ?: "") }
    var groupMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New evaluation" else "Edit evaluation") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = unit, onValueChange = { unit = it }, label = { Text("Unit") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = normal, onValueChange = { normal = it }, label = { Text("Normal value") }, singleLine = true, modifier = Modifier.weight(1.4f))
                }
                ExposedDropdownMenuBox(expanded = groupMenu, onExpandedChange = { groupMenu = !groupMenu }) {
                    OutlinedTextField(
                        value = group, onValueChange = { group = it }, label = { Text("Group (optional)") }, singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(groupMenu) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    if (groups.isNotEmpty()) ExposedDropdownMenu(expanded = groupMenu, onDismissRequest = { groupMenu = false }) {
                        groups.forEach { g -> DropdownMenuItem(text = { Text(g.name) }, onClick = { group = g.name; groupMenu = false }) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave((existing ?: LabEvalMaster(name = "")).copy(name = name.trim(), unit = unit.trim(), normalValue = normal.trim(), groupName = group.trim()))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
