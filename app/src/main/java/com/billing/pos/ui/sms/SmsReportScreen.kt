package com.billing.pos.ui.sms

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.AppDatabase
import com.billing.pos.data.SmsLog
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmsReportViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.get(app).smsLogDao()
    val logs = dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsReportScreen(onBack: () -> Unit, vm: SmsReportViewModel = viewModel()) {
    val logs by vm.logs.collectAsState()
    val fmt = remember { SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()) }

    val sent = logs.count { it.status == "SENT" || it.status == "DELIVERED" }
    val failed = logs.count { it.status == "FAILED" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SMS Report") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(Modifier.fillMaxWidth().padding(12.dp)) {
                Text("Total ${logs.size}", style = MaterialTheme.typography.titleSmall)
                Text("   Sent $sent", color = Color(0xFF2E7D32), style = MaterialTheme.typography.titleSmall)
                Text("   Failed $failed", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
            }
            HorizontalDivider()
            LazyColumn(Modifier.fillMaxSize()) {
                items(logs, key = { it.id }) { log: SmsLog ->
                    ListItem(
                        headlineContent = { Text("${log.mobile}${if (log.contactName.isNotBlank()) "  ·  ${log.contactName}" else ""}") },
                        supportingContent = {
                            Column {
                                Text(log.body, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                                Text("${fmt.format(Date(log.dateMillis))}  ·  ${log.channel}" + (if (log.response.isNotBlank()) "  ·  ${log.response}" else ""),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                        },
                        trailingContent = {
                            val c = when (log.status) {
                                "SENT", "DELIVERED" -> Color(0xFF2E7D32)
                                "FAILED" -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.outline
                            }
                            Text(log.status, color = c, style = MaterialTheme.typography.labelMedium)
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
