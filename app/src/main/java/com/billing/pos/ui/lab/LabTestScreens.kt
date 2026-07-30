package com.billing.pos.ui.lab

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.LabEvaluation
import com.billing.pos.data.LabTest
import com.billing.pos.data.Repository
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One editable evaluation row in the test editor. */
class EvalRow(name: String = "", unit: String = "", normal: String = "", group: String = "", isHeading: Boolean = false, val isPageBreak: Boolean = false) {
    var name by mutableStateOf(name)
    var unit by mutableStateOf(unit)
    var normal by mutableStateOf(normal)
    var group by mutableStateOf(group)
    var isHeading by mutableStateOf(isHeading)
    val uid = next()
    companion object { private var c = 0L; fun next() = ++c }
}

class LabTestViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    val tests: StateFlow<List<LabTest>> = repo.labTests.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val groups: StateFlow<List<com.billing.pos.data.LabGroup>> = repo.labGroups.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val evalMasters: StateFlow<List<com.billing.pos.data.LabEvalMaster>> = repo.labEvalMasters.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val headings: StateFlow<List<com.billing.pos.data.LabHeading>> = repo.labHeadings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addFromMaster(e: com.billing.pos.data.LabEvalMaster) {
        if (evals.none { it.name.equals(e.name, true) && !it.isHeading }) evals.add(EvalRow(e.name, e.unit, e.normalValue, e.groupName))
    }
    fun addGroupEvals(groupName: String) {
        viewModelScope.launch { repo.labEvalsInGroup(groupName).forEach { addFromMaster(it) } }
    }
    fun addHeading() { evals.add(EvalRow(isHeading = true)) }
    fun addHeadingFromMaster(name: String) { evals.add(EvalRow(name = name, isHeading = true)) }
    fun addPageBreak() { evals.add(EvalRow(isPageBreak = true)) }

    var name by mutableStateOf("")
    var priceText by mutableStateOf("")
    var sampleType by mutableStateOf("")
    val evals: SnapshotStateList<EvalRow> = mutableStateListOf()
    var editingId by mutableStateOf<Long?>(null); private set
    private var loaded = false

    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    init {
        // Seed the built-in sample tests the first time.
        viewModelScope.launch { val n = repo.seedSampleLabTests(); if (n > 0) message.value = "Loaded $n sample tests" }
    }

    fun addEval() { evals.add(EvalRow()) }
    fun removeEval(i: Int) { if (i in evals.indices) evals.removeAt(i) }

    fun newTest() { name = ""; priceText = ""; sampleType = ""; evals.clear(); evals.add(EvalRow()); editingId = null }

    fun load(id: Long) {
        if (loaded || id <= 0) { loaded = true; return }
        loaded = true
        viewModelScope.launch {
            val t = repo.labTestById(id) ?: return@launch
            editingId = t.id; name = t.name; priceText = Format.money(t.price); sampleType = t.sampleType
            evals.clear()
            repo.labEvaluationsFor(id).forEach { evals.add(EvalRow(it.name, it.unit, it.normalValue, it.groupName, it.isHeading, it.isPageBreak)) }
            if (evals.isEmpty()) evals.add(EvalRow())
        }
    }

    fun save(onDone: () -> Unit) {
        if (name.isBlank()) { message.value = "Enter a test name"; return }
        viewModelScope.launch {
            val t = LabTest(id = editingId ?: 0, name = name.trim(), price = priceText.toDoubleOrNull() ?: 0.0, sampleType = sampleType.trim())
            val list = evals.filter { it.name.isNotBlank() || it.isPageBreak }.map {
                LabEvaluation(testId = 0, name = it.name.trim(), unit = it.unit.trim(), normalValue = it.normal.trim(), groupName = it.group.trim(), isHeading = it.isHeading, isPageBreak = it.isPageBreak)
            }
            repo.saveLabTest(t, list)
            // New (typed-in) evaluations, groups and headings are saved to their masters.
            evals.filter { it.name.isNotBlank() && !it.isHeading && !it.isPageBreak }.forEach { repo.addEvalToMaster(it.name, it.unit, it.normal, it.group) }
            evals.filter { it.name.isNotBlank() && it.isHeading }.forEach { repo.addHeadingToMaster(it.name) }
            message.value = "Test saved"
            onDone()
        }
    }

    fun delete(t: LabTest) { viewModelScope.launch { repo.deleteLabTest(t); message.value = "Test deleted" } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabTestListScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, onNew: () -> Unit, onMasters: () -> Unit = {}, vm: LabTestViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    val tests by vm.tests.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    var deleteFor by remember { mutableStateOf<LabTest?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Lab Tests") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { TextButton(onClick = onMasters) { Text("Masters", color = MaterialTheme.colorScheme.onPrimary) } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = onNew) { Icon(Icons.Filled.Add, "New") } }
    ) { pad ->
        if (tests.isEmpty()) Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { Text("No tests yet", color = MaterialTheme.colorScheme.outline) }
        else LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
            items(tests, key = { it.id }) { t ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(t.id) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.name, fontWeight = FontWeight.Bold)
                        if (t.sampleType.isNotBlank()) Text(t.sampleType, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    Text(Format.rupee(t.price), fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { deleteFor = t }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                }
                Divider()
            }
        }
    }
    deleteFor?.let { t ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete ${t.name}?") },
            confirmButton = { TextButton(onClick = { vm.delete(t); deleteFor = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabTestEditScreen(editId: Long?, onBack: () -> Unit, vm: LabTestViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsStateSafe()
    var showPicker by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (editId != null && editId > 0) vm.load(editId) else if (vm.evals.isEmpty()) vm.newTest() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (vm.editingId != null) "Edit Test" else "New Test") },
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
                OutlinedTextField(value = vm.name, onValueChange = { vm.name = it }, label = { Text("Test name *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = vm.priceText, onValueChange = { vm.priceText = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Amount") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                    OutlinedTextField(value = vm.sampleType, onValueChange = { vm.sampleType = it }, label = { Text("Sample type") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Text("Evaluations (parameters)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                Text("Pick from the master, or type new ones (saved to the master on save). A heading row prints bold with no result.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Add, null); Text(" From master") }
                    OutlinedButton(onClick = { vm.addHeading() }, modifier = Modifier.weight(1f)) { Text("+ Heading") }
                    OutlinedButton(onClick = { vm.addPageBreak() }, modifier = Modifier.weight(1f)) { Text("+ Break") }
                }
            }
            itemsIndexed(vm.evals, key = { _, e -> e.uid }) { i, e ->
                if (e.isPageBreak) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Divider(Modifier.weight(1f))
                        Text("  PAGE BREAK  ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Divider(Modifier.weight(1f))
                        IconButton(onClick = { vm.removeEval(i) }) { Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.error) }
                    }
                } else Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = e.name, onValueChange = { e.name = it },
                                label = { Text(if (e.isHeading) "Heading text" else "Parameter") }, singleLine = true, modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { vm.removeEval(i) }) { Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.error) }
                        }
                        FilterChip(selected = e.isHeading, onClick = { e.isHeading = !e.isHeading }, label = { Text("Bold heading") })
                        if (!e.isHeading) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                                OutlinedTextField(value = e.unit, onValueChange = { e.unit = it }, label = { Text("Unit") }, singleLine = true, modifier = Modifier.weight(1f))
                                OutlinedTextField(value = e.normal, onValueChange = { e.normal = it }, label = { Text("Normal value") }, singleLine = true, modifier = Modifier.weight(1.4f))
                            }
                            OutlinedTextField(value = e.group, onValueChange = { e.group = it }, label = { Text("Group (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = { vm.addEval() }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Icon(Icons.Filled.Add, null); Text("  Add blank evaluation") }
                Button(onClick = { vm.save { onBack() } }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Save test") }
            }
        }
    }

    if (showPicker) {
        val groups by vm.groups.collectAsStateSafe()
        val masters by vm.evalMasters.collectAsStateSafe()
        val headings by vm.headings.collectAsStateSafe()
        LabMasterPickDialog(
            groups = groups, evals = masters, headings = headings,
            onPickGroup = { vm.addGroupEvals(it); showPicker = false },
            onPickEval = { vm.addFromMaster(it) },
            onPickHeading = { vm.addHeadingFromMaster(it) },
            onDismiss = { showPicker = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabMasterPickDialog(
    groups: List<com.billing.pos.data.LabGroup>,
    evals: List<com.billing.pos.data.LabEvalMaster>,
    headings: List<com.billing.pos.data.LabHeading>,
    onPickGroup: (String) -> Unit,
    onPickEval: (com.billing.pos.data.LabEvalMaster) -> Unit,
    onPickHeading: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var q by remember { mutableStateOf("") }
    val shownGroups = if (q.isBlank()) groups else groups.filter { it.name.contains(q, true) }
    val shownEvals = if (q.isBlank()) evals else evals.filter { it.name.contains(q, true) || it.groupName.contains(q, true) }
    val shownHeadings = if (q.isBlank()) headings else headings.filter { it.name.contains(q, true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick from master") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(value = q, onValueChange = { q = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                    if (shownGroups.isNotEmpty()) item { Text("Groups (adds all its evaluations)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 4.dp)) }
                    items(shownGroups, key = { "g" + it.id }) { g ->
                        Text("▸ ${g.name}", Modifier.fillMaxWidth().clickable { onPickGroup(g.name) }.padding(vertical = 10.dp), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        Divider()
                    }
                    if (shownHeadings.isNotEmpty()) item { Text("Headings (adds a bold heading)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 4.dp)) }
                    items(shownHeadings, key = { "h" + it.id }) { h ->
                        Text(h.name, Modifier.fillMaxWidth().clickable { onPickHeading(h.name) }.padding(vertical = 10.dp), fontWeight = FontWeight.Bold)
                        Divider()
                    }
                    if (shownEvals.isNotEmpty()) item { Text("Evaluations (tap to add)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 4.dp)) }
                    items(shownEvals, key = { "e" + it.id }) { e ->
                        Column(Modifier.fillMaxWidth().clickable { onPickEval(e) }.padding(vertical = 8.dp)) {
                            Text(e.name)
                            Text(listOfNotNull(e.unit.ifBlank { null }, e.normalValue.ifBlank { null }, e.groupName.ifBlank { null }).joinToString("  •  "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                        Divider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}
