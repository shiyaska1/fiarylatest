package com.billing.pos.ui.quickbill

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.BillWithItems
import com.billing.pos.data.Item
import com.billing.pos.data.PaymentMethod
import com.billing.pos.pdf.InvoicePdf
import com.billing.pos.pdf.ThermalPdf
import com.billing.pos.print.ThermalPrinter
import com.billing.pos.data.hasTwoUnits
import com.billing.pos.data.primaryChoice
import com.billing.pos.ui.billing.BillingViewModel
import com.billing.pos.ui.billing.UnitPickDialog
import com.billing.pos.ui.billing.NewCustomerDialog
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.ui.common.rememberThumbnail
import com.billing.pos.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickBillScreen(
    onBack: () -> Unit,
    vm: BillingViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val items by vm.items.collectAsStateSafe()
    val photos by vm.itemPhotos.collectAsStateSafe()
    val customers by vm.customers.collectAsStateSafe()
    var showNewCustomer by remember { mutableStateOf(false) }
    var showNotes by remember { mutableStateOf(false) }
    val requireBatch = remember { com.billing.pos.data.AppPrefs(context).requireItemBatch }
    val allSizes by vm.allSizes.collectAsStateSafe()
    val allBatchesTop by vm.allBatches.collectAsStateSafe()
    var sizePickFor by remember { mutableStateOf<com.billing.pos.data.Item?>(null) }
    var batchPickFor by remember { mutableStateOf<com.billing.pos.data.Item?>(null) }
    var unitPickFor by remember { mutableStateOf<com.billing.pos.data.Item?>(null) }
    var pendingChoice by remember { mutableStateOf<com.billing.pos.data.UnitChoice?>(null) }

    val attachPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> vm.addAttachmentUris(context, uris) }
    val message by vm.message.collectAsStateSafe()

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    var query by remember { mutableStateOf("") }
    var selectedCat by remember { mutableStateOf<String?>(null) }   // null = All
    var showCart by remember { mutableStateOf(false) }

    val cats = remember(items) {
        items.map { it.category.trim() }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }
    }
    val filtered = items.filter {
        (selectedCat == null || it.category.equals(selectedCat, true)) &&
            (query.isBlank() || it.name.contains(query, true) || it.barcode.contains(query, true))
    }
    val cartQty = vm.cart.sumOf { it.qty }

    // --- print (save first, then print) ---
    var pendingPrint by remember { mutableStateOf<BillWithItems?>(null) }
    fun printNow(saved: BillWithItems) {
        scope.launch {
            val company = AppPrefs(context).company
            val r = withContext(Dispatchers.IO) { runCatching { ThermalPrinter.printBill(context, company, saved.bill, saved.lines) } }
            r.onSuccess { snackbar.showSnackbar("Sent to printer") }.onFailure { snackbar.showSnackbar(it.message ?: "Print failed") }
        }
    }
    val printPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val s = pendingPrint; pendingPrint = null
        if (granted && s != null) printNow(s) else scope.launch { snackbar.showSnackbar("Bluetooth permission denied") }
    }
    fun requestPrint(saved: BillWithItems) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !ThermalPrinter.hasConnectPermission(context)) {
            pendingPrint = saved; printPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else printNow(saved)
    }

    fun shareWhatsApp(saved: BillWithItems) {
        val company = AppPrefs(context).company
        val uri = InvoicePdf.make(context, company, saved.bill, saved.lines)
        val base = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(Intent(base).setPackage("com.whatsapp")) }.isSuccess) return
        if (runCatching { context.startActivity(Intent(base).setPackage("com.whatsapp.w4b")) }.isSuccess) return
        runCatching { context.startActivity(Intent.createChooser(base, "Share bill").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /**
     * Saves the current cart and runs [after] with the saved bill. The cart is kept
     * (re-saving updates the same bill); use the New-bill icon to clear.
     */
    fun saveThen(after: (BillWithItems) -> Unit) {
        scope.launch {
            val saved = vm.saveCurrent() ?: return@launch
            after(saved)
            showCart = false
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Quick Bill") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    IconButton(onClick = { showNotes = !showNotes }) {
                        Icon(Icons.Filled.EditNote, contentDescription = "Remarks & attachments",
                            tint = if (showNotes) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f))
                    }
                    IconButton(onClick = { vm.newBill() }) {
                        Icon(Icons.Filled.NoteAdd, contentDescription = "New bill (clear)")
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
        Column(Modifier.fillMaxSize().padding(pad)) {
            // Top: search + cart + big total
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("Search item") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    modifier = Modifier.weight(1f)
                )
                Box {
                    IconButton(onClick = { showCart = true }) {
                        Icon(Icons.Filled.ShoppingCart, contentDescription = "Cart", modifier = Modifier.size(30.dp))
                    }
                    if (vm.cart.isNotEmpty()) {
                        Box(
                            Modifier.align(Alignment.TopEnd).size(18.dp).clip(RoundedCornerShape(9.dp))
                                .background(MaterialTheme.colorScheme.error),
                            contentAlignment = Alignment.Center
                        ) { Text("${vm.cart.size}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }

            Card(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp).clickable { showCart = true },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("TOTAL", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(Format.rupee(vm.grandTotal), fontWeight = FontWeight.Bold, fontSize = 26.sp)
                }
            }

            // Body: left categories, right item grid
            Row(Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.width(96.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(4.dp)) {
                    CatButton("All", selectedCat == null) { selectedCat = null }
                    cats.forEach { c -> CatButton(c, selectedCat == c) { selectedCat = c } }
                }
                Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                if (filtered.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Text("No items", color = MaterialTheme.colorScheme.outline)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(112.dp),
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filtered, key = { it.id }) { item ->
                            ItemTile(item, photos[item.id]) {
                                when {
                                    allSizes.any { s -> s.itemId == item.id } -> sizePickFor = item
                                    item.hasTwoUnits -> unitPickFor = item
                                    requireBatch && allBatchesTop.any { b -> b.itemId == item.id } -> {
                                        pendingChoice = item.primaryChoice(); batchPickFor = item
                                    }
                                    else -> vm.addItemToCart(item)
                                }
                            }
                        }
                    }
                }
            }

            // Bottom: (toggle) remarks + attachment, payment mode + actions
            Divider()
            if (showNotes) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedTextField(
                        value = vm.remarks, onValueChange = { vm.updateRemarks(it) },
                        label = { Text("Remarks (prints on bill)") }, singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { runCatching { attachPicker.launch(arrayOf("*/*")) } }) {
                        Icon(Icons.Filled.AttachFile, contentDescription = "Attach document")
                    }
                }
                if (vm.editAttachments.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        vm.editAttachments.forEach { att ->
                            AssistChip(
                                onClick = { com.billing.pos.bills.BillAttachmentStore.open(context, att) },
                                label = { Text(att.name, maxLines = 1) },
                                trailingIcon = {
                                    Icon(Icons.Filled.Close, contentDescription = "Remove",
                                        modifier = Modifier.size(16.dp).clickable { vm.removeBillAttachment(att) })
                                }
                            )
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PaymentMethod.values().forEach { m ->
                    FilterChip(selected = vm.payment == m, onClick = { vm.selectPayment(m) }, label = { Text(m.label) })
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { saveThen { saved -> scope.launch { snackbar.showSnackbar("Saved ${saved.bill.billNo}") } } }, modifier = Modifier.weight(1f)) {
                    Text("Save")
                }
                IconButton(
                    onClick = { saveThen { shareWhatsApp(it) } },
                    modifier = Modifier.size(48.dp)
                ) { Icon(Icons.Filled.Share, contentDescription = "WhatsApp", tint = MaterialTheme.colorScheme.primary) }
                Button(onClick = { saveThen { requestPrint(it) } }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Print, null); Text("  Print")
                }
            }
        }
    }

    sizePickFor?.let { item ->
        com.billing.pos.ui.billing.SaleSizePickDialog(
            item = item,
            sizes = allSizes.filter { it.itemId == item.id },
            onPick = { size -> vm.addItemWithSize(item, size); sizePickFor = null },
            onDismiss = { sizePickFor = null }
        )
    }
    unitPickFor?.let { item ->
        val allBatches by vm.allBatches.collectAsStateSafe()
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
        val allBatches by vm.allBatches.collectAsStateSafe()
        com.billing.pos.ui.billing.SaleBatchPickDialog(
            item = item,
            batches = allBatches.filter { it.itemId == item.id },
            onPick = { batch ->
                vm.addItemWithBatch(item, batch, pendingChoice ?: item.primaryChoice())
                pendingChoice = null; batchPickFor = null
            },
            onDismiss = { pendingChoice = null; batchPickFor = null },
            onNoBatch = {
                vm.addItemWithUnit(item, pendingChoice ?: item.primaryChoice())
                pendingChoice = null; batchPickFor = null
            }
        )
    }
    if (showCart) {
        CartDialog(vm = vm, onDismiss = { showCart = false }, onAddCustomer = { showNewCustomer = true })
    }
    if (showNewCustomer) {
        NewCustomerDialog(
            onDismiss = { showNewCustomer = false },
            onSave = { n, p, a, t -> vm.addCustomer(n, p, a, t) { showNewCustomer = false } }
        )
    }
}

