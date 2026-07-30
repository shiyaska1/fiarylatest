package com.billing.pos.ui.hire

import android.app.Application
import android.app.DatePickerDialog
import java.util.Calendar
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
import androidx.compose.material.icons.filled.CalendarMonth
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
import com.billing.pos.data.Customer
import com.billing.pos.data.HireInvoice
import com.billing.pos.data.HireInvoiceItem
import com.billing.pos.data.Item
import com.billing.pos.data.Repository
import com.billing.pos.data.UnitChoice
import com.billing.pos.data.hasTwoUnits
import com.billing.pos.data.primaryChoice
import com.billing.pos.ui.billing.CartLine
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

class HireInvoiceViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    val customers: StateFlow<List<Customer>> = repo.customers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val items: StateFlow<List<Item>> = repo.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val hires: StateFlow<List<HireInvoice>> = repo.hireInvoices.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var selectedCustomer by mutableStateOf<Customer?>(null); private set
    val cart: SnapshotStateList<CartLine> = mutableStateListOf()
    var additionalChargeText by mutableStateOf("")
    var discountText by mutableStateOf("")
    var remarks by mutableStateOf("")
    var hireNo by mutableStateOf("HR-0001"); private set
    var dateMillis by mutableStateOf(System.currentTimeMillis())
    var startDateMillis by mutableStateOf(System.currentTimeMillis())
    var endDateMillis by mutableStateOf(System.currentTimeMillis())
    var editingId by mutableStateOf<Long?>(null); private set
    private var loaded = false

    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    init {
        viewModelScope.launch { repo.ensureDefaults(); hireNo = repo.nextHireNo() }
        viewModelScope.launch { customers.collect { list -> if (selectedCustomer == null && list.isNotEmpty()) selectedCustomer = list.firstOrNull { it.isDefault } ?: list.first() } }
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

    fun load(id: Long) {
        if (loaded || id <= 0) { loaded = true; return }
        loaded = true
        viewModelScope.launch {
            val h = repo.hireInvoiceById(id) ?: return@launch
            editingId = h.id; hireNo = h.hireNo; dateMillis = h.dateMillis
            startDateMillis = h.startDateMillis; endDateMillis = h.endDateMillis
            additionalChargeText = if (h.additionalCharge != 0.0) h.additionalCharge.toString() else ""
            discountText = if (h.discount != 0.0) h.discount.toString() else ""
            remarks = h.remarks
            selectedCustomer = customers.value.firstOrNull { it.id == h.customerId } ?: Customer(h.customerId, h.customerName)
            cart.clear()
            repo.hireInvoiceLines(id).forEach { cart.add(CartLine(it.itemId, it.name, it.price, it.taxPercent, it.qty, unit = it.unit)) }
        }
    }

    fun newHire() {
        cart.clear(); additionalChargeText = ""; discountText = ""; remarks = ""
        dateMillis = System.currentTimeMillis(); startDateMillis = dateMillis; endDateMillis = dateMillis; editingId = null
        selectedCustomer = customers.value.firstOrNull { it.isDefault } ?: customers.value.firstOrNull()
        viewModelScope.launch { hireNo = repo.nextHireNo() }
    }

    fun save(onDone: () -> Unit) {
        val customer = selectedCustomer
        if (customer == null) { message.value = "Select a customer"; return }
        if (cart.isEmpty()) { message.value = "Add at least one item"; return }
        if (endDateMillis < startDateMillis) { message.value = "End date is before start date"; return }
        viewModelScope.launch {
            val h = HireInvoice(
                id = editingId ?: 0, hireNo = hireNo, dateMillis = dateMillis,
                startDateMillis = startDateMillis, endDateMillis = endDateMillis,
                customerId = customer.id, customerName = customer.name,
                subTotal = subTotal, taxTotal = taxTotal, additionalCharge = additionalCharge,
                discount = discount, grandTotal = grandTotal, remarks = remarks.trim()
            )
            val lines = cart.map { HireInvoiceItem(0, h.id, it.itemId, it.name, it.qty, it.price, it.taxPercent, it.total, it.unit) }
            if (editingId != null) { repo.updateHireInvoice(h, lines); message.value = "Hire $hireNo updated" }
            else { repo.saveHireInvoice(h, lines); editingId = null; message.value = "Hire $hireNo saved" }
            onDone()
        }
    }

    fun delete(h: HireInvoice) { viewModelScope.launch { repo.deleteHireInvoice(h); message.value = "Hire ${h.hireNo} deleted" } }
}

