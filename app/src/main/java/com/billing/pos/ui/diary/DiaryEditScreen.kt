package com.billing.pos.ui.diary

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.BlockType
import com.billing.pos.data.DiaryExport
import com.billing.pos.data.DownloadSaver
import com.billing.pos.diary.AttachmentStore
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.ui.common.rememberThumbnail
import com.billing.pos.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryEditScreen(
    entryId: Long,
    onBackRequested: () -> Unit,
    vm: DiaryEditViewModel = viewModel()
) {
    // Leaving the entry stops any voice note; moving to another screen does not.
    val onBack: () -> Unit = { DiaryAudio.controller.stop(); onBackRequested() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsStateSafe()

    LaunchedEffect(Unit) { vm.load(entryId) }

    // Files shared in from another app (e.g. a WhatsApp photo/voice/video/document).
    // Keyed on the share generation, not Unit, so sharing again while this entry is still
    // open adds the new file to it instead of needing a fresh entry.
    val shareGeneration = com.billing.pos.auth.PendingSharedMedia.generation
    LaunchedEffect(shareGeneration) {
        val pending = com.billing.pos.auth.PendingSharedMedia
        if (pending.hasItems || pending.hasText) {
            val source = pending.sourceLabel
            val files = pending.consume()
            val text = pending.consumeText()
            if (files.isNotEmpty()) vm.addFileUris(context, files)
            if (text.isNotBlank()) {
                // Drop a forwarded message into the first empty text block, else a new one.
                val blank = vm.blocks.indexOfFirst { it.type == BlockType.TEXT && it.text.isBlank() }
                if (blank >= 0) vm.blocks[blank].text = text
                else vm.blocks.add(BlockUi(0, BlockType.TEXT, text))
            }
            if (vm.title.isBlank()) {
                val prefix = if (source.isNotBlank()) "$source share" else "Shared"
                vm.title = "$prefix — ${Format.dateTime(System.currentTimeMillis())}"
            }
        }
    }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    var confirmDelete by remember { mutableStateOf(false) }
    var searchMode by remember { mutableStateOf(false) }
    var showFormat by remember { mutableStateOf(false) }
    var showLinkDialog by remember { mutableStateOf(false) }
    // One player for the whole entry, so "play all" can run through every voice note.
    // Shared with the rest of the app, so moving to another screen does not cut the
    // playback off. Backing out of the entry does.
    val audio = DiaryAudio.controller
    androidx.activity.compose.BackHandler { onBack() }
    var askPlayFor by remember { mutableStateOf<String?>(null) }
    val titleStyle = diaryStyle(vm.titleSize, vm.titleColor, vm.titleBold, vm.titleItalic)
    val bodyStyle = diaryStyle(vm.bodySize, vm.bodyColor, vm.bodyBold, vm.bodyItalic)

    // When ticked, photos added below are also read with OCR and the text lands in the note.
    var readTextFromImage by remember { mutableStateOf(false) }

    // Attachment photos opened inside the app.
    var viewerPaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var viewerIndex by remember { mutableStateOf(0) }
    // Diary type master helpers.
    var showTypePicker by remember { mutableStateOf(false) }
    var renameType by remember { mutableStateOf<com.billing.pos.data.DiaryType?>(null) }

    // Voice note waiting to be converted to text, once its language is chosen.
    var transcribeBlock by remember { mutableStateOf<BlockUi?>(null) }

    // Language for the photo OCR, asked before the picture is taken (only when OCR is on).
    var imageOcrLang by remember { mutableStateOf<String?>(null) }
    var askImageLangFor by remember { mutableStateOf<String?>(null) }

    // Image: photograph with the camera → auto-shrink → image block (+ OCR if ticked).
    val pickImage = com.billing.pos.ocr.rememberImageCamera { uri ->
        vm.addImageUri(context, uri, readTextFromImage, imageOcrLang)
    }

    // Pick an existing image from the gallery → auto-shrink → image block (+ OCR if ticked).
    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) vm.addImageUri(context, uri, readTextFromImage, imageOcrLang) }

    val docPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> vm.addFileUris(context, uris) }

    // Video capture — file the camera writes into, then adds a block.
    var pendingCapture by remember { mutableStateOf<File?>(null) }
    val takeVideo = rememberLauncherForActivityResult(
        ActivityResultContracts.CaptureVideo()
    ) { ok ->
        val f = pendingCapture; pendingCapture = null
        if (ok && f != null) vm.addCapturedFile(f, "Video_${f.name}", "video/mp4") else f?.delete()
    }

    fun captureUri(ext: String): android.net.Uri {
        val file = File(AttachmentStore.dir(context), "cam_${System.nanoTime()}.$ext")
        pendingCapture = file
        return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }
    fun launchVideo() {
        runCatching { takeVideo.launch(captureUri("mp4")) }
            .onFailure { pendingCapture?.delete(); pendingCapture = null; vm.message.value = "No camera app found" }
    }

    // The app now declares CAMERA (barcode scanner), so capture needs it granted.
    var pendingCameraAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingCameraAction; pendingCameraAction = null
        if (granted) action?.invoke() else vm.message.value = "Camera permission denied"
    }
    fun withCamera(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) action()
        else { pendingCameraAction = action; cameraPermission.launch(Manifest.permission.CAMERA) }
    }

    // Location attach
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) fetchLocation(context, vm) else vm.message.value = "Location permission denied"
    }
    fun requestLocation() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) fetchLocation(context, vm)
        else locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    // Download this entry as a .zip into the Downloads folder
    fun doDownloadEntry() {
        scope.launch {
            val zip = withContext(Dispatchers.IO) { DiaryExport.exportEntry(context, vm.loadedId) }
            if (zip == null) { vm.message.value = "Nothing to export"; return@launch }
            val ok = withContext(Dispatchers.IO) { DownloadSaver.save(context, zip, zip.name, "application/zip") }
            vm.message.value = if (ok) "Saved to Downloads: ${zip.name}" else "Could not save"
        }
    }
    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) doDownloadEntry() else vm.message.value = "Storage permission denied" }
    fun downloadEntry() {
        if (DownloadSaver.needsLegacyPermission() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else doDownloadEntry()
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) vm.startRecording(context) }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { vm.save(context) { onBack() } }

    // Speech-to-text: which field the recognised text goes into.
    var speechTarget by remember { mutableStateOf("") }
    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
            if (spoken.isNotBlank()) {
                when (speechTarget) {
                    "title" -> vm.title = appendText(vm.title, spoken)
                    "body" -> vm.appendSpokenToBody(spoken)
                }
            }
        }
    }

    // Which field the handwriting pad should fill: "title", or "body:<index>".
    var drawTarget by remember { mutableStateOf<String?>(null) }

    fun startSpeech(target: String) {
        speechTarget = target
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now")
        }
        runCatching { speechLauncher.launch(intent) }
            .onFailure { vm.message.value = "Speech input not available on this device" }
    }

    fun doSave() {
        if (vm.reminderEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.save(context) { onBack() }
        }
    }

    fun toggleRecord() {
        if (vm.recording) vm.stopRecording()
        else {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) vm.startRecording(context)
            else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (vm.loadedId == 0L) "New Diary Entry" else "Diary Entry") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    // Share the entry: the attached photos go as files; the customer's
                    // name, the title and the notes travel as the message text. With no
                    // photos the entry goes as a PDF with the same message.
                    IconButton(onClick = {
                        val photoFiles = vm.blocks
                            .filter { it.type == com.billing.pos.data.BlockType.IMAGE && it.path.isNotBlank() }
                            .map { java.io.File(it.path) }.filter { it.exists() }
                        val messageText = buildString {
                            if (vm.customerName.isNotBlank()) appendLine(vm.customerName)
                            if (vm.title.isNotBlank()) appendLine(vm.title)
                            val notes = vm.notesText().trim()
                            if (notes.isNotBlank()) append(notes)
                        }.trim()
                        runCatching {
                            val send: Intent
                            if (photoFiles.isNotEmpty()) {
                                val uris = ArrayList<android.net.Uri>()
                                photoFiles.forEach { f ->
                                    uris.add(androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", f))
                                }
                                send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                    setType("image/*")
                                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                                    if (messageText.isNotBlank()) putExtra(Intent.EXTRA_TEXT, messageText)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                            } else {
                                val type = vm.types.value.firstOrNull { it.id == vm.typeId }?.name.orEmpty()
                                val uri = com.billing.pos.pdf.DiaryPdf.make(
                                    context, type, vm.title, vm.notesText(), System.currentTimeMillis()
                                ) ?: error("no pdf")
                                send = Intent(Intent.ACTION_SEND).apply {
                                    setType("application/pdf")
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    if (messageText.isNotBlank()) putExtra(Intent.EXTRA_TEXT, messageText)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                            }
                            val wa = Intent(send).setPackage("com.whatsapp")
                            val target = if (wa.resolveActivity(context.packageManager) != null) wa
                            else Intent.createChooser(send, "Share entry")
                            context.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.onFailure { vm.message.value = "Could not share" }
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share entry")
                    }
                    IconButton(onClick = {
                        val type = vm.types.value.firstOrNull { it.id == vm.typeId }?.name.orEmpty()
                        scope.launch {
                            val res = withContext(Dispatchers.IO) {
                                runCatching {
                                    com.billing.pos.print.ThermalPrinter.printDiary(
                                        context,
                                        com.billing.pos.data.AppPrefs(context).company,
                                        type, vm.title, vm.notesText(), System.currentTimeMillis()
                                    )
                                }
                            }
                            vm.message.value =
                                if (res.isSuccess) "Sent to printer" else (res.exceptionOrNull()?.message ?: "Print failed")
                        }
                    }) {
                        Icon(Icons.Filled.Print, contentDescription = "Print")
                    }
                    if (vm.loadedId != 0L) {
                        IconButton(onClick = { downloadEntry() }) {
                            Icon(Icons.Filled.Download, contentDescription = "Download entry (zip)")
                        }
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // ---- Diary type (master + add + fix spelling) ----
            val diaryTypes by vm.types.collectAsStateSafe()
            val currentType = diaryTypes.firstOrNull { it.id == vm.typeId }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { showTypePicker = true }, modifier = Modifier.weight(1f)) {
                    Text(currentType?.name ?: "Diary type — none", maxLines = 1)
                }
                if (currentType != null) {
                    IconButton(onClick = { renameType = currentType }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Correct the spelling")
                    }
                }
            }

            // ---- Optional customer this note is about ----
            val diaryCustomers by vm.customers.collectAsStateSafe()
            com.billing.pos.ui.common.CustomerPickField(
                customers = diaryCustomers,
                selectedName = vm.customerName,
                onPick = { c -> vm.customerId = c.id; vm.customerName = c.name },
                label = "Customer (optional)",
                allowFreeText = true,
                onTyped = { t -> vm.customerName = t; vm.customerId = 0L },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )

            OutlinedTextField(
                value = vm.title, onValueChange = { vm.title = it },
                label = { Text("Title") }, singleLine = true,
                textStyle = titleStyle,
                trailingIcon = {
                    Row {
                        IconButton(onClick = {
                            com.billing.pos.util.ShareText.share(context, vm.title, vm.title)
                        }) {
                            Icon(Icons.Filled.Share, contentDescription = "Share title text")
                        }
                        IconButton(onClick = { drawTarget = "title" }) {
                            Icon(Icons.Filled.Draw, contentDescription = "Write title by hand")
                        }
                        IconButton(onClick = { startSpeech("title") }) {
                            Icon(Icons.Filled.Mic, contentDescription = "Speak title")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            TextButton(onClick = { showFormat = !showFormat }, modifier = Modifier.padding(top = 2.dp)) {
                Text(if (showFormat) "Hide text format" else "Aa  Text format")
            }
            if (showFormat) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        FormatRow(
                            "Title", vm.titleSize, { vm.titleSize = it }, vm.titleColor, { vm.titleColor = it },
                            vm.titleBold, { vm.titleBold = it }, vm.titleItalic, { vm.titleItalic = it }
                        )
                        Divider(Modifier.padding(vertical = 8.dp))
                        FormatRow(
                            "Body", vm.bodySize, { vm.bodySize = it }, vm.bodyColor, { vm.bodyColor = it },
                            vm.bodyBold, { vm.bodyBold = it }, vm.bodyItalic, { vm.bodyItalic = it }
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Notes", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { searchMode = !searchMode }) {
                    Icon(
                        if (searchMode) Icons.Filled.Edit else Icons.Filled.Search,
                        contentDescription = if (searchMode) "Edit notes" else "Search notes"
                    )
                }
            }
            if (searchMode) {
                RemarksSearchView(text = vm.notesText(), lineStyle = bodyStyle)
            } else {
                // Ordered body blocks: text, image, voice, etc.
                vm.blocks.forEachIndexed { index, block ->
                    BlockEditor(
                        block = block,
                        isFirst = index == 0,
                        isLast = index == vm.blocks.size - 1,
                        bodyStyle = bodyStyle,
                        onMoveUp = { vm.moveUp(index) },
                        onMoveDown = { vm.moveDown(index) },
                        onRemove = { vm.removeBlock(index) },
                        onOpen = {
                            // Images open in our own viewer; anything else hands off to the OS.
                            if (block.type == BlockType.IMAGE) {
                                val imgs = vm.blocks.filter { b -> b.type == BlockType.IMAGE }
                                viewerPaths = imgs.map { b -> b.path }
                                viewerIndex = imgs.indexOfFirst { b -> b.path == block.path }.coerceAtLeast(0)
                            } else openBlock(context, block) { vm.message.value = it }
                        },
                        onSpeak = { startSpeech("body") },
                        onDraw = { drawTarget = "body:$index" },
                        onShareText = { com.billing.pos.util.ShareText.share(context, block.text, vm.title) },
                        onTranscribe = { transcribeBlock = block },
                        audioPlaying = audio.playing && audio.currentPath == block.path,
                        onAudioToggle = {
                            if (audio.playing && audio.currentPath == block.path) audio.stop()
                            else askPlayFor = block.path
                        }
                    )
                }
                if (vm.recording) {
                    Text("● Recording…", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
                }

                // --- Add-to-note toolbar ---
                Divider(Modifier.padding(vertical = 8.dp))
                Text("Add to note", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.addTextBlock() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Edit, null); Text(" Text")
                    }
                    OutlinedButton(
                        onClick = { if (readTextFromImage) askImageLangFor = "camera" else pickImage() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.PhotoCamera, null); Text(" Photo")
                    }
                    OutlinedButton(
                        onClick = {
                            if (readTextFromImage) askImageLangFor = "gallery"
                            else galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.PhotoLibrary, null); Text(" Gallery")
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clickable { readTextFromImage = !readTextFromImage },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = readTextFromImage, onCheckedChange = { readTextFromImage = it })
                    Column {
                        Text("Read text from image (OCR)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "When ticked, text in the next Photo/Gallery image is added to the note.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { toggleRecord() }, modifier = Modifier.weight(1f)) {
                        if (vm.recording) { Icon(Icons.Filled.Stop, null); Text(" Stop") }
                        else { Icon(Icons.Filled.Mic, null); Text(" Voice") }
                    }
                    OutlinedButton(onClick = { withCamera { launchVideo() } }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Videocam, null); Text(" Video")
                    }
                    OutlinedButton(onClick = { docPicker.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Description, null); Text(" File")
                    }
                }
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { requestLocation() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Place, null); Text(" Place")
                    }
                    OutlinedButton(onClick = { showLinkDialog = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Download, null); Text(" Link")
                    }
                }
            }

            // --- Reminder ---
            Card(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Reminder", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Switch(checked = vm.reminderEnabled, onCheckedChange = { vm.reminderEnabled = it })
                    }
                    if (vm.reminderEnabled) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            Text("Repeat every day", Modifier.weight(1f))
                            Switch(checked = vm.reminderDaily, onCheckedChange = { vm.reminderDaily = it })
                        }
                        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!vm.reminderDaily) {
                                OutlinedButton(onClick = { pickDate(context, vm.reminderAt) { vm.setReminderDateTime(it) } }) {
                                    Text(Format.date(vm.reminderAt))
                                }
                            }
                            OutlinedButton(onClick = { pickTime(context, vm.reminderAt) { vm.setReminderDateTime(it) } }) {
                                Text(timeLabel(vm.reminderAt))
                            }
                        }
                        Text(
                            if (vm.reminderDaily) "Every day at ${timeLabel(vm.reminderAt)}"
                            else "Once on ${Format.dateTime(vm.reminderAt)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            Button(onClick = { doSave() }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text("Save")
            }
        }
    }

    // Tapping a voice note asks how to play it.
    askPlayFor?.let { path ->
        val voices = vm.blocks.filter { it.type == BlockType.AUDIO }.map { it.path }
        val startIndex = voices.indexOf(path).coerceAtLeast(0)
        val remaining = voices.size - startIndex
        AlertDialog(
            onDismissRequest = { askPlayFor = null },
            title = { Text("Play voice note") },
            text = {
                Column {
                    Text("Play once — stop at the end of this note.")
                    Text("Loop — repeat this note until you stop it.", Modifier.padding(top = 6.dp))
                    Text(
                        "Play all — play this note, then continue through the remaining $remaining voice note(s) in this entry.",
                        Modifier.padding(top = 6.dp)
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { askPlayFor = null; audio.start(voices, startIndex, PlayMode.ONCE) }) { Text("Once") }
                    TextButton(onClick = { askPlayFor = null; audio.start(voices, startIndex, PlayMode.LOOP) }) { Text("Loop") }
                    TextButton(
                        onClick = { askPlayFor = null; audio.start(voices, startIndex, PlayMode.ALL) },
                        enabled = remaining > 1
                    ) { Text("Play all") }
                    // Runs the whole list, then starts again from the first note.
                    TextButton(
                        onClick = { askPlayFor = null; audio.start(voices, startIndex, PlayMode.ALL_LOOP) },
                        enabled = remaining > 1
                    ) { Text("All + repeat") }
                }
            },
            dismissButton = { TextButton(onClick = { askPlayFor = null }) { Text("Cancel") } }
        )
    }

    // Attach from a direct file link: paste URL → download with progress → becomes a block.
    if (showLinkDialog) {
        var link by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (!vm.downloading) showLinkDialog = false },
            title = { Text("Attach from link") },
            text = {
                Column {
                    OutlinedTextField(
                        value = link,
                        onValueChange = { link = it },
                        label = { Text("Direct file link") },
                        placeholder = { Text("https://…/file.mp3") },
                        singleLine = false, minLines = 2, maxLines = 4,
                        enabled = !vm.downloading,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Paste a direct link to the file itself (.mp3, .mp4, .pdf, image…). It downloads into this entry and stays offline.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    if (vm.downloading) {
                        Text(
                            vm.downloadName ?: "Connecting…",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                        if (vm.downloadProgress >= 0f) {
                            LinearProgressIndicator(
                                progress = { vm.downloadProgress },
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                            )
                            Text(
                                "${(vm.downloadProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                        }
                    }
                }
            },
            confirmButton = {
                if (vm.downloading) TextButton(onClick = { vm.cancelDownload() }) { Text("Cancel download") }
                else TextButton(
                    onClick = { vm.downloadFromUrl(context, link) },
                    enabled = link.isNotBlank()
                ) { Text("Download") }
            },
            dismissButton = {
                TextButton(onClick = { showLinkDialog = false }, enabled = !vm.downloading) { Text("Close") }
            }
        )
    }

    transcribeBlock?.let { block ->
        com.billing.pos.ui.common.OcrLanguageAskDialog(
            onPick = { picked ->
                transcribeBlock = null
                // Same two languages as everywhere else, as speech-recogniser tags.
                val tag = if (picked == com.billing.pos.data.AppPrefs.OCR_MALAYALAM) "ml-IN" else "en-IN"
                vm.transcribeAudio(context, block, tag)
            },
            onDismiss = { transcribeBlock = null }
        )
    }

    askImageLangFor?.let { which ->
        com.billing.pos.ui.common.OcrLanguageAskDialog(
            onPick = { picked ->
                imageOcrLang = picked; askImageLangFor = null
                if (which == "camera") pickImage()
                else galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onDismiss = { askImageLangFor = null }
        )
    }

    drawTarget?.let { target ->
        com.billing.pos.ui.common.HandwriteTextDialog(
            onDismiss = { drawTarget = null },
            onResult = { text ->
                if (text.isNotBlank()) {
                    if (target == "title") {
                        vm.title = (vm.title.trim() + " " + text).trim()
                    } else {
                        val i = target.removePrefix("body:").toIntOrNull()
                        val b = i?.let { vm.blocks.getOrNull(it) }
                        if (b != null) b.text = (b.text.trimEnd() + " " + text).trim()
                    }
                }
                drawTarget = null
            }
        )
    }
    if (viewerPaths.isNotEmpty()) {
        com.billing.pos.ui.common.ImageViewerDialog(
            paths = viewerPaths,
            startIndex = viewerIndex,
            onDismiss = { viewerPaths = emptyList() }
        )
    }

    if (showTypePicker) {
        val diaryTypes by vm.types.collectAsStateSafe()
        com.billing.pos.ui.common.SearchablePickDialog(
            title = "Diary type",
            options = diaryTypes.map { it.id to it.name },
            selectedId = vm.typeId,
            onPick = { id -> vm.typeId = id; showTypePicker = false },
            onAdd = { name -> vm.addType(name); showTypePicker = false },
            onDismiss = { showTypePicker = false }
        )
    }

    renameType?.let { t ->
        var newName by remember(t.id) { mutableStateOf(t.name) }
        AlertDialog(
            onDismissRequest = { renameType = null },
            title = { Text("Correct diary type") },
            text = {
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it },
                    label = { Text("Name") }, singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.renameType(t, newName); renameType = null }) { Text("Update") }
            },
            dismissButton = { TextButton(onClick = { renameType = null }) { Text("Cancel") } }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete entry?") },
            text = { Text("This removes the entry and its attachments. Cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete(context) { onBack() } }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

/** Renders one body block (text field / image / voice player / file row) plus its controls. */
@Composable
private fun BlockEditor(
    block: BlockUi,
    isFirst: Boolean,
    isLast: Boolean,
    bodyStyle: TextStyle,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onOpen: () -> Unit,
    onSpeak: () -> Unit,
    onDraw: () -> Unit = {},
    onShareText: () -> Unit = {},
    onTranscribe: () -> Unit = {},
    audioPlaying: Boolean = false,
    onAudioToggle: () -> Unit = {}
) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        when (block.type) {
            BlockType.TEXT -> OutlinedTextField(
                value = block.text, onValueChange = { block.text = it },
                placeholder = { Text("Write here…") },
                minLines = 2, textStyle = bodyStyle,
                trailingIcon = {
                    Row {
                        IconButton(onClick = onShareText) { Icon(Icons.Filled.Share, contentDescription = "Share this text") }
                        IconButton(onClick = onDraw) { Icon(Icons.Filled.Draw, contentDescription = "Write by hand") }
                        IconButton(onClick = onSpeak) { Icon(Icons.Filled.Mic, contentDescription = "Dictate") }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            BlockType.IMAGE -> {
                val bmp = rememberThumbnail(block.path, 800)
                Box(
                    Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant).clickable { onOpen() },
                    contentAlignment = Alignment.Center
                ) {
                    if (bmp != null) {
                        androidx.compose.foundation.Image(
                            bmp, contentDescription = block.name,
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(Icons.Filled.Image, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            BlockType.AUDIO -> Column(Modifier.fillMaxWidth()) {
                VoicePlayer(
                    path = block.path, durationMs = block.durationMs,
                    playing = audioPlaying, onToggle = onAudioToggle
                )
                // Convert this recording to text, inserted just below it.
                TextButton(onClick = onTranscribe) {
                    Icon(Icons.Filled.Subtitles, contentDescription = null)
                    Text("  Convert speech to text")
                }
            }
            else -> Row(
                Modifier.fillMaxWidth().clickable { onOpen() }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val icon = when (block.type) {
                    BlockType.VIDEO -> Icons.Filled.Videocam
                    BlockType.LOCATION -> Icons.Filled.Place
                    else -> Icons.Filled.Description
                }
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(block.name.ifBlank { "Attachment" }, Modifier.weight(1f).padding(start = 8.dp), maxLines = 1)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            // Download + share for the real files: voice notes, photos, videos, documents.
            val hasFile = block.path.isNotBlank() &&
                block.type in setOf(BlockType.AUDIO, BlockType.IMAGE, BlockType.VIDEO, BlockType.DOCUMENT)
            if (hasFile) {
                val blockContext = LocalContext.current
                IconButton(onClick = {
                    val f = java.io.File(block.path)
                    val name = block.name.ifBlank { f.name }
                    val ok = com.billing.pos.data.DownloadSaver.save(blockContext, f, name, block.mime.ifBlank { "application/octet-stream" })
                    android.widget.Toast.makeText(
                        blockContext, if (ok) "Saved to Downloads" else "Could not save", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }) { Icon(Icons.Filled.Download, "Download") }
                IconButton(onClick = { shareBlockFile(blockContext, block) }) {
                    Icon(Icons.Filled.Share, "Share")
                }
            }
            IconButton(onClick = onMoveUp, enabled = !isFirst) { Icon(Icons.Filled.KeyboardArrowUp, "Move up") }
            IconButton(onClick = onMoveDown, enabled = !isLast) { Icon(Icons.Filled.KeyboardArrowDown, "Move down") }
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.error) }
        }
        Divider()
    }
}

/** How a tapped voice note should play. */
enum class PlayMode { ONCE, LOOP, ALL, ALL_LOOP }

/**
 * The one player for the whole app.
 *
 * Held outside the screen so a voice note keeps playing while you move around the app.
 * It stops when you back out of the diary entry, and is released when the app closes.
 */
object DiaryAudio {
    val controller = AudioController()
}

/**
 * Owns the one MediaPlayer for the whole entry, so a voice note can play once, loop, or run
 * on through every later voice note in the entry.
 */
class AudioController {
    private val mp = android.media.MediaPlayer()
    private var queue: List<String> = emptyList()
    private var index = 0
    private var mode = PlayMode.ONCE

    var currentPath by mutableStateOf<String?>(null); private set
    var playing by mutableStateOf(false); private set

    init {
        mp.setOnCompletionListener {
            when (mode) {
                PlayMode.LOOP -> Unit                    // isLooping repeats it for us
                PlayMode.ONCE -> stop()
                PlayMode.ALL -> {
                    val next = index + 1
                    if (next < queue.size) { index = next; playAt(next) } else stop()
                }
                // Runs through every voice note, then starts again from the first.
                PlayMode.ALL_LOOP -> {
                    index = if (index + 1 < queue.size) index + 1 else 0
                    playAt(index)
                }
            }
        }
    }

    /** Plays [paths] from [startIndex] in the given [m]ode. */
    fun start(paths: List<String>, startIndex: Int, m: PlayMode) {
        if (paths.isEmpty()) return
        queue = paths
        mode = m
        index = startIndex.coerceIn(0, paths.lastIndex)
        playAt(index)
    }

    private fun playAt(i: Int) {
        val path = queue.getOrNull(i) ?: return stop()
        runCatching {
            mp.reset()                                   // safe to call from onCompletion
            mp.setDataSource(path)
            mp.prepare()
            mp.isLooping = mode == PlayMode.LOOP
            mp.start()
            currentPath = path
            playing = true
        }.onFailure { stop() }
    }

    fun stop() {
        runCatching { if (mp.isPlaying) mp.stop() }
        runCatching { mp.reset() }
        playing = false
        currentPath = null
    }

    fun release() { runCatching { mp.release() } }
}

/** A small inline play/stop bar for a recorded voice block. */
@Composable
private fun VoicePlayer(path: String, durationMs: Long, playing: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (playing) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onToggle) {
            Icon(
                if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Stop" else "Play",
                tint = MaterialTheme.colorScheme.primary
            )
        }
        val secs = (durationMs / 1000).toInt()
        Text("Voice note" + if (secs > 0) "  ${secs / 60}:${"%02d".format(secs % 60)}" else "", Modifier.weight(1f))
        Icon(Icons.Filled.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
    }
}

private fun openBlock(context: Context, block: BlockUi, onError: (String) -> Unit) {
    runCatching {
        if (block.type == BlockType.LOCATION) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(block.text)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", File(block.path))
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, block.mime.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure { onError("Can't open this attachment") }
}

@SuppressLint("MissingPermission")
private fun fetchLocation(context: Context, vm: DiaryEditViewModel) {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    if (lm == null) { vm.message.value = "Location not available"; return }
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

    var best: Location? = null
    for (pr in providers) {
        if (lm.isProviderEnabled(pr)) {
            val l = runCatching { lm.getLastKnownLocation(pr) }.getOrNull()
            if (l != null && (best == null || l.time > best!!.time)) best = l
        }
    }
    if (best != null) {
        vm.addLocation(best!!.latitude, best!!.longitude)
        vm.message.value = "Location attached"
        return
    }

    val enabled = providers.firstOrNull { lm.isProviderEnabled(it) }
    if (enabled == null) { vm.message.value = "Turn on GPS / location"; return }
    vm.message.value = "Getting current location…"
    runCatching {
        lm.requestSingleUpdate(enabled, object : LocationListener {
            override fun onLocationChanged(location: Location) {
                vm.addLocation(location.latitude, location.longitude)
                vm.message.value = "Location attached"
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }, Looper.getMainLooper())
    }.onFailure { vm.message.value = "Could not get location" }
}

private fun pickDate(context: Context, current: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = current }
    android.app.DatePickerDialog(
        context,
        { _, y, m, d -> c.set(Calendar.YEAR, y); c.set(Calendar.MONTH, m); c.set(Calendar.DAY_OF_MONTH, d); onPicked(c.timeInMillis) },
        c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
    ).show()
}

private fun pickTime(context: Context, current: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = current }
    android.app.TimePickerDialog(
        context,
        { _, h, min -> c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, min); c.set(Calendar.SECOND, 0); onPicked(c.timeInMillis) },
        c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false
    ).show()
}

private fun timeLabel(millis: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    return String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
}

/** Appends spoken text to existing field content with a separating space. */
private fun appendText(existing: String, spoken: String): String =
    if (existing.isBlank()) spoken else "$existing $spoken"

/**
 * Read-only, searchable view of the notes. Type a word to highlight (yellow) every match,
 * jump between matches with up/down, copy the focused line, and long-press to select
 * multiple lines and copy them together.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RemarksSearchView(text: String, lineStyle: TextStyle = TextStyle.Default) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val lines = remember(text) { if (text.isEmpty()) listOf("") else text.split("\n") }
    var query by remember { mutableStateOf("") }
    var pos by remember { mutableStateOf(0) }
    val matches = remember(query, lines) {
        if (query.isBlank()) emptyList()
        else lines.indices.filter { lines[it].contains(query, ignoreCase = true) }
    }
    LaunchedEffect(matches.size) { if (pos >= matches.size) pos = 0 }
    val currentLine = matches.getOrNull(pos)
    val listState = rememberLazyListState()
    LaunchedEffect(pos, currentLine) {
        currentLine?.let { runCatching { listState.animateScrollToItem(it) } }
    }

    // Multi-select: long-press a line to start selecting, tap to toggle.
    val selected = remember(text) { mutableStateListOf<Int>() }
    val selectionMode = selected.isNotEmpty()
    fun toggle(i: Int) { if (selected.contains(i)) selected.remove(i) else selected.add(i) }
    fun copySelected() {
        val joined = selected.sorted().joinToString("\n") { lines[it] }
        if (joined.isNotEmpty()) clipboard.setText(AnnotatedString(joined))
    }

    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query, onValueChange = { query = it; pos = 0 },
            label = { Text("Search in notes") }, singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth()
        )
        if (selectionMode) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${selected.size} selected",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary
                )
                TextButton(onClick = { copySelected() }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    Text("Copy")
                }
                TextButton(onClick = { selected.clear() }) { Text("Clear") }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        query.isBlank() -> "Type to search  ·  long-press a line to select"
                        matches.isEmpty() -> "No match"
                        else -> "${pos + 1} / ${matches.size}"
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
                IconButton(onClick = { if (matches.isNotEmpty()) pos = (pos - 1 + matches.size) % matches.size }, enabled = matches.isNotEmpty()) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous match")
                }
                IconButton(onClick = { if (matches.isNotEmpty()) pos = (pos + 1) % matches.size }, enabled = matches.isNotEmpty()) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next match")
                }
                IconButton(
                    onClick = { currentLine?.let { clipboard.setText(AnnotatedString(lines[it])) } },
                    enabled = currentLine != null
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy line", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Box(
            Modifier.fillMaxWidth().height(300.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(8.dp)) {
                itemsIndexed(lines) { i, line ->
                    val isSelected = selected.contains(i)
                    val isCurrent = i == currentLine
                    val bg = when {
                        isSelected -> MaterialTheme.colorScheme.secondaryContainer
                        isCurrent -> MaterialTheme.colorScheme.primaryContainer
                        else -> Color.Transparent
                    }
                    val annotated = remember(line, query) { highlightMatches(line, query) }
                    var layout by remember(line, query) { mutableStateOf<TextLayoutResult?>(null) }
                    Text(
                        annotated,
                        style = lineStyle,
                        onTextLayout = { layout = it },
                        modifier = Modifier.fillMaxWidth()
                            .background(bg)
                            // Tap handling is done here rather than with combinedClickable so the
                            // tap position is available — that is what decides whether a link was hit.
                            .pointerInput(annotated, selectionMode) {
                                detectTapGestures(
                                    onLongPress = { toggle(i) },
                                    onTap = { offset ->
                                        if (selectionMode) { toggle(i); return@detectTapGestures }
                                        val pos = layout?.getOffsetForPosition(offset) ?: return@detectTapGestures
                                        val link = annotated.getStringAnnotations(LINK_TAG, pos, pos).firstOrNull()
                                        if (link != null) openDiaryLink(context, link.item)
                                    }
                                )
                            }
                            .padding(vertical = 3.dp, horizontal = 2.dp)
                    )
                }
            }
        }
    }
}

private const val LINK_TAG = "diary-link"

/** Web addresses, e-mail addresses and phone numbers found in a note line. */
private val LINK_REGEX = Regex(
    """(https?://\S+|www\.\S+|[\w.+-]+@[\w-]+\.[\w.]{2,}|\+?\d[\d\s-]{7,}\d)"""
)

/**
 * Marks every case-insensitive occurrence of [query] with a yellow background, and every
 * link with an underline plus a [LINK_TAG] annotation so a tap can open it.
 *
 * Both can cover the same characters, so the line is cut at every span boundary and each
 * piece is styled for whatever applies to it.
 */
private fun highlightMatches(line: String, query: String): AnnotatedString = buildAnnotatedString {
    val links = LINK_REGEX.findAll(line).map { it.range }.toList()
    val hits = if (query.isBlank()) emptyList()
    else Regex(Regex.escape(query), RegexOption.IGNORE_CASE).findAll(line).map { it.range }.toList()

    if (links.isEmpty() && hits.isEmpty()) { append(line); return@buildAnnotatedString }

    val cuts = sortedSetOf(0, line.length)
    (links + hits).forEach { cuts.add(it.first); cuts.add(it.last + 1) }
    val points = cuts.filter { it in 0..line.length }

    for (k in 0 until points.size - 1) {
        val from = points[k]
        val to = points[k + 1]
        if (from >= to) continue
        val link = links.firstOrNull { from >= it.first && to <= it.last + 1 }
        val isHit = hits.any { from >= it.first && to <= it.last + 1 }

        var style = SpanStyle()
        if (isHit) style = style.copy(background = Color(0xFFFFEB3B), color = Color.Black)
        if (link != null) style = style.copy(
            color = Color(0xFF1565C0), textDecoration = TextDecoration.Underline
        )

        if (link != null) pushStringAnnotation(LINK_TAG, line.substring(link.first, link.last + 1))
        withStyle(style) { append(line.substring(from, to)) }
        if (link != null) pop()
    }
}

/** Opens a tapped note link: web page, e-mail draft or dialler, whichever it looks like. */
private fun openDiaryLink(context: android.content.Context, raw: String) {
    val text = raw.trim().trimEnd('.', ',', ')', ']', ';', ':')
    if (text.isEmpty()) return
    val uri = when {
        text.startsWith("http://", true) || text.startsWith("https://", true) -> Uri.parse(text)
        text.startsWith("www.", true) -> Uri.parse("https://$text")
        text.contains("@") -> Uri.parse("mailto:$text")
        else -> Uri.parse("tel:" + text.filter { it.isDigit() || it == '+' })
    }
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
}

/** Predefined text colours; 0 = use the theme default. */
private val DIARY_COLORS = listOf(
    0, 0xFF000000.toInt(), 0xFFD32F2F.toInt(), 0xFF1976D2.toInt(),
    0xFF388E3C.toInt(), 0xFFF57C00.toInt(), 0xFF7B1FA2.toInt(), 0xFFFFFFFF.toInt()
)

/** Builds a TextStyle from stored size/color/bold/italic. Color 0 → inherit default. */
private fun diaryStyle(size: Int, colorArgb: Int, bold: Boolean, italic: Boolean): TextStyle =
    TextStyle(
        fontSize = size.sp,
        color = if (colorArgb == 0) Color.Unspecified else Color(colorArgb),
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal
    )

@Composable
private fun FormatRow(
    label: String,
    size: Int, onSize: (Int) -> Unit,
    color: Int, onColor: (Int) -> Unit,
    bold: Boolean, onBold: (Boolean) -> Unit,
    italic: Boolean, onItalic: (Boolean) -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = { onSize((size - 1).coerceIn(8, 48)) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)
            ) { Text("A-") }
            Text("$size", modifier = Modifier.padding(horizontal = 4.dp))
            OutlinedButton(
                onClick = { onSize((size + 1).coerceIn(8, 48)) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)
            ) { Text("A+") }
            FilterChip(selected = bold, onClick = { onBold(!bold) }, label = { Text("B", fontWeight = FontWeight.Bold) })
            FilterChip(selected = italic, onClick = { onItalic(!italic) }, label = { Text("I", fontStyle = FontStyle.Italic) })
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DIARY_COLORS.forEach { c ->
                Box(
                    Modifier.size(28.dp).clip(CircleShape)
                        .background(if (c == 0) MaterialTheme.colorScheme.surfaceVariant else Color(c))
                        .border(
                            if (c == color) 2.dp else 1.dp,
                            if (c == color) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            CircleShape
                        )
                        .clickable { onColor(c) },
                    contentAlignment = Alignment.Center
                ) { if (c == 0) Text("A", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

/** Shares one diary attachment (voice/photo/video/document) via the system share sheet. */
private fun shareBlockFile(context: android.content.Context, block: BlockUi) {
    runCatching {
        val f = java.io.File(block.path)
        if (!f.exists()) { android.widget.Toast.makeText(context, "File not found", android.widget.Toast.LENGTH_SHORT).show(); return }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", f)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = block.mime.ifBlank { "application/octet-stream" }
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            android.content.Intent.createChooser(send, "Share").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { android.widget.Toast.makeText(context, "Could not share", android.widget.Toast.LENGTH_SHORT).show() }
}
