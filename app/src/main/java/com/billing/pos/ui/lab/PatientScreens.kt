package com.billing.pos.ui.lab

import android.app.Application
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.Patient
import com.billing.pos.data.Repository
import com.billing.pos.ui.billing.collectAsStateSafe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PatientViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    val patients: StateFlow<List<Patient>> = repo.patients.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val doctors: StateFlow<List<com.billing.pos.data.LabDoctor>> = repo.labDoctors.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }
    fun save(p: Patient) {
        viewModelScope.launch {
            repo.savePatient(p)
            if (p.referredBy.isNotBlank()) repo.addDoctorToMaster(p.referredBy)   // new doctor → master
            message.value = "Patient saved"
        }
    }
    fun delete(p: Patient) { viewModelScope.launch { repo.deletePatient(p); message.value = "Patient deleted" } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientListScreen(onBack: () -> Unit, vm: PatientViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    val patients by vm.patients.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    var editFor by remember { mutableStateOf<Patient?>(null) }
    var showNew by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Patients") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = { showNew = true }) { Icon(Icons.Filled.Add, "New") } }
    ) { pad ->
        if (patients.isEmpty()) Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { Text("No patients yet", color = MaterialTheme.colorScheme.outline) }
        else LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
            items(patients, key = { it.id }) { p ->
                Row(Modifier.fillMaxWidth().clickable { editFor = p }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontWeight = FontWeight.Bold)
                        Text(
                            listOfNotNull(
                                p.age.ifBlank { null }?.let { "Age $it" },
                                p.gender.ifBlank { null },
                                p.phone.ifBlank { null },
                                p.referredBy.ifBlank { null }?.let { "Dr. $it" }
                            ).joinToString("  •  "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                        )
                    }
                    IconButton(onClick = { vm.delete(p) }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                }
                Divider()
            }
        }
    }

    val doctors by vm.doctors.collectAsStateSafe()
    if (showNew) PatientDialog(null, doctors.map { it.name }, onDismiss = { showNew = false }, onSave = { vm.save(it); showNew = false })
    editFor?.let { p -> PatientDialog(p, doctors.map { it.name }, onDismiss = { editFor = null }, onSave = { vm.save(it); editFor = null }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientDialog(existing: Patient?, doctorNames: List<String>, onDismiss: () -> Unit, onSave: (Patient) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var age by remember { mutableStateOf(existing?.age ?: "") }
    var gender by remember { mutableStateOf(existing?.gender ?: "") }
    var phone by remember { mutableStateOf(existing?.phone ?: "") }
    var address by remember { mutableStateOf(existing?.address ?: "") }
    var referredBy by remember { mutableStateOf(existing?.referredBy ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New Patient" else "Edit Patient") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Patient name *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = age, onValueChange = { age = it }, label = { Text("Age") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = phone, onValueChange = { phone = it.filter { c -> c.isDigit() } }, label = { Text("Phone") }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), modifier = Modifier.weight(1.4f))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Sex:")
                    listOf("Male", "Female", "Other").forEach { g ->
                        FilterChip(selected = gender == g, onClick = { gender = g }, label = { Text(g) })
                    }
                }
                var docMenu by remember { mutableStateOf(false) }
                val docMatches = remember(referredBy, doctorNames) {
                    val q = referredBy.trim()
                    if (q.isBlank()) doctorNames.take(8) else doctorNames.filter { it.contains(q, true) && !it.equals(q, true) }.take(8)
                }
                ExposedDropdownMenuBox(expanded = docMenu && docMatches.isNotEmpty(), onExpandedChange = { docMenu = it }) {
                    OutlinedTextField(
                        value = referredBy, onValueChange = { referredBy = it; docMenu = true },
                        label = { Text("Referred by (doctor)") }, singleLine = true,
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = docMenu && docMatches.isNotEmpty(), onDismissRequest = { docMenu = false }) {
                        docMatches.forEach { d ->
                            DropdownMenuItem(text = { Text(d) }, onClick = { referredBy = d; docMenu = false })
                        }
                    }
                }
                OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Address") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    (existing ?: Patient(name = "")).copy(
                        name = name.trim(), age = age.trim(), gender = gender, phone = phone.trim(),
                        address = address.trim(), referredBy = referredBy.trim()
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
