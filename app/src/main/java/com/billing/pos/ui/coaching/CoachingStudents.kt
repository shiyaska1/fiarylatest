package com.billing.pos.ui.coaching

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.mutableStateListOf
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
import com.billing.pos.data.CoachFee
import com.billing.pos.data.CoachStudent
import com.billing.pos.data.CoachingClass
import com.billing.pos.data.CoachingRepository
import com.billing.pos.data.Course
import com.billing.pos.data.PayMode
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

internal fun coachPickDate(context: android.content.Context, current: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = current }
    android.app.DatePickerDialog(context, { _, y, m, d ->
        onPicked(Calendar.getInstance().apply { set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis)
    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
}

internal val INSTALL_OPTS = listOf("Monthly" to 12, "1" to 1, "2" to 2, "3" to 3, "6" to 6)

class CoachingStudentsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = CoachingRepository(app)
    val students = repo.students.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val fees = repo.allFees.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val courses = repo.courses.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val classes = repo.classes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachingStudentsScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, onNew: () -> Unit, vm: CoachingStudentsViewModel = viewModel()) {
    val students by vm.students.collectAsState()
    val fees by vm.fees.collectAsState()
    var query by remember { mutableStateOf("") }
    val dueBy = remember(fees) { fees.groupBy { it.studentId }.mapValues { e -> e.value.sumOf { it.amount - it.paidAmount } } }
    val filtered = students.filter { query.isBlank() || it.name.contains(query, true) || it.phone.contains(query) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Students") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary, titleContentColor = MaterialTheme.colorScheme.onPrimary, navigationIconContentColor = MaterialTheme.colorScheme.onPrimary)) },
        floatingActionButton = { FloatingActionButton(onClick = onNew) { Icon(Icons.Filled.Add, "New admission") } }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search name / phone") }, singleLine = true, leadingIcon = { Icon(Icons.Filled.Search, null) }, modifier = Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
                items(filtered, key = { it.id }) { s ->
                    val due = dueBy[s.id] ?: 0.0
                    ListItem(headlineContent = { Text(s.name) }, supportingContent = { Text(s.phone) },
                        trailingContent = { if (due > 0.01) Text("Due ${Format.money(due)}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium) },
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(s.id) })
                    HorizontalDivider()
                }
            }
        }
    }
}

class CoachStudentViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = CoachingRepository(app)
    val student = MutableStateFlow<CoachStudent?>(null)
    val fees = MutableStateFlow<List<CoachFee>>(emptyList())
    val enrolledCourseIds = MutableStateFlow<Set<Long>>(emptySet())
    val courses = repo.courses.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val classes = repo.classes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val message = MutableStateFlow<String?>(null)
    private var loaded = -1L

    fun load(id: Long) {
        if (loaded == id) return
        loaded = id
        viewModelScope.launch {
            student.value = if (id == 0L) CoachStudent(name = "", joinDateMillis = System.currentTimeMillis()) else repo.studentById(id)
            fees.value = if (id != 0L) repo.feesFor(id) else emptyList()
            enrolledCourseIds.value = if (id != 0L) repo.coursesFor(id).map { it.courseId }.toSet() else emptySet()
        }
    }

    fun save(s: CoachStudent, courseIds: Set<Long>, onSaved: (Long) -> Unit) {
        if (s.name.isBlank()) { message.value = "Name required"; return }
        viewModelScope.launch {
            val selected = courses.value.filter { it.id in courseIds }
            val id = repo.saveStudent(s, selected)
            loaded = -1L; load(id); message.value = "Saved"; onSaved(id)
        }
    }

    fun collect(fee: CoachFee, amount: Double, mode: PayMode, name: String) {
        if (amount <= 0) { message.value = "Enter amount"; return }
        viewModelScope.launch { repo.collectFee(fee, amount, mode, name); loaded = -1L; load(student.value?.id ?: 0L); message.value = "Collected" }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CoachStudentScreen(studentId: Long, onBack: () -> Unit, vm: CoachStudentViewModel = viewModel()) {
    val context = LocalContext.current
    LaunchedEffect(studentId) { vm.load(studentId) }
    val student by vm.student.collectAsState()
    val fees by vm.fees.collectAsState()
    val courses by vm.courses.collectAsState()
    val classes by vm.classes.collectAsState()
    val enrolled by vm.enrolledCourseIds.collectAsState()
    val message by vm.message.collectAsState()

    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf("") }
    var guardian by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var classId by remember { mutableStateOf(0L) }
    var admissionFee by remember { mutableStateOf("") }
    var totalFee by remember { mutableStateOf("") }
    var installLabel by remember { mutableStateOf("1") }
    var joinDate by remember { mutableStateOf(System.currentTimeMillis()) }
    val selectedCourses = remember { mutableStateListOf<Long>() }
    var populated by remember { mutableStateOf(-2L) }

    LaunchedEffect(student?.id, enrolled) {
        val s = student ?: return@LaunchedEffect
        if (populated == s.id) return@LaunchedEffect
        populated = s.id
        name = s.name; phone = s.phone; gender = s.gender; guardian = s.guardian; address = s.address; classId = s.classId
        admissionFee = if (s.admissionFee > 0) Format.money(s.admissionFee) else ""
        totalFee = if (s.totalFee > 0) Format.money(s.totalFee) else ""
        installLabel = INSTALL_OPTS.firstOrNull { it.second == s.installments }?.first ?: "1"
        joinDate = s.joinDateMillis
        selectedCourses.clear(); selectedCourses.addAll(enrolled)
    }

    var genderMenu by remember { mutableStateOf(false) }
    var classMenu by remember { mutableStateOf(false) }
    var installMenu by remember { mutableStateOf(false) }
    var collectFor by remember { mutableStateOf<CoachFee?>(null) }

    collectFor?.let { fee ->
        var amt by remember { mutableStateOf(Format.money(fee.amount - fee.paidAmount)) }
        var mode by remember { mutableStateOf(PayMode.CASH) }
        AlertDialog(onDismissRequest = { collectFor = null }, title = { Text("Collect fee") },
            text = {
                Column {
                    Text("${fee.kind} · balance ${Format.money(fee.amount - fee.paidAmount)}", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = amt, onValueChange = { amt = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Amount") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { PayMode.values().forEach { pm -> FilterChip(selected = mode == pm, onClick = { mode = pm }, label = { Text(pm.label) }) } }
                }
            },
            confirmButton = { TextButton(onClick = { vm.collect(fee, amt.toDoubleOrNull() ?: 0.0, mode, name); collectFor = null }) { Text("Collect") } },
            dismissButton = { TextButton(onClick = { collectFor = null }) { Text("Cancel") } })
    }

    Scaffold(topBar = { TopAppBar(title = { Text(if (studentId == 0L) "New admission" else "Student") },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary, titleContentColor = MaterialTheme.colorScheme.onPrimary, navigationIconContentColor = MaterialTheme.colorScheme.onPrimary)) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = phone, onValueChange = { phone = it.filter { c -> c.isDigit() || c == '+' } }, label = { Text("Phone") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            OutlinedTextField(value = guardian, onValueChange = { guardian = it }, label = { Text("Guardian / parent") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Box(Modifier.padding(top = 8.dp)) {
                OutlinedTextField(value = gender, onValueChange = {}, readOnly = true, label = { Text("Gender") }, trailingIcon = { IconButton(onClick = { genderMenu = true }) { Icon(Icons.Filled.ArrowDropDown, null) } }, modifier = Modifier.fillMaxWidth())
                DropdownMenu(expanded = genderMenu, onDismissRequest = { genderMenu = false }) { listOf("Male", "Female", "Other").forEach { g -> DropdownMenuItem(text = { Text(g) }, onClick = { gender = g; genderMenu = false }) } }
            }
            OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Address") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Box(Modifier.padding(top = 8.dp)) {
                OutlinedTextField(value = classes.firstOrNull { it.id == classId }?.name ?: "", onValueChange = {}, readOnly = true, label = { Text("Class / batch") }, trailingIcon = { IconButton(onClick = { classMenu = true }) { Icon(Icons.Filled.ArrowDropDown, null) } }, modifier = Modifier.fillMaxWidth())
                DropdownMenu(expanded = classMenu, onDismissRequest = { classMenu = false }) { classes.forEach { c -> DropdownMenuItem(text = { Text(c.name) }, onClick = { classId = c.id; classMenu = false }) } }
            }
            Button(onClick = { coachPickDate(context, joinDate) { joinDate = it } }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Join date: ${Format.date(joinDate)}") }

            Text("Courses (tap to enrol)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                courses.forEach { c ->
                    val on = c.id in selectedCourses
                    FilterChip(selected = on, onClick = {
                        if (on) selectedCourses.remove(c.id) else selectedCourses.add(c.id)
                        val sel = courses.filter { it.id in selectedCourses }
                        admissionFee = Format.money(sel.sumOf { it.admissionFee })
                        totalFee = Format.money(sel.sumOf { it.totalFee })
                    }, label = { Text(c.name) })
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = admissionFee, onValueChange = { admissionFee = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Admission fee") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                OutlinedTextField(value = totalFee, onValueChange = { totalFee = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Total fee") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
            }
            Box(Modifier.padding(top = 8.dp)) {
                OutlinedTextField(value = if (installLabel == "Monthly") "Monthly" else "$installLabel installment(s)", onValueChange = {}, readOnly = true, label = { Text("Fee schedule") }, trailingIcon = { IconButton(onClick = { installMenu = true }) { Icon(Icons.Filled.ArrowDropDown, null) } }, modifier = Modifier.fillMaxWidth())
                DropdownMenu(expanded = installMenu, onDismissRequest = { installMenu = false }) { INSTALL_OPTS.forEach { (l, _) -> DropdownMenuItem(text = { Text(if (l == "Monthly") "Monthly" else "$l installment(s)") }, onClick = { installLabel = l; installMenu = false }) } }
            }

            Button(onClick = {
                val s = (student ?: CoachStudent(name = "", joinDateMillis = joinDate)).copy(
                    name = name.trim(), phone = phone.trim(), gender = gender, guardian = guardian.trim(), address = address.trim(),
                    classId = classId, admissionFee = admissionFee.toDoubleOrNull() ?: 0.0, totalFee = totalFee.toDoubleOrNull() ?: 0.0,
                    installments = INSTALL_OPTS.firstOrNull { it.first == installLabel }?.second ?: 1, joinDateMillis = joinDate
                )
                vm.save(s, selectedCourses.toSet()) {}
            }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Save") }

            message?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }

            if (studentId != 0L && fees.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text("Fee schedule  ·  balance ${Format.money(fees.sumOf { it.amount - it.paidAmount })}", style = MaterialTheme.typography.titleSmall)
                fees.forEach { fee ->
                    val bal = fee.amount - fee.paidAmount
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(fee.kind, style = MaterialTheme.typography.bodyMedium)
                            Text("Due ${Format.date(fee.dueDateMillis)} · ${Format.money(fee.amount)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
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

class CoachDueViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = CoachingRepository(app)
    val students = repo.students.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val fees = repo.allFees.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachDueReportScreen(onBack: () -> Unit, vm: CoachDueViewModel = viewModel()) {
    val students by vm.students.collectAsState()
    val fees by vm.fees.collectAsState()
    val nameById = students.associate { it.id to it.name }
    val today = System.currentTimeMillis()
    val rows = fees.filter { it.amount - it.paidAmount > 0.01 }.sortedBy { it.dueDateMillis }
    val total = rows.sumOf { it.amount - it.paidAmount }

    Scaffold(topBar = { TopAppBar(title = { Text("Fees Due") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary, titleContentColor = MaterialTheme.colorScheme.onPrimary, navigationIconContentColor = MaterialTheme.colorScheme.onPrimary)) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
            Text("Total due ${Format.money(total)}", style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows) { f ->
                    val overdue = f.dueDateMillis < today
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(nameById[f.studentId] ?: "?", style = MaterialTheme.typography.bodyMedium)
                            Text("${f.kind} · due ${Format.date(f.dueDateMillis)}", style = MaterialTheme.typography.labelSmall, color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline)
                        }
                        Text(Format.money(f.amount - f.paidAmount), style = MaterialTheme.typography.bodyMedium)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