private fun pickDate(context: android.content.Context, current: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = current }
    DatePickerDialog(
        context,
        { _, y, m, d ->
            val cal = Calendar.getInstance().apply { set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0) }
            onPicked(cal.timeInMillis)
        },
        c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
    ).show()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HireInvoiceScreen(editId: Long?, onBack: () -> Unit, vm: HireInvoiceViewModel = viewModel()) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val items by vm.items.collectAsStateSafe()
    val customers by vm.customers.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    LaunchedEffect(Unit) { if (editId != null && editId > 0) vm.load(editId) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    var showItemPicker by remember { mutableStateOf(false) }
    var unitPickFor by remember { mutableStateOf<Item?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (vm.editingId != null) "Edit Hire" else "Hire Invoice") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    DocumentPdfAction(onMessage = { vm.message.value = it }) {
                        if (vm.cart.isEmpty()) { vm.message.value = "Add at least one item"; null }
                        else PdfDoc(
                            docTitle = "HIRE INVOICE", docNo = vm.hireNo, dateMillis = vm.dateMillis,
                            partyLabel = "Hire To", partyName = vm.selectedCustomer?.name ?: "",
                            extraMeta = "Period: ${Format.date(vm.startDateMillis)} - ${Format.date(vm.endDateMillis)}",
                            lines = vm.cart.map { PdfLine(it.name, it.qty, it.price, it.total, it.unit) },
                            subTotal = vm.subTotal, taxTotal = vm.taxTotal, additionalCharge = vm.additionalCharge,
                            discount = vm.discount, grandTotal = vm.grandTotal, grandLabel = "TOTAL RENT",
                            remarks = vm.remarks, filePrefix = "hire"
                        )
                    }
                    IconButton(onClick = { vm.newHire() }) { Icon(Icons.Filled.NoteAdd, "New") }
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
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickDate(context, vm.startDateMillis) { vm.startDateMillis = it } }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.CalendarMonth, null); Text(" From ${Format.date(vm.startDateMillis)}", maxLines = 1)
                }
                OutlinedButton(onClick = { pickDate(context, vm.endDateMillis) { vm.endDateMillis = it } }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.CalendarMonth, null); Text(" To ${Format.date(vm.endDateMillis)}", maxLines = 1)
                }
            }
            Button(onClick = { showItemPicker = true }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Icon(Icons.Filled.Add, null); Text("  Add item") }

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
                                OutlinedTextField(value = priceText, onValueChange = { v -> val f = v.filter { it.isDigit() || it == '.' }; priceText = f; vm.setLinePrice(i, f.toDoubleOrNull() ?: 0.0) }, label = { Text("Rent") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(116.dp))
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
                        Text("TOTAL RENT", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(Format.rupee(vm.grandTotal), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            Button(onClick = { vm.save { } }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Save hire") }
        }
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
fun HireInvoiceListScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, onNew: () -> Unit, vm: HireInvoiceViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    val hires by vm.hires.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    var deleteFor by remember { mutableStateOf<HireInvoice?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Hire Invoices") },
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
        if (hires.isEmpty()) Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { Text("No hire invoices yet", color = MaterialTheme.colorScheme.outline) }
        else LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
            items(hires, key = { it.id }) { h ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(h.id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(h.hireNo, fontWeight = FontWeight.Bold)
                        Text("${h.customerName} • ${Format.date(h.startDateMillis)} → ${Format.date(h.endDateMillis)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    Text(Format.rupee(h.grandTotal), fontWeight = FontWeight.Bold)
                    IconButton(onClick = { deleteFor = h }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                }
                Divider()
            }
        }
    }

    deleteFor?.let { h ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete ${h.hireNo}?") },
            confirmButton = { TextButton(onClick = { vm.delete(h); deleteFor = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } }
        )
    }
}
