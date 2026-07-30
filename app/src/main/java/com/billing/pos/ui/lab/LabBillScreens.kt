package com.billing.pos.ui.lab

import android.app.Application
import android.app.DatePickerDialog
import java.util.Calendar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Science
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
import com.billing.pos.data.LabBill
import com.billing.pos.data.LabBillTest
import com.billing.pos.data.LabTest
import com.billing.pos.data.Patient
import com.billing.pos.data.Repository
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.ui.materialout.MaterialOutLink
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** An aggregated used-material line with its cost. */
data class UsedMatRow(val name: String, val qty: Double, val unit: String, val cost: Double)

/** A selected test line on a lab bill (amount is editable). */
class BillTestRow(val testId: Long, val name: String, price: Double) {
    var priceText by mutableStateOf(Format.money(price))
    val price: Double get() = priceText.toDoubleOrNull() ?: 0.0
    val uid = next()
    companion object { private var c = 0L; fun next() = ++c }
}

class LabBillViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    val patients: StateFlow<List<Patient>> = repo.patients.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val tests: StateFlow<List<LabTest>> = repo.labTests.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val bills: StateFlow<List<LabBill>> = repo.labBills.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val doctors: StateFlow<List<com.billing.pos.data.LabDoctor>> = repo.labDoctors.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var selectedPatient by mutableStateOf<Patient?>(null)
    var referredBy by mutableStateOf("")
    var discountText by mutableStateOf("")
    var remarks by mutableStateOf("")
    var paymentMethod by mutableStateOf("Cash")
    var advanceText by mutableStateOf("")   // amount received now; blank = fully paid (non-credit)
    var billNo by mutableStateOf("LAB-0001"); private set
    var dateMillis by mutableStateOf(System.currentTimeMillis())
    val cart: SnapshotStateList<BillTestRow> = mutableStateListOf()
    var editingId by mutableStateOf<Long?>(null); private set
    private var loaded = false

    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    init { viewModelScope.launch { billNo = repo.nextLabBillNo() } }

    val subTotal get() = cart.sumOf { it.price }
    val discount get() = discountText.toDoubleOrNull() ?: 0.0
    val grandTotal get() = subTotal - discount

    fun pickPatient(p: Patient) { selectedPatient = p; if (referredBy.isBlank()) referredBy = p.referredBy }
    fun addTest(t: LabTest) { if (cart.none { it.testId == t.id }) cart.add(BillTestRow(t.id, t.name, t.price)) }
    fun removeTest(i: Int) { if (i in cart.indices) cart.removeAt(i) }

    fun load(id: Long) {
        if (loaded || id <= 0) { loaded = true; return }
        loaded = true
        viewModelScope.launch {
            val b = repo.labBillById(id) ?: return@launch
            editingId = b.id; billNo = b.billNo; dateMillis = b.dateMillis
            referredBy = b.referredBy; discountText = if (b.discount != 0.0) b.discount.toString() else ""; remarks = b.remarks
            paymentMethod = b.paymentMethod.ifBlank { "Cash" }
            advanceText = com.billing.pos.util.Format.money(b.paidAmount)
            selectedPatient = repo.patientById(b.patientId) ?: Patient(b.patientId, b.patientName, b.age, b.gender, b.patientPhone, referredBy = b.referredBy)
            cart.clear()
            repo.labBillTests(id).forEach { cart.add(BillTestRow(it.testId, it.testName, it.price)) }
        }
    }

    fun newBill() {
        selectedPatient = null; referredBy = ""; discountText = ""; remarks = ""; cart.clear()
        paymentMethod = "Cash"; advanceText = ""
        dateMillis = System.currentTimeMillis(); editingId = null
        viewModelScope.launch { billNo = repo.nextLabBillNo() }
    }

    fun save(onDone: () -> Unit) {
        val p = selectedPatient
        if (p == null) { message.value = "Select a patient"; return }
        if (cart.isEmpty()) { message.value = "Add at least one test"; return }
        viewModelScope.launch {
            val existing = editingId?.let { repo.labBillById(it) }
            val b = LabBill(
                id = editingId ?: 0, billNo = billNo, dateMillis = dateMillis,
                patientId = p.id, patientName = p.name, patientPhone = p.phone, age = p.age, gender = p.gender, referredBy = referredBy.trim(),
                subTotal = subTotal, discount = discount, grandTotal = grandTotal, remarks = remarks.trim(),
                resultEntered = existing?.resultEntered ?: false, resultDateMillis = existing?.resultDateMillis ?: 0,
                paymentMethod = paymentMethod,
                // Advance received now; blank means fully paid unless it's a credit bill.
                paidAmount = advanceText.toDoubleOrNull() ?: (if (paymentMethod == "Credit") 0.0 else grandTotal)
            )
            val id = repo.saveLabBill(b, cart.map { LabBillTest(0, b.id, it.testId, it.name, it.price) })
            if (referredBy.isNotBlank()) repo.addDoctorToMaster(referredBy)   // new doctor → master
            editingId = id
            message.value = "Bill $billNo saved"
            onDone()
        }
    }

    fun delete(b: LabBill) { viewModelScope.launch { repo.deleteLabBill(b); message.value = "Bill ${b.billNo} deleted" } }

    // ---- used materials for this bill ----
    var usedMaterials by mutableStateOf<List<UsedMatRow>>(emptyList()); private set
    var usedTotal by mutableStateOf(0.0); private set
    fun loadUsedMaterials() {
        val no = billNo
        viewModelScope.launch {
            val lines = repo.usedMaterialsForBill(no)
            val rates = repo.lastPurchaseRates()
            val rows = lines.groupBy { it.name.lowercase() }.map { (_, ls) ->
                val qty = ls.sumOf { it.qty }
                val rate = rates[ls.first().name.lowercase()] ?: 0.0
                UsedMatRow(ls.first().name, qty, ls.first().unit, qty * rate)
            }.sortedBy { it.name.lowercase() }
            usedMaterials = rows
            usedTotal = rows.sumOf { it.cost }
        }
    }

    /** Builds the result-invoice + test strings for the selected bills, for a material-out. */
    fun prepareMaterialOut(ids: Set<Long>, onReady: (refs: String, tests: String) -> Unit) {
        viewModelScope.launch {
            val sel = bills.value.filter { it.id in ids }
            val refs = sel.joinToString(", ") { it.billNo }
            val tests = sel.flatMap { repo.labBillTests(it.id).map { t -> t.testName } }.distinct().joinToString(", ")
            onReady(refs, tests)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabBillScreen(editId: Long?, onBack: () -> Unit, onNewMaterial: () -> Unit = {}, vm: LabBillViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    val patients by vm.patients.collectAsStateSafe()
    val tests by vm.tests.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    LaunchedEffect(Unit) { if (editId != null && editId > 0) vm.load(editId) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    var showTestPicker by remember { mutableStateOf(false) }
    var showUsedMaterials by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (vm.editingId != null) "Edit Lab Bill" else "Lab Bill") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (vm.editingId != null) TextButton(onClick = { vm.loadUsedMaterials(); showUsedMaterials = true }) {
                        Text("Used material", color = MaterialTheme.colorScheme.onPrimary)
                    }
                    IconButton(onClick = { vm.newBill() }) { Icon(Icons.Filled.NoteAdd, "New") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
            var patMenu by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = patMenu, onExpandedChange = { patMenu = !patMenu }) {
                OutlinedTextField(
                    readOnly = true, value = vm.selectedPatient?.name ?: "", onValueChange = {},
                    label = { Text("Patient") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(patMenu) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = patMenu, onDismissRequest = { patMenu = false }) {
                    patients.forEach { p -> DropdownMenuItem(text = { Text(p.name + (if (p.phone.isNotBlank()) " • ${p.phone}" else "")) }, onClick = { vm.pickPatient(p); patMenu = false }) }
                }
            }
            OutlinedTextField(value = vm.referredBy, onValueChange = { vm.referredBy = it }, label = { Text("Referred by (doctor)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            Button(onClick = { showTestPicker = true }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Icon(Icons.Filled.Add, null); Text("  Add test") }

            Card(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
                if (vm.cart.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No tests", color = MaterialTheme.colorScheme.outline) }
                else LazyColumn(Modifier.padding(8.dp)) {
                    itemsIndexed(vm.cart, key = { _, r -> r.uid }) { i, row ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(row.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            OutlinedTextField(value = row.priceText, onValueChange = { v -> row.priceText = v.filter { it.isDigit() || it == '.' } }, label = { Text("Amount") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(110.dp))
                            IconButton(onClick = { vm.removeTest(i) }) { Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.error) }
                        }
                        Divider()
                    }
                }
            }

            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("Sub Total", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline); Text(Format.rupee(vm.subTotal), fontWeight = FontWeight.SemiBold) }
                        OutlinedTextField(value = vm.discountText, onValueChange = { vm.discountText = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Disc.") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(100.dp))
                    }
                    Divider(Modifier.padding(vertical = 6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("TOTAL", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(Format.rupee(vm.grandTotal), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf("Cash", "UPI", "Card", "Credit").forEach { m ->
                            androidx.compose.material3.FilterChip(selected = vm.paymentMethod == m, onClick = { vm.paymentMethod = m }, label = { Text(m) })
                        }
                    }
                    OutlinedTextField(
                        value = vm.advanceText,
                        onValueChange = { vm.advanceText = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Amount received now (advance)") },
                        placeholder = { Text(if (vm.paymentMethod == "Credit") "0" else Format.money(vm.grandTotal)) },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    )
                    run {
                        val paid = vm.advanceText.toDoubleOrNull() ?: (if (vm.paymentMethod == "Credit") 0.0 else vm.grandTotal)
                        val bal = (vm.grandTotal - paid).coerceAtLeast(0.0)
                        if (bal > 0.0) Text("Balance ${Format.rupee(bal)} — collect later from Cash Book or Receipts", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Button(onClick = { vm.save { } }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Save bill") }
        }
    }

    if (showTestPicker) {
        AlertDialog(
            onDismissRequest = { showTestPicker = false },
            title = { Text("Add test") },
            text = {
                var q by remember { mutableStateOf("") }
                val shown = if (q.isBlank()) tests else tests.filter { it.name.contains(q, ignoreCase = true) }
                Column(Modifier.fillMaxWidth()) {
                    OutlinedTextField(value = q, onValueChange = { q = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Divider(Modifier.padding(vertical = 6.dp))
                    if (shown.isEmpty()) Text("No tests — add them in Lab Tests", color = MaterialTheme.colorScheme.outline)
                    else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        items(shown, key = { it.id }) { t ->
                            Row(Modifier.fillMaxWidth().clickable { vm.addTest(t); showTestPicker = false }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Science, null, tint = MaterialTheme.colorScheme.primary)
                                Text("  ${t.name}", Modifier.weight(1f))
                                Text(Format.rupee(t.price), color = MaterialTheme.colorScheme.outline)
                            }
                            Divider()
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTestPicker = false }) { Text("Close") } }
        )
    }
    if (showUsedMaterials) {
        AlertDialog(
            onDismissRequest = { showUsedMaterials = false },
            title = { Text("Used materials — ${vm.billNo}") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    if (vm.usedMaterials.isEmpty()) Text("No materials recorded for this bill yet.", color = MaterialTheme.colorScheme.outline)
                    else {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                            items(vm.usedMaterials, key = { it.name }) { r ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(r.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                        Text("Qty ${Format.qty(r.qty)}" + (if (r.unit.isNotBlank()) " ${r.unit}" else ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                    }
                                    Text(Format.rupee(r.cost), fontWeight = FontWeight.SemiBold)
                                }
                                Divider()
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Total cost", fontWeight = FontWeight.Bold)
                            Text(Format.rupee(vm.usedTotal), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    MaterialOutLink.refs = vm.billNo
                    MaterialOutLink.tests = vm.cart.joinToString(", ") { it.name }
                    showUsedMaterials = false
                    onNewMaterial()
                }) { Text("Add new material") }
            },
            dismissButton = { TextButton(onClick = { showUsedMaterials = false }) { Text("Close") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabBillListScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, onResult: (Long) -> Unit, onNew: () -> Unit, onUsedMaterials: () -> Unit = {}, vm: LabBillViewModel = viewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val bills by vm.bills.collectAsStateSafe()
    val doctors by vm.doctors.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    var deleteFor by remember { mutableStateOf<LabBill?>(null) }
    var selected by remember { mutableStateOf(setOf<Long>()) }

    // Filters: doctor, date range (default today), name/phone search.
    var docFilter by remember { mutableStateOf("") }
    var fromMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var toMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var query by remember { mutableStateOf("") }
    val filtered = bills.filter { b ->
        (docFilter.isBlank() || b.referredBy.equals(docFilter, ignoreCase = true)) &&
            (query.isBlank() || b.patientName.contains(query, true) || b.patientPhone.contains(query)) &&
            b.dateMillis >= labStartOfDay(fromMillis) && b.dateMillis <= labEndOfDay(toMillis)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Lab Bills") },
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
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (selected.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${selected.size} selected", Modifier.weight(1f))
                    TextButton(onClick = { selected = emptySet() }) { Text("Clear") }
                    Button(onClick = { vm.prepareMaterialOut(selected) { refs, tests -> MaterialOutLink.refs = refs; MaterialOutLink.tests = tests; selected = emptySet(); onUsedMaterials() } }) { Text("Used materials") }
                }
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("Search patient name or mobile") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { labPickDate(context, fromMillis) { fromMillis = it } }, modifier = Modifier.weight(1f)) { Text("From ${Format.date(fromMillis)}", maxLines = 1) }
                OutlinedButton(onClick = { labPickDate(context, toMillis) { toMillis = it } }, modifier = Modifier.weight(1f)) { Text("To ${Format.date(toMillis)}", maxLines = 1) }
            }
            var docMenu by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = docMenu, onExpandedChange = { docMenu = !docMenu }, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                OutlinedTextField(
                    readOnly = true, value = docFilter.ifBlank { "All doctors" }, onValueChange = {},
                    label = { Text("Referred doctor") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(docMenu) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = docMenu, onDismissRequest = { docMenu = false }) {
                    DropdownMenuItem(text = { Text("All doctors") }, onClick = { docFilter = ""; docMenu = false })
                    doctors.forEach { d -> DropdownMenuItem(text = { Text(d.name) }, onClick = { docFilter = d.name; docMenu = false }) }
                }
            }
            Divider()
            if (filtered.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No matching lab bills", color = MaterialTheme.colorScheme.outline) }
            else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            items(filtered, key = { it.id }) { b ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(
                        checked = b.id in selected,
                        onCheckedChange = { on -> selected = if (on) selected + b.id else selected - b.id }
                    )
                    Column(Modifier.weight(1f).clickable { onOpen(b.id) }) {
                        Text(b.billNo + "  •  " + b.patientName, fontWeight = FontWeight.Bold)
                        Text(
                            Format.date(b.dateMillis) + (if (b.referredBy.isNotBlank()) "  •  Dr. ${b.referredBy}" else "") +
                                (if (b.resultEntered) "  •  result ready" else "  •  result pending"),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (b.resultEntered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                    Text(Format.rupee(b.grandTotal), fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { onResult(b.id) }) { Text("Result") }
                    IconButton(onClick = { deleteFor = b }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                }
                Divider()
            }
            }
        }
    }
    deleteFor?.let { b ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete ${b.billNo}?") },
            confirmButton = { TextButton(onClick = { vm.delete(b); deleteFor = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } }
        )
    }
}

private fun labPickDate(context: android.content.Context, current: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = current }
    DatePickerDialog(context, { _, y, m, d ->
        onPicked(Calendar.getInstance().apply { set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis)
    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
}

private fun labStartOfDay(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun labEndOfDay(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis; set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
}.timeInMillis
