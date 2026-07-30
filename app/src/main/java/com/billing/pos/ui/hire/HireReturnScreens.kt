package com.billing.pos.ui.hire

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import com.billing.pos.data.HireInvoice
import com.billing.pos.data.HireReturn
import com.billing.pos.data.HireReturnItem
import com.billing.pos.data.Repository
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One returnable line: what was hired, and how much the user is returning now. */
class ReturnLine(
    val itemId: Long,
    val name: String,
    val unit: String,
    val hiredQty: Double,
    qty: Double = 0.0
) {
    var qty by mutableStateOf(qty)
    val uid = nextUid()
    companion object { private var c = 0L; fun nextUid() = ++c }
}

class HireReturnViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    val hires: StateFlow<List<HireInvoice>> = repo.hireInvoices.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val returns: StateFlow<List<HireReturn>> = repo.hireReturns.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var selectedHire by mutableStateOf<HireInvoice?>(null); private set
    val lines: SnapshotStateList<ReturnLine> = mutableStateListOf()
    var remarks by mutableStateOf("")
    var returnNo by mutableStateOf("HRR-0001"); private set
    var dateMillis by mutableStateOf(System.currentTimeMillis())
    var editingId by mutableStateOf<Long?>(null); private set
    private var loaded = false

    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    init { viewModelScope.launch { returnNo = repo.nextHireReturnNo() } }

    /** Pick a hire invoice → load its items with the still-outstanding quantity as the default. */
    fun selectHire(h: HireInvoice) {
        selectedHire = h
        viewModelScope.launch {
            val hired = repo.hireInvoiceLines(h.id)
            lines.clear()
            hired.forEach { lines.add(ReturnLine(it.itemId, it.name, it.unit, it.qty, it.qty)) }
        }
    }

    fun load(id: Long) {
        if (loaded || id <= 0) { loaded = true; return }
        loaded = true
        viewModelScope.launch {
            val r = repo.hireReturnById(id) ?: return@launch
            editingId = r.id; returnNo = r.returnNo; dateMillis = r.dateMillis; remarks = r.remarks
            selectedHire = repo.hireInvoiceById(r.hireId)
            val hired = repo.hireInvoiceLines(r.hireId).associateBy { it.name.lowercase() }
            lines.clear()
            repo.hireReturnLines(id).forEach {
                val h = hired[it.name.lowercase()]
                lines.add(ReturnLine(it.itemId, it.name, it.unit, h?.qty ?: it.qty, it.qty))
            }
        }
    }

    fun newReturn() {
        selectedHire = null; lines.clear(); remarks = ""; dateMillis = System.currentTimeMillis(); editingId = null
        viewModelScope.launch { returnNo = repo.nextHireReturnNo() }
    }

    fun save(onDone: () -> Unit) {
        val h = selectedHire
        if (h == null) { message.value = "Select a hire invoice"; return }
        val picked = lines.filter { it.qty > 0 }
        if (picked.isEmpty()) { message.value = "Enter a return quantity"; return }
        viewModelScope.launch {
            val r = HireReturn(
                id = editingId ?: 0, returnNo = returnNo, dateMillis = dateMillis,
                hireId = h.id, hireNo = h.hireNo, customerId = h.customerId, customerName = h.customerName,
                remarks = remarks.trim()
            )
            val rl = picked.map { HireReturnItem(0, r.id, it.itemId, it.name, it.qty, it.unit) }
            if (editingId != null) { repo.updateHireReturn(r, rl); message.value = "Return $returnNo updated" }
            else { repo.saveHireReturn(r, rl); editingId = null; message.value = "Return $returnNo saved" }
            onDone()
        }
    }

    fun delete(r: HireReturn) { viewModelScope.launch { repo.deleteHireReturn(r); message.value = "Return ${r.returnNo} deleted" } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HireReturnScreen(editId: Long?, onBack: () -> Unit, vm: HireReturnViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    val hires by vm.hires.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    LaunchedEffect(Unit) { if (editId != null && editId > 0) vm.load(editId) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (vm.editingId != null) "Edit Hire Return" else "Hire Return") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
            var hireMenu by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = hireMenu, onExpandedChange = { hireMenu = !hireMenu }) {
                OutlinedTextField(
                    readOnly = true,
                    value = vm.selectedHire?.let { "${it.hireNo} — ${it.customerName}" } ?: "",
                    onValueChange = {}, label = { Text("Against hire invoice") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(hireMenu) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = hireMenu, onDismissRequest = { hireMenu = false }) {
                    hires.forEach { h ->
                        DropdownMenuItem(
                            text = { Text("${h.hireNo} — ${h.customerName}") },
                            onClick = { vm.selectHire(h); hireMenu = false }
                        )
                    }
                }
            }

            Card(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
                if (vm.lines.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Pick a hire invoice to see its items", color = MaterialTheme.colorScheme.outline)
                } else LazyColumn(Modifier.padding(8.dp)) {
                    itemsIndexed(vm.lines, key = { _, l -> l.uid }) { _, line ->
                        var qtyText by remember(line.uid) { mutableStateOf(if (line.qty > 0) Format.qty(line.qty) else "") }
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(line.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(
                                    "Hired: ${Format.qty(line.hiredQty)}" + if (line.unit.isNotBlank()) " ${line.unit}" else "",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline
                                )
                            }
                            OutlinedTextField(
                                value = qtyText,
                                onValueChange = { v -> val f = v.filter { it.isDigit() || it == '.' }; qtyText = f; line.qty = f.toDoubleOrNull() ?: 0.0 },
                                label = { Text("Return") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.width(96.dp)
                            )
                        }
                        Divider()
                    }
                }
            }
            OutlinedTextField(value = vm.remarks, onValueChange = { vm.remarks = it }, label = { Text("Remarks") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Button(onClick = { vm.save { } }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Save return") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HireReturnListScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, onNew: () -> Unit, vm: HireReturnViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    val returns by vm.returns.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    var deleteFor by remember { mutableStateOf<HireReturn?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Hire Returns") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = onNew) { Icon(Icons.Filled.Add, "New") } }
    ) { pad ->
        if (returns.isEmpty()) Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { Text("No hire returns yet", color = MaterialTheme.colorScheme.outline) }
        else LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
            items(returns, key = { it.id }) { r ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(r.id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.returnNo, fontWeight = FontWeight.Bold)
                        Text("${r.customerName} • vs ${r.hireNo} • ${Format.date(r.dateMillis)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    IconButton(onClick = { deleteFor = r }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                }
                Divider()
            }
        }
    }

    deleteFor?.let { r ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete ${r.returnNo}?") },
            confirmButton = { TextButton(onClick = { vm.delete(r); deleteFor = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } }
        )
    }
}
