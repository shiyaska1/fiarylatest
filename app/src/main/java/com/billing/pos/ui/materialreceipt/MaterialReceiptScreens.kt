package com.billing.pos.ui.materialreceipt

import android.app.Application
import android.app.DatePickerDialog
import java.util.Calendar
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.Item
import com.billing.pos.data.MaterialReceipt
import com.billing.pos.data.MaterialReceiptItem
import com.billing.pos.data.PurchaseQuotation
import com.billing.pos.data.Repository
import com.billing.pos.data.Supplier
import com.billing.pos.data.costRate
import com.billing.pos.data.hasTwoUnits
import com.billing.pos.data.primaryCostChoice
import com.billing.pos.ui.billing.CartLine
import com.billing.pos.ui.billing.ItemPickerDialog
import com.billing.pos.ui.billing.UnitPickDialog
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.ui.common.LpoPickerField
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MaterialReceiptViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    val suppliers: StateFlow<List<Supplier>> = repo.suppliers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val items: StateFlow<List<Item>> = repo.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val allBatches: StateFlow<List<com.billing.pos.data.ItemBatch>> = repo.itemBatches.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val lpos: StateFlow<List<PurchaseQuotation>> = repo.purchaseQuotations.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val receipts: StateFlow<List<MaterialReceipt>> = repo.materialReceipts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var selectedSupplier by mutableStateOf<Supplier?>(null); private set
    val cart: SnapshotStateList<CartLine> = mutableStateListOf()
    var lpoId by mutableStateOf(0L); private set
    var lpoNo by mutableStateOf("")
    var remarks by mutableStateOf("")
    var receiptNo by mutableStateOf("MRN-0001"); private set
    var dateMillis by mutableStateOf(System.currentTimeMillis())
    var editingId by mutableStateOf<Long?>(null); private set
    private var loaded = false

    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    init {
        viewModelScope.launch { repo.ensureDefaults(); receiptNo = repo.nextMrnNo() }
        viewModelScope.launch { suppliers.collect { list -> if (selectedSupplier == null && list.isNotEmpty()) selectedSupplier = list.firstOrNull { it.isDefault } ?: list.first() } }
    }

    val subTotal get() = cart.sumOf { it.base }
    val taxTotal get() = cart.sumOf { it.tax }
    val grandTotal get() = subTotal + taxTotal

    fun selectSupplier(s: Supplier) { selectedSupplier = s }
    fun setLpo(l: PurchaseQuotation) {
        lpoId = l.id; lpoNo = l.lpoNo
        selectedSupplier = suppliers.value.firstOrNull { it.id == l.supplierId } ?: Supplier(l.supplierId, l.supplierName)
        viewModelScope.launch {
            val lines = repo.purchaseQuotationLines(l.id)
            cart.clear()
            lines.forEach {
                val itemId = items.value.firstOrNull { m -> m.name.equals(it.name, ignoreCase = true) }?.id ?: it.itemId
                cart.add(CartLine(itemId, it.name, it.price, it.taxPercent, it.qty, unit = it.unit))
            }
            if (lines.isEmpty()) message.value = "That LPO has no items"
        }
    }

    fun addItemToCart(item: Item) = addItemWithUnit(item, item.primaryCostChoice())
    fun addItemWithUnit(item: Item, choice: com.billing.pos.data.UnitChoice) {
        val idx = cart.indexOfFirst { it.itemId == item.id && it.batchNo.isBlank() && it.unit == choice.unit && item.id != 0L }
        if (idx >= 0) cart[idx] = cart[idx].copy(qty = cart[idx].qty + 1)
        else cart.add(CartLine(item.id, item.name, choice.price, item.taxPercent, 1.0, unit = choice.unit, primaryPerUnit = choice.primaryPerUnit))
    }
    fun addBatchLine(item: Item, batchNo: String, expiryMillis: Long, qty: Double, price: Double) {
        cart.add(CartLine(item.id, item.name, price, item.taxPercent, qty.takeIf { it > 0 } ?: 1.0, batchNo = batchNo.trim(), batchExpiry = expiryMillis, unit = item.unit))
    }
    fun setQty(i: Int, q: Double) { val l = cart.getOrNull(i) ?: return; if (q <= 0) cart.removeAt(i) else cart[i] = l.copy(qty = q) }
    fun setLinePrice(i: Int, p: Double) { val l = cart.getOrNull(i) ?: return; cart[i] = l.copy(price = p) }
    fun removeLine(i: Int) { cart.removeAt(i) }

    fun load(id: Long) {
        if (loaded || id <= 0) { loaded = true; return }
        loaded = true
        viewModelScope.launch {
            val m = repo.materialReceiptById(id) ?: return@launch
            editingId = m.id; receiptNo = m.receiptNo; dateMillis = m.dateMillis
            lpoId = m.lpoId; lpoNo = m.lpoNo; remarks = m.remarks
            selectedSupplier = suppliers.value.firstOrNull { it.id == m.supplierId } ?: Supplier(m.supplierId, m.supplierName)
            cart.clear()
            repo.materialReceiptLines(id).forEach { cart.add(CartLine(it.itemId, it.name, it.price, it.taxPercent, it.qty, batchNo = it.batchNo, unit = it.unit)) }
        }
    }

    fun newVoucher() {
        cart.clear(); lpoId = 0; lpoNo = ""; remarks = ""; dateMillis = System.currentTimeMillis(); editingId = null
        viewModelScope.launch { receiptNo = repo.nextMrnNo() }
    }

    fun save(onDone: () -> Unit) {
        val supplier = selectedSupplier
        if (supplier == null) { message.value = "Select a supplier"; return }
        if (cart.isEmpty()) { message.value = "Add at least one item"; return }
        viewModelScope.launch {
            for (i in cart.indices) {
                val l = cart[i]
                if (l.itemId == 0L && l.name.isNotBlank()) {
                    val existing = items.value.firstOrNull { it.name.equals(l.name, true) }
                    val id = existing?.id ?: repo.addItem(l.name, 0.0, 0.0, unit = l.unit.ifBlank { "PCS" }, purchasePrice = l.price)
                    cart[i] = l.copy(itemId = id)
                }
            }
            val m = MaterialReceipt(
                id = editingId ?: 0, receiptNo = receiptNo, dateMillis = dateMillis,
                supplierId = supplier.id, supplierName = supplier.name, lpoId = lpoId, lpoNo = lpoNo, remarks = remarks.trim()
            )
            // Store the PRIMARY-unit quantity so stock increases correctly.
            val lines = cart.map {
                MaterialReceiptItem(0, m.id, it.itemId, it.name, it.primaryQty, it.price, it.taxPercent, it.total, it.batchNo, it.unit)
            }
            if (editingId != null) repo.updateMaterialReceipt(m, lines) else { repo.saveMaterialReceipt(m, lines); editingId = null }
            // Receive batch stock (add to existing / create new) for batch lines.
            cart.filter { it.batchNo.isNotBlank() && it.itemId > 0 }.forEach { repo.receiveBatch(it.itemId, it.batchNo, it.batchExpiry, it.primaryQty) }
            message.value = "Material receipt $receiptNo saved"
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaterialReceiptScreen(editId: Long?, onBack: () -> Unit, vm: MaterialReceiptViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(editId) { if (editId != null && editId > 0) vm.load(editId) }
    val suppliers by vm.suppliers.collectAsStateSafe()
    val items by vm.items.collectAsStateSafe()
    val lpos by vm.lpos.collectAsStateSafe()
    val allBatches by vm.allBatches.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    val requireBatch = remember { AppPrefs(context).requireItemBatch }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    var showItemPicker by remember { mutableStateOf(false) }
    var unitPickFor by remember { mutableStateOf<Item?>(null) }
    var batchFor by remember { mutableStateOf<Item?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (vm.editingId != null) "Edit Receipt" else "Material Receipt") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { vm.newVoucher() }) { Icon(Icons.Filled.Add, "New") } },
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(vm.receiptNo, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { pickDate(context, vm.dateMillis) { vm.dateMillis = it } }) { Text(Format.date(vm.dateMillis)) }
            }
            var supMenu by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = supMenu, onExpandedChange = { supMenu = !supMenu }, modifier = Modifier.padding(top = 6.dp)) {
                OutlinedTextField(
                    readOnly = true, value = vm.selectedSupplier?.name ?: "", onValueChange = {},
                    label = { Text("Supplier *") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(supMenu) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = supMenu, onDismissRequest = { supMenu = false }) {
                    suppliers.forEach { s -> DropdownMenuItem(text = { Text(s.name) }, onClick = { vm.selectSupplier(s); supMenu = false }) }
                }
            }
            LpoPickerField(lpos = lpos, supplierId = vm.selectedSupplier?.id ?: 0L, selectedNo = vm.lpoNo, onPick = { vm.setLpo(it) })

            Button(onClick = { showItemPicker = true }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Icon(Icons.Filled.Add, null); Text("  Add received item") }

            Card(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
                if (vm.cart.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No items received yet", color = MaterialTheme.colorScheme.outline) }
                else LazyColumn(Modifier.fillMaxWidth().padding(8.dp)) {
                    itemsIndexed(vm.cart) { i, line ->
                        var qtyText by remember(line.uid) { mutableStateOf(Format.qty(line.qty)) }
                        var priceText by remember(line.uid) { mutableStateOf(Format.money(line.price)) }
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(line.name + if (line.batchNo.isNotBlank()) "  [${line.batchNo}]" else "", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                                IconButton(onClick = { vm.removeLine(i) }) { Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.error) }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = qtyText, onValueChange = { v -> val f = v.filter { it.isDigit() || it == '.' }; qtyText = f; f.toDoubleOrNull()?.let { if (it > 0) vm.setQty(i, it) } }, label = { Text("Received qty") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(130.dp))
                                OutlinedTextField(value = priceText, onValueChange = { v -> val f = v.filter { it.isDigit() || it == '.' }; priceText = f; vm.setLinePrice(i, f.toDoubleOrNull() ?: 0.0) }, label = { Text("Rate") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(120.dp))
                            }
                            Divider(Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }

            OutlinedTextField(value = vm.remarks, onValueChange = { vm.remarks = it }, label = { Text("Remarks") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            Button(onClick = { vm.save { onBack() } }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Save receipt  (stock in)") }
        }
    }

    if (showItemPicker) {
        ItemPickerDialog(
            items = items,
            onDismiss = { showItemPicker = false },
            onPick = { picked ->
                showItemPicker = false
                when {
                    requireBatch -> batchFor = picked
                    picked.hasTwoUnits -> unitPickFor = picked
                    else -> vm.addItemToCart(picked)
                }
            },
            onNewItem = { showItemPicker = false }
        )
    }
    unitPickFor?.let { item ->
        UnitPickDialog(item = item, useCost = true, onPick = { choice -> unitPickFor = null; vm.addItemWithUnit(item, choice) }, onDismiss = { unitPickFor = null })
    }
    batchFor?.let { item ->
        ReceiptBatchDialog(
            item = item,
            existing = allBatches.filter { it.itemId == item.id },
            onAdd = { no, exp, qty, price -> vm.addBatchLine(item, no, exp, qty, price); batchFor = null },
            onDismiss = { batchFor = null }
        )
    }
}

/** Batch capture when receiving: batch no, expiry, qty and rate. */
@Composable
private fun ReceiptBatchDialog(
    item: Item,
    existing: List<com.billing.pos.data.ItemBatch>,
    onAdd: (String, Long, Double, Double) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var batchNo by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf(0L) }
    var qty by remember { mutableStateOf("1") }
    var price by remember { mutableStateOf(if (item.costRate > 0) Format.money(item.costRate) else "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Receive batch — ${item.name}") },
        text = {
            Column {
                if (existing.isNotEmpty()) {
                    Text("Existing:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    existing.take(4).forEach { b ->
                        Text("• ${b.batchNo}  (${Format.qty(b.quantity)})", Modifier.clickable { batchNo = b.batchNo; expiry = b.expiryMillis }.padding(vertical = 2.dp), color = MaterialTheme.colorScheme.primary)
                    }
                }
                OutlinedTextField(value = batchNo, onValueChange = { batchNo = it }, label = { Text("Batch no *") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                OutlinedButton(onClick = { pickDate(context, if (expiry > 0) expiry else System.currentTimeMillis()) { expiry = it } }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(if (expiry > 0) "Expiry: ${Format.date(expiry)}" else "Set expiry (optional)")
                }
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = qty, onValueChange = { qty = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Qty *") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                    OutlinedTextField(value = price, onValueChange = { price = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Rate") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = batchNo.isNotBlank() && (qty.toDoubleOrNull() ?: 0.0) > 0.0, onClick = { onAdd(batchNo.trim(), expiry, qty.toDoubleOrNull() ?: 0.0, price.toDoubleOrNull() ?: item.costRate) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaterialReceiptListScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, onNew: () -> Unit, vm: MaterialReceiptViewModel = viewModel()) {
    val receipts by vm.receipts.collectAsStateSafe()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Material Receipts") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = onNew) { Icon(Icons.Filled.Add, "New receipt") } }
    ) { pad ->
        if (receipts.isEmpty()) Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { Text("No receipts yet", color = MaterialTheme.colorScheme.outline) }
        else LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            items(receipts, key = { it.id }) { r ->
                Column(Modifier.fillMaxWidth().clickable { onOpen(r.id) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(r.receiptNo + (if (r.lpoNo.isNotBlank()) "  •  vs ${r.lpoNo}" else ""), fontWeight = FontWeight.SemiBold)
                    Text("${r.supplierName} • ${Format.date(r.dateMillis)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                Divider()
            }
        }
    }
}

private fun pickDate(context: android.content.Context, current: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = current }
    DatePickerDialog(context, { _, y, m, d -> onPicked(Calendar.getInstance().apply { set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis) }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
}
