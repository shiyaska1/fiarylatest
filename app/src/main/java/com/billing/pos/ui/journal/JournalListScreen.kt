package com.billing.pos.ui.journal

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.JournalEntry
import com.billing.pos.data.Repository
import com.billing.pos.pdf.TablePdf
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.ui.common.DateSearchFilter
import com.billing.pos.ui.common.endOfDay
import com.billing.pos.ui.common.rememberPdfDownloader
import com.billing.pos.ui.common.startOfDay
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class JournalListViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    val entries: StateFlow<List<JournalEntry>> =
        repo.journalEntries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    /** Per-entry voucher amount = total of its debit lines. */
    val totals: StateFlow<Map<Long, Double>> =
        repo.journalLines.map { lines ->
            lines.filter { it.isDebit }.groupBy { it.entryId }.mapValues { (_, l) -> l.sumOf { it.amount } }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }
    fun delete(e: JournalEntry) {
        viewModelScope.launch { repo.deleteJournal(e); message.value = "Journal ${e.voucherNo} deleted" }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalListScreen(
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    vm: JournalListViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val entries by vm.entries.collectAsStateSafe()
    val totals by vm.totals.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    var deleteFor by remember { mutableStateOf<JournalEntry?>(null) }

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    var query by remember { mutableStateOf("") }
    var fromMillis by remember { mutableStateOf<Long?>(null) }
    var toMillis by remember { mutableStateOf<Long?>(null) }
    val filtered = entries.filter {
        (fromMillis == null || it.dateMillis >= startOfDay(fromMillis!!)) &&
            (toMillis == null || it.dateMillis <= endOfDay(toMillis!!)) &&
            (query.isBlank() || it.voucherNo.contains(query, true) || it.narration.contains(query, true))
    }
    val total = filtered.sumOf { totals[it.id] ?: 0.0 }
    val downloadPdf = rememberPdfDownloader { msg -> scope.launch { snackbar.showSnackbar(msg) } }
    fun buildJournalPdf(): java.io.File {
        val cols = listOf(
            TablePdf.Col("Voucher", 1.4f), TablePdf.Col("Date", 1.3f), TablePdf.Col("Narration", 3f),
            TablePdf.Col("Amount", 1.3f, right = true)
        )
        val data = filtered.sortedByDescending { it.dateMillis }.map {
            listOf(it.voucherNo, Format.date(it.dateMillis), it.narration.ifBlank { "(no narration)" }, Format.money(totals[it.id] ?: 0.0))
        }
        val sub = "Count: ${filtered.size}" + (fromMillis?.let { "  From: ${Format.date(it)}" } ?: "") +
            (toMillis?.let { "  To: ${Format.date(it)}" } ?: "") + (if (query.isNotBlank()) "  Search: $query" else "")
        return TablePdf.generate(context, AppPrefs(context).company, "Journal", sub, cols, data, listOf("TOTAL" to Format.money(total)))
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Journal") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    IconButton(onClick = { downloadPdf { buildJournalPdf() } }) {
                        Icon(Icons.Filled.Download, contentDescription = "Download PDF")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onOpen(0) }) { Icon(Icons.Filled.Add, contentDescription = "New journal") }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            DateSearchFilter(
                query = query, onQuery = { query = it },
                from = fromMillis, onFrom = { fromMillis = it },
                to = toMillis, onTo = { toMillis = it },
                searchLabel = "Search voucher / narration"
            )
            Divider()
            if (filtered.isEmpty()) {
                Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (entries.isEmpty()) "No journal entries" else "No entries match", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp)) {
                    items(filtered, key = { it.id }) { e ->
                        Row(Modifier.fillMaxWidth().clickable { onOpen(e.id) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(e.voucherNo, fontWeight = FontWeight.Bold)
                                Text(
                                    e.narration.ifBlank { "(no narration)" } + "  •  " + Format.date(e.dateMillis),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                                )
                            }
                            Text(Format.rupee(totals[e.id] ?: 0.0), fontWeight = FontWeight.Bold)
                            IconButton(onClick = { deleteFor = e }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                        }
                        Divider()
                    }
                }
            }
            Card(Modifier.fillMaxWidth().padding(12.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Total (${filtered.size}):  ", fontWeight = FontWeight.Bold)
                    Text(
                        Format.rupee(total),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    deleteFor?.let { e ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete ${e.voucherNo}?") },
            text = { Text("This cannot be undone.") },
            confirmButton = { TextButton(onClick = { vm.delete(e); deleteFor = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } }
        )
    }
}
