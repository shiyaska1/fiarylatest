package com.billing.pos.ui.quotation

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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.billing.pos.data.Customer
import com.billing.pos.data.Item
import com.billing.pos.data.Quotation
import com.billing.pos.data.QuotationItem
import com.billing.pos.data.Repository
import com.billing.pos.ui.billing.CartLine
import com.billing.pos.data.UnitChoice
import com.billing.pos.data.hasTwoUnits
import com.billing.pos.data.primaryChoice
import com.billing.pos.ui.billing.ItemPickerDialog
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

/**
 * Hands a quotation id from the list to the editor when "copy" is tapped. The two screens
 * get separate view models, so the id cannot simply be passed in memory on the model.
 */
object QuotationCopy {
    @Volatile var pending: Long? = null
    fun take(): Long? { val p = pending; pending = null; return p }
}

class QuotationViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    val customers: StateFlow<List<Customer>> =
        repo.customers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val items: StateFlow<List<Item>> =
        repo.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val quotations: StateFlow<List<Quotation>> =
        repo.quotations.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var selectedCustomer by mutableStateOf<Customer?>(null); private set
    val cart: SnapshotStateList<CartLine> = mutableStateListOf()
    var additionalChargeText by mutableStateOf("")
    var discountText by mutableStateOf("")
    var remarks by mutableStateOf("")
    var terms by mutableStateOf("")
    var quotationNo by mutableStateOf("QT-0001"); private set
    var dateMillis by mutableStateOf(System.currentTimeMillis())
    var editingId by mutableStateOf<Long?>(null); private set
    private var loaded = false

    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    init {
        viewModelScope.launch { repo.ensureDefaults(); quotationNo = repo.nextQuotationNo() }
        viewModelScope.launch {
            customers.collect { list -> if (selectedCustomer == null && list.isNotEmpty()) selectedCustomer = list.firstOrNull { it.isDefault } ?: list.first() }
        }
    }

    val subTotal get() = cart.sumOf { it.base }
    val taxTotal get() = cart.sumOf { it.tax }
    val additionalCharge get() = additionalChargeText.toDoubleOrNull() ?: 0.0
    val discount get() = discountText.toDoubleOrNull() ?: 0.0
    val grandTotal get() = subTotal + taxTotal + additionalCharge - discount

    fun selectCustomer(c: Customer) { selectedCustomer = c }
    fun addItemToCart(item: Item) = addItemWithUnit(item, item.primaryChoice())

    fun addItemWithUnit(item: Item, choice: UnitChoice) {
        val idx = cart.indexOfFirst { it.itemId == item.id && it.unit == choice.unit && item.id != 0L }
        if (idx >= 0) cart[idx] = cart[idx].copy(qty = cart[idx].qty + 1)
        else cart.add(CartLine(item.id, item.name, choice.price, item.taxPercent, 1.0, unit = choice.unit, primaryPerUnit = choice.primaryPerUnit))
    }
    fun changeQty(i: Int, d: Double) { val l = cart.getOrNull(i) ?: return; val q = l.qty + d; if (q <= 0) cart.removeAt(i) else cart[i] = l.copy(qty = q) }
    fun setQty(i: Int, q: Double) { val l = cart.getOrNull(i) ?: return; if (q <= 0) cart.removeAt(i) else cart[i] = l.copy(qty = q) }
    fun setLinePrice(i: Int, p: Double) { val l = cart.getOrNull(i) ?: return; cart[i] = l.copy(price = p) }
    fun removeLine(i: Int) { cart.removeAt(i) }
    fun setLineNote(i: Int, note: String) { val l = cart.getOrNull(i) ?: return; cart[i] = l.copy(note = note) }

    fun load(id: Long) {
        if (loaded || id <= 0) { loaded = true; return }
        loaded = true
        viewModelScope.launch {
            val q = repo.quotationById(id) ?: return@launch
            editingId = q.id
            quotationNo = q.quotationNo
            dateMillis = q.dateMillis
            additionalChargeText = if (q.additionalCharge != 0.0) q.additionalCharge.toString() else ""
            discountText = if (q.discount != 0.0) q.discount.toString() else ""
            remarks = q.remarks
            terms = q.terms
            selectedCustomer = customers.value.firstOrNull { it.id == q.customerId } ?: Customer(q.customerId, q.customerName)
            cart.clear()
            repo.quotationLines(id).forEach { cart.add(CartLine(0, it.name, it.price, it.taxPercent, it.qty, unit = it.unit, note = it.note)) }
        }
    }

    fun newQuotation() {
        cart.clear(); additionalChargeText = ""; discountText = ""; remarks = ""; terms = ""
        dateMillis = System.currentTimeMillis(); editingId = null
        selectedCustomer = customers.value.firstOrNull { it.isDefault } ?: customers.value.firstOrNull()
        viewModelScope.launch { quotationNo = repo.nextQuotationNo() }
    }

    fun save(onDone: () -> Unit) {
        val customer = selectedCustomer
        if (customer == null) { message.value = "Select a customer"; return }
        if (cart.isEmpty()) { message.value = "Add at least one item"; return }
        viewModelScope.launch {
            val q = Quotation(
                id = editingId ?: 0, quotationNo = quotationNo, dateMillis = dateMillis,
                customerId = customer.id, customerName = customer.name,
                subTotal = subTotal, taxTotal = taxTotal, additionalCharge = additionalCharge,
                discount = discount, grandTotal = grandTotal, remarks = remarks.trim(), terms = terms.trim()
            )
            val lines = cart.map { QuotationItem(0, q.id, it.name, it.qty, it.price, it.taxPercent, it.total, it.unit, it.note) }
            val eid = editingId
            if (eid != null) { repo.updateQuotation(q, lines); message.value = "Quotation $quotationNo updated" }
            else { val id = repo.saveQuotation(q, lines); editingId = id; message.value = "Quotation $quotationNo saved" }
            onDone()
        }
    }

    /**
     * Loads an existing quotation as a brand-new one: same customer, lines, charges and
     * terms, but a fresh number and today's date. Saving creates a second quotation and
     * leaves the original untouched.
     */
    fun copyFrom(id: Long) {
        loaded = true
        viewModelScope.launch {
            val q = repo.quotationById(id) ?: return@launch
            editingId = null
            quotationNo = repo.nextQuotationNo()
            dateMillis = System.currentTimeMillis()
            additionalChargeText = if (q.additionalCharge != 0.0) q.additionalCharge.toString() else ""
            discountText = if (q.discount != 0.0) q.discount.toString() else ""
            remarks = q.remarks
            terms = q.terms
            selectedCustomer = customers.value.firstOrNull { it.id == q.customerId } ?: Customer(q.customerId, q.customerName)
            cart.clear()
            repo.quotationLines(id).forEach { cart.add(CartLine(0, it.name, it.price, it.taxPercent, it.qty, unit = it.unit, note = it.note)) }
            message.value = "Copied — save to keep it as $quotationNo"
        }
    }

    fun delete(q: Quotation) { viewModelScope.launch { repo.deleteQuotation(q); message.value = "Quotation ${q.quotationNo} deleted" } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuotationScreen(editId: Long?, onBack: () -> Unit, vm: QuotationViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val items by vm.items.collectAsStateSafe()
    val customers by vm.customers.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    LaunchedEffect(Unit) {
        val copyId = QuotationCopy.take()
        if (copyId != null) vm.copyFrom(copyId)
        else if (editId != null && editId > 0) vm.load(editId)
    }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    var showItemPicker by remember { mutableStateOf(false) }
    // Index of the line whose description is open, if any.
    var noteFor by remember { mutableStateOf<Int?>(null) }
    var unitPickFor by remember { mutableStateOf<Item?>(null) }
    var showTerms by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (vm.editingId != null) "Edit Quotation" else "New Quotation") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    DocumentPdfAction(onMessage = { vm.message.value = it }) {
                        if (vm.cart.isEmpty()) { vm.message.value = "Add at least one item"; null }
                        else PdfDoc(
                            docTitle = "QUOTATION", docNo = vm.quotationNo, dateMillis = vm.dateMillis,
                            partyLabel = "Quote To", partyName = vm.selectedCustomer?.name ?: "",
                            lines = vm.cart.map { PdfLine(it.name, it.qty, it.price, it.total, it.unit, it.note) },
                            subTotal = vm.subTotal, taxTotal = vm.taxTotal, additionalCharge = vm.additionalCharge,
                            discount = vm.discount, grandTotal = vm.grandTotal, grandLabel = "TOTAL",
                            remarks = vm.remarks, terms = vm.terms, filePrefix = "quotation"
                        )
                    }
                    IconButton(onClick = { vm.newQuotation() }) { Icon(Icons.Filled.NoteAdd, "New") }
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
            var custMenu by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = custMenu, onExpandedChange = { custMenu = !custMenu }) {
                OutlinedTextField(
                    readOnly = true, value = vm.selectedCustomer?.name ?: "", onValueChange = {},
                    label = { Text("Customer") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(custMenu) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = custMenu, onDismissRequest = { custMenu = false }) {
                    customers.forEach { c -> DropdownMenuItem(text = { Text(c.name) }, onClick = { vm.selectCustomer(c); custMenu = false }) }
                }
            }
            OutlinedTextField(
                value = vm.remarks, onValueChange = { vm.remarks = it }, label = { Text("Note (optional)") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
            Button(onClick = { showItemPicker = true }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Icon(Icons.Filled.Add, null); Text("  Add item")
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
                                // Description lives behind this icon; it is never shown inline.
                                IconButton(onClick = { noteFor = i }) {
                                    Icon(
                                        if (line.note.isBlank()) Icons.Filled.NoteAdd else Icons.Filled.Description,
                                        contentDescription = "Item note",
                                        tint = if (line.note.isBlank()) MaterialTheme.colorScheme.outline
                                        else MaterialTheme.colorScheme.primary
                                    )
                                }
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
                        Text("GRAND TOTAL", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(Format.rupee(vm.grandTotal), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = vm.terms,
                    onValueChange = { vm.terms = it },
                    label = { Text("Terms & Conditions") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { showTerms = true }) {
                    Icon(Icons.Filled.FormatBold, "Format terms", tint = MaterialTheme.colorScheme.primary)
                }
            }
            Button(onClick = { vm.save { } }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Save quotation") }
        }
    }

    noteFor?.let { idx ->
        val line = vm.cart.getOrNull(idx)
        if (line == null) noteFor = null
        else com.billing.pos.ui.common.ItemNoteDialog(
            itemName = line.name,
            initialNote = line.note,
            onSave = { vm.setLineNote(idx, it) },
            onDismiss = { noteFor = null }
        )
    }

    if (showTerms) {
        com.billing.pos.ui.common.RichTextFullScreen(
            heading = "Terms & Conditions",
            hint = "Printed at the foot of the quotation and its PDF.",
            initial = vm.terms,
            onSave = { vm.terms = it },
            onDismiss = { showTerms = false }
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
            onPick = { choice -> vm.addItemWithUnit(item, choice); unitPickFor = null },
            onDismiss = { unitPickFor = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuotationListScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, onNew: () -> Unit, vm: QuotationViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    val quotations by vm.quotations.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    var deleteFor by remember { mutableStateOf<Quotation?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Quotations") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = onNew) { Icon(Icons.Filled.Add, "New quotation") } }
    ) { pad ->
        if (quotations.isEmpty()) Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { Text("No quotations yet", color = MaterialTheme.colorScheme.outline) }
        else LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
            items(quotations, key = { it.id }) { q ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(q.id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(q.quotationNo, fontWeight = FontWeight.Bold)
                        Text("${q.customerName} • ${Format.date(q.dateMillis)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    Text(Format.rupee(q.grandTotal), fontWeight = FontWeight.Bold)
                    IconButton(onClick = { QuotationCopy.pending = q.id; onNew() }) {
                        Icon(Icons.Filled.ContentCopy, "Copy to a new quotation", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { deleteFor = q }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                }
                Divider()
            }
        }
    }

    deleteFor?.let { q ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete ${q.quotationNo}?") },
            confirmButton = { TextButton(onClick = { vm.delete(q); deleteFor = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } }
        )
    }
}
