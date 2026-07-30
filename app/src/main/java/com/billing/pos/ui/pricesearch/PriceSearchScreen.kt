package com.billing.pos.ui.pricesearch

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.DownloadSaver
import com.billing.pos.data.Item
import com.billing.pos.data.ItemAttachment
import com.billing.pos.data.Repository
import com.billing.pos.items.ItemAttachmentStore
import com.billing.pos.pdf.ProductPdf
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.ui.common.rememberThumbnail
import com.billing.pos.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** An item with its computed stock/rate and its attachments, for the search result card. */
data class PriceRow(
    val item: com.billing.pos.data.Item,
    val stock: Double,
    val purchaseRate: Double,
    val attachments: List<ItemAttachment>
)

/** One-shot hand-off of items picked in price search to a new sales entry. */
object PriceSearchLink {
    @Volatile var itemIds: List<Long> = emptyList()
    fun take(): List<Long> { val v = itemIds; itemIds = emptyList(); return v }
}

class PriceSearchViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    /** Attaches a photo (camera or gallery) to an item straight from the search results. */
    fun addPhoto(context: android.content.Context, itemId: Long, uri: android.net.Uri) {
        viewModelScope.launch {
            val att = withContext(Dispatchers.IO) {
                ItemAttachmentStore.copyIn(context, uri, "photo")
            }
            if (att == null) { message.value = "Could not add the photo"; return@launch }
            repo.addItemAttachment(att.copy(itemId = itemId))
            message.value = "Photo added"
        }
    }

    val rows: StateFlow<List<PriceRow>> =
        combine(repo.items, repo.stockByName, repo.purchaseLines, repo.itemAttachments) { items, byName, pLines, atts ->
            val rateByName = pLines.groupBy { it.name.lowercase() }
            val attByItem = atts.groupBy { it.itemId }
            items.map { item ->
                val key = item.name.lowercase()
                val lastRate = rateByName[key].orEmpty().maxByOrNull { it.dateMillis }?.price ?: 0.0
                PriceRow(item, item.openingStock + (byName[key] ?: 0.0), lastRate, attByItem[item.id].orEmpty())
            }.sortedBy { it.item.name.lowercase() }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val message = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PriceSearchScreen(
    onBack: () -> Unit,
    onEditItem: (Long) -> Unit = {},
    onAddToSale: () -> Unit = {},
    vm: PriceSearchViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val rows by vm.rows.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    var query by remember { mutableStateOf("") }
    // Fill the search box by hand, or by reading a photo (draw a box round the text).
    var drawSearch by remember { mutableStateOf(false) }
    var searchOcrUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val searchCamera = com.billing.pos.ocr.rememberImageCamera { uri -> searchOcrUri = uri }
    val searchGallery = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) searchOcrUri = uri }

    if (drawSearch) {
        com.billing.pos.ui.common.HandwriteTextDialog(
            onResult = { if (it.isNotBlank()) query = it; drawSearch = false },
            onDismiss = { drawSearch = false }
        )
    }
    searchOcrUri?.let { u ->
        com.billing.pos.ui.common.RegionOcrDialog(
            uri = u,
            onResult = { if (it.isNotBlank()) query = it; searchOcrUri = null },
            onDismiss = { searchOcrUri = null }
        )
    }

    // "Match photo": rank items by how much they look like a photo you take.
    var visualMatches by remember { mutableStateOf<List<com.billing.pos.vision.ItemImageMatcher.Match>?>(null) }
    var matching by remember { mutableStateOf(false) }
    var matchDone by remember { mutableStateOf(0) }
    var matchTotal by remember { mutableStateOf(0) }
    val visualCamera = com.billing.pos.ocr.rememberImageCamera { uri ->
        scope.launch {
            matching = true; matchDone = 0; matchTotal = 0
            val atts = rows.flatMap { it.attachments }
            val found = com.billing.pos.vision.ItemImageMatcher.search(
                context, uri, atts,
                onProgress = { done, total -> matchDone = done; matchTotal = total }
            )
            matching = false
            visualMatches = found
            if (found.isEmpty()) {
                snackbar.showSnackbar(
                    if (atts.none { it.mime.startsWith("image/") })
                        "No item has a photo yet — add photos to items first"
                    else "No close visual match"
                )
            }
        }
    }

    // ---- Multi-select: tick items to share their photos or send them to a sale ----
    val selected = remember { mutableStateListOf<Long>() }
    fun toggle(id: Long) { if (selected.contains(id)) selected.remove(id) else selected.add(id) }

    // Item currently having a photo added, and the pickers that feed it.
    var addPhotoFor by remember { mutableStateOf<Long?>(null) }
    val photoCamera = com.billing.pos.ocr.rememberImageCamera { uri ->
        addPhotoFor?.let { vm.addPhoto(context, it, uri) }; addPhotoFor = null
    }
    val photoGallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) addPhotoFor?.let { vm.addPhoto(context, it, uri) }
        addPhotoFor = null
    }
    var photoSourceFor by remember { mutableStateOf<Long?>(null) }

    // WhatsApp share loop: after each send the app comes back and offers the next contact.
    var askAnother by remember { mutableStateOf(false) }
    var sentCount by remember { mutableStateOf(0) }
    val shareLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { sentCount += 1; askAnother = true }

    fun selectedImagePaths(): List<String> =
        rows.filter { selected.contains(it.item.id) }
            .flatMap { it.attachments }
            .filter { it.mime.startsWith("image/") && it.path.isNotBlank() }
            .map { it.path }

    fun launchWhatsAppShare() {
        val paths = selectedImagePaths()
        if (paths.isEmpty()) {
            scope.launch { snackbar.showSnackbar("Selected items have no photos to share") }
            return
        }
        val uris = ArrayList<Uri>()
        paths.forEach { p ->
            runCatching {
                uris.add(androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", File(p)))
            }
        }
        if (uris.isEmpty()) return
        val names = rows.filter { selected.contains(it.item.id) }.joinToString("\n") { it.item.name }
        val send = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
            type = "image/*"
            if (uris.size > 1) putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            else putExtra(Intent.EXTRA_STREAM, uris[0])
            putExtra(Intent.EXTRA_TEXT, names)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val direct = Intent(send).setPackage("com.whatsapp")
        val target = if (direct.resolveActivity(context.packageManager) != null) direct
        else Intent.createChooser(send, "Share ${uris.size} photo(s)")
        runCatching { shareLauncher.launch(target) }
            .onFailure { scope.launch { snackbar.showSnackbar("Could not open WhatsApp") } }
    }

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    // Download an attachment (photo / PDF) to the public Downloads folder.
    fun doDownload(att: ItemAttachment) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { DownloadSaver.save(context, File(att.path), att.name, att.mime) }
            snackbar.showSnackbar(if (ok) "Saved to Downloads: ${att.name}" else "Could not save")
        }
    }
    var pendingDownload by remember { mutableStateOf<ItemAttachment?>(null) }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val a = pendingDownload; pendingDownload = null
        if (granted && a != null) doDownload(a) else scope.launch { snackbar.showSnackbar("Storage permission denied") }
    }
    fun requestDownload(att: ItemAttachment) {
        if (DownloadSaver.needsLegacyPermission() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) { pendingDownload = att; storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
        else doDownload(att)
    }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
            if (spoken.isNotBlank()) query = spoken
        }
    }
    fun startSpeech() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say the item name")
        }
        runCatching { speechLauncher.launch(intent) }
            .onFailure { vm.message.value = "Voice search not available on this device" }
    }

    val q = query.trim().lowercase()
    val textResults = if (q.isBlank()) rows else rows.filter {
        it.item.name.lowercase().contains(q) ||
            it.item.category.lowercase().contains(q) ||
            it.item.barcode.lowercase().contains(q)
    }
    // A photo match replaces the list and keeps the model's ranking (best first).
    val results = visualMatches?.let { matches ->
        val byId = rows.associateBy { it.item.id }
        matches.mapNotNull { byId[it.itemId] }
    } ?: textResults
    val scoreById = visualMatches?.associate { it.itemId to it.score }.orEmpty()

    var shareFor by remember { mutableStateOf<PriceRow?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Price Search") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search item / barcode / category") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    Row {
                        if (query.isNotBlank()) {
                            IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Clear") }
                        }
                        IconButton(onClick = { startSpeech() }) {
                            Icon(Icons.Filled.Mic, contentDescription = "Voice search", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            // Handwrite, or read the name off a label with the camera / a gallery photo.
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(onClick = { drawSearch = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Draw, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(" Draw", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(onClick = { searchCamera() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(" Camera", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = {
                        searchGallery.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(" Gallery", style = MaterialTheme.typography.labelMedium)
                }
            }

            // Separate from the OCR buttons above: this one matches the picture itself,
            // rather than reading text off it.
            Button(
                onClick = { visualCamera() },
                enabled = !matching,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) {
                Icon(Icons.Filled.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    when {
                        matching && matchTotal > 0 -> "  Indexing photos $matchDone/$matchTotal…"
                        matching -> "  Matching…"
                        else -> "  Find item by photo (visual match)"
                    }
                )
            }
            if (selected.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${selected.size} selected",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Button(onClick = { launchWhatsAppShare() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(" WhatsApp", style = MaterialTheme.typography.labelMedium)
                    }
                    Button(
                        onClick = {
                            PriceSearchLink.itemIds = selected.toList()
                            selected.clear()
                            onAddToSale()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("To sale", style = MaterialTheme.typography.labelMedium) }
                    TextButton(onClick = { selected.clear() }) { Text("Clear") }
                }
            }

            visualMatches?.let { m ->
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (m.isEmpty()) "No visual match" else "Photo match — ${m.size} item(s), closest first",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { visualMatches = null }) { Text("Clear") }
                }
            }

            if (results.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        if (q.isBlank()) "Type or tap the mic to search" else "No matching item",
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(results, key = { it.item.id }) { row ->
                        PriceCard(
                            row,
                            onShare = { shareFor = row },
                            onEdit = { onEditItem(row.item.id) },
                            matchScore = scoreById[row.item.id],
                            checked = selected.contains(row.item.id),
                            onCheck = { toggle(row.item.id) },
                            onAddPhoto = { photoSourceFor = row.item.id }
                        )
                    }
                }
            }
        }
    }

    // Camera or gallery for a new item photo.
    photoSourceFor?.let { id ->
        AlertDialog(
            onDismissRequest = { photoSourceFor = null },
            title = { Text("Add photo") },
            confirmButton = {
                TextButton(onClick = { addPhotoFor = id; photoSourceFor = null; photoCamera() }) { Text("Camera") }
            },
            dismissButton = {
                TextButton(onClick = {
                    addPhotoFor = id; photoSourceFor = null
                    photoGallery.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                }) { Text("Gallery") }
            }
        )
    }

    // After each WhatsApp send: offer the next contact, until Done.
    if (askAnother) {
        AlertDialog(
            onDismissRequest = { askAnother = false },
            title = { Text("Sent to $sentCount contact(s)") },
            text = {
                Text(
                    "WhatsApp can only take one contact per send, so to reach another " +
                        "contact the same photos are sent again. Send to another contact?"
                )
            },
            confirmButton = {
                TextButton(onClick = { askAnother = false; launchWhatsAppShare() }) { Text("Send to another") }
            },
            dismissButton = {
                TextButton(onClick = { askAnother = false; sentCount = 0; selected.clear() }) { Text("Done") }
            }
        )
    }

    shareFor?.let { row ->
        ShareDialog(
            row = row,
            onDismiss = { shareFor = null },
            onDownload = { requestDownload(it) },
            onShareText = { price, selected -> shareProductText(context, row.item, price, selected); shareFor = null },
            onSharePdf = { price, selected -> shareProductPdf(context, row.item, price, selected); shareFor = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareDialog(
    row: PriceRow,
    onDismiss: () -> Unit,
    onDownload: (ItemAttachment) -> Unit,
    onShareText: (Double, List<ItemAttachment>) -> Unit,
    onSharePdf: (Double, List<ItemAttachment>) -> Unit
) {
    var priceText by remember { mutableStateOf(Format.money(row.item.price)) }
    val selectedIds = remember { mutableStateListOf<Long>().apply { addAll(row.attachments.map { it.id }) } }
    val price = priceText.toDoubleOrNull() ?: row.item.price
    fun selectedAtts() = row.attachments.filter { selectedIds.contains(it.id) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share ${row.item.name}") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { priceText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Selling price to send") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                if (row.attachments.isEmpty()) {
                    Text("No photos or catalogue. Sends name + price as text.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                } else {
                    Text("Include / download attachments",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                    row.attachments.forEach { att ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = selectedIds.contains(att.id),
                                onCheckedChange = { on -> if (on) { if (!selectedIds.contains(att.id)) selectedIds.add(att.id) } else selectedIds.remove(att.id) }
                            )
                            Icon(
                                if (att.mime.startsWith("image/")) Icons.Filled.Image else Icons.Filled.PictureAsPdf,
                                contentDescription = null, modifier = Modifier.size(20.dp)
                            )
                            Text(attLabel(att), Modifier.weight(1f).padding(start = 6.dp), maxLines = 1)
                            IconButton(onClick = { onDownload(att) }) {
                                Icon(Icons.Filled.Download, contentDescription = "Download", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onShareText(price, selectedAtts()) }) { Text("Text + Photo") }
                TextButton(onClick = { onSharePdf(price, selectedAtts()) }) { Text("PDF") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun attLabel(att: ItemAttachment): String = when {
    att.kind == "LOCATION" -> "Location photo"
    att.mime.startsWith("image/") -> att.name.ifBlank { "Photo" }
    else -> att.name.ifBlank { "Catalogue (PDF)" }
}

private fun captionFor(item: Item, price: Double): String = buildString {
    append(item.name)
    append("\nSelling Price: ").append(Format.rupee(price))
    if (item.storeLocation.isNotBlank()) append("\nLocation: ").append(item.storeLocation)
}

/** Tries WhatsApp (personal, then business), else falls back to a generic chooser. */
private fun launchShare(context: Context, base: Intent, title: String) {
    base.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(Intent(base).setPackage("com.whatsapp")) }.isSuccess) return
    if (runCatching { context.startActivity(Intent(base).setPackage("com.whatsapp.w4b")) }.isSuccess) return
    runCatching { context.startActivity(Intent.createChooser(base, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Shares the chosen item photos with the name + (edited) selling price as the caption. */
private fun shareProductText(context: Context, item: Item, price: Double, selected: List<ItemAttachment>) {
    val caption = captionFor(item, price)
    val images = selected.filter { it.mime.startsWith("image/") }
    val base = when {
        images.isEmpty() -> Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, caption) }
        images.size == 1 -> Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, ItemAttachmentStore.uriFor(context, images[0]))
            putExtra(Intent.EXTRA_TEXT, caption)
        }
        else -> Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(images.map { ItemAttachmentStore.uriFor(context, it) }))
            putExtra(Intent.EXTRA_TEXT, caption)
        }
    }
    launchShare(context, base, "Share item")
}

/** Shares a generated product PDF (name, edited price, photo) plus chosen catalogue PDFs. */
private fun shareProductPdf(context: Context, item: Item, price: Double, selected: List<ItemAttachment>) {
    val caption = captionFor(item, price)
    val firstImage = selected.firstOrNull { it.mime.startsWith("image/") }?.path
    val productUri = ProductPdf.generate(context, AppPrefs(context).company, item.copy(price = price), firstImage)
    val catalogues = selected.filter { it.mime == "application/pdf" }.map { ItemAttachmentStore.uriFor(context, it) }
    val base = if (catalogues.isEmpty()) {
        Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, productUri)
            putExtra(Intent.EXTRA_TEXT, caption)
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/pdf"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>().apply { add(productUri); addAll(catalogues) })
            putExtra(Intent.EXTRA_TEXT, caption)
        }
    }
    launchShare(context, base, "Share item")
}

@Composable
private fun PriceCard(
    row: PriceRow,
    onShare: () -> Unit,
    onEdit: () -> Unit = {},
    matchScore: Float? = null,
    checked: Boolean = false,
    onCheck: () -> Unit = {},
    onAddPhoto: () -> Unit = {}
) {
    val context = LocalContext.current
    val images = row.attachments.filter { it.mime.startsWith("image/") }
    val pdfs = row.attachments.filter { it.mime == "application/pdf" }
    // Index of the photo opened full-screen, if any.
    var viewerStart by remember(row.item.id) { mutableStateOf<Int?>(null) }

    viewerStart?.let { start ->
        com.billing.pos.ui.common.ImageViewerDialog(
            paths = images.map { it.path },
            startIndex = start,
            onDismiss = { viewerStart = null }
        )
    }

    fun openPdf(att: ItemAttachment) {
        runCatching {
            val uri = ItemAttachmentStore.uriFor(context, att)
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked, onCheckedChange = { onCheck() })
                Column(Modifier.weight(1f)) {
                    // Tapping the name opens the item for editing.
                    Text(
                        row.item.name + (if (row.item.category.isNotBlank()) "  ·  ${row.item.category}" else ""),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onEdit() }
                    )
                    matchScore?.let { sc ->
                        // Shown so a weak match is obviously weak, not presented as certain.
                        Text(
                            "Photo match ${(sc * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (sc >= 0.75f) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                    }
                    if (row.item.barcode.isNotBlank()) {
                        Text(row.item.barcode, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
                IconButton(onClick = onAddPhoto) {
                    Icon(Icons.Filled.PhotoCamera, contentDescription = "Add photo", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onShare) {
                    Icon(Icons.Filled.Share, contentDescription = "Share", tint = MaterialTheme.colorScheme.primary)
                }
            }

            // Item + location photos.
            if (images.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    images.forEachIndexed { idx, att ->
                        val bmp = rememberThumbnail(att.path, 400)
                        if (bmp != null) {
                            Box(
                                Modifier.size(96.dp).clip(RoundedCornerShape(8.dp))
                                    .clickable { viewerStart = idx }   // tap to enlarge
                            ) {
                                Image(bmp, contentDescription = att.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                if (att.kind == "LOCATION") {
                                    Icon(
                                        Icons.Filled.Place, contentDescription = "Location",
                                        tint = Color.White,
                                        modifier = Modifier.align(Alignment.BottomStart).padding(3.dp).size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Location text.
            if (row.item.storeLocation.isNotBlank()) {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Text("  ${row.item.storeLocation}", style = MaterialTheme.typography.bodyMedium)
                }
            }

            // PDF catalogue(s).
            if (pdfs.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    pdfs.forEach { att ->
                        OutlinedButton(onClick = { openPdf(att) }) {
                            Icon(Icons.Filled.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(" Catalogue", maxLines = 1)
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                PriceCell("Sales price", Format.rupee(row.item.price), Modifier.weight(1f))
                PriceCell("Purchase price", Format.rupee(row.purchaseRate), Modifier.weight(1f))
                PriceCell("Stock", Format.qty(row.stock) + " " + row.item.unit, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PriceCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Text(value, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
    }
}
