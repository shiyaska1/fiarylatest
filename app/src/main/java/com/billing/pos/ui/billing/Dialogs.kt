package com.billing.pos.ui.billing

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.billing.pos.data.ItemAttachment
import com.billing.pos.items.ItemAttachmentStore
import com.billing.pos.ui.common.rememberThumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.billing.pos.ui.common.rememberVoiceInput
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.billing.pos.data.Item
import com.billing.pos.util.Format
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@Composable
fun NewCustomerDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, phone: String, address: String, type: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var custType by remember { mutableStateOf("General") }
    var drawName by remember { mutableStateOf(false) }
    if (drawName) {
        com.billing.pos.ui.common.HandwriteTextDialog(
            onResult = { if (it.isNotBlank()) name = it; drawName = false },
            onDismiss = { drawName = false }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Customer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name *") }, singleLine = true,
                    trailingIcon = { IconButton(onClick = { drawName = true }) { Icon(Icons.Filled.Gesture, "Write name") } },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = phone, onValueChange = { phone = it.filter { c -> c.isDigit() } },
                    label = { Text("Phone") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = address, onValueChange = { address = it },
                    label = { Text("Address") },
                    modifier = Modifier.fillMaxWidth()
                )
                com.billing.pos.ui.common.CustomerTypeField(value = custType, onValue = { custType = it })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, phone, address, custType) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** All fields captured by the sales "New item" dialog (mirrors the item master form). */
data class NewItemForm(
    val name: String,
    val price: Double,
    /** Cost/purchase price per primary unit; 0 = not set. */
    val purchasePrice: Double = 0.0,
    val taxPercent: Double,
    val barcode: String,
    val category: String,
    val hsn: String,
    val openingStock: Double,
    val unit: String,
    val secondaryUnit: String,
    val conversionFactor: Double,
    val storeLocation: String,
    val attachments: List<ItemAttachment> = emptyList()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewItemDialog(
    onDismiss: () -> Unit,
    categories: List<String> = emptyList(),
    /** Pre-fills the item name — e.g. the text typed in the item search that found nothing. */
    initialName: String = "",
    onSave: (NewItemForm) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var price by remember { mutableStateOf("") }
    var purchasePrice by remember { mutableStateOf("") }
    var taxable by remember { mutableStateOf(false) }
    var taxPercent by remember { mutableStateOf("18") }
    var barcode by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var catMenu by remember { mutableStateOf(false) }
    var hsn by remember { mutableStateOf("") }
    var openingStock by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("PCS") }
    var unitMenu by remember { mutableStateOf(false) }
    var secondaryUnit by remember { mutableStateOf("PCS") }
    var secUnitMenu by remember { mutableStateOf(false) }
    var factorText by remember { mutableStateOf("1") }
    var storeLocation by remember { mutableStateOf("") }
    val unitsDiffer = !secondaryUnit.trim().equals(unit.trim(), ignoreCase = true) &&
        secondaryUnit.isNotBlank() && unit.isNotBlank()
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { barcode = it }
    }
    val scanName = com.billing.pos.ocr.rememberNameScanner { if (it.isNotBlank()) name = it }
    // Draw-a-box OCR (same as the item master): camera/gallery → drag a box → read only inside it.
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

    // Attachments (photos / camera / document) — same as the item master's add screen.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val staged = remember { mutableStateListOf<ItemAttachment>() }
    fun addStaged(uris: List<android.net.Uri>, kind: String) {
        if (uris.isEmpty()) return
        scope.launch {
            val added = withContext(Dispatchers.IO) { uris.mapNotNull { ItemAttachmentStore.copyIn(context, it, kind) } }
            staged.addAll(added)
        }
    }
    fun removeStaged(att: ItemAttachment) { staged.remove(att); scope.launch(Dispatchers.IO) { ItemAttachmentStore.delete(att) } }
    val photoPicker = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> addStaged(uris, "PHOTO") }
    val docPicker = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> addStaged(uris, "CATALOGUE") }
    var pendingCapture by remember { mutableStateOf<java.io.File?>(null) }
    val takePhoto = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture()
    ) { ok ->
        val f = pendingCapture; pendingCapture = null
        if (ok == true && f != null && f.exists() && f.length() > 0) staged.add(ItemAttachmentStore.fromFile(f, "Photo_${f.name}", "image/jpeg", "PHOTO")) else f?.delete()
    }
    fun launchCamera() {
        val file = java.io.File(ItemAttachmentStore.dir(context), "cam_${System.nanoTime()}.jpg")
        pendingCapture = file
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        runCatching { takePhoto.launch(uri) }.onFailure { pendingCapture?.delete(); pendingCapture = null }
    }
    val camPerm = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) launchCamera() }
    fun withCamera() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) launchCamera()
        else camPerm.launch(android.Manifest.permission.CAMERA)
    }
    // Discard un-saved copied files if the dialog is cancelled.
    fun discardAndDismiss() {
        val copies = staged.toList(); staged.clear()
        if (copies.isNotEmpty()) scope.launch(Dispatchers.IO) { copies.forEach { ItemAttachmentStore.delete(it) } }
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = { discardAndDismiss() },
        title = { Text("New Item") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Item name *") },
                    // Tall + multiline so full OCR text is visible and easy to trim.
                    singleLine = false, minLines = 3, maxLines = 6,
                    trailingIcon = {
                        Row {
                            val ib = Modifier.size(38.dp); val ic = Modifier.size(20.dp)
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
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = price, onValueChange = { price = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Selling price (incl. tax) *") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = purchasePrice,
                    onValueChange = { purchasePrice = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Purchase price (optional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    FilterChip(
                        selected = !taxable,
                        onClick = { taxable = false },
                        label = { Text("Without tax") }
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
                    FilterChip(
                        selected = taxable,
                        onClick = { taxable = true },
                        label = { Text("With tax") }
                    )
                }
                if (taxable) {
                    OutlinedTextField(
                        value = taxPercent,
                        onValueChange = { taxPercent = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Tax %") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                // Category (optional): pick an existing one, or type / tap + for a new one.
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ExposedDropdownMenuBox(expanded = catMenu, onExpandedChange = { catMenu = !catMenu }, modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = category, onValueChange = { category = it },
                            label = { Text("Category (optional)") }, singleLine = true,
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
                    value = barcode, onValueChange = { barcode = it },
                    label = { Text("Barcode (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { barcode = System.currentTimeMillis().toString() }, modifier = Modifier.weight(1f)) { Text("Auto") }
                    OutlinedButton(
                        onClick = { scanLauncher.launch(ScanOptions().setPrompt("Scan barcode").setBeepEnabled(true).setOrientationLocked(false)) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Scan") }
                }
                OutlinedTextField(
                    value = hsn, onValueChange = { hsn = it }, label = { Text("HSN / SAC (optional)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = openingStock, onValueChange = { openingStock = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Opening stock (optional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth()
                )
                // Primary unit.
                ExposedDropdownMenuBox(expanded = unitMenu, onExpandedChange = { unitMenu = !unitMenu }) {
                    OutlinedTextField(
                        value = unit, onValueChange = { unit = it }, label = { Text("Primary unit") }, singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = unitMenu) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = unitMenu, onDismissRequest = { unitMenu = false }) {
                        com.billing.pos.ui.items.ITEM_UNITS.forEach { u ->
                            DropdownMenuItem(text = { Text(u) }, onClick = { unit = u; unitMenu = false })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                        ExposedDropdownMenuBox(expanded = secUnitMenu, onExpandedChange = { secUnitMenu = !secUnitMenu }) {
                            OutlinedTextField(
                                value = secondaryUnit, onValueChange = { secondaryUnit = it },
                                label = { Text("Secondary unit") }, singleLine = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = secUnitMenu) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(expanded = secUnitMenu, onDismissRequest = { secUnitMenu = false }) {
                                com.billing.pos.ui.items.ITEM_UNITS.forEach { u ->
                                    DropdownMenuItem(text = { Text(u) }, onClick = { secondaryUnit = u; secUnitMenu = false })
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = factorText, onValueChange = { factorText = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Conv. factor") }, singleLine = true, enabled = unitsDiffer,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.width(120.dp)
                    )
                }
                OutlinedTextField(
                    value = storeLocation, onValueChange = { storeLocation = it }, label = { Text("Store location (optional)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                // Attachments: photo / camera / document (PDF).
                Text("Photos & documents", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {
                        photoPicker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.PhotoLibrary, null, modifier = Modifier.size(18.dp)); Text(" Photos", maxLines = 1)
                    }
                    OutlinedButton(onClick = { withCamera() }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.PhotoCamera, null, modifier = Modifier.size(18.dp)); Text(" Camera", maxLines = 1)
                    }
                    OutlinedButton(onClick = {
                        runCatching { docPicker.launch(arrayOf("application/pdf", "image/*")) }
                    }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Description, null, modifier = Modifier.size(18.dp)); Text(" Doc", maxLines = 1)
                    }
                }
                if (staged.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        staged.forEach { att -> NewItemAttachThumb(att, onRemove = { removeStaged(att) }) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val p = price.toDoubleOrNull() ?: 0.0
                val t = if (taxable) (taxPercent.toDoubleOrNull() ?: 0.0) else 0.0
                val os = openingStock.toDoubleOrNull() ?: 0.0
                val sec = if (unitsDiffer) secondaryUnit.trim() else unit.trim()
                val f = if (unitsDiffer) (factorText.toDoubleOrNull() ?: 1.0).coerceAtLeast(1.0) else 1.0
                onSave(NewItemForm(name, p, purchasePrice.toDoubleOrNull() ?: 0.0, t, barcode, category, hsn, os, unit.trim().ifBlank { "PCS" }, sec, f, storeLocation, staged.toList()))
            }) { Text("Save & add") }
        },
        dismissButton = { TextButton(onClick = { discardAndDismiss() }) { Text("Cancel") } }
    )
}

/** A small square thumbnail (image) or document tile for one staged new-item attachment. */
@Composable
private fun NewItemAttachThumb(att: ItemAttachment, onRemove: () -> Unit) {
    Box(Modifier.size(64.dp)) {
        val isImage = att.mime.startsWith("image/")
        val bmp = if (isImage) rememberThumbnail(att.path, 200) else null
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (bmp != null) Image(bmp, contentDescription = att.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.PictureAsPdf, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("DOC", style = MaterialTheme.typography.labelSmall)
            }
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

/**
 * Rapid "add by price" entry. Price is on top; each Add (button or keyboard
 * Done/Enter) appends a line and keeps the dialog + keyboard open, refocusing
 * the price box for the next amount. Close with Done when finished.
 */
@Composable
fun CustomLineDialog(
    onDismiss: () -> Unit,
    onAdd: (description: String, price: Double, taxPercent: Double, saveToMaster: Boolean, sellingPrice: Double) -> Unit
) {
    var price by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var taxable by remember { mutableStateOf(false) }
    var taxPercent by remember { mutableStateOf("18") }
    var saveToMaster by remember { mutableStateOf(false) }
    var sellingPrice by remember { mutableStateOf("") }
    var count by remember { mutableStateOf(0) }
    val priceFocus = remember { FocusRequester() }
    val startDescVoice = rememberVoiceInput { description = it }

    LaunchedEffect(Unit) { priceFocus.requestFocus() }

    fun addNow() {
        val p = price.toDoubleOrNull() ?: 0.0
        if (p <= 0) return
        val t = if (taxable) (taxPercent.toDoubleOrNull() ?: 0.0) else 0.0
        val sp = sellingPrice.toDoubleOrNull() ?: 0.0
        onAdd(description, p, t, saveToMaster, sp)
        count++
        price = ""
        description = ""
        sellingPrice = ""
        priceFocus.requestFocus()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add items by price") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = price, onValueChange = { price = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Price *") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addNow() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(priceFocus)
                )
                OutlinedTextField(
                    value = description, onValueChange = { description = it },
                    label = { Text("Description (optional)") }, singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = startDescVoice) {
                            Icon(Icons.Filled.Mic, contentDescription = "Speak description", tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    FilterChip(selected = !taxable, onClick = { taxable = false }, label = { Text("Without tax") })
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
                    FilterChip(selected = taxable, onClick = { taxable = true }, label = { Text("With tax") })
                }
                if (taxable) {
                    OutlinedTextField(
                        value = taxPercent,
                        onValueChange = { taxPercent = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Tax %") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Optionally save this description as a new item in the item master.
                FilterChip(
                    selected = saveToMaster,
                    onClick = { saveToMaster = !saveToMaster },
                    label = { Text(if (saveToMaster) "✓ Save to item master" else "Save to item master") }
                )
                if (saveToMaster) {
                    OutlinedTextField(
                        value = sellingPrice,
                        onValueChange = { sellingPrice = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Selling price for item (optional)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Uses the line price above if left blank. Needs a description.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Button(
                    onClick = { addNow() },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Add to bill", style = MaterialTheme.typography.titleMedium) }
                Text(
                    "$count item(s) added — keep entering, tap Done to finish",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemPickerDialog(
    items: List<Item>,
    onDismiss: () -> Unit,
    onPick: (Item) -> Unit,
    /** Receives the current search text so the New Item form can be pre-filled with it. */
    onNewItem: (String) -> Unit,
    stockByItem: Map<Long, Double> = emptyMap(),
    photosByItem: Map<Long, List<String>> = emptyMap()
) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("All") }   // defaults to All on every open
    var catMenu by remember { mutableStateOf(false) }
    var viewImages by remember { mutableStateOf<List<String>?>(null) }
    val categories = remember(items) {
        listOf("All") + items.map { it.category }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }
    }
    val filtered = remember(query, items, category) {
        items.filter {
            (category == "All" || it.category.equals(category, ignoreCase = true)) &&
                (query.isBlank() || it.name.contains(query, ignoreCase = true) || it.chemicalContent.contains(query, ignoreCase = true))
        }
    }
    // Load 50 at a time; grow as the user scrolls near the end.
    val pageSize = 50
    var visibleCount by remember { mutableStateOf(pageSize) }
    LaunchedEffect(query, category, items) { visibleCount = pageSize }
    val shown = filtered.take(visibleCount)
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(listState, filtered) {
        androidx.compose.runtime.snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (visibleCount < filtered.size && last >= visibleCount - 8) visibleCount = (visibleCount + pageSize).coerceAtMost(filtered.size) }
    }
    val startVoice = rememberVoiceInput { query = it }
    // Fill the search box by handwriting, or by drawing a box on a camera/gallery photo (OCR).
    var showDraw by remember { mutableStateOf(false) }
    var searchRegionUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val searchCamera = com.billing.pos.ocr.rememberImageCamera { uri -> searchRegionUri = uri }
    val searchGallery = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) searchRegionUri = uri }
    if (showDraw) {
        com.billing.pos.ui.common.HandwriteTextDialog(
            onResult = { if (it.isNotBlank()) query = it; showDraw = false },
            onDismiss = { showDraw = false }
        )
    }
    searchRegionUri?.let { u ->
        com.billing.pos.ui.common.RegionOcrDialog(
            uri = u,
            onResult = { if (it.isNotBlank()) query = it; searchRegionUri = null },
            onDismiss = { searchRegionUri = null }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select item") },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("Search") }, singleLine = true,
                    trailingIcon = {
                        // Compact so all four fit inside the field: voice, handwrite, photo, gallery.
                        Row {
                            val ib = Modifier.size(38.dp)
                            val ic = Modifier.size(20.dp)
                            IconButton(onClick = startVoice, modifier = ib) {
                                Icon(Icons.Filled.Mic, "Voice search", ic, tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = { showDraw = true }, modifier = ib) {
                                Icon(Icons.Filled.Gesture, "Handwrite search", ic, tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = { searchCamera() }, modifier = ib) {
                                Icon(Icons.Filled.PhotoCamera, "Photo — draw a box to read", ic, tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = {
                                searchGallery.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            }, modifier = ib) {
                                Icon(Icons.Filled.PhotoLibrary, "Gallery — draw a box to read", ic, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                // Category filter (defaults to All).
                if (categories.size > 1) {
                    ExposedDropdownMenuBox(expanded = catMenu, onExpandedChange = { catMenu = !catMenu }, modifier = Modifier.padding(top = 6.dp)) {
                        OutlinedTextField(
                            readOnly = true, value = category, onValueChange = {},
                            label = { Text("Category") }, singleLine = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = catMenu) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = catMenu, onDismissRequest = { catMenu = false }) {
                            categories.forEach { c ->
                                DropdownMenuItem(text = { Text(c) }, onClick = { category = c; catMenu = false })
                            }
                        }
                    }
                }
                if (items.isEmpty()) {
                    Text(
                        "No items yet. Tap \"New item\" to create one.",
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    LazyColumn(state = listState, modifier = Modifier.heightIn(max = 320.dp)) {
                        items(shown, key = { it.id }) { item ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(item) }
                                    .padding(vertical = 10.dp)
                            ) {
                                // Item name — larger, bold — with a photo-viewer icon if it has images.
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Text(
                                        item.name,
                                        modifier = Modifier.weight(1f),
                                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                                    )
                                    val imgs = photosByItem[item.id].orEmpty()
                                    if (imgs.isNotEmpty()) IconButton(onClick = { viewImages = imgs }) {
                                        Icon(Icons.Filled.Image, contentDescription = "View photos", tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                // Price / stock / location — each a distinct colour.
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(
                                        "Sale ${Format.rupee(item.price)}",
                                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                        color = androidx.compose.material3.MaterialTheme.colorScheme.primary
                                    )
                                    if (item.purchasePrice > 0.0) {
                                        Text(
                                            "Buy ${Format.rupee(item.purchasePrice)}",
                                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                            color = androidx.compose.material3.MaterialTheme.colorScheme.error
                                        )
                                    }
                                    Text(
                                        "Stock ${Format.qty(stockByItem[item.id] ?: item.openingStock)} ${item.unit}",
                                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                        color = androidx.compose.material3.MaterialTheme.colorScheme.tertiary
                                    )
                                    if (item.storeLocation.isNotBlank()) {
                                        Text(
                                            item.storeLocation,
                                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                            color = androidx.compose.material3.MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                }
                                if (item.chemicalContent.isNotBlank()) {
                                    Text(
                                        item.chemicalContent,
                                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                                        color = androidx.compose.material3.MaterialTheme.colorScheme.error
                                    )
                                }
                                Divider(Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                    // Nothing matched the search → offer to create it with the typed name.
                    if (filtered.isEmpty() && query.isNotBlank()) {
                        Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                            Text(
                                "No item matches \"$query\"",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                            TextButton(onClick = { onNewItem(query.trim()) }, modifier = Modifier.padding(top = 2.dp)) {
                                Icon(Icons.Filled.Add, null)
                                Text("  Create new item \"${query.trim()}\"")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onNewItem(query.trim()) }) { Text("New item") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
    // Full-screen image viewer for the tapped item's photos (share doesn't close it).
    viewImages?.let { com.billing.pos.ui.common.ImageViewerDialog(paths = it, onDismiss = { viewImages = null }) }
}
