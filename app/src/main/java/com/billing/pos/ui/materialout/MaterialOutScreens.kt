package com.billing.pos.ui.materialout

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
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PhotoCamera
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
import com.billing.pos.data.Item
import com.billing.pos.data.MaterialOut
import com.billing.pos.data.MaterialOutItem
import com.billing.pos.data.Repository
import com.billing.pos.data.UnitChoice
import com.billing.pos.data.hasTwoUnits
import com.billing.pos.data.primaryChoice
import com.billing.pos.ui.billing.BillOcrReviewDialog
import com.billing.pos.ui.billing.CartLine
import com.billing.pos.ui.billing.EditLineNameDialog
import com.billing.pos.ui.billing.HandwriteQuickBillDialog
import com.billing.pos.ui.billing.ItemPickerDialog
import com.billing.pos.ui.billing.NewItemDialog
import com.billing.pos.ui.billing.UnitPickDialog
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.ocr.ScannedItem
import com.billing.pos.ocr.rememberListScanner
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One-shot hand-off of selected result invoices from the lab bill list to a new material-out. */
object MaterialOutLink {
    var refs: String? = null
    var tests: String? = null
    fun take(): Pair<String?, String?> { val r = refs to tests; refs = null; tests = null; return r }
}

class MaterialOutViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    val items: StateFlow<List<Item>> = repo.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val outs: StateFlow<List<MaterialOut>> = repo.materialOuts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    /** material-out movements (voucher id + item name), for the item filter on the list. */
    val movements: StateFlow<List<com.billing.pos.data.MoveRow>> = repo.materialMovements.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val stockByItem: StateFlow<Map<Long, Double>> =
        combine(repo.items, repo.stockByName) { list, byName ->
            list.associate { it.id to (it.openingStock + (byName[it.name.lowercase()] ?: 0.0)) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val cart: SnapshotStateList<CartLine> = mutableStateListOf()
    var resultRef by mutableStateOf("")
    var resultTests by mutableStateOf("")
    var remarks by mutableStateOf("")
    var voucherNo by mutableStateOf("MAT-0001"); private set
    var dateMillis by mutableStateOf(System.currentTimeMillis())
    var editingId by mutableStateOf<Long?>(null); private set
    private var loaded = false

    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    init { viewModelScope.launch { voucherNo = repo.nextMaterialOutNo() } }

    fun presetResults(refs: String, tests: String) {
        if (editingId == null && resultRef.isBlank()) { resultRef = refs; resultTests = tests }
    }

    fun addItemToCart(item: Item) = addItemWithUnit(item, item.primaryChoice())
    fun addItemWithUnit(item: Item, choice: UnitChoice) {
        val idx = cart.indexOfFirst { it.itemId == item.id && it.unit == choice.unit && item.id != 0L }
        if (idx >= 0) cart[idx] = cart[idx].copy(qty = cart[idx].qty + 1)
        else cart.add(CartLine(item.id, item.name, 0.0, 0.0, 1.0, unit = choice.unit, primaryPerUnit = choice.primaryPerUnit))
    }
    fun addByName(name: String, qty: Double) {
        if (name.isBlank()) return
        val match = items.value.firstOrNull { it.name.equals(name, true) }
        cart.add(CartLine(match?.id ?: 0L, name.trim(), 0.0, 0.0, if (qty > 0) qty else 1.0, unit = match?.unit ?: ""))
    }
    fun addOcrItems(scanned: List<ScannedItem>) { scanned.forEach { addByName(it.name, 1.0) } }
    fun setLineName(i: Int, newName: String) {
        val l = cart.getOrNull(i) ?: return; val t = newName.trim(); if (t.isBlank()) return
        val match = items.value.firstOrNull { it.name.equals(t, true) }
        cart[i] = l.copy(name = t, itemId = match?.id ?: 0L, uid = CartLine.nextUid())
    }
    fun changeQty(i: Int, d: Double) { val l = cart.getOrNull(i) ?: return; val q = l.qty + d; if (q <= 0) cart.removeAt(i) else cart[i] = l.copy(qty = q) }
    fun setQty(i: Int, q: Double) { val l = cart.getOrNull(i) ?: return; if (q <= 0) cart.removeAt(i) else cart[i] = l.copy(qty = q) }
    fun removeLine(i: Int) { cart.removeAt(i) }

    fun addItem(form: com.billing.pos.ui.billing.NewItemForm) {
        if (form.name.isBlank()) { message.value = "Enter item name"; return }
        viewModelScope.launch {
            val id = repo.addItem(form.name, form.price, form.taxPercent, form.barcode, form.hsn, form.category, form.openingStock, form.unit, form.storeLocation, secondaryUnit = form.secondaryUnit, conversionFactor = form.conversionFactor, purchasePrice = form.purchasePrice)
            form.attachments.forEach { repo.addItemAttachment(it.copy(itemId = id)) }
            cart.add(CartLine(id, form.name.trim(), 0.0, 0.0, 1.0, unit = form.unit))
        }
    }

    fun load(id: Long) {
        if (loaded || id <= 0) { loaded = true; return }
        loaded = true
        viewModelScope.launch {
            val m = repo.materialOutById(id) ?: return@launch
            editingId = m.id; voucherNo = m.voucherNo; dateMillis = m.dateMillis
            resultRef = m.resultRef; resultTests = m.resultTests; remarks = m.remarks
            cart.clear()
            repo.materialOutLines(id).forEach { cart.add(CartLine(it.itemId, it.name, 0.0, 0.0, it.qty, unit = it.unit)) }
        }
    }

    fun newVoucher() {
        cart.clear(); resultRef = ""; resultTests = ""; remarks = ""; dateMillis = System.currentTimeMillis(); editingId = null
        viewModelScope.launch { voucherNo = repo.nextMaterialOutNo() }
    }

    fun save(onDone: () -> Unit) {
        if (cart.isEmpty()) { message.value = "Add at least one item"; return }
        viewModelScope.launch {
            // Create master items for typed-in names.
            for (i in cart.indices) {
                val l = cart[i]
                if (l.itemId == 0L && l.name.isNotBlank()) {
                    val existing = items.value.firstOrNull { it.name.equals(l.name, true) }
                    val id = existing?.id ?: repo.addItem(l.name, 0.0, 0.0, unit = l.unit.ifBlank { "PCS" })
                    cart[i] = l.copy(itemId = id)
                }
            }
            val m = MaterialOut(id = editingId ?: 0, voucherNo = voucherNo, dateMillis = dateMillis, resultRef = resultRef.trim(), resultTests = resultTests.trim(), remarks = remarks.trim())
            // Store the PRIMARY-unit quantity so stock deducts correctly.
            val lines = cart.map { MaterialOutItem(0, m.id, it.itemId, it.name, it.primaryQty, it.unit) }
            if (editingId != null) repo.updateMaterialOut(m, lines) else { repo.saveMaterialOut(m, lines); editingId = null }
            message.value = "Material out $voucherNo saved"
            onDone()
        }
    }

    fun delete(m: MaterialOut) { viewModelScope.launch { repo.deleteMaterialOut(m); message.value = "Deleted ${m.voucherNo}" } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaterialOutScreen(editId: Long?, resultRefs: String?, resultTests: String?, onBack: () -> Unit, vm: MaterialOutViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    val items by vm.items.collectAsStateSafe()
    val stock by vm.stockByItem.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    LaunchedEffect(Unit) {
        if (editId != null && editId > 0) vm.load(editId)
        else if (!resultRefs.isNullOrBlank()) vm.presetResults(resultRefs, resultTests ?: "")
    }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    var showItemPicker by remember { mutableStateOf(false) }
    var showNewItem by remember { mutableStateOf(false) }
    var newItemName by remember { mutableStateOf("") }
    var showHandwrite by remember { mutableStateOf(false) }
    var unitPickFor by remember { mutableStateOf<Item?>(null) }
    var editNameFor by remember { mutableStateOf<Int?>(null) }
    var ocrReview by remember { mutableStateOf<List<ScannedItem>?>(null) }
    val scanList = rememberListScanner { lines -> ocrReview = lines.filter { it.isNotBlank() }.map { ScannedItem(it, 0.0) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (vm.editingId != null) "Edit Material Out" else "Material Out") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { vm.newVoucher() }) { Icon(Icons.Filled.NoteAdd, "New") } },
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
            OutlinedTextField(value = vm.resultRef, onValueChange = { vm.resultRef = it }, label = { Text("Against result invoice(s)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showItemPicker = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Add, null); Text(" Item") }
                OutlinedButton(onClick = { showHandwrite = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Draw, null); Text(" Draw") }
                OutlinedButton(onClick = { scanList() }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.PhotoCamera, null); Text(" Scan") }
            }

            Card(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
                if (vm.cart.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No items", color = MaterialTheme.colorScheme.outline) }
                else LazyColumn(Modifier.padding(8.dp)) {
                    itemsIndexed(vm.cart, key = { _, l -> l.uid }) { i, line ->
                        var qtyText by remember(line.uid, line.qty) { mutableStateOf(Format.qty(line.qty)) }
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { editNameFor = i }) {
                                Text(line.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                if (line.unit.isNotBlank()) Text(line.unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                            IconButton(onClick = { vm.changeQty(i, -1.0) }) { Icon(Icons.Filled.Remove, "-") }
                            OutlinedTextField(value = qtyText, onValueChange = { v -> val f = v.filter { it.isDigit() || it == '.' }; qtyText = f; f.toDoubleOrNull()?.let { if (it > 0) vm.setQty(i, it) } }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(70.dp))
                            IconButton(onClick = { vm.changeQty(i, 1.0) }) { Icon(Icons.Filled.Add, "+") }
                            IconButton(onClick = { vm.removeLine(i) }) { Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.error) }
                        }
                        Divider()
                    }
                }
            }
            OutlinedTextField(value = vm.remarks, onValueChange = { vm.remarks = it }, label = { Text("Remarks") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Button(onClick = { vm.save { } }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Save material out") }
        }
    }

    if (showItemPicker) {
        ItemPickerDialog(
            items = items, stockByItem = stock,
            onDismiss = { showItemPicker = false },
            onPick = { picked -> showItemPicker = false; if (picked.hasTwoUnits) unitPickFor = picked else vm.addItemToCart(picked) },
            onNewItem = { q -> showItemPicker = false; newItemName = q; showNewItem = true }
        )
    }
    unitPickFor?.let { item ->
        UnitPickDialog(item = item, onPick = { c -> vm.addItemWithUnit(item, c); unitPickFor = null }, onDismiss = { unitPickFor = null })
    }
    if (showNewItem) {
        val cats = remember(items) { items.map { it.category }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() } }
        NewItemDialog(onDismiss = { showNewItem = false }, initialName = newItemName, categories = cats, onSave = { form -> vm.addItem(form); showNewItem = false })
    }
    if (showHandwrite) {
        HandwriteQuickBillDialog(onDismiss = { showHandwrite = false }, onReview = { list -> showHandwrite = false; ocrReview = list })
    }
    editNameFor?.let { idx ->
        val cur = vm.cart.getOrNull(idx)
        if (cur != null) EditLineNameDialog(initial = cur.name, allNames = items.map { it.name }, onDone = { n -> vm.setLineName(idx, n); editNameFor = null }, onDismiss = { editNameFor = null })
        else editNameFor = null
    }
    ocrReview?.let { list ->
        BillOcrReviewDialog(initial = list, masterItems = items, onDismiss = { ocrReview = null }, onConfirm = { confirmed -> vm.addOcrItems(confirmed); ocrReview = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaterialOutListScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, onNew: () -> Unit, vm: MaterialOutViewModel = viewModel()) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val outs by vm.outs.collectAsStateSafe()
    val items by vm.items.collectAsStateSafe()
    val movements by vm.movements.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    var deleteFor by remember { mutableStateOf<MaterialOut?>(null) }
    var itemFilter by remember { mutableStateOf("") }
    var resultFilter by remember { mutableStateOf("") }
    var fromMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var toMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    // voucher id -> set of item names it contains (for the item filter).
    val vouchersWithItem = remember(movements, itemFilter) {
        if (itemFilter.isBlank()) null
        else movements.filter { it.name.equals(itemFilter, true) }.map { it.voucherId }.toSet()
    }
    val resultTokens = remember(outs) { outs.flatMap { it.resultRef.split(",") }.map { it.trim() }.filter { it.isNotBlank() }.distinct().sorted() }
    val filtered = outs.filter { m ->
        (vouchersWithItem == null || m.id in vouchersWithItem) &&
            (resultFilter.isBlank() || m.resultRef.contains(resultFilter, true)) &&
            m.dateMillis >= matStartOfDay(fromMillis) && m.dateMillis <= matEndOfDay(toMillis)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Material Out") },
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
        Column(Modifier.fillMaxSize().padding(pad)) {
            // Item filter — searchable dropdown.
            var itemMenu by remember { mutableStateOf(false) }
            var itemQuery by remember { mutableStateOf("") }
            val itemMatches = remember(itemQuery, items) { if (itemQuery.isBlank()) items else items.filter { it.name.contains(itemQuery, true) } }
            ExposedDropdownMenuBox(expanded = itemMenu, onExpandedChange = { itemMenu = !itemMenu }, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                OutlinedTextField(
                    value = if (itemFilter.isNotBlank()) itemFilter else itemQuery,
                    onValueChange = { itemQuery = it; itemFilter = ""; itemMenu = true },
                    label = { Text("Filter by item (search)") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(itemMenu) },
                    singleLine = true, modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = itemMenu, onDismissRequest = { itemMenu = false }) {
                    DropdownMenuItem(text = { Text("All items") }, onClick = { itemFilter = ""; itemQuery = ""; itemMenu = false })
                    itemMatches.take(30).forEach { it2 -> DropdownMenuItem(text = { Text(it2.name) }, onClick = { itemFilter = it2.name; itemQuery = ""; itemMenu = false }) }
                }
            }
            // Result-invoice filter — dropdown.
            var resMenu by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = resMenu, onExpandedChange = { resMenu = !resMenu }, modifier = Modifier.padding(horizontal = 12.dp)) {
                OutlinedTextField(
                    readOnly = true, value = resultFilter.ifBlank { "All result invoices" }, onValueChange = {},
                    label = { Text("Filter by result invoice") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(resMenu) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = resMenu, onDismissRequest = { resMenu = false }) {
                    DropdownMenuItem(text = { Text("All result invoices") }, onClick = { resultFilter = ""; resMenu = false })
                    resultTokens.forEach { r -> DropdownMenuItem(text = { Text(r) }, onClick = { resultFilter = r; resMenu = false }) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { matPickDate(context, fromMillis) { fromMillis = it } }, modifier = Modifier.weight(1f)) { Text("From ${Format.date(fromMillis)}", maxLines = 1) }
                OutlinedButton(onClick = { matPickDate(context, toMillis) { toMillis = it } }, modifier = Modifier.weight(1f)) { Text("To ${Format.date(toMillis)}", maxLines = 1) }
            }
            Divider()
            if (filtered.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No material-out vouchers", color = MaterialTheme.colorScheme.outline) }
            else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                items(filtered, key = { it.id }) { m ->
                    Row(Modifier.fillMaxWidth().clickable { onOpen(m.id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.voucherNo + (if (m.resultRef.isNotBlank()) "  •  ${m.resultRef}" else ""), fontWeight = FontWeight.Bold)
                            Text(Format.date(m.dateMillis) + (if (m.resultTests.isNotBlank()) "  •  ${m.resultTests}" else ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                        IconButton(onClick = { deleteFor = m }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                    }
                    Divider()
                }
            }
        }
    }
    deleteFor?.let { m ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete ${m.voucherNo}?") },
            confirmButton = { TextButton(onClick = { vm.delete(m); deleteFor = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } }
        )
    }
}

private fun matPickDate(context: android.content.Context, current: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = current }
    DatePickerDialog(context, { _, y, m, d -> onPicked(Calendar.getInstance().apply { set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis) }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
}
private fun matStartOfDay(m: Long) = Calendar.getInstance().apply { timeInMillis = m; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
private fun matEndOfDay(m: Long) = Calendar.getInstance().apply { timeInMillis = m; set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999) }.timeInMillis
