package com.billing.pos.ui.sms

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.AppDatabase
import com.billing.pos.data.SmsTemplate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SmsTemplateViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.get(app).smsTemplateDao()
    val templates = dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun save(t: SmsTemplate) { viewModelScope.launch { if (t.id == 0L) dao.insert(t) else dao.update(t) } }
    fun delete(t: SmsTemplate) { viewModelScope.launch { dao.delete(t) } }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SmsTemplatesScreen(onBack: () -> Unit, vm: SmsTemplateViewModel = viewModel()) {
    val templates by vm.templates.collectAsState()
    var editing by remember { mutableStateOf<SmsTemplate?>(null) }
    var show by remember { mutableStateOf(false) }

    if (show) {
        val t = editing
        var name by remember { mutableStateOf(t?.name ?: "") }
        var body by remember { mutableStateOf(TextFieldValue(t?.body ?: "")) }
        val variables = listOf("name", "mobile", "field1", "field2", "amount", "date")

        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text(if (t == null) "New template" else "Edit template") },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Template name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text("Message") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp), minLines = 3)
                    Text("Tap to insert a variable:", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    FlowRow {
                        variables.forEach { v ->
                            AssistChip(
                                onClick = {
                                    val insert = "{$v}"
                                    val cur = body
                                    val start = cur.selection.start.coerceIn(0, cur.text.length)
                                    val newText = cur.text.substring(0, start) + insert + cur.text.substring(start)
                                    body = TextFieldValue(newText, TextRange(start + insert.length))
                                },
                                label = { Text("{$v}") },
                                modifier = Modifier.padding(end = 6.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.trim().isNotBlank() && body.text.trim().isNotBlank()) {
                        vm.save((t ?: SmsTemplate(name = "", body = "")).copy(name = name.trim(), body = body.text.trim()))
                    }
                    show = false
                }) { Text("Save") }
            },
            dismissButton = {
                androidx.compose.foundation.layout.Row {
                    if (t != null) TextButton(onClick = { vm.delete(t); show = false }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { show = false }) { Text("Cancel") }
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SMS Templates") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = { editing = null; show = true }) { Icon(Icons.Filled.Add, "New template") } }
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            items(templates, key = { it.id }) { t ->
                ListItem(
                    headlineContent = { Text(t.name) },
                    supportingContent = { Text(t.body, maxLines = 2) },
                    modifier = Modifier.fillMaxWidth().clickable { editing = t; show = true }
                )
                HorizontalDivider()
            }
        }
    }
}
