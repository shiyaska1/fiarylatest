package com.billing.pos.ui.items

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import android.net.Uri
import com.billing.pos.data.DownloadSaver
import com.billing.pos.data.Item
import com.billing.pos.data.ItemAttachment
import com.billing.pos.data.Repository
import com.billing.pos.items.ItemAttachmentStore
import com.billing.pos.pdf.BarcodePdf
import com.billing.pos.pdf.TablePdf
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.ui.common.rememberPdfDownloader
import com.billing.pos.ui.common.rememberThumbnail
import com.billing.pos.util.Format
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** An item with its computed stock, last purchase rate and last supplier. */
data class ItemStockRow(val item: Item, val stock: Double, val purchaseRate: Double, val lastSupplier: String = "")

/** A row in the batch-expiry report. */
data class ExpiryRow(val itemName: String, val batchNo: String, val expiryMillis: Long, val quantity: Double)

/** Common units of measure offered in the item entry dropdown. */
val ITEM_UNITS = listOf(
    "PCS", "NOS", "BOX", "PACK", "SET", "PAIR", "DOZEN",
    "KG", "GRAM", "QUINTAL", "TON",
    "LTR", "ML",
    "METER", "CM", "FEET", "INCH", "SQFT", "ROLL", "BAG", "BOTTLE", "UNIT"
)

class ItemsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    val rows: StateFlow<List<ItemStockRow>> =
        combine(repo.items, repo.stockByName, repo.purchaseLines, repo.purchaseLineParties) { items, byName, pLines, parties ->
            // stockByName already nets receipts/purchases/sales/material-out; purchaseLines is only
            // for the last purchase rate, parties for the last supplier.
            val rateByName = pLines.groupBy { it.name.lowercase() }
            val lastSupplierByName = parties.groupBy { it.name.lowercase() }
                .mapValues { (_, l) -> l.maxByOrNull { it.dateMillis }?.supplierName ?: "" }
            items.map { item ->
                val key = item.name.lowercase()
                val lastRate = rateByName[key].orEmpty().maxByOrNull { it.dateMillis }?.price ?: 0.0
                ItemStockRow(item, item.openingStock + (byName[key] ?: 0.0), lastRate, lastSupplierByName[key] ?: "")
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Distinct categories already in use, for the category dropdown. */
    val categories: StateFlow<List<String>> =
        repo.items.map { list ->
            list.map { it.category.trim() }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    /** Saves every filled row from the multi-item form, with its photos. */
    fun saveMany(context: android.content.Context, rows: List<MultiItemRow>, onDone: () -> Unit) {
        val usable = rows.filter { it.isFilled }
        if (usable.isEmpty()) { message.value = "Nothing to save"; return }
        viewModelScope.launch {
            var saved = 0
            usable.forEach { r ->
                val id = repo.addItem(
                    name = r.name.trim(),
                    price = r.price.toDoubleOrNull() ?: 0.0,
                    taxPercent = 0.0,
                    category = r.category.trim()
                )
                // Photos are only copied in once the item exists, so nothing is orphaned.
                r.photos.forEach { uri ->
                    val att = withContext(Dispatchers.IO) {
                        com.billing.pos.items.ItemAttachmentStore.copyIn(context, uri, "photo")
                    }
                    if (att != null) repo.addItemAttachment(att.copy(itemId = id))
                }
                saved++
            }
            message.value = "$saved item(s) saved"
            onDone()
        }
    }

    /** Attachments (photos / location photo / PDF) staged for the item being edited. */
    val editAttachments: SnapshotStateList<ItemAttachment> = mutableStateListOf()

    /** Batches (batch no + expiry + qty) staged for the item being edited. */
    val editBatches: SnapshotStateList<com.billing.pos.data.ItemBatch> = mutableStateListOf()

    /** Sizes/variants (name + price) staged for the item being edited. */
    val editSizes: SnapshotStateList<com.billing.pos.data.ItemSize> = mutableStateListOf()
    fun addSizeRow(name: String, price: Double) {
        editSizes.add(com.billing.pos.data.ItemSize(itemId = 0, name = name.trim(), price = price))
    }
    fun removeSizeRow(index: Int) { if (index in editSizes.indices) editSizes.removeAt(index) }
    fun updateSizeRow(index: Int, name: String, price: Double) {
        if (index in editSizes.indices) editSizes[index] = editSizes[index].copy(name = name, price = price)
    }

    /** Item batches joined with item names, sorted by expiry, for the expiry report. */
    val expiryRows: StateFlow<List<ExpiryRow>> =
        combine(repo.itemBatches, repo.items) { batches, items ->
            val nameById = items.associate { it.id to it.name }
            batches.filter { it.expiryMillis > 0 }
                .sortedBy { it.expiryMillis }
                .map { ExpiryRow(nameById[it.itemId] ?: "?", it.batchNo, it.expiryMillis, it.quantity) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Load the current item's attachments + batches into the staging lists when a dialog opens. */
    fun beginEdit(item: Item?) {
        editAttachments.clear()
        editBatches.clear()
        editSizes.clear()
        val id = item?.id ?: return
        viewModelScope.launch {
            editAttachments.addAll(repo.itemAttachmentsFor(id))
            editBatches.addAll(repo.batchesForItem(id))
            editSizes.addAll(repo.sizesForItem(id))
        }
    }

    fun addBatchRow(batchNo: String, expiryMillis: Long, qty: Double) {
        editBatches.add(com.billing.pos.data.ItemBatch(itemId = 0, batchNo = batchNo.trim(), expiryMillis = expiryMillis, quantity = qty))
    }
    fun removeBatchRow(index: Int) { if (index in editBatches.indices) editBatches.removeAt(index) }
    fun updateBatchRow(index: Int, batchNo: String, expiryMillis: Long, qty: Double) {
        if (index in editBatches.indices) editBatches[index] = editBatches[index].copy(batchNo = batchNo.trim(), expiryMillis = expiryMillis, quantity = qty)
    }

    fun addUris(context: android.content.Context, uris: List<Uri>, kind: String) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) { uris.mapNotNull { ItemAttachmentStore.copyIn(context, it, kind) } }
            editAttachments.addAll(added)
        }
    }

    fun addCapturedFile(file: java.io.File, name: String, mime: String, kind: String) {
        if (file.exists() && file.length() > 0) editAttachments.add(ItemAttachmentStore.fromFile(file, name, mime, kind))
        else file.delete()
    }

    fun removeAttachment(attachment: ItemAttachment) {
        editAttachments.remove(attachment)
        viewModelScope.launch {
            if (attachment.id > 0) repo.deleteItemAttachment(attachment)
            else withContext(Dispatchers.IO) { ItemAttachmentStore.delete(attachment) }
        }
    }

    /** Discards the edit: deletes any newly-added (unsaved) attachment files. */
    fun cancelEdit() {
        val unsaved = editAttachments.filter { it.id == 0L }
        editAttachments.clear()
        editBatches.clear()
        editSizes.clear()
        if (unsaved.isNotEmpty()) viewModelScope.launch(Dispatchers.IO) { unsaved.forEach { ItemAttachmentStore.delete(it) } }
    }

    fun save(
        existing: Item?, name: String, price: Double, tax: Double, barcode: String, hsn: String,
        category: String, openingStock: Double, unit: String, storeLocation: String, chemicalContent: String,
        secondaryUnit: String = "PCS", conversionFactor: Double = 1.0, purchasePrice: Double = 0.0,
        onDone: () -> Unit
    ) {
        if (name.isBlank()) { message.value = "Enter a name"; return }
        viewModelScope.launch {
            val id: Long = if (existing == null) {
                repo.addItem(
                    name, price, tax, barcode, hsn, category, openingStock, unit, storeLocation, chemicalContent,
                    secondaryUnit, conversionFactor, purchasePrice
                )
            } else {
                repo.updateItem(existing.copy(
                    name = name.trim(), price = price, purchasePrice = purchasePrice, taxPercent = tax, barcode = barcode.trim(),
                    hsn = hsn.trim(), category = category.trim(), openingStock = openingStock,
                    unit = unit.trim().ifBlank { "PCS" }, storeLocation = storeLocation.trim(),
                    chemicalContent = chemicalContent.trim(),
                    secondaryUnit = secondaryUnit.trim().ifBlank { "PCS" },
                    conversionFactor = if (conversionFactor > 0) conversionFactor else 1.0
                ))
                existing.id
            }
            editAttachments.filter { it.id == 0L }.forEach { repo.addItemAttachment(it.copy(itemId = id)) }
            editAttachments.clear()
            repo.replaceBatches(id, editBatches.toList())
            editBatches.clear()
            repo.replaceSizes(id, editSizes.toList())
            editSizes.clear()
            message.value = "Saved"; onDone()
        }
    }

    fun delete(item: Item) {
        viewModelScope.launch { repo.deleteItem(item); message.value = "Item deleted" }
    }

    fun clearAllItems() {
        viewModelScope.launch { repo.clearAllItems(); message.value = "All items cleared" }
    }

    /** Imports items from an .xlsx/.csv file, skipping names already in the master. */
    fun importSpreadsheet(context: Context, uri: Uri) {
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) { com.billing.pos.data.SpreadsheetImport.read(context, uri) }
            if (rows.isEmpty()) { message.value = "No item rows found in the file"; return@launch }
            var added = 0; var skipped = 0
            rows.forEach { r ->
                val existing = repo.itemByName(r.name)
                val itemId = if (existing == null) {
                    added++
                    repo.addItem(r.name, r.price, r.taxPercent, r.barcode, r.hsn, r.category, r.openingStock, r.unit, r.location, r.chemicalContent)
                } else { skipped++; existing.id }
                if (r.batchNo.isNotBlank() || r.expiryMillis > 0) {
                    repo.addBatch(com.billing.pos.data.ItemBatch(
                        itemId = itemId, batchNo = r.batchNo,
                        expiryMillis = r.expiryMillis,
                        quantity = if (r.batchQty > 0) r.batchQty else r.openingStock
                    ))
                }
            }
            message.value = "Imported $added item(s)" + if (skipped > 0) ", skipped $skipped existing" else ""
        }
    }

    /** Writes a blank import template (CSV with the expected headers) to Downloads. */
    fun downloadTemplate(context: Context) {
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                val f = java.io.File(context.cacheDir, "item-import-template.csv")
                f.writeText(
                    "Name,Price,Tax,Category,Opening Stock,Unit,Barcode,HSN,Location,Chemical Content,Batch No,Expiry Date,Batch Qty\n" +
                        "Sample item,100,0,General,10,PCS,,,,,B001,2026-12-31,10\n"
                )
                f
            }
            val ok = withContext(Dispatchers.IO) { DownloadSaver.save(context, file, file.name, "text/csv") }
            message.value = if (ok) "Template saved to Downloads: ${file.name}" else "Could not save template"
        }
    }

    /** Inserts scanned items into the master, skipping any name that already exists. */
    fun importItems(list: List<ImportItem>, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            var added = 0
            list.forEach { p ->
                val name = p.name.trim()
                if (name.isNotBlank() && repo.itemByName(name) == null) {
                    repo.addItem(name, p.price, 0.0, "", "", p.category, p.openingStock, p.unit, "")
                    added++
                }
            }
            message.value = "Imported $added new item(s)"
            onDone(added)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemsScreen(
    onBack: () -> Unit,
    initialEditItemId: Long? = null,
    vm: ItemsViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val rows by vm.rows.collectAsStateSafe()
    val categories by vm.categories.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    val requireBatch = remember { com.billing.pos.data.AppPrefs(context).requireItemBatch }
    val businessType = remember { com.billing.pos.data.AppPrefs(context).businessType }
    val expiryRows by vm.expiryRows.collectAsStateSafe()
    var showExpiry by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    // Filters: name contains, category, stock below a number.
    var filterName by remember { mutableStateOf("") }
    var filterCategory by remember { mutableStateOf("") }
    var stockBelow by remember { mutableStateOf("") }
    val stockBelowVal = stockBelow.toDoubleOrNull()
    val filteredRows = rows.filter {
        (filterName.isBlank() || it.item.name.contains(filterName, true)) &&
            (filterCategory.isBlank() || it.item.category.equals(filterCategory, true)) &&
            (stockBelowVal == null || it.stock < stockBelowVal)
    }
    val downloadPdf = rememberPdfDownloader { msg -> scope.launch { snackbar.showSnackbar(msg) } }
    fun buildItemsPdf(): java.io.File {
        val company = com.billing.pos.data.AppPrefs(context).company
        val subtitle = buildString {
            append("Items: ${filteredRows.size}")
            if (filterCategory.isNotBlank()) append("  |  Category: $filterCategory")
            if (filterName.isNotBlank()) append("  |  Name~ $filterName")
            if (stockBelowVal != null) append("  |  Stock < ${Format.qty(stockBelowVal)}")
        }
        val cols = listOf(
            TablePdf.Col("Item", 3f), TablePdf.Col("Category", 1.6f), TablePdf.Col("Unit", 0.9f),
            TablePdf.Col("Stock", 1f, right = true), TablePdf.Col("Buy", 1.1f, right = true),
            TablePdf.Col("Sell", 1.1f, right = true), TablePdf.Col("Last Supplier", 2f)
        )
        val data = filteredRows.map { r ->
            listOf(r.item.name, r.item.category, r.item.unit, Format.qty(r.stock),
                Format.money(r.purchaseRate), Format.money(r.item.price), r.lastSupplier)
        }
        return TablePdf.generate(context, company, "Item List", subtitle, cols, data)
    }

    var showDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Item?>(null) }
    // "+" asks whether one item or several, then opens the matching form.
    var askAddMode by remember { mutableStateOf(false) }
    var showMulti by remember { mutableStateOf(false) }
    var showPhotoMulti by remember { mutableStateOf(false) }
    var deleteFor by remember { mutableStateOf<Item?>(null) }
    // Deep link (from the item-wise sales report): open this item in edit mode once loaded.
    var editLinkDone by remember { mutableStateOf(false) }
    LaunchedEffect(rows, initialEditItemId) {
        if (!editLinkDone && initialEditItemId != null && initialEditItemId > 0) {
            rows.firstOrNull { it.item.id == initialEditItemId }?.let { r ->
                editing = r.item; vm.beginEdit(r.item); showDialog = true; editLinkDone = true
            }
        }
    }
    var printFor by remember { mutableStateOf<Item?>(null) }

    // Import items: Excel/CSV file, camera scan, or gallery photo (OCR → review).
    var scanResult by remember { mutableStateOf<List<com.billing.pos.ocr.ScannedItem>?>(null) }
    var importMenu by remember { mutableStateOf(false) }
    val scanList = com.billing.pos.ocr.rememberListScanner { lines ->
        scanResult = com.billing.pos.ocr.ItemListParser.parse(lines)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importSpreadsheet(context, uri)
    }
    // Language is asked before the gallery opens — this flow fills the review list directly.
    var galleryOcrLang by remember { mutableStateOf<String?>(null) }
    var askGalleryLang by remember { mutableStateOf(false) }
    val galleryScan = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            scanResult = com.billing.pos.ocr.ItemListParser.parse(
                com.billing.pos.ocr.TextOcr.lines(context, uri, galleryOcrLang)
            )
        }
    }

    if (askGalleryLang) {
        com.billing.pos.ui.common.OcrLanguageAskDialog(
            onPick = { picked ->
                galleryOcrLang = picked; askGalleryLang = false
                galleryScan.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onDismiss = { askGalleryLang = false }
        )
    }

    fun doPrint(item: Item, count: Int) {
        scope.launch {
            val pdf = withContext(Dispatchers.IO) { BarcodePdf.generate(context, item, count) }
            if (pdf == null) { snackbar.showSnackbar("This item has no barcode"); return@launch }
            val ok = withContext(Dispatchers.IO) { DownloadSaver.save(context, pdf, pdf.name, "application/pdf") }
            snackbar.showSnackbar(if (ok) "Barcodes saved to Downloads: ${pdf.name}" else "Could not save")
        }
    }
    var pendingPrint by remember { mutableStateOf<Pair<Item, Int>?>(null) }
    var pendingTemplate by remember { mutableStateOf(false) }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pp = pendingPrint; pendingPrint = null
        val tpl = pendingTemplate; pendingTemplate = false
        when {
            granted && pp != null -> doPrint(pp.first, pp.second)
            granted && tpl -> vm.downloadTemplate(context)
            else -> scope.launch { snackbar.showSnackbar("Storage permission denied") }
        }
    }
    fun requestTemplate() {
        if (DownloadSaver.needsLegacyPermission() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) { pendingTemplate = true; storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
        else vm.downloadTemplate(context)
    }
    fun requestPrint(item: Item, count: Int) {
        if (DownloadSaver.needsLegacyPermission() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) { pendingPrint = item to count; storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
        else doPrint(item, count)
    }

    // --- Item attachments: photos, location photo, PDF catalogue ---
    var galleryKind by remember { mutableStateOf("PHOTO") }
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> vm.addUris(context, uris, galleryKind) }
    val cataloguePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> vm.addUris(context, uris, "CATALOGUE") }

    var pendingCapture by remember { mutableStateOf<java.io.File?>(null) }
    var captureKind by remember { mutableStateOf("PHOTO") }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pendingCapture; pendingCapture = null
        if (ok && f != null) vm.addCapturedFile(f, "Photo_${f.name}", "image/jpeg", captureKind) else f?.delete()
    }
    fun launchCapture(kind: String) {
        captureKind = kind
        val file = java.io.File(ItemAttachmentStore.dir(context), "cam_${System.nanoTime()}.jpg")
        pendingCapture = file
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        runCatching { takePhoto.launch(uri) }
            .onFailure { pendingCapture?.delete(); pendingCapture = null; scope.launch { snackbar.showSnackbar("No camera app found") } }
    }
    var pendingCameraAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingCameraAction; pendingCameraAction = null
        if (granted) action?.invoke() else scope.launch { snackbar.showSnackbar("Camera permission denied") }
    }
    fun withCamera(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) action()
        else { pendingCameraAction = action; cameraPermission.launch(Manifest.permission.CAMERA) }
    }
    fun pickPhotos(kind: String) {
        galleryKind = kind
        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Items") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    if (requireBatch) {
                        IconButton(onClick = { showExpiry = true }) {
                            Icon(Icons.Filled.EventBusy, contentDescription = "Batch expiry report")
                        }
                    }
                    Box {
                        IconButton(onClick = { importMenu = true }) {
                            Icon(Icons.Filled.DocumentScanner, contentDescription = "Import items")
                        }
                        DropdownMenu(expanded = importMenu, onDismissRequest = { importMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Import Excel / CSV") },
                                onClick = {
                                    importMenu = false
                                    filePicker.launch(arrayOf(
                                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                        "application/vnd.ms-excel", "text/csv", "text/comma-separated-values",
                                        "application/octet-stream", "*/*"
                                    ))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Download blank template") },
                                onClick = { importMenu = false; requestTemplate() }
                            )
                            Divider()
                            DropdownMenuItem(
                                text = { Text("Clear all items", color = MaterialTheme.colorScheme.error) },
                                onClick = { importMenu = false; confirmClear = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Scan with camera") },
                                onClick = { importMenu = false; scanList() }
                            )
                            DropdownMenuItem(
                                text = { Text("Pick photo (gallery)") },
                                onClick = { importMenu = false; askGalleryLang = true }
                            )
                        }
                    }
                    IconButton(onClick = { downloadPdf { buildItemsPdf() } }) {
                        Icon(Icons.Filled.Download, contentDescription = "Download list PDF")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { askAddMode = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add item")
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            // Filter bar: name, category, stock-below.
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = filterName, onValueChange = { filterName = it },
                        label = { Text("Item name") }, singleLine = true, modifier = Modifier.weight(1.4f)
                    )
                    OutlinedTextField(
                        value = stockBelow, onValueChange = { stockBelow = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Stock <") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f)
                    )
                }
                var catMenu by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = catMenu, onExpandedChange = { catMenu = !catMenu }) {
                    OutlinedTextField(
                        readOnly = true, value = filterCategory.ifBlank { "All categories" }, onValueChange = {},
                        label = { Text("Category") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(catMenu) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = catMenu, onDismissRequest = { catMenu = false }) {
                        DropdownMenuItem(text = { Text("All categories") }, onClick = { filterCategory = ""; catMenu = false })
                        categories.forEach { cat ->
                            DropdownMenuItem(text = { Text(cat) }, onClick = { filterCategory = cat; catMenu = false })
                        }
                    }
                }
            }
            Divider()
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                items(filteredRows, key = { it.item.id }) { row ->
                val item = row.item
                Row(
                    Modifier.fillMaxWidth().clickable { editing = item; vm.beginEdit(item); showDialog = true }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.name + (if (item.category.isNotBlank()) "  ·  ${item.category}" else ""),
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Stock: ${Format.qty(row.stock)} ${item.unit}   •   Buy: ${Format.rupee(row.purchaseRate)}   •   Sell: ${Format.rupee(item.price)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                        )
                        if (row.lastSupplier.isNotBlank()) {
                            Text(
                                "Last supplier: ${row.lastSupplier}",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline
                            )
                        }
                        if (item.barcode.isNotBlank() || item.taxPercent > 0) {
                            Text(
                                (if (item.taxPercent > 0) "Tax ${Format.money(item.taxPercent)}%" else "") +
                                    (if (item.taxPercent > 0 && item.barcode.isNotBlank()) "  •  " else "") +
                                    (if (item.barcode.isNotBlank()) item.barcode else ""),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                    if (item.barcode.isNotBlank()) {
                        IconButton(onClick = { printFor = item }) { Icon(Icons.Filled.QrCode, "Print barcode") }
                    }
                    IconButton(onClick = { deleteFor = item }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                }
                Divider()
                }
            }
        }
    }

    if (askAddMode) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askAddMode = false },
            title = { Text("Add items") },
            text = {
                Column {
                    androidx.compose.material3.TextButton(onClick = {
                        askAddMode = false; editing = null; vm.beginEdit(null); showDialog = true
                    }, modifier = Modifier.fillMaxWidth()) { Text("Single item — the full form") }
                    androidx.compose.material3.TextButton(onClick = {
                        askAddMode = false; showMulti = true
                    }, modifier = Modifier.fillMaxWidth()) { Text("Multiple items — a table of rows") }
                    androidx.compose.material3.TextButton(onClick = {
                        askAddMode = false; showPhotoMulti = true
                    }, modifier = Modifier.fillMaxWidth()) { Text("Multiple by image — photograph each item") }
                }
            },
            confirmButton = {},
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { askAddMode = false }) { Text("Cancel") }
            }
        )
    }

    if (showPhotoMulti) {
        val cats = remember(rows) {
            rows.map { it.item.category }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }
        }
        PhotoItemsDialog(
            categories = cats,
            onSave = { entered -> vm.saveMany(context, entered) { showPhotoMulti = false } },
            onDismiss = { showPhotoMulti = false }
        )
    }

    if (showMulti) {
        val cats = remember(rows) {
            rows.map { it.item.category }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }
        }
        MultiItemDialog(
            categories = cats,
            onSave = { entered -> vm.saveMany(context, entered) { showMulti = false } },
            onDismiss = { showMulti = false }
        )
    }

    if (showDialog) {
        ItemDialog(
            existing = editing,
            categories = categories,
            attachments = vm.editAttachments,
            showBatches = requireBatch,
            batches = vm.editBatches,
            onAddBatch = { no, exp, q -> vm.addBatchRow(no, exp, q) },
            onRemoveBatch = { vm.removeBatchRow(it) },
            onUpdateBatch = { idx, no, exp, q -> vm.updateBatchRow(idx, no, exp, q) },
            businessType = businessType,
            sizes = vm.editSizes,
            onAddSize = { nm, pr -> vm.addSizeRow(nm, pr) },
            onRemoveSize = { vm.removeSizeRow(it) },
            onUpdateSize = { idx, nm, pr -> vm.updateSizeRow(idx, nm, pr) },
            onAddPhotoGallery = { pickPhotos("PHOTO") },
            onAddPhotoCamera = { withCamera { launchCapture("PHOTO") } },
            onAddLocationPhoto = { withCamera { launchCapture("LOCATION") } },
            onAddCatalogue = { runCatching { cataloguePicker.launch(arrayOf("application/pdf")) } },
            onRemoveAttachment = { vm.removeAttachment(it) },
            onDismiss = { vm.cancelEdit(); showDialog = false },
            onSave = { n, p, t, b, h, cat, os, u, loc, chem, sec, f, pp ->
                vm.save(editing, n, p, t, b, h, cat, os, u, loc, chem, sec, f, pp) { showDialog = false }
            }
        )
    }
    if (showExpiry) {
        ExpiryReportDialog(rows = expiryRows, onDismiss = { showExpiry = false })
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all items?") },
            text = { Text("Removes every item, its batches and photos. Existing bills keep their lines. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { vm.clearAllItems(); confirmClear = false }) { Text("Clear all", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
    deleteFor?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete ${item.name}?") },
            text = { Text("Existing bills keep their line items; only the master item is removed.") },
            confirmButton = { TextButton(onClick = { vm.delete(item); deleteFor = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } }
        )
    }
    printFor?.let { item ->
        PrintCountDialog(item = item, onDismiss = { printFor = null }, onPrint = { count -> printFor = null; requestPrint(item, count) })
    }
    scanResult?.let { parsed ->
        ScanImportDialog(
            initial = parsed,
            existingNames = rows.map { it.item.name.trim().lowercase() }.toSet(),
            categories = categories,
            onDismiss = { scanResult = null },
            onImport = { list -> vm.importItems(list) { scanResult = null } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemDialog(
    existing: Item?,
    categories: List<String>,
    attachments: List<ItemAttachment>,
    showBatches: Boolean,
    batches: List<com.billing.pos.data.ItemBatch>,
    onAddBatch: (String, Long, Double) -> Unit,
    onRemoveBatch: (Int) -> Unit,
    onUpdateBatch: (Int, String, Long, Double) -> Unit,
    businessType: String,
    sizes: List<com.billing.pos.data.ItemSize>,
    onAddSize: (String, Double) -> Unit,
    onRemoveSize: (Int) -> Unit,
    onUpdateSize: (Int, String, Double) -> Unit,
    onAddPhotoGallery: () -> Unit,
    onAddPhotoCamera: () -> Unit,
    onAddLocationPhoto: () -> Unit,
    onAddCatalogue: () -> Unit,
    onRemoveAttachment: (ItemAttachment) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, Double, Double, String, String, String, Double, String, String, String, String, Double, Double) -> Unit
) {
    val context = LocalContext.current
    var showBatchInput by remember { mutableStateOf(false) }
    var editBatchIndex by remember { mutableStateOf(-1) }
    var showSizeInput by remember { mutableStateOf(false) }
    // Full-screen image viewer for attachment photos.
    var viewImages by remember { mutableStateOf<List<String>?>(null) }
    var viewStart by remember { mutableStateOf(0) }
    var chemical by remember { mutableStateOf(existing?.chemicalContent ?: "") }
    val isMedical = businessType == "Medical store"
    val isRestaurant = businessType == "Restaurant"
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var price by remember { mutableStateOf(existing?.price?.let { Format.money(it) } ?: "") }
    var purchasePrice by remember {
        mutableStateOf(if ((existing?.purchasePrice ?: 0.0) > 0.0) Format.money(existing!!.purchasePrice) else "")
    }
    var priceForQty by remember { mutableStateOf("1") }
    var taxable by remember { mutableStateOf((existing?.taxPercent ?: 0.0) > 0.0) }
    var taxPercent by remember { mutableStateOf(if ((existing?.taxPercent ?: 0.0) > 0.0) Format.money(existing!!.taxPercent) else "18") }
    var barcode by remember { mutableStateOf(existing?.barcode ?: "") }
    var hsn by remember { mutableStateOf(existing?.hsn ?: "") }
    var category by remember { mutableStateOf(existing?.category ?: "") }
    var catMenu by remember { mutableStateOf(false) }
    var openingStock by remember { mutableStateOf(if ((existing?.openingStock ?: 0.0) != 0.0) Format.qty(existing!!.openingStock) else "") }
    var unit by remember { mutableStateOf(existing?.unit?.ifBlank { "PCS" } ?: "PCS") }
    var unitMenu by remember { mutableStateOf(false) }
    var secondaryUnit by remember { mutableStateOf(existing?.secondaryUnit?.ifBlank { "PCS" } ?: "PCS") }
    var secUnitMenu by remember { mutableStateOf(false) }
    var factorText by remember {
        mutableStateOf(if ((existing?.conversionFactor ?: 1.0) != 1.0) Format.qty(existing!!.conversionFactor) else "1")
    }
    // Two genuinely different units → the factor matters and a unit prompt appears at billing.
    val unitsDiffer = !secondaryUnit.trim().equals(unit.trim(), ignoreCase = true) &&
        secondaryUnit.isNotBlank() && unit.isNotBlank()
    val secRate = run {
        val f = factorText.toDoubleOrNull() ?: 1.0
        val entered = price.toDoubleOrNull() ?: 0.0
        val forQty = priceForQty.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0
        val perPrimary = entered / forQty
        if (f > 0) kotlin.math.round(perPrimary / f * 100.0) / 100.0 else 0.0
    }
    var storeLocation by remember { mutableStateOf(existing?.storeLocation ?: "") }
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { barcode = it }
    }
    // Photo barcode: take/pick an image, draw a box on the barcode, decode only inside it.
    // More reliable than a fast live scan for worn or curved labels.
    var barcodeUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val barcodeCamera = com.billing.pos.ocr.rememberImageCamera { uri -> barcodeUri = uri }
    val barcodeGallery = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) barcodeUri = uri }
    barcodeUri?.let { u ->
        com.billing.pos.ui.common.RegionBarcodeDialog(
            uri = u,
            onResult = { if (it.isNotBlank()) barcode = it; barcodeUri = null },
            onDismiss = { barcodeUri = null }
        )
    }
    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()?.let { if (it.isNotBlank()) name = it }
        }
    }
    fun speakName() {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Say the item name")
        }
        runCatching { speechLauncher.launch(intent) }
    }
    val scanName = com.billing.pos.ocr.rememberNameScanner { if (it.isNotBlank()) name = it }
    // Draw-a-box OCR: pick from camera or gallery, drag a rectangle over just the
    // item name, and only the text inside the box is read into the name field.
    var regionUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val regionCamera = com.billing.pos.ocr.rememberImageCamera { uri -> regionUri = uri }
    val regionGallery = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) regionUri = uri }
    regionUri?.let { u ->
        com.billing.pos.ui.common.RegionOcrDialog(
            uri = u,
            onResult = { if (it.isNotBlank()) name = it; regionUri = null },
            onDismiss = { regionUri = null }
        )
    }
    // Handwrite the item name.
    var showNameDraw by remember { mutableStateOf(false) }
    if (showNameDraw) {
        com.billing.pos.ui.common.HandwriteTextDialog(
            onResult = { if (it.isNotBlank()) name = it; showNameDraw = false },
            onDismiss = { showNameDraw = false }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New item" else "Edit item") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("Name *") },
                    // Tall + multiline so full OCR text is visible and easy to trim.
                    singleLine = false, minLines = 3, maxLines = 6,
                    trailingIcon = {
                        // Compact so all four fit: handwrite, photo, gallery, voice.
                        Row {
                            val ib = Modifier.size(38.dp)
                            val ic = Modifier.size(20.dp)
                            IconButton(onClick = { showNameDraw = true }, modifier = ib) {
                                Icon(Icons.Filled.Gesture, "Handwrite item name", ic, tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = { regionCamera() }, modifier = ib) {
                                Icon(Icons.Filled.PhotoCamera, "Photo — draw a box to read the name", ic, tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = {
                                regionGallery.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            }, modifier = ib) {
                                Icon(Icons.Filled.PhotoLibrary, "Gallery — draw a box to read the name", ic, tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = { speakName() }, modifier = ib) {
                                Icon(Icons.Filled.Mic, "Speak item name", ic, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // Price for a quantity of units (e.g. 120 for 12 => 10 per unit).
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = price, onValueChange = { price = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Price *") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1.4f)
                    )
                    OutlinedTextField(
                        value = priceForQty, onValueChange = { priceForQty = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("for units") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f)
                    )
                }
                // Cost/purchase price per primary unit — used for profit and stock value.
                OutlinedTextField(
                    value = purchasePrice,
                    onValueChange = { purchasePrice = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Purchase price (optional)") }, singleLine = true,
                    supportingText = {
                        val sell = (price.toDoubleOrNull() ?: 0.0) /
                            ((priceForQty.toDoubleOrNull() ?: 1.0).takeIf { it > 0.0 } ?: 1.0)
                        val cost = purchasePrice.toDoubleOrNull() ?: 0.0
                        if (cost > 0.0 && sell > 0.0) {
                            val margin = sell - cost
                            Text(
                                "Margin ${Format.money(margin)} (${Format.money(margin / cost * 100.0)}%)",
                                color = if (margin >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                run {
                    val pv = price.toDoubleOrNull() ?: 0.0
                    val qv = priceForQty.toDoubleOrNull() ?: 1.0
                    if (qv > 1.0 && pv > 0.0) {
                        Text(
                            "= ${Format.rupee(pv / qv)} per unit",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                // Primary unit — price and stock are always expressed in this unit.
                ExposedDropdownMenuBox(expanded = unitMenu, onExpandedChange = { unitMenu = !unitMenu }) {
                    OutlinedTextField(
                        value = unit, onValueChange = { unit = it }, label = { Text("Primary unit") }, singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = unitMenu) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = unitMenu, onDismissRequest = { unitMenu = false }) {
                        ITEM_UNITS.forEach { u ->
                            DropdownMenuItem(text = { Text(u) }, onClick = { unit = u; unitMenu = false })
                        }
                    }
                }

                // Secondary unit + how many of it make one primary unit.
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        ExposedDropdownMenuBox(expanded = secUnitMenu, onExpandedChange = { secUnitMenu = !secUnitMenu }) {
                            OutlinedTextField(
                                value = secondaryUnit, onValueChange = { secondaryUnit = it },
                                label = { Text("Secondary unit") }, singleLine = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = secUnitMenu) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(expanded = secUnitMenu, onDismissRequest = { secUnitMenu = false }) {
                                ITEM_UNITS.forEach { u ->
                                    DropdownMenuItem(text = { Text(u) }, onClick = { secondaryUnit = u; secUnitMenu = false })
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = factorText,
                        onValueChange = { factorText = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Conv. factor") }, singleLine = true,
                        enabled = unitsDiffer,
                        isError = unitsDiffer && (factorText.toDoubleOrNull() ?: 0.0) <= 1.0,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.width(120.dp)
                    )
                }
                Text(
                    if (unitsDiffer)
                        "1 ${unit.ifBlank { "PCS" }} = ${factorText.ifBlank { "?" }} ${secondaryUnit.ifBlank { "PCS" }}." +
                            " Selling rate per ${secondaryUnit.ifBlank { "PCS" }} = ${Format.rupee(secRate)}"
                    else "Both units are the same — no unit prompt when billing.",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (unitsDiffer && (factorText.toDoubleOrNull() ?: 0.0) <= 1.0)
                        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                )

                // Category: pick an existing one from the dropdown, or type/tap + for a new one.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ExposedDropdownMenuBox(
                        expanded = catMenu,
                        onExpandedChange = { catMenu = !catMenu },
                        modifier = Modifier.weight(1f)
                    ) {
                        OutlinedTextField(
                            value = category,
                            onValueChange = { category = it },
                            label = { Text("Category") },
                            singleLine = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = catMenu) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        if (categories.isNotEmpty()) {
                            ExposedDropdownMenu(expanded = catMenu, onDismissRequest = { catMenu = false }) {
                                categories.forEach { c ->
                                    DropdownMenuItem(text = { Text(c) }, onClick = { category = c; catMenu = false })
                                }
                            }
                        }
                    }
                    IconButton(onClick = { category = ""; catMenu = false }) {
                        Icon(Icons.Filled.Add, contentDescription = "New category")
                    }
                }

                OutlinedTextField(
                    value = openingStock, onValueChange = { openingStock = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Opening stock") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth()
                )

                // --- Batches (batch no + expiry + qty) — shown when batch tracking is on ---
                if (showBatches) {
                    Divider(Modifier.padding(top = 4.dp))
                    Text("Batches", style = MaterialTheme.typography.titleSmall)
                    batches.forEachIndexed { i, b ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { editBatchIndex = i }) {
                                Text(b.batchNo.ifBlank { "(no batch no)" }, fontWeight = FontWeight.SemiBold)
                                Text(
                                    (if (b.expiryMillis > 0) "Exp ${Format.date(b.expiryMillis)}" else "No expiry") + "   •   Qty ${Format.qty(b.quantity)}  •  tap to edit",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                                )
                            }
                            IconButton(onClick = { onRemoveBatch(i) }) { Icon(Icons.Filled.Delete, "Remove batch", tint = MaterialTheme.colorScheme.error) }
                        }
                    }
                    OutlinedButton(onClick = { showBatchInput = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Add, null); Text(" Add batch")
                    }
                }

                // Medical: chemical content / composition (searchable at sale).
                if (isMedical) {
                    OutlinedTextField(
                        value = chemical, onValueChange = { chemical = it },
                        label = { Text("Chemical content / composition") }, modifier = Modifier.fillMaxWidth()
                    )
                }

                // Restaurant: sizes/variants, each with its own selling price.
                if (isRestaurant) {
                    Divider(Modifier.padding(top = 4.dp))
                    Text("Sizes", style = MaterialTheme.typography.titleSmall)
                    Text("Leave empty for a single-price item.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    sizes.forEachIndexed { i, s ->
                        var nm by remember(s.id) { mutableStateOf(s.name) }
                        var pr by remember(s.id) { mutableStateOf(Format.money(s.price)) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = nm, onValueChange = { nm = it; onUpdateSize(i, nm, pr.toDoubleOrNull() ?: 0.0) },
                                label = { Text("Size") }, singleLine = true, modifier = Modifier.weight(1f)
                            )
                            androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
                            OutlinedTextField(
                                value = pr, onValueChange = { pr = it.filter { c -> c.isDigit() || c == '.' }; onUpdateSize(i, nm, pr.toDoubleOrNull() ?: 0.0) },
                                label = { Text("Price") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.width(100.dp)
                            )
                            IconButton(onClick = { onRemoveSize(i) }) { Icon(Icons.Filled.Delete, "Remove size", tint = MaterialTheme.colorScheme.error) }
                        }
                    }
                    OutlinedButton(onClick = { showSizeInput = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Add, null); Text(" Add size")
                    }
                }

                Row {
                    FilterChip(selected = !taxable, onClick = { taxable = false }, label = { Text("Without tax") })
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
                    FilterChip(selected = taxable, onClick = { taxable = true }, label = { Text("With tax") })
                }
                if (taxable) {
                    OutlinedTextField(
                        value = taxPercent, onValueChange = { taxPercent = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Tax %") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = barcode, onValueChange = { barcode = it },
                    label = { Text("Barcode (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { barcode = System.currentTimeMillis().toString() }, modifier = Modifier.weight(1f)) { Text("Auto") }
                    OutlinedButton(
                        onClick = { scanLauncher.launch(ScanOptions().setPrompt("Scan barcode").setBeepEnabled(true).setOrientationLocked(false)) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Scan") }
                    OutlinedButton(onClick = { barcodeCamera() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.PhotoCamera, null, modifier = Modifier.size(18.dp)); Text(" Photo")
                    }
                    OutlinedButton(onClick = {
                        barcodeGallery.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.PhotoLibrary, null, modifier = Modifier.size(18.dp))
                    }
                }
                OutlinedTextField(
                    value = hsn, onValueChange = { hsn = it },
                    label = { Text("HSN / SAC (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )

                Divider(Modifier.padding(top = 4.dp))

                // Store location (text) + optional location photo.
                OutlinedTextField(
                    value = storeLocation, onValueChange = { storeLocation = it },
                    label = { Text("Location in store (e.g. Rack A-3)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )

                // Attachment add buttons.
                Text("Photos & catalogue", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = onAddPhotoGallery, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.PhotoLibrary, null, modifier = Modifier.size(18.dp)); Text(" Photos", maxLines = 1)
                    }
                    OutlinedButton(onClick = onAddPhotoCamera, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.PhotoCamera, null, modifier = Modifier.size(18.dp)); Text(" Camera", maxLines = 1)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = onAddLocationPhoto, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Place, null, modifier = Modifier.size(18.dp)); Text(" Loc photo", maxLines = 1)
                    }
                    OutlinedButton(onClick = onAddCatalogue, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.PictureAsPdf, null, modifier = Modifier.size(18.dp)); Text(" PDF", maxLines = 1)
                    }
                }

                // Thumbnails / chips of staged attachments. Tap a photo to view full screen
                // (swipe between photos); tap a PDF to open it.
                if (attachments.isNotEmpty()) {
                    val imagePaths = attachments.filter { it.mime.startsWith("image/") }.map { it.path }
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        attachments.forEach { att ->
                            AttachmentThumb(
                                att,
                                onRemove = { onRemoveAttachment(att) },
                                onOpen = {
                                    if (att.mime.startsWith("image/")) {
                                        viewStart = imagePaths.indexOf(att.path).coerceAtLeast(0)
                                        viewImages = imagePaths
                                    } else {
                                        val uri = ItemAttachmentStore.uriFor(context, att)
                                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                            setDataAndType(uri, att.mime)
                                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        runCatching { context.startActivity(intent) }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            val factorInvalid = unitsDiffer && (factorText.toDoubleOrNull() ?: 0.0) <= 1.0
            TextButton(
                // Batches stay optional — an item can always be saved without one.
                enabled = !factorInvalid,
                onClick = {
                    val entered = price.toDoubleOrNull() ?: 0.0
                    val forQty = (priceForQty.toDoubleOrNull() ?: 1.0).takeIf { it > 0.0 } ?: 1.0
                    val p = entered / forQty            // store the per-unit price
                    val t = if (taxable) (taxPercent.toDoubleOrNull() ?: 0.0) else 0.0
                    val os = openingStock.toDoubleOrNull() ?: 0.0
                    // Same units ⇒ factor is meaningless, force it to 1.
                    val sec = if (unitsDiffer) secondaryUnit.trim() else unit.trim()
                    val f = if (unitsDiffer) (factorText.toDoubleOrNull() ?: 1.0) else 1.0
                    val pp = purchasePrice.toDoubleOrNull() ?: 0.0
                    onSave(name, p, t, barcode, hsn, category, os, unit, storeLocation, chemical, sec, f, pp)
                }
            ) {
                Text(if (factorInvalid) "Set conv. factor" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    viewImages?.let { paths ->
        com.billing.pos.ui.common.ImageViewerDialog(paths = paths, startIndex = viewStart, onDismiss = { viewImages = null })
    }

    if (showBatchInput) {
        BatchInputDialog(
            onDismiss = { showBatchInput = false },
            onAdd = { no, exp, q -> onAddBatch(no, exp, q); showBatchInput = false }
        )
    }
    if (editBatchIndex in batches.indices) {
        val b = batches[editBatchIndex]
        BatchInputDialog(
            initialNo = b.batchNo, initialExpiry = b.expiryMillis, initialQty = b.quantity,
            onDismiss = { editBatchIndex = -1 },
            onAdd = { no, exp, q -> onUpdateBatch(editBatchIndex, no, exp, q); editBatchIndex = -1 }
        )
    }
    if (showSizeInput) {
        SizeInputDialog(
            onDismiss = { showSizeInput = false },
            onAdd = { nm, pr -> onAddSize(nm, pr); showSizeInput = false }
        )
    }
}

/** Enter one size/variant: a name and its selling price. */
@Composable
private fun SizeInputDialog(onDismiss: () -> Unit, onAdd: (String, Double) -> Unit) {
    var name by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add size") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Size name *  (e.g. Small)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = price, onValueChange = { price = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Selling price *") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onAdd(name.trim(), price.toDoubleOrNull() ?: 0.0) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Enter one batch: batch number, expiry date and quantity. */
@Composable
private fun BatchInputDialog(
    onDismiss: () -> Unit,
    onAdd: (String, Long, Double) -> Unit,
    initialNo: String = "",
    initialExpiry: Long = 0L,
    initialQty: Double = 0.0
) {
    val context = LocalContext.current
    var batchNo by remember { mutableStateOf(initialNo) }
    var qty by remember { mutableStateOf(if (initialQty > 0) Format.qty(initialQty) else "") }
    var expiry by remember { mutableStateOf(initialExpiry) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialNo.isBlank() && initialExpiry == 0L) "Add batch" else "Edit batch") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = batchNo, onValueChange = { batchNo = it }, label = { Text("Batch no *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = qty, onValueChange = { qty = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Quantity") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(onClick = { pickDate(context, if (expiry > 0) expiry else System.currentTimeMillis()) { expiry = it } }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (expiry > 0) "Expiry: ${Format.date(expiry)}" else "Set expiry date")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = batchNo.isNotBlank(), onClick = { onAdd(batchNo.trim(), expiry, qty.toDoubleOrNull() ?: 0.0) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Full-list report of item batches by expiry, with expired ones flagged. */
@Composable
private fun ExpiryReportDialog(rows: List<ExpiryRow>, onDismiss: () -> Unit) {
    val now = System.currentTimeMillis()
    var daysText by remember { mutableStateOf("") }
    val days = daysText.toIntOrNull()
    val cutoff = if (days != null) now + days * 86_400_000L else Long.MAX_VALUE
    val shown = rows.filter { it.expiryMillis <= cutoff }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Batch expiry") },
        text = {
            Column {
                OutlinedTextField(
                    value = daysText, onValueChange = { daysText = it.filter { c -> c.isDigit() } },
                    label = { Text("Expiring within days (blank = all)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                )
                if (shown.isEmpty()) {
                    Text(
                        if (rows.isEmpty()) "No batches with an expiry date yet." else "No batches expiring in $days days.",
                        color = MaterialTheme.colorScheme.outline
                    )
                } else LazyColumn(Modifier.fillMaxWidth().height(360.dp)) {
                    items(shown) { r ->
                        val expired = r.expiryMillis in 1 until now
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.itemName, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Batch ${r.batchNo.ifBlank { "-" }}  •  Exp ${Format.date(r.expiryMillis)}  •  Qty ${Format.qty(r.quantity)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                                )
                            }
                            if (expired) Text("EXPIRED", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                        }
                        Divider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

private fun pickDate(context: android.content.Context, current: Long, onPicked: (Long) -> Unit) {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = current }
    android.app.DatePickerDialog(
        context,
        { _, y, m, d -> c.set(java.util.Calendar.YEAR, y); c.set(java.util.Calendar.MONTH, m); c.set(java.util.Calendar.DAY_OF_MONTH, d); onPicked(c.timeInMillis) },
        c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH)
    ).show()
}

/** A small square thumbnail (image) or PDF tile for one staged attachment, with a remove badge. */
@Composable
private fun AttachmentThumb(att: ItemAttachment, onRemove: () -> Unit, onOpen: () -> Unit = {}) {
    Box(Modifier.size(72.dp)) {
        val isImage = att.mime.startsWith("image/")
        val bmp = if (isImage) rememberThumbnail(att.path, 200) else null
        Box(
            Modifier.size(72.dp).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { onOpen() },
            contentAlignment = Alignment.Center
        ) {
            if (bmp != null) {
                Image(bmp, contentDescription = att.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.PictureAsPdf, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("PDF", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (att.kind == "LOCATION") {
            Icon(
                Icons.Filled.Place, contentDescription = "Location photo",
                tint = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(2.dp).size(16.dp)
            )
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(2.dp).size(20.dp).clip(RoundedCornerShape(10.dp))
                .background(Color(0xAA000000)).clickable { onRemove() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun PrintCountDialog(item: Item, onDismiss: () -> Unit, onPrint: (Int) -> Unit) {
    var count by remember { mutableStateOf("10") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Print barcodes") },
        text = {
            Column {
                Text("${item.name} — ${item.barcode}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                OutlinedTextField(
                    value = count, onValueChange = { count = it.filter { c -> c.isDigit() } },
                    label = { Text("Number of labels") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        },
        confirmButton = { Button(onClick = { onPrint((count.toIntOrNull() ?: 1).coerceIn(1, 500)) }) { Text("Generate PDF") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
