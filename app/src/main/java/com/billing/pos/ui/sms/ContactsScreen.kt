package com.billing.pos.ui.sms

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.Contact
import com.billing.pos.data.ContactGroup
import com.billing.pos.data.ContactImportRow
import com.billing.pos.data.ContactRepository
import com.billing.pos.data.DownloadSaver
import com.billing.pos.data.SpreadsheetImport
import com.billing.pos.data.XlsxWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ContactViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ContactRepository(app)

    val contacts = repo.contacts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val groups = repo.groups.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val message = MutableStateFlow<String?>(null)
    fun clearMessage() { message.value = null }

    fun saveContact(c: Contact) { viewModelScope.launch { repo.saveContact(c) } }
    fun deleteContact(c: Contact) { viewModelScope.launch { repo.deleteContact(c) } }
    fun addGroup(name: String, parentId: Long, level: Int, onSaved: (Long) -> Unit) {
        viewModelScope.launch { onSaved(repo.ensureGroup(name, parentId, level)) }
    }

    /** Download a blank .xlsx template into Downloads. */
    fun downloadTemplate(context: Context) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val rows = listOf(
                    XlsxWriter.row(
                        XlsxWriter.text("Contact Name"), XlsxWriter.text("Mobile Number"),
                        XlsxWriter.text("Group"), XlsxWriter.text("Sub Group"), XlsxWriter.text("Sub Sub Group")
                    ),
                    XlsxWriter.row(
                        XlsxWriter.text("John Doe"), XlsxWriter.text("9876543210, 9123456789"),
                        XlsxWriter.text("Customers"), XlsxWriter.text("VIP"), XlsxWriter.text("Gold")
                    )
                )
                val file = File(File(context.cacheDir, "shared").apply { mkdirs() }, "contacts-template.xlsx")
                XlsxWriter.write(file, "Contacts", rows)
                DownloadSaver.save(context, file, "contacts-template.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            }
            message.value = if (ok) "Template saved to Downloads" else "Could not save template"
        }
    }

    /** Export all contacts to .xlsx in Downloads. */
    fun exportContacts(context: Context) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val all = repo.allContacts()
                val gs = repo.allGroups().associateBy { it.id }
                val rows = ArrayList<List<XlsxWriter.Cell>>()
                rows.add(XlsxWriter.row(
                    XlsxWriter.text("Contact Name"), XlsxWriter.text("Mobile Number"),
                    XlsxWriter.text("Group"), XlsxWriter.text("Sub Group"), XlsxWriter.text("Sub Sub Group")
                ))
                all.forEach { c ->
                    rows.add(XlsxWriter.row(
                        XlsxWriter.text(c.name), XlsxWriter.text(c.mobile),
                        XlsxWriter.text(gs[c.groupId]?.name ?: ""),
                        XlsxWriter.text(gs[c.subGroupId]?.name ?: ""),
                        XlsxWriter.text(gs[c.subSubGroupId]?.name ?: "")
                    ))
                }
                val file = File(File(context.cacheDir, "shared").apply { mkdirs() }, "contacts.xlsx")
                XlsxWriter.write(file, "Contacts", rows)
                DownloadSaver.save(context, file, "contacts.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            }
            message.value = if (ok) "Contacts exported to Downloads" else "Could not export"
        }
    }

    /** Import from a picked spreadsheet. [replaceAll] clears the book first; else append/update. */
    fun importFrom(context: Context, uri: Uri, replaceAll: Boolean) {
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) {
                val raw = SpreadsheetImport.readRaw(context, uri)
                if (raw.isEmpty()) return@withContext emptyList<ContactImportRow>()
                val header = raw.first().map { it.trim().lowercase() }
                fun col(vararg keys: String) = header.indexOfFirst { h -> keys.any { h == it || h.contains(it) } }
                val iName = col("contact name", "name", "contact")
                val iMob = col("mobile", "phone", "number", "cell")
                val iGroup = col("group")
                val iSub = col("sub group", "subgroup")
                val iSubSub = col("sub sub group", "sub-sub group", "subsubgroup")
                raw.drop(1).mapNotNull { r ->
                    fun cell(i: Int) = if (i in 0 until r.size) r[i].trim() else ""
                    val name = if (iName >= 0) cell(iName) else r.firstOrNull()?.trim().orEmpty()
                    val mobile = (if (iMob >= 0) cell(iMob) else "")
                        .split(",").map { it.trim() }.filter { it.isNotBlank() }.joinToString(", ")
                    if (name.isBlank() || mobile.isBlank()) return@mapNotNull null
                    ContactImportRow(
                        name = name, mobile = mobile,
                        group = if (iGroup >= 0) cell(iGroup) else "",
                        subGroup = if (iSub >= 0) cell(iSub) else "",
                        subSubGroup = if (iSubSub >= 0) cell(iSubSub) else ""
                    )
                }
            }
            if (rows.isEmpty()) { message.value = "No valid rows found (need Contact Name + Mobile Number)"; return@launch }
            val (added, updated) = repo.importContacts(rows, replaceAll)
            message.value = if (replaceAll) "Imported $added contacts (replaced all)" else "Added $added, updated $updated"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(onBack: () -> Unit, vm: ContactViewModel = viewModel()) {
    val context = LocalContext.current
    val contacts by vm.contacts.collectAsState()
    val groups by vm.groups.collectAsState()
    val message by vm.message.collectAsState()

    var query by remember { mutableStateOf("") }
    var filterGroup by remember { mutableStateOf(0L) }
    var filterSub by remember { mutableStateOf(0L) }
    var filterSubSub by remember { mutableStateOf(0L) }
    var editing by remember { mutableStateOf<Contact?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var importReplace by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFrom(context, uri, importReplace)
    }
    fun launchImport(replace: Boolean) {
        importReplace = replace
        picker.launch(arrayOf(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-excel", "text/csv", "text/comma-separated-values", "*/*"
        ))
    }

    message?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.clearMessage() },
            confirmButton = { TextButton(onClick = { vm.clearMessage() }) { Text("OK") } },
            text = { Text(msg) }
        )
    }

    if (showEditor) {
        ContactEditor(
            existing = editing,
            groups = groups,
            onDismiss = { showEditor = false },
            onSave = { vm.saveContact(it); showEditor = false },
            onDelete = { editing?.let { c -> vm.deleteContact(c) }; showEditor = false },
            onAddGroup = { name, parent, level, cb -> vm.addGroup(name, parent, level, cb) }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Contacts") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Download blank template") }, onClick = { menuOpen = false; vm.downloadTemplate(context) })
                        DropdownMenuItem(text = { Text("Import — append / update") }, onClick = { menuOpen = false; launchImport(false) })
                        DropdownMenuItem(text = { Text("Import — replace all") }, onClick = { menuOpen = false; launchImport(true) })
                        DropdownMenuItem(text = { Text("Export contacts") }, onClick = { menuOpen = false; vm.exportContacts(context) })
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = null; showEditor = true }) { Icon(Icons.Filled.Add, "New contact") }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("Search name or number") }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                modifier = Modifier.fillMaxWidth()
            )
            // Group filters
            val topGroups = groups.filter { it.level == 1 }
            val subGroups = groups.filter { it.level == 2 && (filterGroup == 0L || it.parentId == filterGroup) }
            val subSubGroups = groups.filter { it.level == 3 && (filterSub == 0L || it.parentId == filterSub) }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GroupFilter("Group", filterGroup, topGroups, Modifier.weight(1f)) { filterGroup = it; filterSub = 0; filterSubSub = 0 }
                GroupFilter("Sub", filterSub, subGroups, Modifier.weight(1f)) { filterSub = it; filterSubSub = 0 }
                GroupFilter("Sub-sub", filterSubSub, subSubGroups, Modifier.weight(1f)) { filterSubSub = it }
            }

            val q = query.trim().lowercase()
            val nameById = remember(groups) { groups.associate { it.id to it.name } }
            val filtered = contacts.filter { c ->
                (q.isBlank() || c.name.lowercase().contains(q) || c.mobile.contains(q)) &&
                    (filterGroup == 0L || c.groupId == filterGroup) &&
                    (filterSub == 0L || c.subGroupId == filterSub) &&
                    (filterSubSub == 0L || c.subSubGroupId == filterSubSub)
            }

            Text("${filtered.size} contact(s)", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 8.dp))

            LazyColumn(Modifier.fillMaxSize()) {
                items(filtered, key = { it.id }) { c ->
                    val path = listOfNotNull(
                        nameById[c.groupId], nameById[c.subGroupId], nameById[c.subSubGroupId]
                    ).filter { it.isNotBlank() }.joinToString(" › ")
                    ListItem(
                        headlineContent = { Text(c.name) },
                        supportingContent = { Text(c.mobile + (if (path.isNotBlank()) "  ·  $path" else "")) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editing = c; showEditor = true }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupFilter(label: String, selected: Long, options: List<ContactGroup>, modifier: Modifier = Modifier, onPick: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val text = if (selected == 0L) "All" else options.firstOrNull { it.id == selected }?.name ?: "All"
    Box(modifier) {
        OutlinedTextField(
            value = text, onValueChange = {}, readOnly = true, singleLine = true,
            label = { Text(label) },
            trailingIcon = { IconButton(onClick = { open = true }) { Icon(Icons.Filled.ArrowDropDown, null) } },
            modifier = Modifier.fillMaxWidth()
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("All") }, onClick = { onPick(0); open = false })
            options.forEach { g -> DropdownMenuItem(text = { Text(g.name) }, onClick = { onPick(g.id); open = false }) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactEditor(
    existing: Contact?,
    groups: List<ContactGroup>,
    onDismiss: () -> Unit,
    onSave: (Contact) -> Unit,
    onDelete: () -> Unit,
    onAddGroup: (String, Long, Int, (Long) -> Unit) -> Unit
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var mobile by remember { mutableStateOf(existing?.mobile ?: "") }
    var groupId by remember { mutableStateOf(existing?.groupId ?: 0L) }
    var subId by remember { mutableStateOf(existing?.subGroupId ?: 0L) }
    var subSubId by remember { mutableStateOf(existing?.subSubGroupId ?: 0L) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New contact" else "Edit contact") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Contact name *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = mobile,
                    onValueChange = { mobile = it.filter { ch -> ch.isDigit() || ch == '+' || ch == ',' || ch == ' ' } },
                    label = { Text("Mobile number(s) *") },
                    supportingText = { Text("Separate multiple numbers with a comma") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )

                GroupPicker("Group", groupId, groups.filter { it.level == 1 }, 0, 1, onAddGroup, Modifier.padding(top = 8.dp)) { groupId = it; subId = 0; subSubId = 0 }
                GroupPicker("Sub group", subId, groups.filter { it.level == 2 && it.parentId == groupId }, groupId, 2, onAddGroup, Modifier.padding(top = 8.dp), enabled = groupId != 0L) { subId = it; subSubId = 0 }
                GroupPicker("Sub-sub group", subSubId, groups.filter { it.level == 3 && it.parentId == subId }, subId, 3, onAddGroup, Modifier.padding(top = 8.dp), enabled = subId != 0L) { subSubId = it }

                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Clean up multiple numbers: trim each, drop blanks, rejoin with ", ".
                val cleanMobile = mobile.split(",").map { it.trim() }.filter { it.isNotBlank() }.joinToString(", ")
                if (name.trim().isBlank() || cleanMobile.isBlank()) { error = "Name and mobile number are required"; return@TextButton }
                onSave(
                    (existing ?: Contact(name = "", mobile = "")).copy(
                        name = name.trim(), mobile = cleanMobile,
                        groupId = groupId, subGroupId = subId, subSubGroupId = subSubId
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (existing != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupPicker(
    label: String,
    selected: Long,
    options: List<ContactGroup>,
    parentId: Long,
    level: Int,
    onAddGroup: (String, Long, Int, (Long) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onPick: (Long) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val text = if (selected == 0L) "" else options.firstOrNull { it.id == selected }?.name ?: ""

    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("New $label") },
            text = { OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("$label name") }, singleLine = true) },
            confirmButton = { TextButton(onClick = {
                val n = newName.trim()
                if (n.isNotBlank()) onAddGroup(n, parentId, level) { id -> onPick(id) }
                newName = ""; adding = false
            }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } }
        )
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f)) {
            OutlinedTextField(
                value = text, onValueChange = {}, readOnly = true, singleLine = true, enabled = enabled,
                label = { Text(label) },
                trailingIcon = { IconButton(onClick = { if (enabled) open = true }) { Icon(Icons.Filled.ArrowDropDown, null) } },
                modifier = Modifier.fillMaxWidth()
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("(none)") }, onClick = { onPick(0); open = false })
                options.forEach { g -> DropdownMenuItem(text = { Text(g.name) }, onClick = { onPick(g.id); open = false }) }
            }
        }
        IconButton(onClick = { if (enabled) adding = true }, enabled = enabled) {
            Icon(Icons.Filled.Add, "New $label", tint = MaterialTheme.colorScheme.primary)
        }
    }
}
