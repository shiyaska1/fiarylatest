package com.billing.pos.ui.coaching

import android.app.Application
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.CoachAttendance
import com.billing.pos.data.CoachStudent
import com.billing.pos.data.CoachSubject
import com.billing.pos.data.CoachingClass
import com.billing.pos.data.CoachingRepository
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CoachAttendanceViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = CoachingRepository(app)
    val classes = repo.classes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val subjects = repo.subjects.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val roster = MutableStateFlow<List<CoachStudent>>(emptyList())
    val message = MutableStateFlow<String?>(null)

    fun loadRoster(classId: Long, subjectId: Long, dateMillis: Long, onMarks: (Map<Long, Boolean>) -> Unit) {
        viewModelScope.launch {
            val students = repo.studentsInClass(classId)
            roster.value = students
            val existing = repo.attendanceForDay(classId, subjectId, dateMillis).associateBy { it.studentId }
            onMarks(students.associate { it.id to (existing[it.id]?.present ?: true) })
        }
    }

    fun save(classId: Long, subjectId: Long, staffId: Long, dateMillis: Long, marks: Map<Long, Boolean>) {
        if (marks.isEmpty()) { message.value = "No students in this class"; return }
        viewModelScope.launch { repo.saveAttendance(classId, subjectId, staffId, dateMillis, marks); message.value = "Attendance saved" }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachAttendanceScreen(onBack: () -> Unit, vm: CoachAttendanceViewModel = viewModel()) {
    val context = LocalContext.current
    val classes by vm.classes.collectAsState()
    val subjects by vm.subjects.collectAsState()
    val roster by vm.roster.collectAsState()
    val message by vm.message.collectAsState()

    var classId by remember { mutableStateOf(0L) }
    var subjectId by remember { mutableStateOf(0L) }
    var date by remember { mutableStateOf(System.currentTimeMillis()) }
    var classMenu by remember { mutableStateOf(false) }
    var subjMenu by remember { mutableStateOf(false) }
    val marks = remember { mutableStateMapOf<Long, Boolean>() }
    var loaded by remember { mutableStateOf(false) }

    val classSubjects = subjects.filter { it.classId == classId }
    val staffId = subjects.firstOrNull { it.id == subjectId }?.staffId ?: 0L

    Scaffold(topBar = { TopAppBar(title = { Text("Take Attendance") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary, titleContentColor = MaterialTheme.colorScheme.onPrimary, navigationIconContentColor = MaterialTheme.colorScheme.onPrimary)) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
            Box {
                OutlinedTextField(value = classes.firstOrNull { it.id == classId }?.name ?: "", onValueChange = {}, readOnly = true, label = { Text("Class") },
                    trailingIcon = { IconButton(onClick = { classMenu = true }) { Icon(Icons.Filled.ArrowDropDown, null) } }, modifier = Modifier.fillMaxWidth())
                DropdownMenu(expanded = classMenu, onDismissRequest = { classMenu = false }) {
                    classes.forEach { c -> DropdownMenuItem(text = { Text(c.name) }, onClick = { classId = c.id; subjectId = 0; loaded = false; classMenu = false }) }
                }
            }
            Box(Modifier.padding(top = 8.dp)) {
                OutlinedTextField(value = subjects.firstOrNull { it.id == subjectId }?.name ?: "", onValueChange = {}, readOnly = true, label = { Text("Subject") },
                    trailingIcon = { IconButton(onClick = { subjMenu = true }) { Icon(Icons.Filled.ArrowDropDown, null) } }, modifier = Modifier.fillMaxWidth())
                DropdownMenu(expanded = subjMenu, onDismissRequest = { subjMenu = false }) {
                    classSubjects.forEach { s -> DropdownMenuItem(text = { Text(s.name) }, onClick = { subjectId = s.id; loaded = false; subjMenu = false }) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { coachPickDate(context, date) { date = it; loaded = false } }, modifier = Modifier.weight(1f)) { Text(Format.date(date)) }
                Button(onClick = { if (classId != 0L && subjectId != 0L) vm.loadRoster(classId, subjectId, date) { m -> marks.clear(); marks.putAll(m); loaded = true } }, enabled = classId != 0L && subjectId != 0L, modifier = Modifier.weight(1f)) { Text("Load class") }
            }

            if (loaded) {
                val present = marks.values.count { it }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Present $present / ${roster.size}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    OutlinedButton(onClick = { roster.forEach { marks[it.id] = true } }) { Text("All present") }
                }
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f)) {
                    items(roster, key = { it.id }) { s ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = marks[s.id] ?: true, onCheckedChange = { marks[s.id] = it })
                            Text(s.name, Modifier.weight(1f))
                            Text(if (marks[s.id] != false) "Present" else "Absent", color = if (marks[s.id] != false) androidx.compose.ui.graphics.Color(0xFF2E7D32) else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                        }
                        HorizontalDivider()
                    }
                }
                Button(onClick = { vm.save(classId, subjectId, staffId, date, marks.toMap()) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Save attendance") }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}

class CoachAttReportViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = CoachingRepository(app)
    val attendance = repo.attendance.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val students = repo.students.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val subjects = repo.subjects.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val classes = repo.classes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachAttendanceReportScreen(onBack: () -> Unit, vm: CoachAttReportViewModel = viewModel()) {
    val attendance by vm.attendance.collectAsState()
    val students by vm.students.collectAsState()
    val subjects by vm.subjects.collectAsState()
    val classes by vm.classes.collectAsState()
    var mode by remember { mutableStateOf("Student") }

    data class Row3(val name: String, val present: Int, val total: Int)
    val rows: List<Row3> = when (mode) {
        "Student" -> {
            val nm = students.associate { it.id to it.name }
            attendance.groupBy { it.studentId }.map { (id, l) -> Row3(nm[id] ?: "?", l.count { it.present }, l.size) }
        }
        "Subject" -> {
            val nm = subjects.associate { it.id to it.name }
            attendance.groupBy { it.subjectId }.map { (id, l) -> Row3(nm[id] ?: "?", l.count { it.present }, l.size) }
        }
        else -> {
            val nm = classes.associate { it.id to it.name }
            attendance.groupBy { it.classId }.map { (id, l) -> Row3(nm[id] ?: "?", l.count { it.present }, l.size) }
        }
    }.sortedBy { it.name.lowercase() }

    Scaffold(topBar = { TopAppBar(title = { Text("Attendance Report") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary, titleContentColor = MaterialTheme.colorScheme.onPrimary, navigationIconContentColor = MaterialTheme.colorScheme.onPrimary)) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Student", "Subject", "Class").forEach { m -> FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m) }) }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows) { r ->
                    val pct = if (r.total > 0) (r.present * 100 / r.total) else 0
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(r.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text("${r.present}/${r.total}  ·  $pct%", style = MaterialTheme.typography.bodyMedium,
                            color = if (pct >= 75) androidx.compose.ui.graphics.Color(0xFF2E7D32) else if (pct < 50) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
