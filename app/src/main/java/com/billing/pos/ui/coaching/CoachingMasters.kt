package com.billing.pos.ui.coaching

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.CoachStaff
import com.billing.pos.data.CoachSubject
import com.billing.pos.data.CoachingClass
import com.billing.pos.data.CoachingRepository
import com.billing.pos.data.Course
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CoachingMastersViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = CoachingRepository(app)
    val courses = repo.courses.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val classes = repo.classes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val subjects = repo.subjects.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val staff = repo.staff.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun saveCourse(c: Course) = viewModelScope.launch { repo.saveCourse(c) }
    fun saveClass(c: CoachingClass) = viewModelScope.launch { repo.saveClass(c) }
    fun saveSubject(s: CoachSubject) = viewModelScope.launch { repo.saveSubject(s) }
    fun saveStaff(s: CoachStaff) = viewModelScope.launch { repo.saveStaff(s) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachingMastersScreen(onBack: () -> Unit, vm: CoachingMastersViewModel = viewModel()) {
    val courses by vm.courses.collectAsState()
    val classes by vm.classes.collectAsState()
    val subjects by vm.subjects.collectAsState()
    val staff by vm.staff.collectAsState()
    var tab by remember { mutableStateOf("Courses") }
    var editCourse by remember { mutableStateOf<Course?>(null) }
    var editClass by remember { mutableStateOf<CoachingClass?>(null) }
    var editSubject by remember { mutableStateOf<CoachSubject?>(null) }
    var editStaff by remember { mutableStateOf<CoachStaff?>(null) }
    var showDialog by remember { mutableStateOf(false) }

    editCourse?.takeIf { showDialog && tab == "Courses" }?.let { c ->
        CourseDialog(c, { vm.saveCourse(it); showDialog = false }, { showDialog = false })
    }
    editClass?.takeIf { showDialog && tab == "Classes" }?.let { c ->
        NameDialog("Class", c.name, { vm.saveClass(c.copy(name = it)); showDialog = false }, { showDialog = false })
    }
    editSubject?.takeIf { showDialog && tab == "Subjects" }?.let { s ->
        SubjectDialog(s, classes, staff, { vm.saveSubject(it); showDialog = false }, { showDialog = false })
    }
    editStaff?.takeIf { showDialog && tab == "Staff" }?.let { s ->
        StaffDialog(s, { vm.saveStaff(it); showDialog = false }, { showDialog = false })
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Coaching Masters") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary, titleContentColor = MaterialTheme.colorScheme.onPrimary, navigationIconContentColor = MaterialTheme.colorScheme.onPrimary))
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                when (tab) {
                    "Courses" -> editCourse = Course(name = "")
                    "Classes" -> editClass = CoachingClass(name = "")
                    "Subjects" -> editSubject = CoachSubject(name = "")
                    else -> editStaff = CoachStaff(name = "")
                }
                showDialog = true
            }) { Icon(Icons.Filled.Add, "Add") }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Courses", "Classes", "Subjects", "Staff").forEach { t ->
                    FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t) })
                }
            }
            val classNames = remember(classes) { classes.associate { it.id to it.name } }
            val staffNames = remember(staff) { staff.associate { it.id to it.name } }
            LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
                when (tab) {
                    "Courses" -> items(courses, key = { it.id }) { c ->
                        MasterRow(c.name, "Fee ${Format.money(c.totalFee)}  ·  ${c.durationMonths} mo") { editCourse = c; showDialog = true }
                    }
                    "Classes" -> items(classes, key = { it.id }) { c -> MasterRow(c.name, "") { editClass = c; showDialog = true } }
                    "Subjects" -> items(subjects, key = { it.id }) { s ->
                        MasterRow(s.name, listOfNotNull(classNames[s.classId], staffNames[s.staffId]).joinToString("  ·  ")) { editSubject = s; showDialog = true }
                    }
                    else -> items(staff, key = { it.id }) { s -> MasterRow(s.name, listOfNotNull(s.designation.ifBlank { null }, s.phone.ifBlank { null }).joinToString("  ·  ")) { editStaff = s; showDialog = true } }
                }
            }
        }
    }
}

