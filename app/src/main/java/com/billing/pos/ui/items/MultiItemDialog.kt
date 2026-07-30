package com.billing.pos.ui.items

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** One row being entered in the multi-item form. */
class MultiItemRow {
    var name by mutableStateOf("")
    var price by mutableStateOf("")
    var category by mutableStateOf("")
    /** Photos picked for this item, copied in only when the whole form is saved. */
    val photos = mutableStateListOf<android.net.Uri>()

    val isFilled: Boolean get() = name.isNotBlank()
}

/**
 * Adds several items in one pass.
 *
 * A row per item — name (typed, handwritten or read off a photo), selling price, category,
 * and any number of photos. Nothing is written until Save, which creates every filled row.
 */
@Composable
fun MultiItemDialog(
    categories: List<String>,
    onSave: (List<MultiItemRow>) -> Unit,
    onDismiss: () -> Unit
) {
    val rows = remember { mutableStateListOf(MultiItemRow(), MultiItemRow(), MultiItemRow()) }

    // Which row is currently using the shared draw / OCR / photo pickers.
    var drawFor by remember { mutableStateOf<MultiItemRow?>(null) }
    var ocrFor by remember { mutableStateOf<MultiItemRow?>(null) }
    var ocrUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var photoFor by remember { mutableStateOf<MultiItemRow?>(null) }

    // Categories offered in the dropdowns: the existing ones plus anything added here.
    val addedCategories = remember { mutableStateListOf<String>() }
    val allCategories = (categories + addedCategories).distinct().filter { it.isNotBlank() }
    var newCatFor by remember { mutableStateOf<MultiItemRow?>(null) }

    val ocrCamera = com.billing.pos.ocr.rememberImageCamera { uri -> ocrUri = uri }
    val ocrGallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) ocrUri = uri
    }
    val photoCamera = com.billing.pos.ocr.rememberImageCamera { uri ->
        photoFor?.photos?.add(uri); photoFor = null
    }
    val photoGallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) photoFor?.photos?.add(uri)
        photoFor = null
    }

    drawFor?.let { row ->
        com.billing.pos.ui.common.HandwriteTextDialog(
            onResult = { t -> if (t.isNotBlank()) row.name = (row.name.trimEnd() + " " + t).trim(); drawFor = null },
            onDismiss = { drawFor = null }
        )
    }
    ocrUri?.let { u ->
        val row = ocrFor
        com.billing.pos.ui.common.RegionOcrDialog(
            uri = u,
            onResult = { t -> if (t.isNotBlank() && row != null) row.name = t; ocrUri = null; ocrFor = null },
            onDismiss = { ocrUri = null; ocrFor = null }
        )
    }

    newCatFor?.let { row ->
        var typed by remember(row) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newCatFor = null },
            title = { Text("New category") },
            text = {
                OutlinedTextField(
                    value = typed, onValueChange = { typed = it },
                    label = { Text("Category name") }, singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = typed.trim()
                    if (name.isNotBlank()) { addedCategories.add(name); row.category = name }
                    newCatFor = null
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { newCatFor = null }) { Text("Cancel") } }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Column(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .safeDrawingPadding()
                .imePadding()
        ) {
            // Actions on top, clear of the navigation bar.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = { onSave(rows.filter { it.isFilled }.toList()) },
                    enabled = rows.any { it.isFilled },
                    modifier = Modifier.weight(1.6f)
                ) { Text("Save ${rows.count { it.isFilled }} item(s)") }
            }
            Divider()

            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
                itemsIndexed(rows) { index, row ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Column(Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Item ${index + 1}",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { if (rows.size > 1) rows.removeAt(index) },
                                    enabled = rows.size > 1
                                ) {
                                    Icon(Icons.Filled.Delete, "Remove row", tint = MaterialTheme.colorScheme.error)
                                }
                            }

                            OutlinedTextField(
                                value = row.name,
                                onValueChange = { row.name = it },
                                label = { Text("Item name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            // Name by hand, or read off a label.
                            Row(
                                Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedButton(onClick = { drawFor = row }, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Filled.Draw, null, Modifier.size(16.dp))
                                    Text(" Draw", style = MaterialTheme.typography.labelSmall)
                                }
                                OutlinedButton(
                                    onClick = { ocrFor = row; ocrCamera() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Filled.PhotoCamera, null, Modifier.size(16.dp))
                                    Text(" Camera", style = MaterialTheme.typography.labelSmall)
                                }
                                OutlinedButton(
                                    onClick = {
                                        ocrFor = row
                                        ocrGallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Filled.PhotoLibrary, null, Modifier.size(16.dp))
                                    Text(" Gallery", style = MaterialTheme.typography.labelSmall)
                                }
                            }

                            Row(
                                Modifier.fillMaxWidth().padding(top = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = row.price,
                                    onValueChange = { row.price = it.filter { c -> c.isDigit() || c == '.' } },
                                    label = { Text("Selling price") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.weight(1f)
                                )
                                // Category is picked from the ones already in use; "+" adds a new
                                // one, which then shows up for every other row straight away.
                                Box(Modifier.weight(1f)) {
                                    var catMenu by remember { mutableStateOf(false) }
                                    OutlinedTextField(
                                        readOnly = true,
                                        value = row.category,
                                        onValueChange = {},
                                        label = { Text("Category") },
                                        singleLine = true,
                                        trailingIcon = {
                                            Row {
                                                IconButton(onClick = { newCatFor = row }) {
                                                    Icon(Icons.Filled.Add, "New category", Modifier.size(18.dp))
                                                }
                                                IconButton(onClick = { catMenu = true }) {
                                                    Icon(Icons.Filled.ArrowDropDown, "Pick category")
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    DropdownMenu(expanded = catMenu, onDismissRequest = { catMenu = false }) {
                                        if (allCategories.isEmpty()) DropdownMenuItem(
                                            text = { Text("No categories yet — use +") },
                                            onClick = { catMenu = false }
                                        )
                                        allCategories.forEach { c ->
                                            DropdownMenuItem(
                                                text = { Text(c) },
                                                onClick = { row.category = c; catMenu = false }
                                            )
                                        }
                                    }
                                }
                            }

                            // Item photos, copied in on save.
                            Row(
                                Modifier.fillMaxWidth().padding(top = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = { photoFor = row; photoCamera() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Filled.PhotoCamera, null, Modifier.size(16.dp))
                                    Text(" Photo", style = MaterialTheme.typography.labelSmall)
                                }
                                OutlinedButton(
                                    onClick = {
                                        photoFor = row
                                        photoGallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Filled.PhotoLibrary, null, Modifier.size(16.dp))
                                    Text(" Image", style = MaterialTheme.typography.labelSmall)
                                }
                                if (row.photos.isNotEmpty()) {
                                    Text(
                                        "${row.photos.size} photo(s)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    IconButton(onClick = { row.photos.clear() }) {
                                        Icon(Icons.Filled.Delete, "Clear photos", Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    OutlinedButton(
                        onClick = { rows.add(MultiItemRow()) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
                    ) {
                        Icon(Icons.Filled.Add, null)
                        Text("  Add another row")
                    }
                }
            }
        }
    }
}

/**
 * Adds several items from photographs: shoot each item in turn, then name them by reading
 * the text off each picture.
 *
 * The camera reopens itself after every shot so a shelf can be worked through without
 * returning to the app between items; backing out of the camera ends the run and leaves
 * one row per photo taken. Tapping a picture opens it full screen, where drawing a box
 * over the name reads it into that row.
 */
@Composable
fun PhotoItemsDialog(
    categories: List<String>,
    onSave: (List<MultiItemRow>) -> Unit,
    onDismiss: () -> Unit
) {
    val rows = remember { mutableStateListOf<MultiItemRow>() }
    var shots by remember { mutableStateOf(0) }
    var keepShooting by remember { mutableStateOf(true) }

    val addedCategories = remember { mutableStateListOf<String>() }
    val allCategories = (categories + addedCategories).distinct().filter { it.isNotBlank() }
    var newCatFor by remember { mutableStateOf<MultiItemRow?>(null) }
    var ocrFor by remember { mutableStateOf<MultiItemRow?>(null) }
    var drawFor by remember { mutableStateOf<MultiItemRow?>(null) }

    val camera = com.billing.pos.ocr.rememberImageCamera { uri ->
        rows.add(MultiItemRow().also { it.photos.add(uri) })
        shots++
    }
    val gallery = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(30)
    ) { uris ->
        uris.forEach { u -> rows.add(MultiItemRow().also { it.photos.add(u) }) }
    }

    // Fires once on open and again after every shot, so the camera keeps coming back.
    // Cancelling the camera reports nothing, so the run simply stops there.
    LaunchedEffect(shots) { if (keepShooting) camera() }

    drawFor?.let { row ->
        com.billing.pos.ui.common.HandwriteTextDialog(
            onResult = { t -> if (t.isNotBlank()) row.name = (row.name.trimEnd() + " " + t).trim(); drawFor = null },
            onDismiss = { drawFor = null }
        )
    }

    ocrFor?.let { row ->
        val uri = row.photos.firstOrNull()
        if (uri == null) ocrFor = null
        else com.billing.pos.ui.common.RegionOcrDialog(
            uri = uri,
            onResult = { t -> if (t.isNotBlank()) row.name = t; ocrFor = null },
            onDismiss = { ocrFor = null }
        )
    }

    newCatFor?.let { row ->
        var typed by remember(row) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newCatFor = null },
            title = { Text("New category") },
            text = {
                OutlinedTextField(
                    value = typed, onValueChange = { typed = it },
                    label = { Text("Category name") }, singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = typed.trim()
                    if (name.isNotBlank()) { addedCategories.add(name); row.category = name }
                    newCatFor = null
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { newCatFor = null }) { Text("Cancel") } }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Column(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .safeDrawingPadding()
                .imePadding()
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { keepShooting = false; onDismiss() },
                    modifier = Modifier.weight(1f)
                ) { Text("Cancel") }
                Button(
                    onClick = { keepShooting = false; onSave(rows.filter { it.isFilled }.toList()) },
                    enabled = rows.any { it.isFilled },
                    modifier = Modifier.weight(1.6f)
                ) { Text("Save ${rows.count { it.isFilled }} item(s)") }
            }
            Divider()

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { keepShooting = true; camera() },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.PhotoCamera, null, Modifier.size(18.dp))
                    Text(" More photos", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = {
                        keepShooting = false
                        gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.PhotoLibrary, null, Modifier.size(18.dp))
                    Text(" From gallery", style = MaterialTheme.typography.labelMedium)
                }
            }

            if (rows.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Take a photo of each item. Back out of the camera when you are done.",
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(24.dp)
                    )
                }
                return@Column
            }

            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
                itemsIndexed(rows) { index, row ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Column(Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Photo ${index + 1}",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { rows.removeAt(index) }) {
                                    Icon(Icons.Filled.Delete, "Remove row", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Tap the picture to open it full screen and box the name.
                                UriThumbnail(
                                    uri = row.photos.firstOrNull(),
                                    modifier = Modifier.size(84.dp).clickable { ocrFor = row }
                                )
                                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                    OutlinedTextField(
                                        value = row.name,
                                        onValueChange = { row.name = it },
                                        label = { Text("Item name") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Row(
                                        Modifier.fillMaxWidth().padding(top = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = { ocrFor = row },
                                            modifier = Modifier.weight(1.6f)
                                        ) {
                                            Icon(Icons.Filled.PhotoCamera, null, Modifier.size(16.dp))
                                            Text(" Read photo", style = MaterialTheme.typography.labelSmall)
                                        }
                                        // Handwriting, for a name the photo cannot be read from.
                                        OutlinedButton(
                                            onClick = { keepShooting = false; drawFor = row },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Filled.Draw, null, Modifier.size(16.dp))
                                            Text(" Draw", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }

                            Row(
                                Modifier.fillMaxWidth().padding(top = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = row.price,
                                    onValueChange = { row.price = it.filter { c -> c.isDigit() || c == '.' } },
                                    label = { Text("Selling price") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.weight(1f)
                                )
                                Box(Modifier.weight(1f)) {
                                    var catMenu by remember { mutableStateOf(false) }
                                    OutlinedTextField(
                                        readOnly = true,
                                        value = row.category,
                                        onValueChange = {},
                                        label = { Text("Category") },
                                        singleLine = true,
                                        trailingIcon = {
                                            Row {
                                                IconButton(onClick = { newCatFor = row }) {
                                                    Icon(Icons.Filled.Add, "New category", Modifier.size(18.dp))
                                                }
                                                IconButton(onClick = { catMenu = true }) {
                                                    Icon(Icons.Filled.ArrowDropDown, "Pick category")
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    DropdownMenu(expanded = catMenu, onDismissRequest = { catMenu = false }) {
                                        if (allCategories.isEmpty()) DropdownMenuItem(
                                            text = { Text("No categories yet — use +") },
                                            onClick = { catMenu = false }
                                        )
                                        allCategories.forEach { c ->
                                            DropdownMenuItem(
                                                text = { Text(c) },
                                                onClick = { row.category = c; catMenu = false }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Small preview of a picked image, decoded once and downsampled to keep the list light. */
@Composable
private fun UriThumbnail(uri: android.net.Uri?, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bmp = remember(uri) {
        uri?.let {
            runCatching {
                context.contentResolver.openInputStream(it)?.use { input ->
                    val bytes = input.readBytes()
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 300) sample *= 2
                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    if (bmp != null) {
        androidx.compose.foundation.Image(
            bitmap = bmp,
            contentDescription = "Item photo",
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = modifier
        )
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.PhotoCamera, null, Modifier.size(24.dp))
        }
    }
}
