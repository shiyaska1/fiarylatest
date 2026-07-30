package com.billing.pos.ui.order

import android.Manifest
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.CustOrder
import com.billing.pos.data.CustOrderAttachment
import com.billing.pos.data.CustOrderItem
import com.billing.pos.data.Customer
import com.billing.pos.data.Item
import com.billing.pos.data.OrderAttachmentStore
import com.billing.pos.data.Repository
import com.billing.pos.data.hasTwoUnits
import com.billing.pos.data.primaryChoice
import com.billing.pos.data.UnitChoice
import com.billing.pos.ui.billing.CartLine
import com.billing.pos.ui.billing.ItemPickerDialog
import com.billing.pos.ui.billing.UnitPickDialog
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

class OrderViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    val customers: StateFlow<List<Customer>> = repo.customers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val items: StateFlow<List<Item>> = repo.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val orders: StateFlow<List<CustOrder>> = repo.custOrders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    suspend fun addCustomer(name: String, phone: String, type: String = "General") = repo.addCustomerReturning(name, phone, type)
    suspend fun load(id: Long): CustOrder? = repo.orderById(id)
    suspend fun linesFor(id: Long) = repo.orderLines(id)
    suspend fun attachmentsFor(id: Long) = repo.orderAttachmentsFor(id)

    fun save(
        existingId: Long?, customer: Customer?, cart: List<CartLine>, remark: String,
        lat: Double, lng: Double, attachments: List<CustOrderAttachment>, onDone: () -> Unit
    ) {
        if (customer == null) { message.value = "Select a customer"; return }
        if (cart.isEmpty()) { message.value = "Add at least one item"; return }
        viewModelScope.launch {
            val total = cart.sumOf { it.total }
            val no = if (existingId == null) repo.nextOrderNo() else repo.orderById(existingId)?.orderNo ?: repo.nextOrderNo()
            val order = CustOrder(
                id = existingId ?: 0, orderNo = no, dateMillis = System.currentTimeMillis(),
                customerId = customer.id, customerName = customer.name, remark = remark.trim(),
                latitude = lat, longitude = lng, grandTotal = total
            )
            val lines = cart.map { CustOrderItem(0, order.id, it.itemId, it.name, it.qty, it.price, it.total, it.unit) }
            val id = if (existingId != null) { repo.updateOrder(order, lines); existingId } else repo.saveOrder(order, lines)
            repo.replaceOrderAttachments(id, attachments)
            message.value = "Order $no saved"
            onDone()
        }
    }

    fun delete(o: CustOrder) { viewModelScope.launch { repo.deleteOrder(o); message.value = "Order ${o.orderNo} deleted" } }

    /**
     * Gathers the ticked orders into a bill hand-off: one customer only, with identical
     * item lines merged so the same product ordered twice becomes one line with the summed
     * quantity. Stock is untouched until the bill itself is saved.
     */
    fun convertToBill(ids: List<Long>, onReady: () -> Unit) {
        viewModelScope.launch {
            val chosen = orders.value.filter { it.id in ids }
            if (chosen.isEmpty()) { message.value = "Tick at least one order"; return@launch }
            if (chosen.map { it.customerId }.distinct().size > 1) { message.value = "Tick orders of one customer only"; return@launch }
            val custId = chosen.first().customerId; val custName = chosen.first().customerName
            val agg = LinkedHashMap<String, com.billing.pos.ui.billing.BillPrefillLine>()
            chosen.forEach { o ->
                repo.orderLines(o.id).forEach { l ->
                    val key = l.name.lowercase() + "|" + l.price + "|" + l.unit
                    val ex = agg[key]
                    agg[key] = if (ex == null) com.billing.pos.ui.billing.BillPrefillLine(l.itemId, l.name, l.qty, l.price, l.unit)
                    else ex.copy(qty = ex.qty + l.qty)
                }
            }
            if (agg.isEmpty()) { message.value = "These orders have no items"; return@launch }
            com.billing.pos.ui.billing.OrderToBillLink.set(custId, custName, agg.values.toList())
            onReady()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderEntryScreen(editId: Long?, onBack: () -> Unit, vm: OrderViewModel = viewModel()) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val items by vm.items.collectAsStateSafe()
    val customers by vm.customers.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var selectedCustomer by remember { mutableStateOf<Customer?>(null) }
    val cart = remember { mutableStateListOf<CartLine>() }
    var remark by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf(0.0) }
    var lng by remember { mutableStateOf(0.0) }
    val attachments = remember { mutableStateListOf<CustOrderAttachment>() }
    // The remark / attachment / location block is hidden until the title-bar icon opens it.
    var extrasOpen by remember { mutableStateOf(false) }
    var showItemPicker by remember { mutableStateOf(false) }
    var unitPickFor by remember { mutableStateOf<Item?>(null) }
    var newCust by remember { mutableStateOf(false) }

    LaunchedEffect(editId) {
        if (editId != null && editId > 0) {
            val o = vm.load(editId) ?: return@LaunchedEffect
            selectedCustomer = customers.firstOrNull { it.id == o.customerId } ?: Customer(o.customerId, o.customerName)
            remark = o.remark; lat = o.latitude; lng = o.longitude
            attachments.clear(); attachments.addAll(vm.attachmentsFor(editId))
            if (o.remark.isNotBlank() || o.latitude != 0.0 || attachments.isNotEmpty()) extrasOpen = true
            cart.clear()
            vm.linesFor(editId).forEach { cart.add(CartLine(it.itemId, it.name, it.price, 0.0, it.qty, unit = it.unit)) }
        }
    }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        uris.forEach { u -> OrderAttachmentStore.copyIn(context, u)?.let(attachments::add) }
    }
    val filePick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { u -> OrderAttachmentStore.copyIn(context, u)?.let(attachments::add) }
    }
    val locationPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) captureLocation(context) { la, lo -> lat = la; lng = lo; vm.message.value = "Location attached" }
        else vm.message.value = "Location permission denied"
    }

    if (newCust) {
        var nm by remember { mutableStateOf("") }
        var ph by remember { mutableStateOf("") }
        var ctype by remember { mutableStateOf("General") }
        var drawNm by remember { mutableStateOf(false) }
        if (drawNm) {
            com.billing.pos.ui.common.HandwriteTextDialog(
                onResult = { if (it.isNotBlank()) nm = it; drawNm = false },
                onDismiss = { drawNm = false }
            )
        }
        AlertDialog(
            onDismissRequest = { newCust = false },
            title = { Text("New customer") },
            text = {
                Column {
                    OutlinedTextField(
                        value = nm, onValueChange = { nm = it }, label = { Text("Name") }, singleLine = true,
                        trailingIcon = { IconButton(onClick = { drawNm = true }) { Icon(Icons.Filled.Gesture, "Write name") } }
                    )
                    OutlinedTextField(value = ph, onValueChange = { ph = it.filter { c -> c.isDigit() } }, label = { Text("Phone") }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), modifier = Modifier.padding(top = 8.dp))
                    com.billing.pos.ui.common.CustomerTypeField(value = ctype, onValue = { ctype = it }, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { if (nm.isNotBlank()) scope.launch { selectedCustomer = vm.addCustomer(nm.trim(), ph.trim(), ctype); newCust = false } }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { newCust = false }) { Text("Cancel") } }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (editId != null && editId > 0) "Edit Order" else "New Order") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    // Toggle the remark / attachment / location block.
                    IconButton(onClick = { extrasOpen = !extrasOpen }) {
                        Icon(if (extrasOpen) Icons.Filled.UnfoldLess else Icons.Filled.UnfoldMore, "Remark & attachments")
                    }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.billing.pos.ui.common.CustomerPickField(
                    customers = customers,
                    selectedName = selectedCustomer?.name ?: "",
                    onPick = { selectedCustomer = it },
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { newCust = true }) { Icon(Icons.Filled.Add, "New customer", tint = MaterialTheme.colorScheme.primary) }
            }

            Button(onClick = { showItemPicker = true }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Icon(Icons.Filled.Add, null); Text("  Add item")
            }

            Card(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
                if (cart.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No items", color = MaterialTheme.colorScheme.outline) }
                else LazyColumn(Modifier.padding(8.dp)) {
                    itemsIndexed(cart, key = { _, l -> l.uid }) { i, line ->
                        var priceText by remember(line.uid) { mutableStateOf(Format.money(line.price)) }
                        var qtyText by remember(line.uid, line.qty) { mutableStateOf(Format.qty(line.qty)) }
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(line.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1)
                                if (line.unit.isNotBlank()) Text(line.unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                IconButton(onClick = { cart.removeAt(i) }) { Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.error) }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(value = priceText, onValueChange = { v -> val f = v.filter { it.isDigit() || it == '.' }; priceText = f; cart[i] = cart[i].copy(price = f.toDoubleOrNull() ?: 0.0) }, label = { Text("Rate") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(116.dp))
                                Spacer(Modifier.width(6.dp))
                                IconButton(onClick = { val q = cart[i].qty - 1; if (q <= 0) cart.removeAt(i) else cart[i] = cart[i].copy(qty = q) }) { Icon(Icons.Filled.Remove, "-") }
                                OutlinedTextField(value = qtyText, onValueChange = { v -> val f = v.filter { it.isDigit() || it == '.' }; qtyText = f; f.toDoubleOrNull()?.let { if (it > 0) cart[i] = cart[i].copy(qty = it) } }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(64.dp))
                                IconButton(onClick = { cart[i] = cart[i].copy(qty = cart[i].qty + 1) }) { Icon(Icons.Filled.Add, "+") }
                                Spacer(Modifier.weight(1f))
                                Text(Format.rupee(line.total), fontWeight = FontWeight.Bold)
                            }
                            Divider()
                        }
                    }
                }
            }

            if (extrasOpen) {
                Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        OutlinedTextField(value = remark, onValueChange = { remark = it }, label = { Text("Remark") }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.PhotoLibrary, "Photo", Modifier.size(16.dp)); Text(" Photo", style = MaterialTheme.typography.labelSmall)
                            }
                            OutlinedButton(onClick = { filePick.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.AttachFile, "File", Modifier.size(16.dp)); Text(" File", style = MaterialTheme.typography.labelSmall)
                            }
                            OutlinedButton(onClick = {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                                    captureLocation(context) { la, lo -> lat = la; lng = lo; vm.message.value = "Location attached" }
                                else locationPerm.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.Place, "Location", Modifier.size(16.dp)); Text(" GPS", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        val extras = buildList {
                            if (attachments.isNotEmpty()) add("${attachments.size} file(s)")
                            if (lat != 0.0 || lng != 0.0) add("Location " + String.format("%.5f, %.5f", lat, lng))
                        }
                        if (extras.isNotEmpty()) Text(extras.joinToString("  •  "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("TOTAL", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text(Format.rupee(cart.sumOf { it.total }), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            }
            Button(
                onClick = { vm.save(if (editId != null && editId > 0) editId else null, selectedCustomer, cart.toList(), remark, lat, lng, attachments.toList()) { onBack() } },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("Save order") }
        }
    }

    if (showItemPicker) {
        ItemPickerDialog(items = items, onDismiss = { showItemPicker = false },
            onPick = { picked ->
                showItemPicker = false
                if (picked.hasTwoUnits) unitPickFor = picked
                else { val ch = picked.primaryChoice(); cart.add(CartLine(picked.id, picked.name, ch.price, 0.0, 1.0, unit = ch.unit)) }
            },
            onNewItem = { showItemPicker = false })
    }
    unitPickFor?.let { item ->
        UnitPickDialog(item = item, onPick = { choice -> cart.add(CartLine(item.id, item.name, choice.price, 0.0, 1.0, unit = choice.unit)); unitPickFor = null }, onDismiss = { unitPickFor = null })
    }
}

private fun captureLocation(context: android.content.Context, onGot: (Double, Double) -> Unit) {
    runCatching {
        val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
        val loc = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
        if (loc != null) onGot(loc.latitude, loc.longitude)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderListScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, onNew: () -> Unit, onReport: () -> Unit, onConvertToBill: () -> Unit, vm: OrderViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    val orders by vm.orders.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    var deleteFor by remember { mutableStateOf<CustOrder?>(null) }
    var nameQuery by remember { mutableStateOf("") }
    var dateOn by remember { mutableStateOf(true) }
    var dayMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    val context = LocalContext.current
    // Selecting orders to convert to one bill. Off by default so the list stays a plain list.
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<Long>() }

    fun sameDay(m: Long): Boolean {
        val a = Calendar.getInstance().apply { timeInMillis = m }
        val b = Calendar.getInstance().apply { timeInMillis = dayMillis }
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }
    val shown = orders.filter {
        (nameQuery.isBlank() || it.customerName.contains(nameQuery.trim(), true)) && (!dateOn || sameDay(it.dateMillis))
    }
    val total = shown.sumOf { it.grandTotal }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (selecting) "${selected.size} selected" else "Orders") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (selecting) {
                        IconButton(onClick = { selecting = false; selected.clear() }) { Icon(Icons.Filled.Remove, "Cancel selection") }
                    } else {
                        IconButton(onClick = { selecting = true }) { Icon(Icons.Filled.Add, "Convert orders to a bill") }
                        IconButton(onClick = onReport) { Icon(Icons.Filled.NoteAdd, "Consolidated report") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = onNew) { Icon(Icons.Filled.Add, "New order") } }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            OutlinedTextField(value = nameQuery, onValueChange = { nameQuery = it }, label = { Text("Search customer") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(checked = dateOn, onCheckedChange = { dateOn = it })
                Text("Date", style = MaterialTheme.typography.labelLarge)
                if (dateOn) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { pickDate(context, dayMillis) { dayMillis = it } }) { Text(Format.date(dayMillis)) }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
                items(shown, key = { it.id }) { o ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { if (selecting) { if (o.id in selected) selected.remove(o.id) else selected.add(o.id) } else onOpen(o.id) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (selecting) androidx.compose.material3.Checkbox(checked = o.id in selected, onCheckedChange = { if (o.id in selected) selected.remove(o.id) else selected.add(o.id) })
                        Column(Modifier.weight(1f)) {
                            Text(o.orderNo + "  •  " + o.customerName, fontWeight = FontWeight.Bold)
                            Text(Format.date(o.dateMillis) + (if (o.remark.isNotBlank()) "  •  " + o.remark.take(24) else ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                        Text(Format.rupee(o.grandTotal), fontWeight = FontWeight.Bold)
                        if (!selecting) IconButton(onClick = { deleteFor = o }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                    }
                    Divider()
                }
                if (shown.isEmpty()) item { Text("No orders.", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(16.dp)) }
            }
            Divider(thickness = 2.dp)
            if (selecting) {
                // Convert the ticked orders (one customer) into a prefilled sales bill.
                Button(
                    onClick = { vm.convertToBill(selected.toList()) { selecting = false; selected.clear(); onConvertToBill() } },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(14.dp)
                ) { Text("Convert ${selected.size} order(s) to a sales bill") }
            } else {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Bottom) {
                    Column {
                        Text("TOTAL ORDERS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        Text(Format.money(total), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.weight(1f))
                    Text("${shown.size} order(s)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }

    deleteFor?.let { o ->
        AlertDialog(onDismissRequest = { deleteFor = null }, title = { Text("Delete ${o.orderNo}?") },
            confirmButton = { TextButton(onClick = { vm.delete(o); deleteFor = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } })
    }
}

private fun pickDate(context: android.content.Context, current: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = current }
    android.app.DatePickerDialog(context, { _, y, m, d -> c.set(Calendar.YEAR, y); c.set(Calendar.MONTH, m); c.set(Calendar.DAY_OF_MONTH, d); onPicked(c.timeInMillis) },
        c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
}
