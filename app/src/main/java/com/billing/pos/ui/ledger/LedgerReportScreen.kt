package com.billing.pos.ui.ledger

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.AccountGroup
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.Repository
import com.billing.pos.data.XlsxWriter
import com.billing.pos.pdf.TablePdf
import com.billing.pos.report.AccountingEngine
import com.billing.pos.report.Posting
import com.billing.pos.util.Format
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar

internal data class LedgerRow(val date: Long, val particulars: String, val vch: String, val debit: Double, val credit: Double, val balance: Double)
internal data class LedgerResult(val opening: Double, val rows: List<LedgerRow>, val closing: Double)

private fun drcr(v: Double): String = if (v >= 0) "${Format.money(v)} Dr" else "${Format.money(-v)} Cr"

class LedgerReportViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    val groups = repo.accountGroups.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** The unified account-transaction view — same source used by P&L / Balance Sheet. */
    val postings: kotlinx.coroutines.flow.StateFlow<List<Posting>> =
        combine(
            listOf<Flow<Any?>>(
                repo.accountHeads, repo.accountGroups, repo.allBills, repo.allPurchases, repo.allReceipts,
                repo.allExpenses, repo.journalEntries, repo.journalLines, repo.salesReturns, repo.purchaseReturns
            )
        ) { a ->
            @Suppress("UNCHECKED_CAST")
            AccountingEngine.build(
                a[0] as List<com.billing.pos.data.AccountHead>, a[1] as List<AccountGroup>,
                a[2] as List<com.billing.pos.data.Bill>, a[3] as List<com.billing.pos.data.Purchase>,
                a[4] as List<com.billing.pos.data.Receipt>, a[5] as List<com.billing.pos.data.Expense>,
                a[6] as List<com.billing.pos.data.JournalEntry>, a[7] as List<com.billing.pos.data.JournalLine>,
                a[8] as List<com.billing.pos.data.SalesReturn>, a[9] as List<com.billing.pos.data.PurchaseReturn>
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

private fun accountNames(postings: List<Posting>, groupName: String?): List<String> =
    postings.filter { groupName == null || it.group == groupName }
        .map { it.head }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }

private fun safeName(s: String): String = s.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)

private fun shareFile(context: android.content.Context, file: File, mime: String) {
    val shared = File(context.cacheDir, "shared").apply { mkdirs() }
    val dest = if (file.parentFile == shared) file else File(shared, file.name).also { file.copyTo(it, overwrite = true) }
    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", dest)
    val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType(mime)
        .putExtra(android.content.Intent.EXTRA_STREAM, uri)
        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(android.content.Intent.createChooser(i, "Share ledger"))
}

