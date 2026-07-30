package com.billing.pos.ui.purchasereturn

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Remove
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
import com.billing.pos.data.PurchaseReturn
import com.billing.pos.data.PurchaseReturnItem
import com.billing.pos.data.Repository
import com.billing.pos.data.Supplier
import com.billing.pos.data.UnitChoice
import com.billing.pos.data.hasTwoUnits
import com.billing.pos.data.primaryChoice
import com.billing.pos.ui.billing.CartLine
import com.billing.pos.ui.billing.ItemPickerDialog
import com.billing.pos.ui.billing.SaleBatchPickDialog
import com.billing.pos.ui.billing.UnitPickDialog
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.ui.common.DocumentPdfAction
import com.billing.pos.pdf.PdfDoc
import com.billing.pos.pdf.PdfLine
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PurchaseReturnViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    val suppliers: StateFlow<List<Supplier>> = repo.suppliers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val items: StateFlow<List<Item>> = repo.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val allBatches: StateFlow<List<com.billing.pos.data.ItemBatch>> = repo.itemBatches.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val returns: StateFlow<List<PurchaseReturn>> = repo.purchaseReturns.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    /** Past purchases, for the "against bill" picker (newest first). */
    val purchases: StateFlow<List<com.billing.pos.data.Purchase>> =
        repo.allPurchases.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var selectedSupplier by mutableStateOf<Supplier?>(null); private set
    val cart: SnapshotStateList<CartLine> = mutableStateListOf()
    var additionalChargeText by mutableStateOf("")
    var discountText by mutableStateOf("")
    var remarks by mutableStateOf("")
    var billNo by mutableStateOf("")
    /** true = return against a chosen purchase (auto-fills the items); false = direct return. */
    var againstBill by mutableStateOf(false)

    /** Loads every line of [purchase] into the cart and picks its supplier. */
    fun loadFromPurchase(purchase: com.billing.pos.data.Purchase) {
        billNo = purchase.purchaseNo
        selectedSupplier = suppliers.value.firstOrNull { it.id == purchase.supplierId }
            ?: Supplier(purchase.supplierId, purchase.supplierName)
        viewModelScope.launch {
            val lines = repo.purchaseLinesFor(purchase.id)
            cart.clear()
            lines.forEach {
                val perUnit = if (it.qty > 0) it.primaryQty / it.qty else 1.0
                val itemId = items.value.firstOrNull { m -> m.name.equals(it.name, ignoreCase = true) }?.id ?: 0L
                cart.add(CartLine(itemId, it.name, it.price, it.taxPercent, it.qty, batchNo = it.batchNo, unit = it.unit, primaryPerUnit = if (perUnit > 0) perUnit else 1.0))
            }
            if (lines.isEmpty()) message.value = "That purchase has no items"
        }
    }
    var returnNo by mutableStateOf("PR-0001"); private set
    var dateMillis by mutableStateOf(System.currentTimeMillis())
    var editingId by mutableStateOf<Long?>(null); private set
    private var loaded = false

    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    init {
        viewModelScope.launch { repo.ensureDefaults(); returnNo = repo.nextPurchaseReturnNo() }
        viewModelScope.launch { suppliers.collect { list -> if (selectedSupplier == null && list.isNotEmpty()) selectedSupplier = list.firstOrNull { it.isDefault } ?: list.first() } }
    }

    val subTotal get() = cart.sumOf { it.base }
    val taxTotal get() = cart.sumOf { it.tax }
    val additionalCharge get() = additionalChargeText.toDoubleOrNull() ?: 0.0
    val discount get() = discountText.toDoubleOrNull() ?: 0.0
    val grandTotal get() = subTotal + taxTotal + additionalCharge - discount

    fun selectSupplier(s: Supplier) { selectedSupplier = s }
    fun addItemToCart(item: Item) = addItemWithUnit(item, item.primaryChoice())

    fun addItemWithUnit(item: Item, choice: UnitChoice) {
        val idx = cart.indexOfFirst { it.itemId == item.id && it.batchNo.isBlank() && it.unit == choice.unit && item.id != 0L }
        if (idx >= 0) cart[idx] = cart[idx].copy(qty = cart[idx].qty + 1)
        else cart.add(CartLine(item.id, item.name, choice.price, item.taxPercent, 1.0, unit = choice.unit, primaryPerUnit = choice.primaryPerUnit))
    }
    fun addItemWithBatch(item: Item, batch: com.billing.pos.data.ItemBatch, choice: UnitChoice = item.primaryChoice()) {
        val idx = cart.indexOfFirst { it.itemId == item.id && it.batchNo == batch.batchNo && it.unit == choice.unit }
        if (idx >= 0) cart[idx] = cart[idx].copy(qty = cart[idx].qty + 1)
        else cart.add(CartLine(item.id, item.name, choice.price, item.taxPercent, 1.0, batchNo = batch.batchNo, unit = choice.unit, primaryPerUnit = choice.primaryPerUnit))
    }
    fun changeQty(i: Int, d: Double) { val l = cart.getOrNull(i) ?: return; val q = l.qty + d; if (q <= 0) cart.removeAt(i) else cart[i] = l.copy(qty = q) }
    fun setQty(i: Int, q: Double) { val l = cart.getOrNull(i) ?: return; if (q <= 0) cart.removeAt(i) else cart[i] = l.copy(qty = q) }
    fun setLinePrice(i: Int, p: Double) { val l = cart.getOrNull(i) ?: return; cart[i] = l.copy(price = p) }
    fun removeLine(i: Int) { cart.removeAt(i) }

    fun load(id: Long) {
        if (loaded || id <= 0) { loaded = true; return }
        loaded = true
        viewModelScope.launch {
            val r = repo.purchaseReturnById(id) ?: return@launch
            editingId = r.id; returnNo = r.returnNo; dateMillis = r.dateMillis
            additionalChargeText = if (r.additionalCharge != 0.0) r.additionalCharge.toString() else ""
            discountText = if (r.discount != 0.0) r.discount.toString() else ""
            remarks = r.remarks; billNo = r.billNo
            selectedSupplier = suppliers.value.firstOrNull { it.id == r.supplierId } ?: Supplier(r.supplierId, r.supplierName)
            cart.clear()
            repo.purchaseReturnLines(id).forEach {
                val perUnit = if (it.primaryQty > 0 && it.qty > 0) it.primaryQty / it.qty else 1.0
                cart.add(CartLine(it.itemId, it.name, it.price, it.taxPercent, it.qty, batchNo = it.batchNo, unit = it.unit, primaryPerUnit = perUnit))
            }
        }
    }

    fun newReturn() {
        cart.clear(); additionalChargeText = ""; discountText = ""; remarks = ""; billNo = ""
        dateMillis = System.currentTimeMillis(); editingId = null
        selectedSupplier = suppliers.value.firstOrNull { it.isDefault } ?: suppliers.value.firstOrNull()
        viewModelScope.launch { returnNo = repo.nextPurchaseReturnNo() }
    }

    fun save(onDone: () -> Unit) {
        val supplier = selectedSupplier
        if (supplier == null) { message.value = "Select a supplier"; return }
        if (cart.isEmpty()) { message.value = "Add at least one item"; return }
        viewModelScope.launch {
            val r = PurchaseReturn(
                id = editingId ?: 0, returnNo = returnNo, dateMillis = dateMillis,
                supplierId = supplier.id, supplierName = supplier.name, billNo = billNo.trim(),
                subTotal = subTotal, taxTotal = taxTotal, additionalCharge = additionalCharge,
                discount = discount, grandTotal = grandTotal, remarks = remarks.trim()
            )
            val lines = cart.map { PurchaseReturnItem(0, r.id, it.itemId, it.name, it.qty, it.price, it.taxPercent, it.total, it.batchNo, it.unit, it.primaryQty) }
            val eid = editingId
            if (eid != null) { repo.updatePurchaseReturn(r, lines); message.value = "Return $returnNo updated" }
            else {
                repo.savePurchaseReturn(r, lines)
                // Goods returned to supplier leave batch stock (new returns only).
                cart.filter { it.batchNo.isNotBlank() && it.itemId > 0 }.forEach { repo.deductBatch(it.itemId, it.batchNo, it.primaryQty) }
                editingId = null
                message.value = "Return $returnNo saved"
            }
            onDone()
        }
    }

    fun delete(r: PurchaseReturn) { viewModelScope.launch { repo.deletePurchaseReturn(r); message.value = "Return ${r.returnNo} deleted" } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurchaseReturnScreen(editId: Long?, onBack: () -> Unit, vm: PurchaseReturnViewModel = viewModel()) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val items by vm.items.collectAsStateSafe()
    val suppliers by vm.suppliers.collectAsStateSafe()
    val allBatches by vm.allBatches.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    val requireBatch = remember { AppPrefs(context).requireItemBatch }
    LaunchedEffect(Unit) { if (editId != null && editId > 0) vm.load(editId) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    var showItemPicker by remember { mutableStateOf(false) }
    var batchPickFor by remember { mutableStateOf<Item?>(null) }
    var unitPickFor by remember { mutableStateOf<Item?>(null) }
    var pendingChoice by remember { mutableStateOf<UnitChoice?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (vm.editingId != null) "Edit Purchase Return" else "Purchase Return") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    DocumentPdfAction(onMessage = { vm.message.value = it }) {
                        if (vm.cart.isEmpty()) { vm.message.value = "Add at least one item"; null }
                        else PdfDoc(
                            docTitle = "PURCHASE RETURN", docNo = vm.returnNo, dateMillis = vm.dateMillis,
                            partyLabel = "Return To", partyName = vm.selectedSupplier?.name ?: "",
                            extraMeta = if (vm.billNo.isNotBlank()) "Against: ${vm.billNo}" else "",
                            lines = vm.cart.map { PdfLine(it.name, it.qty, it.price, it.total, it.unit) },
                            subTotal = vm.subTotal, taxTotal = vm.taxTotal, additionalCharge = vm.additionalCharge,
                            discount = vm.discount, grandTotal = vm.grandTotal, grandLabel = "DEBIT TOTAL",
                            remarks = vm.remarks, filePrefix = "purchase_return"
                        )
                    }
                    IconButton(onClick = { vm.newReturn() }) { Icon(Icons.Filled.NoteAdd, "New") }
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
            var supMenu by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = supMenu, onExpandedChange = { supMenu = !supMenu }) {
                OutlinedTextField(
                    readOnly = true, value = vm.selectedSupplier?.name ?: "", onValueChange = {},
                    label = { Text("Supplier") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(supMenu) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = supMenu, onDismissRequest = { supMenu = false }) {
                    suppliers.forEach { s -> DropdownMenuItem(text = { Text(s.name) }, onClick = { vm.selectSupplier(s); supMenu = false }) }
                }
            }
            // Return type: Direct (type an optional purchase no) or Against bill (pick a purchase).
            var typeMenu by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = typeMenu, onExpandedChange = { typeMenu = !typeMenu }, modifier = Modifier.padding(top = 6.dp)) {
                OutlinedTextField(
                    readOnly = true, value = if (vm.againstBill) "Against bill" else "Direct", onValueChange = {},
                    label = { Text("Return type") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeMenu) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                    DropdownMenuItem(text = { Text("Direct") }, onClick = { vm.againstBill = false; typeMenu = false })
                    DropdownMenuItem(text = { Text("Against bill") }, onClick = { vm.againstBill = true; typeMenu = false })
                }
            }
            if (vm.againstBill) {
                val purchases by vm.purchases.collectAsStateSafe()
                com.billing.pos.ui.common.PurchasePickerField(
                    purchases = purchases,
                    selectedNo = vm.billNo,
                    onPick = { vm.loadFromPurchase(it) }
                )
            } else {
                OutlinedTextField(value = vm.billNo, onValueChange = { vm.billNo = it }, label = { Text("Against purchase no (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
            Button(onClick = { showItemPicker = true }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Icon(Icons.Filled.Add, null); Text("  Add returned item") }

            Card(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
                if (vm.cart.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No items", color = MaterialTheme.colorScheme.outline) }
                else LazyColumn(Modifier.padding(8.dp)) {
                    itemsIndexed(vm.cart, key = { _, l -> l.uid }) { i, line ->
                        var priceText by remember(line.uid) { mutableStateOf(Format.money(line.price)) }
                        var qtyText by remember(line.uid, line.qty) { mutableStateOf(Format.qty(line.qty)) }
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(line.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1)
                                if (line.unit.isNotBlank()) Text(line.unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                if (line.batchNo.isNotBlank()) Text("  B:${line.batchNo}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                IconButton(onClick = { vm.removeLine(i) }) { Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.error) }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(value = priceText, onValueChange = { v -> val f = v.filter { it.isDigit() || it == '.' }; priceText = f; vm.setLinePrice(i, f.toDoubleOrNull() ?: 0.0) }, label = { Text("Rate") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(116.dp))
                                Spacer(Modifier.width(6.dp))
                                IconButton(onClick = { vm.changeQty(i, -1.0) }) { Icon(Icons.Filled.Remove, "-") }
                                OutlinedTextField(value = qtyText, onValueChange = { v -> val f = v.filter { it.isDigit() || it == '.' }; qtyText = f; f.toDoubleOrNull()?.let { if (it > 0) vm.setQty(i, it) } }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(64.dp))
                                IconButton(onClick = { vm.changeQty(i, 1.0) }) { Icon(Icons.Filled.Add, "+") }
                                Spacer(Modifier.weight(1f))
                                Text(Format.rupee(line.total), fontWeight = FontWeight.Bold)
                            }
                            Divider()
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("Sub Total", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline); Text(Format.rupee(vm.subTotal + vm.taxTotal), fontWeight = FontWeight.SemiBold) }
                        OutlinedTextField(value = vm.additionalChargeText, onValueChange = { vm.additionalChargeText = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Add.") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(90.dp))
                        OutlinedTextField(value = vm.discountText, onValueChange = { vm.discountText = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Disc.") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(90.dp))
                    }
                    Divider(Modifier.padding(vertical = 6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("DEBIT TOTAL", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(Format.rupee(vm.grandTotal), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            Button(onClick = { vm.save { } }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Save return") }
        }
    }

    if (showItemPicker) {
        ItemPickerDialog(
            items = items,
            onDismiss = { showItemPicker = false },
            onPick = { picked ->
                showItemPicker = false
                when {
                    picked.hasTwoUnits -> unitPickFor = picked
                    requireBatch && allBatches.any { it.itemId == picked.id } -> {
                        pendingChoice = picked.primaryChoice(); batchPickFor = picked
                    }
                    else -> vm.addItemToCart(picked)
                }
            },
            onNewItem = { showItemPicker = false }
        )
    }
    unitPickFor?.let { item ->
        UnitPickDialog(
            item = item,
            onPick = { choice ->
                unitPickFor = null
                if (requireBatch && allBatches.any { it.itemId == item.id }) {
                    pendingChoice = choice; batchPickFor = item
                } else vm.addItemWithUnit(item, choice)
            },
            onDismiss = { unitPickFor = null }
        )
    }
    batchPickFor?.let { item ->
        SaleBatchPickDialog(
            item = item,
            batches = allBatches.filter { it.itemId == item.id },
            onPick = { batch ->
                vm.addItemWithBatch(item, batch, pendingChoice ?: item.primaryChoice())
                pendingChoice = null; batchPickFor = null
            },
            onDismiss = { pendingChoice = null; batchPickFor = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurchaseReturnListScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, onNew: () -> Unit, vm: PurchaseReturnViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    val returns by vm.returns.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    var deleteFor by remember { mutableStateOf<PurchaseReturn?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Purchase Returns") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = onNew) { Icon(Icons.Filled.Add, "New") } }
    ) { pad ->
        if (returns.isEmpty()) Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { Text("No purchase returns yet", color = MaterialTheme.colorScheme.outline) }
        else LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
            items(returns, key = { it.id }) { r ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(r.id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.returnNo, fontWeight = FontWeight.Bold)
                        Text("${r.supplierName} • ${Format.date(r.dateMillis)}" + (if (r.billNo.isNotBlank()) " • vs ${r.billNo}" else ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    Text(Format.rupee(r.grandTotal), fontWeight = FontWeight.Bold)
                    IconButton(onClick = { deleteFor = r }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                }
                Divider()
            }
        }
    }

    deleteFor?.let { r ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete ${r.returnNo}?") },
            confirmButton = { TextButton(onClick = { vm.delete(r); deleteFor = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } }
        )
    }
}
