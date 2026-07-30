package com.billing.pos.ui.journal

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.AccountHead
import com.billing.pos.data.JournalEntry
import com.billing.pos.data.JournalLine
import com.billing.pos.data.Repository
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.abs

data class JLine(val uid: Long, val headId: Long, val headName: String, val amount: Double, val isDebit: Boolean) {
    companion object {
        private var counter = 0L
        fun next(): Long = ++counter
    }
}

class JournalEntryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    val heads: StateFlow<List<AccountHead>> =
        repo.accountHeads.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val groups: StateFlow<List<com.billing.pos.data.AccountGroup>> =
        repo.accountGroups.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Creates a head from the inline "+" dialog and puts it straight on the line. */
    fun createHead(index: Int, name: String, groupId: Long, opening: Double, isDebit: Boolean) {
        if (name.isBlank()) { message.value = "Enter an account name"; return }
        if (groupId <= 0) { message.value = "Pick an account group"; return }
        viewModelScope.launch {
            val id = repo.addAccountHead(name, groupId, opening, isDebit)
            setHead(index, AccountHead(id = id, name = name.trim(), groupId = groupId, openingBalance = opening, openingIsDebit = isDebit))
            message.value = "Account created"
        }
    }

    var voucherNo by mutableStateOf("JV-0001"); private set
    var dateMillis by mutableStateOf(System.currentTimeMillis())
    var narration by mutableStateOf("")
    /** "" = don't show in cash book; else Cash/UPI/Card/Cheque. */
    var cashMode by mutableStateOf("")
    var cashIsIn by mutableStateOf(true)
    var editingId by mutableStateOf<Long?>(null); private set
    val lines: SnapshotStateList<JLine> = mutableStateListOf()

    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    private var loaded = false

    init {
        viewModelScope.launch {
            repo.ensureDefaults()
            voucherNo = repo.nextJournalNo()
        }
    }

    val totalDr: Double get() = lines.filter { it.isDebit }.sumOf { it.amount }
    val totalCr: Double get() = lines.filter { !it.isDebit }.sumOf { it.amount }
    val balanced: Boolean get() = totalDr > 0.001 && abs(totalDr - totalCr) < 0.01

    fun addLine(isDebit: Boolean) { lines.add(JLine(JLine.next(), 0, "", 0.0, isDebit)) }
    fun removeLine(index: Int) { lines.removeAt(index) }
    fun setHead(index: Int, head: AccountHead) {
        lines.getOrNull(index)?.let { lines[index] = it.copy(headId = head.id, headName = head.name) }
    }
    fun setAmount(index: Int, amount: Double) {
        lines.getOrNull(index)?.let { lines[index] = it.copy(amount = amount) }
    }
    fun setDrCr(index: Int, isDebit: Boolean) {
        lines.getOrNull(index)?.let { lines[index] = it.copy(isDebit = isDebit) }
    }

    fun load(id: Long) {
        if (loaded) return
        loaded = true
        if (id <= 0) { if (lines.isEmpty()) { addLine(true); addLine(false) }; return }
        viewModelScope.launch {
            val entry = repo.journalById(id) ?: return@launch
            val jlines = repo.journalLinesFor(id)
            editingId = entry.id
            voucherNo = entry.voucherNo
            dateMillis = entry.dateMillis
            narration = entry.narration
            cashMode = entry.cashMode
            cashIsIn = entry.cashIsIn
            lines.clear()
            jlines.forEach { lines.add(JLine(JLine.next(), it.headId, it.headName, it.amount, it.isDebit)) }
        }
    }

    fun save(onDone: () -> Unit) {
        val valid = lines.filter { it.headId > 0 && it.amount > 0 }
        if (valid.size < 2) { message.value = "Add at least two lines"; return }
        if (!balanced) { message.value = "Debit and credit must be equal"; return }
        viewModelScope.launch {
            val entry = JournalEntry(
                id = editingId ?: 0, voucherNo = voucherNo, dateMillis = dateMillis, narration = narration.trim(),
                cashMode = cashMode, cashIsIn = cashIsIn,
                cashAmount = if (cashMode.isNotBlank()) totalDr else 0.0
            )
            val jlines = valid.map { JournalLine(0, 0, it.headId, it.headName, it.amount, it.isDebit) }
            if (editingId != null) repo.updateJournal(entry, jlines) else repo.saveJournal(entry, jlines)
            message.value = "Journal saved"
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalEntryScreen(
    entryId: Long,
    onBack: () -> Unit,
    vm: JournalEntryViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val heads by vm.heads.collectAsStateSafe()
    val groups by vm.groups.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    var newHeadFor by remember { mutableStateOf(-1) }
    var newHeadName by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.load(entryId) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    if (newHeadFor >= 0) {
        NewAccountHeadDialog(
            initialName = newHeadName,
            groups = groups,
            onDismiss = { newHeadFor = -1 },
            onSave = { name, groupId, opening, isDebit ->
                vm.createHead(newHeadFor, name, groupId, opening, isDebit)
                newHeadFor = -1
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (vm.editingId != null) "Edit Journal" else "New Journal") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("No: ${vm.voucherNo}", fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { pickDate(context, vm.dateMillis) { vm.dateMillis = it } }) { Text(Format.date(vm.dateMillis)) }
            }
            OutlinedTextField(
                value = vm.narration, onValueChange = { vm.narration = it },
                label = { Text("Narration") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )

            Divider(Modifier.padding(vertical = 8.dp))

            vm.lines.forEachIndexed { index, line ->
                key(line.uid) {
                    var amtText by remember(line.uid) { mutableStateOf(if (line.amount != 0.0) Format.money(line.amount) else "") }
                    var expanded by remember { mutableStateOf(false) }
                    var headQuery by remember(line.uid) { mutableStateOf(line.headName) }
                    LaunchedEffect(line.headName) { if (!expanded) headQuery = line.headName }
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Type to search (five suggestions), or "+" to create the account.
                            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.weight(1f)) {
                                OutlinedTextField(
                                    value = headQuery,
                                    onValueChange = { headQuery = it; expanded = true },
                                    label = { Text("Account head") },
                                    placeholder = { Text("Search account") },
                                    singleLine = true,
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                                    modifier = Modifier.menuAnchor().fillMaxWidth()
                                        .onFocusChanged { fs -> if (!fs.isFocused && !expanded) headQuery = line.headName }
                                )
                                val matches = heads.filter { headQuery.isBlank() || it.name.contains(headQuery, true) }.take(5)
                                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false; headQuery = line.headName }) {
                                    matches.forEach { h ->
                                        DropdownMenuItem(text = { Text(h.name) }, onClick = { vm.setHead(index, h); headQuery = h.name; expanded = false })
                                    }
                                    if (matches.isEmpty()) DropdownMenuItem(text = { Text("No match") }, onClick = { expanded = false })
                                }
                            }
                            IconButton(onClick = { newHeadFor = index; newHeadName = headQuery.trim() }) {
                                Icon(Icons.Filled.Add, "New account", tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = { vm.removeLine(index) }) { Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.error) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            OutlinedButton(onClick = { vm.setDrCr(index, !line.isDebit) }) { Text(if (line.isDebit) "Dr" else "Cr") }
                            OutlinedTextField(
                                value = amtText,
                                onValueChange = { v -> val f = v.filter { it.isDigit() || it == '.' }; amtText = f; vm.setAmount(index, f.toDoubleOrNull() ?: 0.0) },
                                label = { Text("Amount") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.width(160.dp).padding(start = 8.dp)
                            )
                        }
                    }
                    Divider()
                }
            }

            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.addLine(true) }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Add, null); Text("Debit line") }
                OutlinedButton(onClick = { vm.addLine(false) }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Add, null); Text("Credit line") }
            }

            Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Total Debit"); Text(Format.rupee(vm.totalDr)) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Total Credit"); Text(Format.rupee(vm.totalCr)) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Difference", fontWeight = FontWeight.Bold)
                        Text(Format.rupee(vm.totalDr - vm.totalCr), fontWeight = FontWeight.Bold,
                            color = if (vm.balanced) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    }
                }
            }

            // Cash Book link: mark this voucher as a cash/bank movement so it appears in the Cash Book.
            Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Show in Cash Book", fontWeight = FontWeight.SemiBold)
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val modes = listOf("None" to "", "Cash" to "Cash", "UPI" to "UPI", "Card" to "Card", "Cheque" to "Cheque")
                        modes.forEach { (label, value) ->
                            FilterChip(selected = vm.cashMode == value, onClick = { vm.cashMode = value }, label = { Text(label) })
                        }
                    }
                    if (vm.cashMode.isNotBlank()) {
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = vm.cashIsIn, onClick = { vm.cashIsIn = true }, label = { Text("Received (in)") })
                            FilterChip(selected = !vm.cashIsIn, onClick = { vm.cashIsIn = false }, label = { Text("Paid (out)") })
                        }
                        Text(
                            "Cash Book amount: ${Format.rupee(vm.totalDr)}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            Button(onClick = { vm.save { onBack() } }, enabled = vm.balanced, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text("Save")
            }
        }
    }
}