private fun shareLedger(context: android.content.Context, res: LedgerResult, name: String, from: Long, to: Long, asExcel: Boolean) {
    val fname = safeName(name.ifBlank { "ledger" })
    if (asExcel) {
        val t = XlsxWriter::text; val n = XlsxWriter::num
        val rows = ArrayList<List<XlsxWriter.Cell>>()
        rows.add(XlsxWriter.row(t("Date"), t("Particulars"), t("Voucher"), t("Debit"), t("Credit"), t("Balance")))
        rows.add(XlsxWriter.row(t(""), t("Opening Balance"), t(""), t(""), t(""), t(drcr(res.opening))))
        res.rows.forEach { r -> rows.add(XlsxWriter.row(t(Format.date(r.date)), t(r.particulars), t(r.vch), n(r.debit), n(r.credit), t(drcr(r.balance)))) }
        rows.add(XlsxWriter.row(t(""), t("Closing Balance"), t(""), t(""), t(""), t(drcr(res.closing))))
        val f = File(File(context.cacheDir, "shared").apply { mkdirs() }, "ledger-$fname.xlsx")
        XlsxWriter.write(f, "Ledger", rows)
        shareFile(context, f, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    } else {
        val cols = listOf(
            TablePdf.Col("Date", 1.3f), TablePdf.Col("Particulars", 2.5f), TablePdf.Col("Vch", 1.2f),
            TablePdf.Col("Debit", 1.2f, right = true), TablePdf.Col("Credit", 1.2f, right = true), TablePdf.Col("Balance", 1.5f, right = true)
        )
        val data = res.rows.map { listOf(Format.date(it.date), it.particulars, it.vch, if (it.debit != 0.0) Format.money(it.debit) else "", if (it.credit != 0.0) Format.money(it.credit) else "", drcr(it.balance)) }
        val sub = "$name   ·   ${Format.date(from)} to ${Format.date(to)}   ·   Opening ${drcr(res.opening)}"
        val f = TablePdf.generate(context, AppPrefs(context).company, "Ledger", sub, cols, data, listOf("Closing Balance" to drcr(res.closing)))
        shareFile(context, f, "application/pdf")
    }
}

private fun buildLedger(postings: List<Posting>, head: String, from: Long, to: Long): LedgerResult {
    val mine = postings.filter { it.head.equals(head, ignoreCase = true) }
    var opening = mine.filter { it.date < from }.sumOf { it.debit - it.credit }
    var running = opening
    val rows = mine.filter { it.date in from..to }.sortedBy { it.date }.map { p ->
        running += p.debit - p.credit
        LedgerRow(p.date, p.particulars, p.vch, p.debit, p.credit, running)
    }
    return LedgerResult(opening, rows, running)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerReportScreen(onBack: () -> Unit, vm: LedgerReportViewModel = viewModel()) {
    val context = LocalContext.current
    val groups by vm.groups.collectAsState()
    val postings by vm.postings.collectAsState()

    var groupId by remember { mutableStateOf(0L) }
    var groupMenu by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    var accQuery by remember { mutableStateOf("") }
    val cal = remember { Calendar.getInstance() }
    var toMillis by remember { mutableStateOf(cal.timeInMillis) }
    var fromMillis by remember { mutableStateOf(Calendar.getInstance().apply { add(Calendar.MONTH, -1) }.timeInMillis) }
    var result by remember { mutableStateOf<LedgerResult?>(null) }

    val groupName = if (groupId == 0L) null else groups.firstOrNull { it.id == groupId }?.name
    val names = remember(postings, groupName) { accountNames(postings, groupName) }

    fun pickDate(current: Long, onPicked: (Long) -> Unit) {
        val c = Calendar.getInstance().apply { timeInMillis = current }
        android.app.DatePickerDialog(context, { _, y, m, d ->
            onPicked(Calendar.getInstance().apply { set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis)
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ledger") },
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
            Box {
                OutlinedTextField(
                    value = if (groupId == 0L) "All groups" else groupName ?: "All groups",
                    onValueChange = {}, readOnly = true, label = { Text("Account group (optional)") },
                    trailingIcon = { IconButton(onClick = { groupMenu = true }) { Icon(Icons.Filled.ArrowDropDown, null) } },
                    modifier = Modifier.fillMaxWidth()
                )
                DropdownMenu(expanded = groupMenu, onDismissRequest = { groupMenu = false }) {
                    DropdownMenuItem(text = { Text("All groups") }, onClick = { groupId = 0; selected = null; result = null; groupMenu = false })
                    groups.forEach { g -> DropdownMenuItem(text = { Text(g.name) }, onClick = { groupId = g.id; selected = null; result = null; groupMenu = false }) }
                }
            }
            OutlinedTextField(
                value = if (selected != null) selected!! else accQuery,
                onValueChange = { accQuery = it; selected = null; result = null },
                label = { Text("Account (type to search)") },
                trailingIcon = { if (selected != null) IconButton(onClick = { selected = null; accQuery = "" }) { Icon(Icons.Filled.ArrowDropDown, "Clear") } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            if (selected == null && accQuery.isNotBlank()) {
                names.filter { it.contains(accQuery, ignoreCase = true) }.take(5).forEach { nm ->
                    Text(nm, modifier = Modifier.fillMaxWidth().clickable { selected = nm; accQuery = "" }.padding(vertical = 10.dp, horizontal = 8.dp),
                        style = MaterialTheme.typography.bodyMedium)
                    HorizontalDivider()
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickDate(fromMillis) { fromMillis = it } }, modifier = Modifier.weight(1f)) { Text("From: ${Format.date(fromMillis)}") }
                OutlinedButton(onClick = { pickDate(toMillis) { toMillis = it } }, modifier = Modifier.weight(1f)) { Text("To: ${Format.date(toMillis)}") }
            }
            Button(
                onClick = { selected?.let { result = buildLedger(postings, it, com.billing.pos.ui.common.startOfDay(fromMillis), com.billing.pos.ui.common.endOfDay(toMillis)) } },
                enabled = selected != null, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("View ledger") }

            result?.let { res ->
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { selected?.let { shareLedger(context, res, it, com.billing.pos.ui.common.startOfDay(fromMillis), com.billing.pos.ui.common.endOfDay(toMillis), asExcel = false) } }, modifier = Modifier.weight(1f)) { Text("Share PDF") }
                    OutlinedButton(onClick = { selected?.let { shareLedger(context, res, it, com.billing.pos.ui.common.startOfDay(fromMillis), com.billing.pos.ui.common.endOfDay(toMillis), asExcel = true) } }, modifier = Modifier.weight(1f)) { Text("Share Excel") }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Opening balance", fontWeight = FontWeight.Bold)
                    Text(drcr(res.opening), fontWeight = FontWeight.Bold)
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("Date", Modifier.weight(1.2f), style = MaterialTheme.typography.labelSmall)
                    Text("Particulars", Modifier.weight(2f), style = MaterialTheme.typography.labelSmall)
                    Text("Debit", Modifier.weight(1.1f), style = MaterialTheme.typography.labelSmall)
                    Text("Credit", Modifier.weight(1.1f), style = MaterialTheme.typography.labelSmall)
                    Text("Balance", Modifier.weight(1.4f), style = MaterialTheme.typography.labelSmall)
                }
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f)) {
                    items(res.rows) { r ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(Format.date(r.date), Modifier.weight(1.2f), style = MaterialTheme.typography.bodySmall)
                            Column(Modifier.weight(2f)) {
                                Text(r.particulars, style = MaterialTheme.typography.bodySmall)
                                if (r.vch.isNotBlank()) Text(r.vch, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                            Text(if (r.debit != 0.0) Format.money(r.debit) else "", Modifier.weight(1.1f), style = MaterialTheme.typography.bodySmall)
                            Text(if (r.credit != 0.0) Format.money(r.credit) else "", Modifier.weight(1.1f), style = MaterialTheme.typography.bodySmall)
                            Text(drcr(r.balance), Modifier.weight(1.4f), style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Closing balance", fontWeight = FontWeight.Bold)
                    Text(drcr(res.closing), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
