package com.billing.pos.ui.service

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import com.billing.pos.data.ServiceJobCard
import com.billing.pos.data.ServiceRepository
import com.billing.pos.pdf.TablePdf
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.ui.common.rememberPdfDownloader
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.TimeUnit

class ServiceReportViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ServiceRepository(app)
    val cards: StateFlow<List<ServiceJobCard>> = repo.cards.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

/** Counts and money by status over a date range, with drill-down to the cards. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceStatusReportScreen(
    onBack: () -> Unit,
    onOpenCard: (Long) -> Unit,
    vm: ServiceReportViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val cards by vm.cards.collectAsStateSafe()
    val downloadPdf = rememberPdfDownloader { msg -> scope.launch { snackbar.showSnackbar(msg) } }

    var fromMillis by remember { mutableStateOf(Calendar.getInstance().apply { add(Calendar.MONTH, -3) }.timeInMillis) }
    var toMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var drillStatus by remember { mutableStateOf<String?>(null) }

    fun dayStart(m: Long) = Calendar.getInstance().apply { timeInMillis = m; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    fun dayEnd(m: Long) = Calendar.getInstance().apply { timeInMillis = m; set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999) }.timeInMillis

    val inRange = cards.filter { it.dateMillis >= dayStart(fromMillis) && it.dateMillis <= dayEnd(toMillis) }
    val byStatus = inRange.groupBy { it.status }.toList().sortedByDescending { it.second.size }
    val totalAmount = inRange.sumOf { it.grandTotal }
    val totalAdvance = inRange.sumOf { it.advance }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Job Status Report") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = {
                        downloadPdf {
                            TablePdf.generate(
                                context, AppPrefs(context).company,
                                "Job Status Report", "${Format.date(fromMillis)} - ${Format.date(toMillis)}",
                                listOf(
                                    TablePdf.Col("Status", 2f), TablePdf.Col("Cards", 1f, right = true),
                                    TablePdf.Col("Amount", 1.4f, right = true), TablePdf.Col("Advance", 1.4f, right = true),
                                    TablePdf.Col("Balance", 1.4f, right = true)
                                ),
                                byStatus.map { (s, list) ->
                                    listOf(
                                        s, list.size.toString(), Format.money(list.sumOf { it.grandTotal }),
                                        Format.money(list.sumOf { it.advance }), Format.money(list.sumOf { it.balance })
                                    )
                                },
                                footer = listOf(
                                    "Total" to Format.money(totalAmount),
                                    "Advance received" to Format.money(totalAdvance),
                                    "Balance outstanding" to Format.money(totalAmount - totalAdvance)
                                )
                            )
                        }
                    }) { Icon(Icons.Filled.PictureAsPdf, "Download report PDF") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickReportDate(context, fromMillis) { fromMillis = it } }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.CalendarMonth, null, Modifier.size(16.dp)); Text(" ${Format.date(fromMillis)}", maxLines = 1)
                }
                OutlinedButton(onClick = { pickReportDate(context, toMillis) { toMillis = it } }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.CalendarMonth, null, Modifier.size(16.dp)); Text(" ${Format.date(toMillis)}", maxLines = 1)
                }
            }

            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Job cards"); Text("${inRange.size}", fontWeight = FontWeight.Bold)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Total amount"); Text(Format.rupee(totalAmount), fontWeight = FontWeight.Bold)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Advance received"); Text(Format.rupee(totalAdvance), fontWeight = FontWeight.Bold)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Balance outstanding", fontWeight = FontWeight.SemiBold)
                        Text(Format.rupee(totalAmount - totalAdvance), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text("Status", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text("Cards", Modifier.padding(end = 16.dp), fontWeight = FontWeight.Bold)
                Text("Balance", fontWeight = FontWeight.Bold)
            }
            Divider()
            if (byStatus.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No job cards in this range", color = MaterialTheme.colorScheme.outline)
            }
            else LazyColumn {
                items(byStatus, key = { it.first }) { (status, list) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { drillStatus = status }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(status, Modifier.weight(1f), color = statusColor(status), fontWeight = FontWeight.SemiBold)
                        Text("${list.size}", Modifier.padding(end = 32.dp))
                        Text(Format.rupee(list.sumOf { it.balance }))
                    }
                    Divider()
                }
            }
        }
    }

    drillStatus?.let { status ->
        val list = inRange.filter { it.status == status }
        AlertDialog(
            onDismissRequest = { drillStatus = null },
            title = { Text("$status (${list.size})") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(list, key = { it.id }) { c ->
                        Column(Modifier.fillMaxWidth().clickable { drillStatus = null; onOpenCard(c.id) }.padding(vertical = 8.dp)) {
                            Text("${c.jobNo}  ${c.customerName}", fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(
                                "${Format.date(c.dateMillis)}  •  Bal ${Format.rupee(c.balance)}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Divider()
                    }
                }
            },
            confirmButton = { TextButton(onClick = { drillStatus = null }) { Text("Close") } }
        )
    }
}

/** Open cards past (or approaching) their expected completion date. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceDueReportScreen(
    onBack: () -> Unit,
    onOpenCard: (Long) -> Unit,
    vm: ServiceReportViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val cards by vm.cards.collectAsStateSafe()
    val downloadPdf = rememberPdfDownloader { msg -> scope.launch { snackbar.showSnackbar(msg) } }

    var asOf by remember { mutableStateOf(System.currentTimeMillis()) }

    // Open = neither completed nor rejected. Sorted so the most overdue come first;
    // cards with no expected date sink to the bottom.
    val open = cards.filter { !it.status.equals("Completed", true) && !it.status.equals("Rejected", true) }
        .sortedWith(compareBy({ it.expectedMillis <= 0 }, { it.expectedMillis }))
    val overdue = open.filter { it.expectedMillis in 1 until asOf }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Pending Jobs") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = {
                        downloadPdf {
                            TablePdf.generate(
                                context, AppPrefs(context).company,
                                "Pending Jobs", "As of ${Format.date(asOf)}",
                                listOf(
                                    TablePdf.Col("Job no", 1.2f), TablePdf.Col("Customer", 2f),
                                    TablePdf.Col("Status", 1.2f), TablePdf.Col("Expected", 1.2f),
                                    TablePdf.Col("Balance", 1.2f, right = true)
                                ),
                                open.map {
                                    listOf(
                                        it.jobNo, it.customerName, it.status,
                                        if (it.expectedMillis > 0) Format.date(it.expectedMillis) else "-",
                                        Format.money(it.balance)
                                    )
                                },
                                footer = listOf(
                                    "Open cards" to "${open.size}",
                                    "Overdue" to "${overdue.size}",
                                    "Balance outstanding" to Format.money(open.sumOf { it.balance })
                                )
                            )
                        }
                    }) { Icon(Icons.Filled.PictureAsPdf, "Download report PDF") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
            OutlinedButton(onClick = { pickReportDate(context, asOf) { asOf = it } }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.CalendarMonth, null, Modifier.size(16.dp)); Text(" As of ${Format.date(asOf)}")
            }
            Text(
                "${open.size} open  •  ${overdue.size} overdue",
                Modifier.padding(vertical = 8.dp), fontWeight = FontWeight.SemiBold
            )
            Divider()
            if (open.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nothing pending", color = MaterialTheme.colorScheme.outline)
            }
            else LazyColumn {
                items(open, key = { it.id }) { c ->
                    val late = c.expectedMillis in 1 until asOf
                    val lateDays = if (late) TimeUnit.MILLISECONDS.toDays(asOf - c.expectedMillis) else 0
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenCard(c.id) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${c.jobNo}  ${c.customerName}", fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(
                                listOfNotNull(
                                    c.typeName.ifBlank { null },
                                    if (c.expectedMillis > 0) "exp ${Format.date(c.expectedMillis)}" else "no expected date",
                                    if (late) "$lateDays day(s) late" else null
                                ).joinToString("  •  "),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(c.status, color = statusColor(c.status), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            Text("Bal ${Format.rupee(c.balance)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Divider()
                }
            }
        }
    }
}

private fun pickReportDate(context: android.content.Context, current: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = current }
    android.app.DatePickerDialog(
        context,
        { _, y, m, d ->
            val cal = Calendar.getInstance().apply { set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0) }
            onPicked(cal.timeInMillis)
        },
        c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
    ).show()
}