private fun pickQuickDate(context: android.content.Context, current: Long, onPicked: (Long) -> Unit) {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = current }
    android.app.DatePickerDialog(
        context,
        { _, y, m, d ->
            c.set(java.util.Calendar.YEAR, y); c.set(java.util.Calendar.MONTH, m); c.set(java.util.Calendar.DAY_OF_MONTH, d)
            onPicked(c.timeInMillis)
        },
        c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH)
    ).show()
}

@Composable
private fun CatButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Text(
            label,
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            textAlign = TextAlign.Center,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            maxLines = 2
        )
    }
}

@Composable
private fun ItemTile(item: Item, photoPath: String?, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onClick() }) {
        Column {
            Box(
                Modifier.fillMaxWidth().aspectRatio(1.3f).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                val bmp = photoPath?.let { rememberThumbnail(it, 250) }
                if (bmp != null) {
                    Image(bmp, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Filled.Fastfood, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(32.dp))
                }
            }
            Text(
                item.name,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
                maxLines = 2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
            )
            Text(
                Format.rupee(item.price),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp).padding(bottom = 6.dp),
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun CartDialog(vm: BillingViewModel, onDismiss: () -> Unit, onAddCustomer: () -> Unit) {
    val context = LocalContext.current
    val customers by vm.customers.collectAsStateSafe()
    var custExpanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cart") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                // Customer + add-new + date
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ExposedDropdownMenuBox(expanded = custExpanded, onExpandedChange = { custExpanded = !custExpanded }, modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            readOnly = true,
                            value = vm.selectedCustomer?.name ?: "Cash Customer",
                            onValueChange = {},
                            label = { Text("Customer") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(custExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = custExpanded, onDismissRequest = { custExpanded = false }) {
                            customers.forEach { c ->
                                DropdownMenuItem(text = { Text(c.name + if (c.isDefault) "  (default)" else "") }, onClick = { vm.selectCustomer(c); custExpanded = false })
                            }
                        }
                    }
                    IconButton(onClick = onAddCustomer) { Icon(Icons.Filled.PersonAdd, contentDescription = "New customer") }
                }
                OutlinedButton(onClick = { pickQuickDate(context, vm.dateMillis) { vm.updateDate(it) } }, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Date: ${Format.date(vm.dateMillis)}")
                }
                Divider(Modifier.padding(vertical = 8.dp))

                if (vm.cart.isEmpty()) {
                    Text("Cart is empty. Tap items to add.")
                } else {
                    vm.cart.forEachIndexed { index, line ->
                        var priceText by remember(line.uid) { mutableStateOf(Format.money(line.price)) }
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(line.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1)
                                IconButton(onClick = { vm.removeLine(index) }) { Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.error) }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = priceText,
                                    onValueChange = { v -> val f = v.filter { it.isDigit() || it == '.' }; priceText = f; vm.setLinePrice(index, f.toDoubleOrNull() ?: 0.0) },
                                    label = { Text("Price") }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.width(110.dp)
                                )
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = { vm.changeQty(index, -1.0) }) { Icon(Icons.Filled.Remove, "Less") }
                                Text(Format.qty(line.qty), fontWeight = FontWeight.Bold)
                                IconButton(onClick = { vm.changeQty(index, 1.0) }) { Icon(Icons.Filled.Add, "More") }
                                Spacer(Modifier.width(6.dp))
                                Text(Format.rupee(line.total), fontWeight = FontWeight.Bold)
                            }
                            Divider()
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("TOTAL", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(Format.rupee(vm.grandTotal), fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
