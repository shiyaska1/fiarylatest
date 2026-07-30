package com.billing.pos.ui.materialout

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.Item
import com.billing.pos.data.MoveRow
import com.billing.pos.data.Repository
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** One line of the item-movement ledger. */
data class MovLine(val date: Long, val voucherNo: String, val kind: String, val voucherId: Long, val delta: Double, val balance: Double)

class ItemMovementViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    val items: StateFlow<List<Item>> = repo.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val sales: StateFlow<List<MoveRow>> = repo.saleMovements.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val purchases: StateFlow<List<MoveRow>> = repo.purchaseMovements.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val materials: StateFlow<List<MoveRow>> = repo.materialMovements.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemMovementScreen(onBack: () -> Unit, onOpenVoucher: (String, Long) -> Unit, vm: ItemMovementViewModel = viewModel()) {
    val items by vm.items.collectAsStateSafe()
    val sales by vm.sales.collectAsStateSafe()
    val purchases by vm.purchases.collectAsStateSafe()
    val materials by vm.materials.collectAsStateSafe()
    var selected by remember { mutableStateOf<Item?>(null) }

    val opening = selected?.openingStock ?: 0.0
    val rows: List<MovLine> = remember(selected, sales, purchases, materials) {
        val item = selected ?: return@remember emptyList()
        val key = item.name.lowercase()
        val raw = buildList {
            purchases.filter { it.name.lowercase() == key }.forEach { add(Triple(it, "PURCHASE", it.qty)) }
            sales.filter { it.name.lowercase() == key }.forEach { add(Triple(it, "SALE", -it.qty)) }
            materials.filter { it.name.lowercase() == key }.forEach { add(Triple(it, "MATERIAL", -it.qty)) }
        }.sortedBy { it.first.dateMillis }
        var bal = item.openingStock
        raw.map { (mv, kind, delta) -> bal += delta; MovLine(mv.dateMillis, mv.voucherNo, kind, mv.voucherId, delta, bal) }
    }
    val closing = if (rows.isEmpty()) opening else rows.last().balance

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Item Movement") },
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
            var itemMenu by remember { mutableStateOf(false) }
            var q by remember { mutableStateOf("") }
            val shown = if (q.isBlank()) items else items.filter { it.name.contains(q, true) }
            ExposedDropdownMenuBox(expanded = itemMenu, onExpandedChange = { itemMenu = !itemMenu }, modifier = Modifier.padding(12.dp)) {
                OutlinedTextField(
                    value = selected?.name ?: q, onValueChange = { q = it; selected = null; itemMenu = true },
                    label = { Text("Pick / search item") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(itemMenu) },
                    singleLine = true, modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = itemMenu, onDismissRequest = { itemMenu = false }) {
                    shown.take(30).forEach { it2 ->
                        DropdownMenuItem(text = { Text(it2.name) }, onClick = { selected = it2; q = ""; itemMenu = false })
                    }
                }
            }

            if (selected == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Pick an item to see its movement", color = MaterialTheme.colorScheme.outline) }
            else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text("Date", Modifier.width(78.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Text("Voucher", Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Text("In/Out", Modifier.width(70.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Text("Balance", Modifier.width(70.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                }
                Divider()
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text("—", Modifier.width(78.dp), style = MaterialTheme.typography.bodySmall)
                            Text("Opening stock", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            Text("", Modifier.width(70.dp))
                            Text(Format.qty(opening), Modifier.width(70.dp), fontWeight = FontWeight.Bold)
                        }
                        Divider()
                    }
                    items(rows, key = { it.kind + it.voucherId + it.date }) { r ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenVoucher(r.kind, r.voucherId) }.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(Format.date(r.date), Modifier.width(78.dp), style = MaterialTheme.typography.bodySmall)
                            Column(Modifier.weight(1f)) {
                                Text(r.voucherNo, maxLines = 1)
                                Text(kindLabel(r.kind), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                            Text(
                                (if (r.delta >= 0) "+" else "") + Format.qty(r.delta),
                                Modifier.width(70.dp),
                                color = if (r.delta >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            Text(Format.qty(r.balance), Modifier.width(70.dp), fontWeight = FontWeight.SemiBold)
                        }
                        Divider()
                    }
                }
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Balance stock", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(Format.qty(closing) + " " + (selected?.unit ?: ""), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

private fun kindLabel(kind: String) = when (kind) {
    "PURCHASE" -> "Purchase (in)"
    "SALE" -> "Sale (out)"
    "MATERIAL" -> "Material out"
    else -> kind
}
