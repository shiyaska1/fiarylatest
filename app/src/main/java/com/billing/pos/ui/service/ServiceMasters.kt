package com.billing.pos.ui.service

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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.ServiceEmployee
import com.billing.pos.data.ServiceJobMaster
import com.billing.pos.data.ServiceRepository
import com.billing.pos.data.ServiceStatus
import com.billing.pos.data.ServiceType
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ServiceMasterViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ServiceRepository(app)
    val types: StateFlow<List<ServiceType>> = repo.types.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val statuses: StateFlow<List<ServiceStatus>> = repo.statuses.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val jobs: StateFlow<List<ServiceJobMaster>> = repo.jobs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val employees: StateFlow<List<ServiceEmployee>> = repo.employees.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val models: StateFlow<List<com.billing.pos.data.ServiceModel>> = repo.models.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init { viewModelScope.launch { repo.ensureDefaults() } }

    fun saveType(t: ServiceType) { viewModelScope.launch { repo.saveType(t) } }
    fun deleteType(t: ServiceType) { viewModelScope.launch { repo.deleteType(t) } }
    fun saveStatus(s: ServiceStatus) { viewModelScope.launch { repo.saveStatus(s) } }
    fun deleteStatus(s: ServiceStatus) { viewModelScope.launch { repo.deleteStatus(s) } }
    fun saveJob(j: ServiceJobMaster) { viewModelScope.launch { repo.saveJob(j) } }
    fun deleteJob(j: ServiceJobMaster) { viewModelScope.launch { repo.deleteJob(j) } }
    fun saveEmployee(e: ServiceEmployee) { viewModelScope.launch { repo.saveEmployee(e) } }
    fun deleteEmployee(e: ServiceEmployee) { viewModelScope.launch { repo.deleteEmployee(e) } }
    fun addModel(name: String) { viewModelScope.launch { repo.saveModel(name) } }
    fun renameModel(m: com.billing.pos.data.ServiceModel, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { com.billing.pos.data.AppDatabase.get(getApplication()).serviceDao().upsertModel(m.copy(name = name.trim())) }
    }
    fun deleteModel(m: com.billing.pos.data.ServiceModel) { viewModelScope.launch { repo.deleteModel(m) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceMastersScreen(onBack: () -> Unit, vm: ServiceMasterViewModel = viewModel()) {
    val types by vm.types.collectAsStateSafe()
    val statuses by vm.statuses.collectAsStateSafe()
    val jobs by vm.jobs.collectAsStateSafe()
    val employees by vm.employees.collectAsStateSafe()
    var newType by remember { mutableStateOf("") }
    var newStatus by remember { mutableStateOf("") }
    var editType by remember { mutableStateOf<ServiceType?>(null) }
    var editStatus by remember { mutableStateOf<ServiceStatus?>(null) }
    var editJob by remember { mutableStateOf<ServiceJobMaster?>(null) }
    var showNewJob by remember { mutableStateOf(false) }
    var editEmployee by remember { mutableStateOf<ServiceEmployee?>(null) }
    var showNewEmployee by remember { mutableStateOf(false) }
    val models by vm.models.collectAsStateSafe()
    var newModel by remember { mutableStateOf("") }
    var editModel by remember { mutableStateOf<com.billing.pos.data.ServiceModel?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Service Masters") },
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
                Text("Service types", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = newType, onValueChange = { newType = it }, label = { Text("New service type") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { if (newType.isNotBlank()) { vm.saveType(ServiceType(name = newType)); newType = "" } }) { Text("Add") }
                }
            }
            items(types, key = { "t" + it.id }) { t ->
                Row(Modifier.fillMaxWidth().clickable { editType = t }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { vm.deleteType(t) }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                }
                Divider()
            }
            item {
                Text("Statuses", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = newStatus, onValueChange = { newStatus = it }, label = { Text("New status") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { if (newStatus.isNotBlank()) { vm.saveStatus(ServiceStatus(name = newStatus)); newStatus = "" } }) { Text("Add") }
                }
            }
            items(statuses, key = { "s" + it.id }) { s ->
                Row(Modifier.fillMaxWidth().clickable { editStatus = s }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { vm.deleteStatus(s) }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                }
                Divider()
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Jobs", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = { showNewJob = true }) { Icon(Icons.Filled.Add, null); Text(" Add") }
                }
            }
            items(jobs, key = { "j" + it.id }) { j ->
                Row(Modifier.fillMaxWidth().clickable { editJob = j }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(j.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text(Format.rupee(j.price), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    IconButton(onClick = { vm.deleteJob(j) }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                }
                Divider()
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Employees", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = { showNewEmployee = true }) { Icon(Icons.Filled.Add, null); Text(" Add") }
                }
            }
            items(employees, key = { "emp" + it.id }) { e ->
                Row(Modifier.fillMaxWidth().clickable { editEmployee = e }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.name, fontWeight = FontWeight.SemiBold)
                        if (e.phone.isNotBlank()) Text(e.phone, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                    IconButton(onClick = { vm.deleteEmployee(e) }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                }
                Divider()
            }
            item {
                Text("Models / Vehicles / Items", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = newModel, onValueChange = { newModel = it }, label = { Text("New model") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { if (newModel.isNotBlank()) { vm.addModel(newModel); newModel = "" } }) { Text("Add") }
                }
            }
            items(models, key = { "m" + it.id }) { m ->
                Row(Modifier.fillMaxWidth().clickable { editModel = m }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(m.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { vm.deleteModel(m) }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                }
                Divider()
            }
        }
    }

    editType?.let { t ->
        NameEditDialog(title = "Edit service type", initial = t.name, onDismiss = { editType = null }) {
            vm.saveType(t.copy(name = it)); editType = null
        }
    }
    editStatus?.let { s ->
        NameEditDialog(title = "Edit status", initial = s.name, onDismiss = { editStatus = null }) {
            vm.saveStatus(s.copy(name = it)); editStatus = null
        }
    }
    if (showNewJob) JobMasterDialog(null, onDismiss = { showNewJob = false }, onSave = { vm.saveJob(it); showNewJob = false })
    editJob?.let { j -> JobMasterDialog(j, onDismiss = { editJob = null }, onSave = { vm.saveJob(it); editJob = null }) }
    if (showNewEmployee) EmployeeDialog(null, onDismiss = { showNewEmployee = false }, onSave = { vm.saveEmployee(it); showNewEmployee = false })
    editEmployee?.let { e -> EmployeeDialog(e, onDismiss = { editEmployee = null }, onSave = { vm.saveEmployee(it); editEmployee = null }) }
    editModel?.let { m ->
        NameEditDialog(title = "Edit model", initial = m.name, onDismiss = { editModel = null }) {
            vm.renameModel(m, it); editModel = null
        }
    }
}

/** One dialog serves add and edit of an employee with their contact number. */
@Composable
fun EmployeeDialog(existing: ServiceEmployee?, onDismiss: () -> Unit, onSave: (ServiceEmployee) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var phone by remember { mutableStateOf(existing?.phone ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New employee" else "Edit employee") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = phone, onValueChange = { phone = it.filter { c -> c.isDigit() } },
                    label = { Text("Contact number") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) onSave((existing ?: ServiceEmployee(name = "")).copy(name = name.trim(), phone = phone.trim()))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun NameEditDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name *") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onSave(name.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** One dialog serves add and edit of a job with its usual price. */
@Composable
fun JobMasterDialog(existing: ServiceJobMaster?, onDismiss: () -> Unit, onSave: (ServiceJobMaster) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var price by remember { mutableStateOf(if (existing != null && existing.price != 0.0) Format.money(existing.price) else "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New job" else "Edit job") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Job name *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = price, onValueChange = { v -> price = v.filter { it.isDigit() || it == '.' } },
                    label = { Text("Price") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) onSave((existing ?: ServiceJobMaster(name = "")).copy(name = name.trim(), price = price.toDoubleOrNull() ?: 0.0))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
