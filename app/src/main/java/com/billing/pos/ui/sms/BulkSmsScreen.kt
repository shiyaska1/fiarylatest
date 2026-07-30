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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.billing.pos.data.AppDatabase
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.Contact
import com.billing.pos.data.ContactGroup
import com.billing.pos.data.DownloadSaver
import com.billing.pos.data.SpreadsheetImport
import com.billing.pos.data.XlsxWriter
import com.billing.pos.sms.SmsSender
import com.billing.pos.sms.WhatsAppSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class BulkSmsViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    val contacts = db.contactDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val groups = db.contactGroupDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val templates = db.smsTemplateDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** mobile (first number) -> (variable -> value), loaded from the filled Excel. */
    val imported = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
    val status = MutableStateFlow<String?>(null)
    val sending = MutableStateFlow(false)

    fun exportForMapping(context: Context, selected: List<Contact>, vars: List<String>) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val header = mutableListOf(XlsxWriter.text("Contact Name"), XlsxWriter.text("Mobile Number"))
                vars.forEach { header.add(XlsxWriter.text(it)) }
                val rows = ArrayList<List<XlsxWriter.Cell>>()
                rows.add(header)
                selected.forEach { c ->
                    val cells = mutableListOf(XlsxWriter.text(c.name), XlsxWriter.text(c.mobile))
                    vars.forEach { cells.add(XlsxWriter.text("")) }
                    rows.add(cells)
                }
                val file = File(File(context.cacheDir, "shared").apply { mkdirs() }, "bulk-sms-fields.xlsx")
                XlsxWriter.write(file, "Fields", rows)
                DownloadSaver.save(context, file, "bulk-sms-fields.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            }
            status.value = if (ok) "Field sheet saved to Downloads — fill it and import" else "Could not save sheet"
        }
    }

    fun importMapping(context: Context, uri: Uri) {
        viewModelScope.launch {
            val map = withContext(Dispatchers.IO) {
                val raw = SpreadsheetImport.readRaw(context, uri)
                if (raw.isEmpty()) return@withContext emptyMap<String, Map<String, String>>()
                val header = raw.first().map { it.trim() }
                val lower = header.map { it.lowercase() }
                val iMob = lower.indexOfFirst { it.contains("mobile") || it.contains("phone") || it.contains("number") }
                if (iMob < 0) return@withContext emptyMap<String, Map<String, String>>()
                val varCols = header.indices.filter { it != iMob && !lower[it].contains("contact name") && lower[it] != "name" }
                val out = HashMap<String, Map<String, String>>()
                raw.drop(1).forEach { r ->
                    fun cell(i: Int) = if (i in 0 until r.size) r[i].trim() else ""
                    val mob = cell(iMob).split(",").firstOrNull()?.trim().orEmpty()
                    if (mob.isBlank()) return@forEach
                    val vals = HashMap<String, String>()
                    varCols.forEach { ci -> vals[header[ci]] = cell(ci) }
                    out[mob] = vals
                }
                out
            }
            imported.value = map
            status.value = if (map.isEmpty()) "No rows found in that sheet" else "Loaded field values for ${map.size} contact(s)"
        }
    }

    /** Send to every selected contact via Gateway or SIM (WhatsApp is handled in the UI stepper). */
    fun sendBulk(context: Context, selected: List<Contact>, template: String, channel: String) {
        if (selected.isEmpty() || template.isBlank()) { status.value = "Pick contacts and a message"; return }
        sending.value = true
        val fields = imported.value
        viewModelScope.launch {
            var ok = 0; var fail = 0
            val batch = System.currentTimeMillis()
            selected.forEach { c ->
                val firstMob = SmsSender.splitNumbers(c.mobile).firstOrNull() ?: ""
                val values = HashMap<String, String>()
                values["name"] = c.name; values["mobile"] = firstMob
                fields[firstMob]?.forEach { (k, v) -> values[k] = v }
                val msg = SmsSender.fillTemplate(template, values)
                SmsSender.splitNumbers(c.mobile).forEach { num ->
                    val r = SmsSender.send(getApplication(), num, msg, channel, c.name, batch)
                    if (r.ok) ok++ else fail++
                }
            }
            sending.value = false
            status.value = "Bulk done · sent $ok, failed $fail"
        }
    }

    fun messageFor(c: Contact, template: String): String {
        val firstMob = SmsSender.splitNumbers(c.mobile).firstOrNull() ?: ""
        val values = HashMap<String, String>()
        values["name"] = c.name; values["mobile"] = firstMob
        imported.value[firstMob]?.forEach { (k, v) -> values[k] = v }
        return SmsSender.fillTemplate(template, values)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulkSmsScreen(onBack: () -> Unit, vm: BulkSmsViewModel = viewModel()) {
    val context = LocalContext.current
    val contacts by vm.contacts.collectAsState()
    val groups by vm.groups.collectAsState()
    val templates by vm.templates.collectAsState()
    val status by vm.status.collectAsState()
    val sending by vm.sending.collectAsState()

    var fGroup by remember { mutableStateOf(0L) }
    var fSub by remember { mutableStateOf(0L) }
    var fSubSub by remember { mutableStateOf(0L) }
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateMapOf<Long, Boolean>() }
    var message by remember { mutableStateOf("") }
    var tplMenu by remember { mutableStateOf(false) }
    var channel by remember { mutableStateOf(AppPrefs(context).smsChannel) }
    var channelMenu by remember { mutableStateOf(false) }

    val filtered = contacts.filter { c ->
        val q = query.trim().lowercase()
        (q.isBlank() || c.name.lowercase().contains(q) || c.mobile.contains(q)) &&
            (fGroup == 0L || c.groupId == fGroup) &&
            (fSub == 0L || c.subGroupId == fSub) &&
            (fSubSub == 0L || c.subSubGroupId == fSubSub)
    }
    val selectedContacts = filtered.filter { selected[it.id] == true }
    val vars = remember(message) { SmsSender.customVars(message) }

    // WhatsApp one-by-one stepper state
    var waQueue by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var waIndex by remember { mutableStateOf(0) }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importMapping(context, uri)
    }
    var pendingSend by remember { mutableStateOf<(() -> Unit)?>(null) }
    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingSend; pendingSend = null
        if (granted) action?.invoke() else vm.status.value = "SMS permission denied — can't send via SIM"
    }
    fun onBulkSend() {
        if (channel == "WhatsApp") { waQueue = selectedContacts; waIndex = 0; return }
        val action = { vm.sendBulk(context, selectedContacts, message, channel) }
        if (channel == "SIM" && androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.SEND_SMS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) { pendingSend = action; smsPermission.launch(android.Manifest.permission.SEND_SMS) }
        else action()
    }

    if (waQueue.isNotEmpty()) {
        val current = waQueue.getOrNull(waIndex)
        AlertDialog(
            onDismissRequest = { waQueue = emptyList() },
            title = { Text("WhatsApp ${waIndex + 1} of ${waQueue.size}") },
            text = {
                Column {
                    if (current != null) {
                        Text("${current.name} · ${current.mobile}")
                        Text(vm.messageFor(current, message), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    } else Text("All done.")
                }
            },
            confirmButton = {
                if (current != null) TextButton(onClick = {
                    val num = SmsSender.splitNumbers(current.mobile).firstOrNull() ?: ""
                    WhatsAppSender.open(context, num, vm.messageFor(current, message))
                }) { Text("Open WhatsApp") }
                else TextButton(onClick = { waQueue = emptyList() }) { Text("Close") }
            },
            dismissButton = {
                if (current != null) TextButton(onClick = {
                    if (waIndex + 1 < waQueue.size) waIndex++ else waQueue = emptyList()
                }) { Text(if (waIndex + 1 < waQueue.size) "Next ▶" else "Finish") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bulk SMS") },
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
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmsGroupFilter("Group", fGroup, groups.filter { it.level == 1 }, Modifier.weight(1f)) { fGroup = it; fSub = 0; fSubSub = 0 }
                SmsGroupFilter("Sub", fSub, groups.filter { it.level == 2 && (fGroup == 0L || it.parentId == fGroup) }, Modifier.weight(1f)) { fSub = it; fSubSub = 0 }
                SmsGroupFilter("Sub-sub", fSubSub, groups.filter { it.level == 3 && (fSub == 0L || it.parentId == fSub) }, Modifier.weight(1f)) { fSubSub = it }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                val allSelected = filtered.isNotEmpty() && filtered.all { selected[it.id] == true }
                Checkbox(checked = allSelected, onCheckedChange = { on -> filtered.forEach { selected[it.id] = on } })
                Text("Select all (${selectedContacts.size}/${filtered.size})")
            }

            LazyColumn(Modifier.weight(1f)) {
                items(filtered, key = { it.id }) { c ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { selected[c.id] = !(selected[c.id] ?: false) }) {
                        Checkbox(checked = selected[c.id] == true, onCheckedChange = { selected[c.id] = it })
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text(c.name)
                            Text(c.mobile, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    HorizontalDivider()
                }
            }

            // Compose area
            Box(Modifier.padding(top = 4.dp)) {
                OutlinedTextField(value = "Insert template", onValueChange = {}, readOnly = true, label = { Text("Template") },
                    trailingIcon = { IconButton(onClick = { tplMenu = true }) { Icon(Icons.Filled.ArrowDropDown, null) } },
                    modifier = Modifier.fillMaxWidth())
                DropdownMenu(expanded = tplMenu, onDismissRequest = { tplMenu = false }) {
                    if (templates.isEmpty()) DropdownMenuItem(text = { Text("No templates yet") }, onClick = { tplMenu = false })
                    templates.forEach { t -> DropdownMenuItem(text = { Text(t.name) }, onClick = { message = t.body; tplMenu = false }) }
                }
            }
            OutlinedTextField(value = message, onValueChange = { message = it }, label = { Text("Message ({name}, {mobile}, custom fields)") },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp), minLines = 2)

            if (vars.isNotEmpty()) {
                Text("Custom fields: ${vars.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.exportForMapping(context, selectedContacts, vars) }, enabled = selectedContacts.isNotEmpty()) { Text("Export fields Excel") }
                    OutlinedButton(onClick = {
                        importPicker.launch(arrayOf(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "application/vnd.ms-excel", "text/csv", "*/*"
                        ))
                    }) { Text("Import filled") }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    OutlinedTextField(value = channel, onValueChange = {}, readOnly = true, label = { Text("Send via") },
                        trailingIcon = { IconButton(onClick = { channelMenu = true }) { Icon(Icons.Filled.ArrowDropDown, null) } },
                        modifier = Modifier.fillMaxWidth())
                    DropdownMenu(expanded = channelMenu, onDismissRequest = { channelMenu = false }) {
                        val options = (if (com.billing.pos.BuildConfig.DEBUG) listOf("Gateway", "SIM") else listOf("Gateway")) + "WhatsApp"
                        options.forEach { c -> DropdownMenuItem(text = { Text(c) }, onClick = { channel = c; channelMenu = false }) }
                    }
                }
                Button(
                    onClick = { onBulkSend() },
                    enabled = !sending && selectedContacts.isNotEmpty() && message.isNotBlank(),
                    modifier = Modifier.padding(start = 8.dp)
                ) { Text(if (sending) "Sending…" else "Send (${selectedContacts.size})") }
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SmsGroupFilter(label: String, selected: Long, options: List<ContactGroup>, modifier: Modifier = Modifier, onPick: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val text = if (selected == 0L) "All" else options.firstOrNull { it.id == selected }?.name ?: "All"
    Box(modifier) {
        OutlinedTextField(value = text, onValueChange = {}, readOnly = true, singleLine = true, label = { Text(label) },
            trailingIcon = { IconButton(onClick = { open = true }) { Icon(Icons.Filled.ArrowDropDown, null) } },
            modifier = Modifier.fillMaxWidth())
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("All") }, onClick = { onPick(0); open = false })
            options.forEach { g -> DropdownMenuItem(text = { Text(g.name) }, onClick = { onPick(g.id); open = false }) }
        }
    }
}