@Composable
private fun MasterRow(title: String, sub: String, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(title) }, supportingContent = { if (sub.isNotBlank()) Text(sub) },
        modifier = Modifier.fillMaxWidth().clickable { onClick() })
    HorizontalDivider()
}

@Composable
private fun NameDialog(label: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (initial.isBlank()) "New $label" else "Edit $label") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("$label name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onSave(name.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun CourseDialog(initial: Course, onSave: (Course) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var dur by remember { mutableStateOf(if (initial.durationMonths > 0) initial.durationMonths.toString() else "") }
    var adm by remember { mutableStateOf(if (initial.admissionFee > 0) Format.money(initial.admissionFee) else "") }
    var total by remember { mutableStateOf(if (initial.totalFee > 0) Format.money(initial.totalFee) else "") }
    var desc by remember { mutableStateOf(initial.description) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (initial.id == 0L) "New course" else "Edit course") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Course name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = dur, onValueChange = { dur = it.filter { c -> c.isDigit() } }, label = { Text("Duration (months)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(value = adm, onValueChange = { adm = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Admission fee") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(value = total, onValueChange = { total = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Total fee") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onSave(initial.copy(name = name.trim(), durationMonths = dur.toIntOrNull() ?: 0, admissionFee = adm.toDoubleOrNull() ?: 0.0, totalFee = total.toDoubleOrNull() ?: 0.0, description = desc.trim())) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun StaffDialog(initial: CoachStaff, onSave: (CoachStaff) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var phone by remember { mutableStateOf(initial.phone) }
    var desig by remember { mutableStateOf(initial.designation) }
    var addr by remember { mutableStateOf(initial.address) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (initial.id == 0L) "New staff" else "Edit staff") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = phone, onValueChange = { phone = it.filter { c -> c.isDigit() } }, label = { Text("Phone") }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(value = desig, onValueChange = { desig = it }, label = { Text("Designation") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(value = addr, onValueChange = { addr = it }, label = { Text("Address") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onSave(initial.copy(name = name.trim(), phone = phone.trim(), designation = desig.trim(), address = addr.trim())) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubjectDialog(initial: CoachSubject, classes: List<CoachingClass>, staff: List<CoachStaff>, onSave: (CoachSubject) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var classId by remember { mutableStateOf(initial.classId) }
    var staffId by remember { mutableStateOf(initial.staffId) }
    var classMenu by remember { mutableStateOf(false) }
    var staffMenu by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (initial.id == 0L) "New subject" else "Edit subject") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Subject name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Box(Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(value = classes.firstOrNull { it.id == classId }?.name ?: "", onValueChange = {}, readOnly = true, label = { Text("Class") },
                        trailingIcon = { IconButton(onClick = { classMenu = true }) { Icon(Icons.Filled.ArrowDropDown, null) } }, modifier = Modifier.fillMaxWidth())
                    DropdownMenu(expanded = classMenu, onDismissRequest = { classMenu = false }) {
                        classes.forEach { c -> DropdownMenuItem(text = { Text(c.name) }, onClick = { classId = c.id; classMenu = false }) }
                    }
                }
                Box(Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(value = staff.firstOrNull { it.id == staffId }?.name ?: "", onValueChange = {}, readOnly = true, label = { Text("Staff / teacher") },
                        trailingIcon = { IconButton(onClick = { staffMenu = true }) { Icon(Icons.Filled.ArrowDropDown, null) } }, modifier = Modifier.fillMaxWidth())
                    DropdownMenu(expanded = staffMenu, onDismissRequest = { staffMenu = false }) {
                        staff.forEach { s -> DropdownMenuItem(text = { Text(s.name) }, onClick = { staffId = s.id; staffMenu = false }) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onSave(initial.copy(name = name.trim(), classId = classId, staffId = staffId)) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