private fun pickDate(context: Context, current: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = current }
    android.app.DatePickerDialog(
        context,
        { _, y, m, d -> c.set(Calendar.YEAR, y); c.set(Calendar.MONTH, m); c.set(Calendar.DAY_OF_MONTH, d); onPicked(c.timeInMillis) },
        c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
    ).show()
}

/** New account head from inside a journal line: name, group, opening balance. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewAccountHeadDialog(
    initialName: String,
    groups: List<com.billing.pos.data.AccountGroup>,
    onDismiss: () -> Unit,
    onSave: (name: String, groupId: Long, opening: Double, isDebit: Boolean) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var groupId by remember { mutableStateOf(0L) }
    var groupMenu by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf("") }
    var isDebit by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New account") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Account name *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ExposedDropdownMenuBox(expanded = groupMenu, onExpandedChange = { groupMenu = !groupMenu }) {
                    OutlinedTextField(
                        readOnly = true,
                        value = groups.firstOrNull { it.id == groupId }?.name ?: "Select group *",
                        onValueChange = {},
                        label = { Text("Account group") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(groupMenu) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = groupMenu, onDismissRequest = { groupMenu = false }) {
                        groups.forEach { g ->
                            DropdownMenuItem(text = { Text(g.name) }, onClick = { groupId = g.id; groupMenu = false })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = opening,
                        onValueChange = { v -> opening = v.filter { it.isDigit() || it == '.' } },
                        label = { Text("Opening balance") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = { isDebit = !isDebit }) { Text(if (isDebit) "Dr" else "Cr") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), groupId, opening.toDoubleOrNull() ?: 0.0, isDebit) }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
