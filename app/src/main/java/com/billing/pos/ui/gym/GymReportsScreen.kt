package com.billing.pos.ui.gym

import android.app.Application
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.GymFee
import com.billing.pos.data.GymMember
import com.billing.pos.data.GymRepository
import com.billing.pos.data.XlsxWriter
import com.billing.pos.pdf.TablePdf
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.io.File

class GymReportsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = GymRepository(app)
    val members = repo.members.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val fees = repo.allFees.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

private fun shareFile(context: android.content.Context, file: File, mime: String) {
    val shared = File(context.cacheDir, "shared").apply { mkdirs() }
    val dest = if (file.parentFile == shared) file else File(shared, file.name).also { file.copyTo(it, overwrite = true) }
    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", dest)
    val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType(mime)
        .putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(android.content.Intent.createChooser(i, "Share"))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GymDueReportScreen(onBack: () -> Unit, vm: GymReportsViewModel = viewModel()) {
    val context = LocalContext.current
    val members by vm.members.collectAsState()
    val fees by vm.fees.collectAsState()
    val nameById = members.associate { it.id to it.name }
    val today = System.currentTimeMillis()
    data class DueRow(val member: String, val kind: String, val due: Long, val balance: Double, val overdue: Boolean)
    val rows = fees.mapNotNull { f ->
        val bal = f.amount - f.paidAmount
        if (bal <= 0.01) null else DueRow(nameById[f.memberId] ?: "?", f.kind, f.dueDateMillis, bal, f.dueDateMillis < today)
    }.sortedBy { it.due }
    val total = rows.sumOf { it.balance }
    val overdue = rows.filter { it.overdue }.sumOf { it.balance }

    fun sharePdf() {
        val cols = listOf(TablePdf.Col("Member", 2.2f), TablePdf.Col("Fee", 1.6f), TablePdf.Col("Due date", 1.3f), TablePdf.Col("Balance", 1.2f, right = true))
        val data = rows.map { listOf(it.member, it.kind, Format.date(it.due) + (if (it.overdue) " !" else ""), Format.money(it.balance)) }
        val f = TablePdf.generate(context, AppPrefs(context).company, "Fees Due", "Overdue: ${Format.money(overdue)}", cols, data, listOf("TOTAL DUE" to Format.money(total)))
        shareFile(context, f, "application/pdf")
    }
    fun shareExcel() {
        val t = XlsxWriter::text; val n = XlsxWriter::num
        val x = ArrayList<List<XlsxWriter.Cell>>()
        x.add(XlsxWriter.row(t("Member"), t("Fee"), t("Due date"), t("Overdue"), t("Balance")))
        rows.forEach { x.add(XlsxWriter.row(t(it.member), t(it.kind), t(Format.date(it.due)), t(if (it.overdue) "Yes" else ""), n(it.balance))) }
        val f = File(File(context.cacheDir, "shared").apply { mkdirs() }, "fees-due.xlsx")
        XlsxWriter.write(f, "Fees Due", x)
        shareFile(context, f, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Fees Due") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary, titleContentColor = MaterialTheme.colorScheme.onPrimary, navigationIconContentColor = MaterialTheme.colorScheme.onPrimary))
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text("Total due ${Format.money(total)}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("Overdue ${Format.money(overdue)}", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { sharePdf() }, modifier = Modifier.weight(1f)) { Text("Share PDF") }
                OutlinedButton(onClick = { shareExcel() }, modifier = Modifier.weight(1f)) { Text("Share Excel") }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows) { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(r.member, style = MaterialTheme.typography.bodyMedium)
                            Text("${r.kind} · due ${Format.date(r.due)}", style = MaterialTheme.typography.labelSmall,
                                color = if (r.overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline)
                        }
                        Text(Format.money(r.balance), fontWeight = FontWeight.Bold)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GymSlotReportScreen(onBack: () -> Unit, vm: GymReportsViewModel = viewModel()) {
    val members by vm.members.collectAsState()
    val bySlot = members.filter { it.active }.groupBy { it.slot.ifBlank { "(no slot)" } }.toSortedMap()

    Scaffold(topBar = {
        TopAppBar(title = { Text("Slots / Timings") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary, titleContentColor = MaterialTheme.colorScheme.onPrimary, navigationIconContentColor = MaterialTheme.colorScheme.onPrimary))
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
            bySlot.forEach { (slot, list) ->
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)) {
                        Text(slot, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        Text("${list.size} member(s)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                    }
                    HorizontalDivider()
                }
                items(list) { m ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(m.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        if (m.trainer.isNotBlank()) Text(m.trainer, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
