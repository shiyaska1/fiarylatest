package com.billing.pos.ui.gym

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.GymFee
import com.billing.pos.data.GymMember
import com.billing.pos.data.GymRepository
import com.billing.pos.data.PayMode
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

internal val INSTALLMENT_OPTIONS = listOf("Monthly" to 12, "1" to 1, "2" to 2, "3" to 3, "6" to 6)

class GymMembersViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = GymRepository(app)
    val members = repo.members.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val fees = repo.allFees.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GymMembersScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, onNew: () -> Unit, vm: GymMembersViewModel = viewModel()) {
    val members by vm.members.collectAsState()
    val fees by vm.fees.collectAsState()
    var query by remember { mutableStateOf("") }
    val dueByMember = remember(fees) { fees.groupBy { it.memberId }.mapValues { e -> e.value.sumOf { it.amount - it.paidAmount } } }

    val filtered = members.filter { query.isBlank() || it.name.contains(query, true) || it.phone.contains(query) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Members") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = onNew) { Icon(Icons.Filled.Add, "New admission") } }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search name / phone") },
                singleLine = true, leadingIcon = { Icon(Icons.Filled.Search, null) }, modifier = Modifier.fillMaxWidth())
            Text("${filtered.size} member(s)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 8.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(filtered, key = { it.id }) { m ->
                    val due = dueByMember[m.id] ?: 0.0
                    ListItem(
                        headlineContent = { Text(m.name) },
                        supportingContent = { Text(listOfNotNull(m.phone.ifBlank { null }, m.slot.ifBlank { null }).joinToString("  ·  ")) },
                        trailingContent = { if (due > 0.01) Text("Due ${Format.money(due)}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium) },
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(m.id) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

class GymMemberViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = GymRepository(app)
    val member = MutableStateFlow<GymMember?>(null)
    val fees = MutableStateFlow<List<GymFee>>(emptyList())
    val message = MutableStateFlow<String?>(null)
    private var loadedId = -1L

    fun load(id: Long) {
        if (loadedId == id) return
        loadedId = id
        viewModelScope.launch {
            member.value = if (id == 0L) GymMember(name = "", joinDateMillis = System.currentTimeMillis())
            else repo.memberById(id)
            refreshFees(id)
        }
    }
    private suspend fun refreshFees(id: Long) { fees.value = if (id != 0L) repo.feesOnce(id) else emptyList() }

    fun save(m: GymMember, onSaved: (Long) -> Unit) {
        if (m.name.isBlank()) { message.value = "Name is required"; return }
        viewModelScope.launch {
            val id = repo.saveMember(m)
            loadedId = -1L; load(id)
            message.value = "Saved"
            onSaved(id)
        }
    }

    fun collect(fee: GymFee, amount: Double, mode: PayMode, memberName: String) {
        if (amount <= 0) { message.value = "Enter amount"; return }
        viewModelScope.launch {
            repo.collectFee(fee, amount, mode, memberName)
            loadedId = -1L; load(member.value?.id ?: 0L)
            message.value = "Fee collected"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GymMemberScreen(memberId: Long, onBack: () -> Unit, vm: GymMemberViewModel = viewModel()) {
    val context = LocalContext.current
    LaunchedEffect(memberId) { vm.load(memberId) }
    val member by vm.member.collectAsState()
    val fees by vm.fees.collectAsState()
    val message by vm.message.collectAsState()

    // form state, populated once the member loads
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf("") }
    var admissionFee by remember { mutableStateOf("") }
    var totalFee by remember { mutableStateOf("") }
    var installLabel by remember { mutableStateOf("1") }
    var slot by remember { mutableStateOf("") }
    var trainer by remember { mutableStateOf("") }
    var joinDate by remember { mutableStateOf(System.currentTimeMillis()) }
    var populatedFor by remember { mutableStateOf(-2L) }

    LaunchedEffect(member?.id) {
        val m = member ?: return@LaunchedEffect
        if (populatedFor == m.id) return@LaunchedEffect
        populatedFor = m.id
        name = m.name; phone = m.phone; gender = m.gender; address = m.address; plan = m.plan
        admissionFee = if (m.admissionFee > 0) Format.money(m.admissionFee) else ""
        totalFee = if (m.totalFee > 0) Format.money(m.totalFee) else ""
        installLabel = INSTALLMENT_OPTIONS.firstOrNull { it.second == m.installments }?.first ?: "1"
        slot = m.slot; trainer = m.trainer; joinDate = m.joinDateMillis
    }

    var genderMenu by remember { mutableStateOf(false) }
    var installMenu by remember { mutableStateOf(false) }
    var slotMenu by remember { mutableStateOf(false) }
    var newSlot by remember { mutableStateOf(false) }
    var newSlotName by remember { mutableStateOf("") }
    var collectFor by remember { mutableStateOf<GymFee?>(null) }

    message?.let { LaunchedEffect(it) { /* transient */ } }

    if (newSlot) {
        AlertDialog(onDismissRequest = { newSlot = false }, title = { Text("New slot / time") },
            text = { OutlinedTextField(value = newSlotName, onValueChange = { newSlotName = it }, label = { Text("e.g. 6:00–7:00 AM") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { val n = newSlotName.trim(); if (n.isNotBlank()) { AppPrefs(context).addGymSlot(n); slot = n }; newSlotName = ""; newSlot = false }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { newSlot = false }) { Text("Cancel") } })
    }

    collectFor?.let { fee ->
        var amt by remember { mutableStateOf(Format.money(fee.amount - fee.paidAmount)) }
        var mode by remember { mutableStateOf(PayMode.CASH) }
        AlertDialog(
            onDismissRequest = { collectFor = null },
            title = { Text("Collect fee") },
            text = {
                Column {
                    Text("${fee.kind} · due ${Format.date(fee.dueDateMillis)} · balance ${Format.money(fee.amount - fee.paidAmount)}", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = amt, onValueChange = { amt = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Amount") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PayMode.values().forEach { pm -> FilterChip(selected = mode == pm, onClick = { mode = pm }, label = { Text(pm.label) }) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.collect(fee, amt.toDoubleOrNull() ?: 0.0, mode, name); collectFor = null }) { Text("Collect") } },
            dismissButton = { TextButton(onClick = { collectFor = null }) { Text("Cancel") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (memberId == 0L) "New admission" else "Member") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = phone, onValueChange = { phone = it.filter { c -> c.isDigit() || c == '+' } }, label = { Text("Phone") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Box(Modifier.padding(top = 8.dp)) {
                OutlinedTextField(value = gender, onValueChange = {}, readOnly = true, label = { Text("Gender") },
                    trailingIcon = { IconButton(onClick = { genderMenu = true }) { Icon(Icons.Filled.ArrowDropDown, null) } }, modifier = Modifier.fillMaxWidth())
                DropdownMenu(expanded = genderMenu, onDismissRequest = { genderMenu = false }) {
                    listOf("Male", "Female", "Other").forEach { g -> DropdownMenuItem(text = { Text(g) }, onClick = { gender = g; genderMenu = false }) }
                }
            }
            OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Address") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Button(onClick = { pickDate(context, joinDate) { joinDate = it } }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Join date: ${Format.date(joinDate)}") }
            OutlinedTextField(value = plan, onValueChange = { plan = it }, label = { Text("Plan / package") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = admissionFee, onValueChange = { admissionFee = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Admission fee") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                OutlinedTextField(value = totalFee, onValueChange = { totalFee = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Total fee") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
            }
            Box(Modifier.padding(top = 8.dp)) {
                OutlinedTextField(value = if (installLabel == "Monthly") "Monthly" else "$installLabel installment(s)", onValueChange = {}, readOnly = true, label = { Text("Fee schedule") },
                    trailingIcon = { IconButton(onClick = { installMenu = true }) { Icon(Icons.Filled.ArrowDropDown, null) } }, modifier = Modifier.fillMaxWidth())
                DropdownMenu(expanded = installMenu, onDismissRequest = { installMenu = false }) {
                    INSTALLMENT_OPTIONS.forEach { (label, _) -> DropdownMenuItem(text = { Text(if (label == "Monthly") "Monthly" else "$label installment(s)") }, onClick = { installLabel = label; installMenu = false }) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    OutlinedTextField(value = slot, onValueChange = {}, readOnly = true, label = { Text("Training slot / time") },
                        trailingIcon = { IconButton(onClick = { slotMenu = true }) { Icon(Icons.Filled.ArrowDropDown, null) } }, modifier = Modifier.fillMaxWidth())
                    DropdownMenu(expanded = slotMenu, onDismissRequest = { slotMenu = false }) {
                        AppPrefs(context).gymSlots.forEach { s -> DropdownMenuItem(text = { Text(s) }, onClick = { slot = s; slotMenu = false }) }
                    }
                }
                IconButton(onClick = { newSlot = true }) { Icon(Icons.Filled.Add, "New slot", tint = MaterialTheme.colorScheme.primary) }
            }
            OutlinedTextField(value = trainer, onValueChange = { trainer = it }, label = { Text("Trainer") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))

            Button(onClick = {
                val m = (member ?: GymMember(name = "", joinDateMillis = joinDate)).copy(
                    name = name.trim(), phone = phone.trim(), gender = gender, address = address.trim(), plan = plan.trim(),
                    admissionFee = admissionFee.toDoubleOrNull() ?: 0.0, totalFee = totalFee.toDoubleOrNull() ?: 0.0,
                    installments = INSTALLMENT_OPTIONS.firstOrNull { it.first == installLabel }?.second ?: 1,
                    slot = slot, trainer = trainer.trim(), joinDateMillis = joinDate
                )
                vm.save(m) {}
            }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Save") }

            message?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }

            if (memberId != 0L && fees.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                val totalDue = fees.sumOf { it.amount - it.paidAmount }
                Text("Fee schedule  ·  balance ${Format.money(totalDue)}", style = MaterialTheme.typography.titleSmall)
                fees.forEach { fee ->
                    val bal = fee.amount - fee.paidAmount
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(fee.kind, style = MaterialTheme.typography.bodyMedium)
                            Text("Due ${Format.date(fee.dueDateMillis)}  ·  ${Format.money(fee.amount)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                        if (bal > 0.01) Button(onClick = { collectFor = fee }) { Text("Collect") }
                        else Text("Paid", color = androidx.compose.ui.graphics.Color(0xFF2E7D32), style = MaterialTheme.typography.labelMedium)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

internal fun pickDate(context: android.content.Context, current: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = current }
    android.app.DatePickerDialog(context, { _, y, m, d ->
        onPicked(Calendar.getInstance().apply { set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis)
    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
}
