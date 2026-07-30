package com.billing.pos.ui.coaching

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.CoachingRepository
import com.billing.pos.data.Enquiry
import com.billing.pos.data.EnquiryFollowup
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal val ENQUIRY_STATUS = listOf("New", "Follow-up", "Converted", "Lost")

class CoachingEnquiryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = CoachingRepository(app)
    val enquiries = repo.enquiries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val current = MutableStateFlow<Enquiry?>(null)
    val followups = MutableStateFlow<List<EnquiryFollowup>>(emptyList())
    val message = MutableStateFlow<String?>(null)
    private var loaded = -1L

    fun load(id: Long) {
        loaded = id
        viewModelScope.launch {
            current.value = if (id == 0L) Enquiry(name = "", dateMillis = System.currentTimeMillis()) else repo.enquiryById(id)
            if (id != 0L) repo.followups(id).let { flow -> flow.collect { followups.value = it } }
        }
    }

    fun save(e: Enquiry, onSaved: (Long) -> Unit) {
        if (e.name.isBlank()) { message.value = "Name required"; return }
        viewModelScope.launch { val id = repo.saveEnquiry(e); message.value = "Saved"; onSaved(id) }
    }

    fun addFollowup(enquiryId: Long, note: String, status: String) {
        viewModelScope.launch { repo.addFollowup(enquiryId, note, status); current.value = repo.enquiryById(enquiryId); message.value = "Follow-up added" }
    }

    fun convert(e: Enquiry, onConverted: (Long) -> Unit) {
        viewModelScope.launch { val sid = repo.convertEnquiry(e); message.value = "Converted to student"; onConverted(sid) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachingEnquiryScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, onNew: () -> Unit, vm: CoachingEnquiryViewModel = viewModel()) {
    val list by vm.enquiries.collectAsState()
    Scaffold(
        topBar = { TopAppBar(title = { Text("Enquiries") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary, titleContentColor = MaterialTheme.colorScheme.onPrimary, navigationIconContentColor = MaterialTheme.colorScheme.onPrimary)) },
        floatingActionButton = { FloatingActionButton(onClick = onNew) { Icon(Icons.Filled.Add, "New enquiry") } }
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
            items(list, key = { it.id }) { e ->
                ListItem(headlineContent = { Text(e.name) },
                    supportingContent = { Text(listOfNotNull(e.phone.ifBlank { null }, e.courseInterest.ifBlank { null }).joinToString("  ·  ")) },
                    trailingContent = { Text(e.status, style = MaterialTheme.typography.labelMedium, color = if (e.status == "Converted") androidx.compose.ui.graphics.Color(0xFF2E7D32) else if (e.status == "Lost") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(e.id) })
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachingEnquiryDetailScreen(enquiryId: Long, onBack: () -> Unit, onOpenStudent: (Long) -> Unit, vm: CoachingEnquiryViewModel = viewModel()) {
    val context = LocalContext.current
    LaunchedEffect(enquiryId) { vm.load(enquiryId) }
    val enquiry by vm.current.collectAsState()
    val followups by vm.followups.collectAsState()
    val message by vm.message.collectAsState()

    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var course by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("New") }
    var notes by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(System.currentTimeMillis()) }
    var populated by remember { mutableStateOf(-2L) }
    var statusMenu by remember { mutableStateOf(false) }
    var followNote by remember { mutableStateOf("") }
    var followStatus by remember { mutableStateOf("Follow-up") }
    var followStatusMenu by remember { mutableStateOf(false) }

    LaunchedEffect(enquiry?.id) {
        val e = enquiry ?: return@LaunchedEffect
        if (populated == e.id) return@LaunchedEffect
        populated = e.id
        name = e.name; phone = e.phone; course = e.courseInterest; source = e.source; status = e.status; notes = e.notes; date = e.dateMillis
    }

    Scaffold(topBar = { TopAppBar(title = { Text(if (enquiryId == 0L) "New enquiry" else "Enquiry") },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary, titleContentColor = MaterialTheme.colorScheme.onPrimary, navigationIconContentColor = MaterialTheme.colorScheme.onPrimary)) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = phone, onValueChange = { phone = it.filter { c -> c.isDigit() || c == '+' } }, label = { Text("Phone") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            OutlinedTextField(value = course, onValueChange = { course = it }, label = { Text("Course interested") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            OutlinedTextField(value = source, onValueChange = { source = it }, label = { Text("Source (walk-in, phone, ad…)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Box(Modifier.padding(top = 8.dp)) {
                OutlinedTextField(value = status, onValueChange = {}, readOnly = true, label = { Text("Status") }, trailingIcon = { IconButton(onClick = { statusMenu = true }) { Icon(Icons.Filled.ArrowDropDown, null) } }, modifier = Modifier.fillMaxWidth())
                DropdownMenu(expanded = statusMenu, onDismissRequest = { statusMenu = false }) { ENQUIRY_STATUS.forEach { s -> DropdownMenuItem(text = { Text(s) }, onClick = { status = s; statusMenu = false }) } }
            }
            OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))

            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val e = (enquiry ?: Enquiry(name = "", dateMillis = date)).copy(name = name.trim(), phone = phone.trim(), courseInterest = course.trim(), source = source.trim(), status = status, notes = notes.trim(), dateMillis = date)
                    vm.save(e) { }
                }, modifier = Modifier.weight(1f)) { Text("Save") }
                if (enquiryId != 0L && status != "Converted") {
                    OutlinedButton(onClick = {
                        enquiry?.let { e -> vm.convert(e.copy(name = name.trim(), phone = phone.trim())) { sid -> onOpenStudent(sid) } }
                    }, modifier = Modifier.weight(1f)) { Text("Convert → Admission") }
                }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }

            if (enquiryId != 0L) {
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text("Follow-ups", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(value = followNote, onValueChange = { followNote = it }, label = { Text("Add a follow-up note") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        OutlinedTextField(value = followStatus, onValueChange = {}, readOnly = true, label = { Text("Set status") }, trailingIcon = { IconButton(onClick = { followStatusMenu = true }) { Icon(Icons.Filled.ArrowDropDown, null) } }, modifier = Modifier.fillMaxWidth())
                        DropdownMenu(expanded = followStatusMenu, onDismissRequest = { followStatusMenu = false }) { ENQUIRY_STATUS.forEach { s -> DropdownMenuItem(text = { Text(s) }, onClick = { followStatus = s; followStatusMenu = false }) } }
                    }
                    Button(onClick = { if (followNote.isNotBlank()) { vm.addFollowup(enquiryId, followNote.trim(), followStatus); followNote = ""; status = followStatus } }) { Text("Add") }
                }
                followups.forEach { f ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(f.note, style = MaterialTheme.typography.bodyMedium)
                        Text("${Format.date(f.dateMillis)}${if (f.status.isNotBlank()) " · ${f.status}" else ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
