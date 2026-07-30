package com.billing.pos.ui.sms

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.billing.pos.data.AppDatabase
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.Contact
import com.billing.pos.data.SmsTemplate
import com.billing.pos.sms.SmsSender
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SendSmsViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    val contacts = db.contactDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val templates = db.smsTemplateDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val status = MutableStateFlow<String?>(null)
    var sending = MutableStateFlow(false)

    fun send(numbers: List<String>, message: String, channel: String, contactName: String) {
        if (numbers.isEmpty() || message.isBlank()) { status.value = "Enter a number and a message"; return }
        sending.value = true
        viewModelScope.launch {
            var ok = 0; var fail = 0; var lastErr = ""
            numbers.forEach { num ->
                val r = SmsSender.send(getApplication(), num, message, channel, contactName)
                if (r.ok) ok++ else { fail++; lastErr = r.response }
            }
            sending.value = false
            status.value = "Sent $ok, failed $fail" + (if (fail > 0) " · $lastErr" else "")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendSmsScreen(onBack: () -> Unit, vm: SendSmsViewModel = viewModel()) {
    val context = LocalContext.current
    val contacts by vm.contacts.collectAsState()
    val templates by vm.templates.collectAsState()
    val status by vm.status.collectAsState()
    val sending by vm.sending.collectAsState()

    var contact by remember { mutableStateOf<Contact?>(null) }
    var manualNumber by remember { mutableStateOf("") }
    var contactQuery by remember { mutableStateOf("") }
    var contactMenu by remember { mutableStateOf(false) }
    var rawTemplate by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    var tplMenu by remember { mutableStateOf(false) }
    var channel by remember { mutableStateOf(AppPrefs(context).smsChannel) }
    var channelMenu by remember { mutableStateOf(false) }

    fun values(): Map<String, String> {
        val nm = contact?.name ?: ""
        val mob = contact?.let { SmsSender.splitNumbers(it.mobile).firstOrNull() } ?: manualNumber
        return mapOf("name" to nm, "mobile" to mob)
    }
    fun refill() { rawTemplate?.let { message = SmsSender.fillTemplate(it, values()) } }

    var pendingSend by remember { mutableStateOf<(() -> Unit)?>(null) }
    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingSend; pendingSend = null
        if (granted) action?.invoke() else vm.status.value = "SMS permission denied — can't send via SIM"
    }
    fun runSend() {
        val numbers = contact?.let { SmsSender.splitNumbers(it.mobile) } ?: SmsSender.splitNumbers(manualNumber)
        vm.send(numbers, message, channel, contact?.name ?: "")
    }
    fun onSendClick() {
        if (channel == "SIM" && androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.SEND_SMS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            pendingSend = { runSend() }
            smsPermission.launch(android.Manifest.permission.SEND_SMS)
        } else runSend()
    }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("Send SMS") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
            // Contact picker
            Box {
                OutlinedTextField(
                    value = contact?.let { "${it.name} · ${it.mobile}" } ?: contactQuery,
                    onValueChange = { contactQuery = it; contact = null; contactMenu = true },
                    label = { Text("Pick a contact (or type a number below)") },
                    trailingIcon = { IconButton(onClick = { contactMenu = true }) { Icon(Icons.Filled.ArrowDropDown, null) } },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                DropdownMenu(expanded = contactMenu, onDismissRequest = { contactMenu = false }, modifier = Modifier.heightIn(max = 280.dp)) {
                    val q = contactQuery.trim().lowercase()
                    contacts.filter { q.isBlank() || it.name.lowercase().contains(q) || it.mobile.contains(q) }.take(30).forEach { c ->
                        DropdownMenuItem(text = { Text("${c.name} · ${c.mobile}") }, onClick = {
                            contact = c; contactQuery = ""; contactMenu = false; manualNumber = ""; refill()
                        })
                    }
                }
            }
            OutlinedTextField(value = manualNumber, onValueChange = { manualNumber = it.filter { ch -> ch.isDigit() || ch == '+' || ch == ',' || ch == ' ' }; contact = null; refill() },
                label = { Text("Or type number(s), comma for multiple") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))

            // Template picker
            Box(Modifier.padding(top = 8.dp)) {
                OutlinedTextField(value = "Insert template", onValueChange = {}, readOnly = true, label = { Text("Template") },
                    trailingIcon = { IconButton(onClick = { tplMenu = true }) { Icon(Icons.Filled.ArrowDropDown, null) } },
                    modifier = Modifier.fillMaxWidth())
                DropdownMenu(expanded = tplMenu, onDismissRequest = { tplMenu = false }) {
                    if (templates.isEmpty()) DropdownMenuItem(text = { Text("No templates yet") }, onClick = { tplMenu = false })
                    templates.forEach { t: SmsTemplate ->
                        DropdownMenuItem(text = { Text(t.name) }, onClick = { rawTemplate = t.body; refill(); tplMenu = false })
                    }
                }
            }
            OutlinedTextField(value = message, onValueChange = { message = it }, label = { Text("Message") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp), minLines = 3)

            // Channel
            Box(Modifier.padding(top = 8.dp)) {
                OutlinedTextField(value = channel, onValueChange = {}, readOnly = true, label = { Text("Send via") },
                    trailingIcon = { IconButton(onClick = { channelMenu = true }) { Icon(Icons.Filled.ArrowDropDown, null) } },
                    modifier = Modifier.fillMaxWidth())
                DropdownMenu(expanded = channelMenu, onDismissRequest = { channelMenu = false }) {
                    val options = if (com.billing.pos.BuildConfig.DEBUG) listOf("Gateway", "SIM") else listOf("Gateway")
                    options.forEach { c -> DropdownMenuItem(text = { Text(c) }, onClick = { channel = c; channelMenu = false }) }
                }
            }

            Button(
                onClick = { onSendClick() },
                enabled = !sending,
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.padding(end = 8.dp))
                Text(if (sending) "Sending…" else "Send")
            }

            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp)) }
        }
    }
}
