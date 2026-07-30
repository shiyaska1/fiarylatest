package com.billing.pos.ui.sms

import android.app.Application
import android.content.Context
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.AppDatabase
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.Contact
import com.billing.pos.sms.SmsSender
import com.billing.pos.sms.WhatsAppSender
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AttendanceViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    val contacts = db.contactDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val groups = db.contactGroupDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val templates = db.smsTemplateDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val status = MutableStateFlow<String?>(null)
    val sending = MutableStateFlow(false)

    fun sendTo(list: List<Contact>, template: String, channel: String) {
        if (list.isEmpty() || template.isBlank()) { status.value = "No absentees or empty message"; return }
        sending.value = true
        viewModelScope.launch {
            var ok = 0; var fail = 0
            val batch = System.currentTimeMillis()
            list.forEach { c ->
                val firstMob = SmsSender.splitNumbers(c.mobile).firstOrNull() ?: ""
                val msg = SmsSender.fillTemplate(template, mapOf("name" to c.name, "mobile" to firstMob))
                SmsSender.splitNumbers(c.mobile).forEach { num ->
                    val r = SmsSender.send(getApplication(), num, msg, channel, c.name, batch)
                    if (r.ok) ok++ else fail++
                }
            }
            sending.value = false
            status.value = "Notified absentees · sent $ok, failed $fail"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttendanceScreen(onBack: () -> Unit, vm: AttendanceViewModel = viewModel()) {
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
    val present = remember { mutableStateMapOf<Long, Boolean>() }
    var showSend by remember { mutableStateOf(false) }

    val filtered = contacts.filter { c ->
        val q = query.trim().lowercase()
        (q.isBlank() || c.name.lowercase().contains(q) || c.mobile.contains(q)) &&
            (fGroup == 0L || c.groupId == fGroup) &&
            (fSub == 0L || c.subGroupId == fSub) &&
            (fSubSub == 0L || c.subSubGroupId == fSubSub)
    }
    val presentCount = filtered.count { present[it.id] == true }
    val absentees = filtered.filter { present[it.id] != true }

    var pendingSend by remember { mutableStateOf<(() -> Unit)?>(null) }
    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingSend; pendingSend = null
        if (granted) action?.invoke() else vm.status.value = "SMS permission denied — can't send via SIM"
    }

    if (showSend) {
        var message by remember { mutableStateOf("") }
        var tplMenu by remember { mutableStateOf(false) }
        var channel by remember { mutableStateOf(AppPrefs(context).smsChannel) }
        var channelMenu by remember { mutableStateOf(false) }
        var waQueue by remember { mutableStateOf<List<Contact>>(emptyList()) }
        var waIndex by remember { mutableStateOf(0) }

        if (waQueue.isNotEmpty()) {
            val current = waQueue.getOrNull(waIndex)
            AlertDialog(
                onDismissRequest = { waQueue = emptyList() },
                title = { Text("WhatsApp ${waIndex + 1} of ${waQueue.size}") },
                text = { Text(current?.let { "${it.name} · ${it.mobile}" } ?: "Done") },
                confirmButton = {
                    if (current != null) TextButton(onClick = {
                        val num = SmsSender.splitNumbers(current.mobile).firstOrNull() ?: ""
                        val msg = SmsSender.fillTemplate(message, mapOf("name" to current.name, "mobile" to num))
                        WhatsAppSender.open(context, num, msg)
                    }) { Text("Open WhatsApp") } else TextButton(onClick = { waQueue = emptyList() }) { Text("Close") }
                },
                dismissButton = {
                    if (current != null) TextButton(onClick = { if (waIndex + 1 < waQueue.size) waIndex++ else waQueue = emptyList() }) {
                        Text(if (waIndex + 1 < waQueue.size) "Next ▶" else "Finish")
                    }
                }
            )
        }

        AlertDialog(
            onDismissRequest = { showSend = false },
            title = { Text("Message ${absentees.size} absentee(s)") },
            text = {
                Column {
                    Box {
                        OutlinedTextField(value = "Insert template", onValueChange = {}, readOnly = true, label = { Text("Template") },
                            trailingIcon = { IconButton(onClick = { tplMenu = true }) { Icon(Icons.Filled.ArrowDropDown, null) } },
                            modifier = Modifier.fillMaxWidth())
                        DropdownMenu(expanded = tplMenu, onDismissRequest = { tplMenu = false }) {
                            templates.forEach { t -> DropdownMenuItem(text = { Text(t.name) }, onClick = { message = t.body; tplMenu = false }) }
                        }
                    }
                    OutlinedTextField(value = message, onValueChange = { message = it }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), minLines = 2)
                    Box(Modifier.padding(top = 8.dp)) {
                        OutlinedTextField(value = channel, onValueChange = {}, readOnly = true, label = { Text("Send via") },
                            trailingIcon = { IconButton(onClick = { channelMenu = true }) { Icon(Icons.Filled.ArrowDropDown, null) } },
                            modifier = Modifier.fillMaxWidth())
                        DropdownMenu(expanded = channelMenu, onDismissRequest = { channelMenu = false }) {
                            val options = (if (com.billing.pos.BuildConfig.DEBUG) listOf("Gateway", "SIM") else listOf("Gateway")) + "WhatsApp"
                            options.forEach { c -> DropdownMenuItem(text = { Text(c) }, onClick = { channel = c; channelMenu = false }) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (channel == "WhatsApp") { waQueue = absentees; waIndex = 0 }
                    else {
                        val action = { vm.sendTo(absentees, message, channel); showSend = false }
                        if (channel == "SIM" && androidx.core.content.ContextCompat.checkSelfPermission(
                                context, android.Manifest.permission.SEND_SMS
                            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) { pendingSend = action; smsPermission.launch(android.Manifest.permission.SEND_SMS) }
                        else action()
                    }
                }, enabled = !sending && message.isNotBlank()) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { showSend = false }) { Text("Close") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Attendance") },
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
            Text("Present $presentCount · Absent ${absentees.size} of ${filtered.size}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 8.dp))

            LazyColumn(Modifier.weight(1f)) {
                items(filtered, key = { it.id }) { c ->
                    val isPresent = present[c.id] == true
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { present[c.id] = !isPresent }) {
                        Checkbox(checked = isPresent, onCheckedChange = { present[c.id] = it })
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(c.name)
                            Text(c.mobile, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                        Text(if (isPresent) "Present" else "Absent",
                            color = if (isPresent) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium)
                    }
                    HorizontalDivider()
                }
            }

            Button(onClick = { showSend = true }, enabled = absentees.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Message ${absentees.size} absentee(s)")
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}
