package com.billing.pos.ui.backup

import android.app.Application
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.AppDatabase
import com.billing.pos.data.MergeCategories
import com.billing.pos.data.MergeGroup
import com.billing.pos.data.MergeReport
import com.billing.pos.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One merged record, summarised for the list. */
data class MergedRow(val id: Long, val title: String, val subtitle: String, val amount: String)

class MergeLogViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)

    /**
     * Loads a one-line summary for each merged id in a category. Reads whole tables and
     * filters by id: the id sets are small and this avoids a bespoke DAO query per type.
     */
    suspend fun rowsFor(group: MergeGroup): List<MergedRow> = withContext(Dispatchers.IO) {
        val ids = group.ids.toSet()
        runCatching {
            when (group.key) {
                "bills" -> db.billDao().all().filter { it.id in ids }
                    .map { MergedRow(it.id, it.billNo, "${it.customerName} · ${Format.date(it.dateMillis)}", Format.rupee(it.grandTotal)) }
                "estimates" -> db.estimateDao().all().filter { it.id in ids }
                    .map { MergedRow(it.id, it.estimateNo, "${it.customerName} · ${Format.date(it.dateMillis)}", Format.rupee(it.grandTotal)) }
                "quotations" -> db.quotationDao().all().filter { it.id in ids }
                    .map { MergedRow(it.id, it.quotationNo, "${it.customerName} · ${Format.date(it.dateMillis)}", Format.rupee(it.grandTotal)) }
                "purchases" -> db.purchaseDao().all().filter { it.id in ids }
                    .map { MergedRow(it.id, it.purchaseNo, "${it.supplierName} · ${Format.date(it.dateMillis)}", Format.rupee(it.grandTotal)) }
                "items" -> db.itemDao().all().filter { it.id in ids }
                    .map { MergedRow(it.id, it.name, it.category.ifBlank { "—" }, Format.rupee(it.price)) }
                "customers" -> db.customerDao().all().filter { it.id in ids }
                    .map { MergedRow(it.id, it.name, it.phone.ifBlank { "—" }, "") }
                "suppliers" -> db.supplierDao().all().filter { it.id in ids }
                    .map { MergedRow(it.id, it.name, it.phone.ifBlank { "—" }, "") }
                "receipts" -> db.receiptDao().all().filter { it.id in ids }
                    .map { MergedRow(it.id, it.receiptNo, "${it.customerName} · ${Format.date(it.dateMillis)}", Format.rupee(it.amount)) }
                "expenses" -> db.expenseDao().all().filter { it.id in ids }
                    .map { MergedRow(it.id, it.voucherNo, "${it.payTo.ifBlank { it.description }} · ${Format.date(it.dateMillis)}", Format.rupee(it.amount)) }
                "diaryEntries" -> db.diaryDao().allEntries().filter { it.id in ids }
                    .map { MergedRow(it.id, it.title.ifBlank { "(untitled)" }, Format.dateTime(it.updatedAt), "") }
                "purchaseQuotes" -> db.purchaseQuoteDao().all().filter { it.id in ids }
                    .map { MergedRow(it.id, it.quoteNo, it.supplierName + " · " + Format.date(it.dateMillis), Format.rupee(it.grandTotal)) }
                "savedCalcs" -> db.savedCalcDao().all().filter { it.id in ids }
                    .map { MergedRow(it.id, Format.money(it.total), Format.dateTime(it.dateMillis), "") }
                "materialReceipts" -> db.materialReceiptDao().all().filter { it.id in ids }
                    .map { MergedRow(it.id, it.receiptNo, "${it.supplierName} · ${Format.date(it.dateMillis)}", "") }
                "users" -> db.userDao().all().filter { it.id in ids }
                    .map { MergedRow(it.id, it.username, it.role.label, "") }
                "accountHeads" -> db.accountDao().allHeads().filter { it.id in ids }
                    .map { MergedRow(it.id, it.name, "", "") }
                // Anything else: show the ids so the count is still auditable.
                else -> group.ids.map { MergedRow(it, "#$it", "", "") }
            }
        }.getOrElse { group.ids.map { MergedRow(it, "#$it", "", "") } }
    }
}

/** Summary of the last merge: totals per category. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeLogScreen(onBack: () -> Unit, onOpenGroup: (String) -> Unit) {
    val context = LocalContext.current
    val report = remember { MergeReport.load(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Merge log") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        if (report == null || report.nonEmpty().isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("No merge has been run yet", color = MaterialTheme.colorScheme.outline)
            }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
            Text(
                "Merged ${Format.dateTime(report.atMillis)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline
            )
            Text(
                "${report.total} record(s) added",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(report.nonEmpty(), key = { it.key }) { g ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenGroup(g.key) }.padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(g.label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Text(
                            "${g.count}",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline
                        )
                    }
                    Divider()
                }
            }
        }
    }
}

/** The entries merged in one category. Tapping one opens it for editing where possible. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeLogDetailScreen(
    groupKey: String,
    onBack: () -> Unit,
    onEdit: (route: String) -> Unit,
    vm: MergeLogViewModel = viewModel()
) {
    val context = LocalContext.current
    val report = remember { MergeReport.load(context) }
    val group = remember(groupKey) { report?.groups?.firstOrNull { it.key == groupKey } }
    var rows by remember { mutableStateOf<List<MergedRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(groupKey) {
        rows = group?.let { vm.rowsFor(it) } ?: emptyList()
        loading = false
    }

    val editRoute = MergeCategories.editRoute(groupKey)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(group?.label ?: "Merged records") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
            if (editRoute == null && !loading) {
                Text(
                    "These have no edit screen — open them from their own menu.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.id }) { r ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = editRoute != null) {
                                editRoute?.let { onEdit(it.replace("{id}", r.id.toString())) }
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                r.title,
                                fontWeight = FontWeight.SemiBold,
                                color = if (editRoute != null) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                            if (r.subtitle.isNotBlank()) {
                                Text(
                                    r.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                        if (r.amount.isNotBlank()) Text(r.amount, fontWeight = FontWeight.Bold)
                    }
                    Divider()
                }
            }
        }
    }
}
