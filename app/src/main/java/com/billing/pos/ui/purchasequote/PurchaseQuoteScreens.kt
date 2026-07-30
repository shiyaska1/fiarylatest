package com.billing.pos.ui.purchasequote

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.PhotoLibrary
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.Item
import com.billing.pos.data.PurchaseQuote
import com.billing.pos.data.PurchaseQuoteItem
import com.billing.pos.data.Repository
import com.billing.pos.data.Supplier
import com.billing.pos.data.UnitChoice
import com.billing.pos.data.hasTwoUnits
import com.billing.pos.data.primaryCostChoice
import com.billing.pos.pdf.PdfDoc
import com.billing.pos.pdf.PdfLine
import com.billing.pos.ui.billing.CartLine
import com.billing.pos.ui.billing.ItemPickerDialog
import com.billing.pos.ui.billing.UnitPickDialog
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.ui.common.DocumentPdfAction
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PurchaseQuoteViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    val suppliers: StateFlow<List<Supplier>> = repo.suppliers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val items: StateFlow<List<Item>> = repo.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val quotes: StateFlow<List<PurchaseQuote>> = repo.purchaseQuotes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var selectedSupplier by mutableStateOf<Supplier?>(null); private set
    val cart: SnapshotStateList<CartLine> = mutableStateListOf()
    var additionalChargeText by mutableStateOf("")
    var discountText by mutableStateOf("")
    var remarks by mutableStateOf("")
    var quoteNo by mutableStateOf("PQ-0001"); private set
    var dateMillis by mutableStateOf(System.currentTimeMillis())
    var editingId by mutableStateOf<Long?>(null); private set
    private var loaded = false

    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    init {
        viewModelScope.launch { repo.ensureDefaults(); quoteNo = repo.nextPurchaseQuoteNo() }
        viewModelScope.launch {
            suppliers.collect { list -> if (selectedSupplier == null && list.isNotEmpty()) selectedSupplier = list.firstOrNull { it.isDefault } ?: list.first() }
        }
    }

    val subTotal get() = cart.sumOf { it.base }
    val taxTotal get() = cart.sumOf { it.tax }
    val additionalCharge get() = additionalChargeText.toDoubleOrNull() ?: 0.0
    val discount get() = discountText.toDoubleOrNull() ?: 0.0
    val grandTotal get() = subTotal + taxTotal + additionalCharge - discount

    fun selectSupplier(s: Supplier) { selectedSupplier = s }
    fun addItemToCart(item: Item) = addItemWithUnit(item, item.primaryCostChoice())

    fun addItemWithUnit(item: Item, choice: UnitChoice) {
        val idx = cart.indexOfFirst { it.itemId == item.id && it.unit == choice.unit && item.id != 0L }
        if (idx >= 0) cart[idx] = cart[idx].copy(qty = cart[idx].qty + 1)
        else cart.add(CartLine(item.id, item.name, choice.price, item.taxPercent, 1.0, unit = choice.unit, primaryPerUnit = choice.primaryPerUnit))
    }

    /**
     * Adds a line for a name that was typed, handwritten or read off a photo.
     *
     * itemId stays 0 and nothing is written to the item master: a quotation often covers
     * goods never bought before, and those names should not clutter the item list.
     */
    fun addTypedLine(name: String, price: Double, qty: Double, unit: String) {
        if (name.isBlank()) return
        cart.add(CartLine(0, name.trim(), price, 0.0, if (qty > 0) qty else 1.0, unit = unit.trim()))
    }

    fun changeQty(i: Int, d: Double) { val l = cart.getOrNull(i) ?: return; val q = l.qty + d; if (q <= 0) cart.removeAt(i) else cart[i] = l.copy(qty = q) }
    fun setQty(i: Int, q: Double) { val l = cart.getOrNull(i) ?: return; if (q <= 0) cart.removeAt(i) else cart[i] = l.copy(qty = q) }
    fun setLinePrice(i: Int, p: Double) { val l = cart.getOrNull(i) ?: return; cart[i] = l.copy(price = p) }
    fun removeLine(i: Int) { cart.removeAt(i) }

    fun load(id: Long) {
        if (loaded || id <= 0) { loaded = true; return }
        loaded = true
        viewModelScope.launch {
            val r = repo.purchaseQuoteById(id) ?: return@launch
            editingId = r.id; quoteNo = r.quoteNo; dateMillis = r.dateMillis
            additionalChargeText = if (r.additionalCharge != 0.0) r.additionalCharge.toString() else ""
            discountText = if (r.discount != 0.0) r.discount.toString() else ""
            remarks = r.remarks
            selectedSupplier = suppliers.value.firstOrNull { it.id == r.supplierId } ?: Supplier(r.supplierId, r.supplierName)
            cart.clear()
            repo.purchaseQuoteLines(id).forEach { cart.add(CartLine(it.itemId, it.name, it.price, it.taxPercent, it.qty, unit = it.unit)) }
        }
    }

    fun newQuote() {
        cart.clear(); additionalChargeText = ""; discountText = ""; remarks = ""
        dateMillis = System.currentTimeMillis(); editingId = null
        selectedSupplier = suppliers.value.firstOrNull { it.isDefault } ?: suppliers.value.firstOrNull()
        viewModelScope.launch { quoteNo = repo.nextPurchaseQuoteNo() }
    }

    fun save(onDone: () -> Unit) {
        val supplier = selectedSupplier
        if (supplier == null) { message.value = "Select a supplier"; return }
        if (cart.isEmpty()) { message.value = "Add at least one item"; return }
        viewModelScope.launch {
            val r = PurchaseQuote(
                id = editingId ?: 0, quoteNo = quoteNo, dateMillis = dateMillis,
                supplierId = supplier.id, supplierName = supplier.name,
                subTotal = subTotal, taxTotal = taxTotal, additionalCharge = additionalCharge,
                discount = discount, grandTotal = grandTotal, remarks = remarks.trim()
            )
            val lines = cart.map { PurchaseQuoteItem(0, r.id, it.itemId, it.name, it.qty, it.price, it.taxPercent, it.total, it.unit) }
            val eid = editingId
            if (eid != null) { repo.updatePurchaseQuote(r, lines); message.value = "Quotation $quoteNo updated" }
            else { val id = repo.savePurchaseQuote(r, lines); editingId = id; message.value = "Quotation $quoteNo saved" }
            onDone()
        }
    }

    fun delete(r: PurchaseQuote) { viewModelScope.launch { repo.deletePurchaseQuote(r); message.value = "Quotation ${r.quoteNo} deleted" } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurchaseQuoteScreen(editId: Long?, onBack: () -> Unit, vm: PurchaseQuoteViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    val items by vm.items.collectAsStateSafe()
    val suppliers by vm.suppliers.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    LaunchedEffect(Unit) { if (editId != null && editId > 0) vm.load(editId) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    var showItemPicker by remember { mutableStateOf(false) }
    var unitPickFor by remember { mutableStateOf<Item?>(null) }
    var typeLine by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (vm.editingId != null) "Edit Purchase Quotation" else "Purchase Quotation") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    DocumentPdfAction(onMessage = { vm.message.value = it }) {
                        if (vm.cart.isEmpty()) { vm.message.value = "Add at least one item"; null }
                        else PdfDoc(
                            docTitle = "PURCHASE QUOTATION", docNo = vm.quoteNo, dateMillis = vm.dateMillis,
                            partyLabel = "Quote From", partyName = vm.selectedSupplier?.name ?: "",
                            lines = vm.cart.map { PdfLine(it.name, it.qty, it.price, it.total, it.unit) },
                            subTotal = vm.subTotal, taxTotal = vm.taxTotal, additionalCharge = vm.additionalCharge,
                            discount = vm.discount, grandTotal = vm.grandTotal, grandLabel = "QUOTED TOTAL",
                            remarks = vm.remarks, filePrefix = "purchase_quotation"
                        )
                    }
                    IconButton(onClick = { vm.newQuote() }) { Icon(Icons.Filled.NoteAdd, "New") }
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
            OutlinedTextField(
                value = vm.remarks, onValueChange = { vm.remarks = it }, label = { Text("Note (optional)") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showItemPicker = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Add, null); Text("  From items")
                }
                OutlinedButton(onClick = { typeLine = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Draw, null); Text("  Type item")
                }
            }

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
                        Text("QUOTED TOTAL", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(Format.rupee(vm.grandTotal), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            Button(onClick = { vm.save { } }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Save quotation") }
        }
    }

    if (typeLine) {
        TypedItemDialog(
            onAdd = { name, price, qty, unit -> vm.addTypedLine(name, price, qty, unit) },
            onDismiss = { typeLine = false }
        )
    }

    if (showItemPicker) {
        ItemPickerDialog(
            items = items,
            onDismiss = { showItemPicker = false },
            onPick = { picked ->
                showItemPicker = false
                if (picked.hasTwoUnits) unitPickFor = picked else vm.addItemToCart(picked)
            },
            onNewItem = { showItemPicker = false }
        )
    }
    unitPickFor?.let { item ->
        UnitPickDialog(
            item = item,
            useCost = true,
            onPick = { choice -> vm.addItemWithUnit(item, choice); unitPickFor = null },
            onDismiss = { unitPickFor = null }
        )
    }
}

/**
 * A line typed by hand, with handwriting and photo-OCR for the name.
 *
 * Deliberately does not offer "save to items": on a quotation the name is descriptive,
 * often a one-off, and adding it to the master would pollute the item list.
 */
@Composable
private fun TypedItemDialog(
    onAdd: (String, Double, Double, String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("1") }
    var unit by remember { mutableStateOf("") }
    var draw by remember { mutableStateOf(false) }
    var ocrUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val camera = com.billing.pos.ocr.rememberImageCamera { uri -> ocrUri = uri }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) ocrUri = uri
    }

    if (draw) {
        com.billing.pos.ui.common.HandwriteTextDialog(
            onResult = { t -> if (t.isNotBlank()) name = (name.trimEnd() + " " + t).trim(); draw = false },
            onDismiss = { draw = false }
        )
    }
    ocrUri?.let { u ->
        com.billing.pos.ui.common.RegionOcrDialog(
            uri = u,
            onResult = { t -> if (t.isNotBlank()) name = t; ocrUri = null },
            onDismiss = { ocrUri = null }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Type an item") },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Item name") }, modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { draw = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Draw, "Draw", Modifier.size(18.dp))
                    }
                    OutlinedButton(onClick = { camera() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.PhotoCamera, "Camera", Modifier.size(18.dp))
                    }
                    OutlinedButton(
                        onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        modifier = Modifier.weight(1f)
                    ) { Icon(Icons.Filled.PhotoLibrary, "Gallery", Modifier.size(18.dp)) }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = price, onValueChange = { price = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Rate") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = qty, onValueChange = { qty = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Qty") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = unit, onValueChange = { unit = it },
                        label = { Text("Unit") }, singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    "Typed names stay on this quotation — they are not added to the item list.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onAdd(name, price.toDoubleOrNull() ?: 0.0, qty.toDoubleOrNull() ?: 1.0, unit)
                // Kept open so several lines can be typed one after another.
                name = ""; price = ""; qty = "1"; unit = ""
            }, enabled = name.isNotBlank()) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurchaseQuoteListScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, onNew: () -> Unit, vm: PurchaseQuoteViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    val quotes by vm.quotes.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    var deleteFor by remember { mutableStateOf<PurchaseQuote?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Purchase Quotations") },
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
        if (quotes.isEmpty()) Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { Text("No purchase quotations yet", color = MaterialTheme.colorScheme.outline) }
        else LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
            items(quotes, key = { it.id }) { r ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(r.id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.quoteNo, fontWeight = FontWeight.Bold)
                        Text("${r.supplierName} • ${Format.date(r.dateMillis)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
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
            title = { Text("Delete ${r.quoteNo}?") },
            confirmButton = { TextButton(onClick = { vm.delete(r); deleteFor = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } }
        )
    }
}
