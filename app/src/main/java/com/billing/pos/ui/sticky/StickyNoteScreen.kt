package com.billing.pos.ui.sticky

import android.Manifest
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import android.graphics.Rect
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.BlockType
import com.billing.pos.data.DiaryBlock
import com.billing.pos.data.DiaryEntry
import com.billing.pos.data.DiaryRepository
import com.billing.pos.diary.AttachmentStore
import com.billing.pos.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Session flag so the launch sticky-note shows only once per app start. */
object StickyGate { var shown = false }

/** One-shot hand-off of OCR'd items from the sticky note to the sales entry. */
object StickyOcrLink {
    var items: List<com.billing.pos.ocr.ScannedItem>? = null
    /** true = open the review popup (text mode, edit names/prices); false = add straight to cart. */
    var review: Boolean = false
    fun take(): Pair<List<com.billing.pos.ocr.ScannedItem>?, Boolean> { val i = items to review; items = null; review = false; return i }
}

private typealias StrokePts = List<Offset>

/** A typed label placed on the page; position is in canvas coordinates. */
class NoteText(
    text: String,
    x: Float,
    y: Float,
    colorInt: Int,
    sizePx: Float
) {
    var text by mutableStateOf(text)
    var x by mutableStateOf(x)
    var y by mutableStateOf(y)
    var colorInt by mutableStateOf(colorInt)
    var sizePx by mutableStateOf(sizePx)

    fun snapshot() = NoteTextData(text, x, y, colorInt, sizePx)
}

/** Immutable copy of a [NoteText], for rendering off the UI thread. */
data class NoteTextData(
    val text: String, val x: Float, val y: Float, val colorInt: Int, val sizePx: Float
)

/** A page of the note: handwriting strokes over an optional background image. */
class NotePage {
    val strokes = mutableStateListOf<StrokePts>()
    val texts = mutableStateListOf<NoteText>()
    var bg by mutableStateOf<String?>(null)
    // Optional OCR selection box (canvas coords): when set, only content inside is read.
    var selStart by mutableStateOf<Offset?>(null)
    var selEnd by mutableStateOf<Offset?>(null)
    fun clearSelection() { selStart = null; selEnd = null }
    fun regionRect(): androidx.compose.ui.geometry.Rect? {
        val s = selStart; val e = selEnd ?: return null
        if (s == null) return null
        val l = minOf(s.x, e.x); val t = minOf(s.y, e.y); val r = maxOf(s.x, e.x); val b = maxOf(s.y, e.y)
        if (r - l < 8f || b - t < 8f) return null
        return androidx.compose.ui.geometry.Rect(l, t, r, b)
    }
}

/** Immutable snapshot passed to the VM for rendering. */
data class PageData(
    val strokes: List<StrokePts>,
    val bg: String?,
    val region: androidx.compose.ui.geometry.Rect? = null,
    val texts: List<NoteTextData> = emptyList()
)

class StickyNoteViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = DiaryRepository(app)
    private val posRepo = com.billing.pos.data.Repository(app)
    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    val customers = posRepo.customers.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())

    suspend fun addCustomer(name: String, phone: String, type: String = "General"): com.billing.pos.data.Customer =
        posRepo.addCustomerReturning(name, phone, type)

    /**
     * Renders every page and files the whole note — pages, photos, voice and video — against a
     * customer as attachments, without disturbing that customer's existing attachments.
     * [onDone] receives the file paths written, for an optional share afterwards.
     */
    fun attachToCustomer(
        customerId: Long, pages: List<PageData>, w: Int, h: Int,
        images: List<String>, audios: List<String>, videos: List<String>,
        onDone: (List<String>) -> Unit
    ) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val paths = withContext(Dispatchers.IO) {
                val atts = ArrayList<com.billing.pos.data.CustomerAttachment>()
                if (w > 0 && h > 0) pages.forEachIndexed { i, p ->
                    if (p.strokes.isNotEmpty() || p.bg != null || p.texts.isNotEmpty()) {
                        val bmp = renderPage(p, w, h)
                        atts.add(com.billing.pos.data.CustomerAttachmentStore.saveBitmap(ctx, bmp, "note_page_${i + 1}.jpg"))
                        bmp.recycle()
                    }
                }
                images.forEach { com.billing.pos.data.CustomerAttachmentStore.importFrom(ctx, it, "image/jpeg")?.let(atts::add) }
                videos.forEach { com.billing.pos.data.CustomerAttachmentStore.importFrom(ctx, it, "video/mp4")?.let(atts::add) }
                audios.forEach { com.billing.pos.data.CustomerAttachmentStore.importFrom(ctx, it, "audio/mp4")?.let(atts::add) }
                posRepo.appendCustomerAttachments(customerId, atts)
                atts.map { it.path }
            }
            message.value = "${paths.size} attachment(s) saved to customer"
            onDone(paths)
        }
    }

    var recording by mutableStateOf(false); private set
    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null

    // Re-use one diary entry across repeated saves (e.g. Save-on-share).
    private var savedEntryId: Long = 0
    private var savedCreatedAt: Long = 0
    private var lastPagePaths: List<String> = emptyList()

    /** The customer this note was filed against; shares and the diary entry carry them. */
    var attachedCustomer by mutableStateOf<com.billing.pos.data.Customer?>(null)

    fun startRecording() {
        if (recording) return
        val ctx = getApplication<Application>()
        val file = File(AttachmentStore.dir(ctx), "note_voice_${System.nanoTime()}.m4a")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setOutputFile(file.absolutePath)
            rec.prepare(); rec.start()
            recorder = rec; recordFile = file; recording = true
        } catch (e: Exception) { runCatching { rec.release() }; file.delete(); message.value = "Could not start recording" }
    }

    fun stopRecording(onSaved: (String) -> Unit) {
        val rec = recorder ?: return
        runCatching { rec.stop() }; runCatching { rec.release() }
        recorder = null; recording = false
        recordFile?.let { if (it.exists() && it.length() > 0) onSaved(it.absolutePath) }
    }

    override fun onCleared() { runCatching { recorder?.release() }; recorder = null }

    /** Renders each page (background + strokes) to a picture and creates a diary entry with all attachments. */
    fun save(pages: List<PageData>, w: Int, h: Int, images: List<String>, audios: List<String>, videos: List<String>, onDone: () -> Unit) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val pagePaths = withContext(Dispatchers.IO) {
                val out = ArrayList<String>()
                if (w > 0 && h > 0) pages.filter { it.strokes.isNotEmpty() || it.bg != null }.forEach { p ->
                    val bmp = renderPage(p, w, h)
                    val f = AttachmentStore.newFile(ctx, "jpg")
                    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    bmp.recycle(); out.add(f.absolutePath)
                }
                out
            }
            if (pagePaths.isEmpty() && images.isEmpty() && audios.isEmpty() && videos.isEmpty()) { message.value = "Nothing to save"; return@launch }
            // Remove page images rendered by a previous save of this same note.
            withContext(Dispatchers.IO) { lastPagePaths.forEach { runCatching { File(it).delete() } } }
            val now = System.currentTimeMillis()
            if (savedCreatedAt == 0L) savedCreatedAt = now
            val id = repo.upsert(DiaryEntry(
                id = savedEntryId, title = "Sticky note - ${Format.dateTime(savedCreatedAt)}", remarks = "",
                createdAt = savedCreatedAt, updatedAt = now,
                customerId = attachedCustomer?.id ?: 0, customerName = attachedCustomer?.name ?: ""
            ))
            savedEntryId = id
            val blocks = ArrayList<DiaryBlock>()
            pagePaths.forEach { blocks.add(DiaryBlock(entryId = id, position = 0, type = BlockType.IMAGE, path = it, name = "Sticky note.jpg", mime = "image/jpeg")) }
            images.forEach { blocks.add(DiaryBlock(entryId = id, position = 0, type = BlockType.IMAGE, path = it, name = "Photo.jpg", mime = "image/jpeg")) }
            audios.forEach { blocks.add(DiaryBlock(entryId = id, position = 0, type = BlockType.AUDIO, path = it, name = "Voice note.m4a", mime = "audio/mp4")) }
            videos.forEach { blocks.add(DiaryBlock(entryId = id, position = 0, type = BlockType.VIDEO, path = it, name = "Video.mp4", mime = "video/mp4")) }
            repo.replaceBlocks(id, blocks)
            lastPagePaths = pagePaths
            message.value = "Saved to My Diary"
            onDone()
        }
    }

    /**
     * Reads NUMBERS ONLY from every page. Handwriting is recognised with ML Kit Digital Ink
     * (built for handwriting) directly from the strokes; a photo background falls back to
     * image OCR. Each page's number becomes a price (no description).
     */
    fun ocrAllPages(pages: List<PageData>, w: Int, h: Int, onResult: (List<com.billing.pos.ocr.ScannedItem>) -> Unit) {
        val ctx = getApplication<Application>()
        message.value = "Reading numbers…"
        viewModelScope.launch {
            // Prices are digits, so this one stays on English whatever the app language is.
            val ink = com.billing.pos.ink.InkRecognizer(com.billing.pos.ink.InkLang.ENGLISH)
            val ready = ink.ensureReady()
            val numRegex = Regex("[0-9]+(?:[.,][0-9]+)?")
            val result = withContext(Dispatchers.IO) {
                val out = ArrayList<com.billing.pos.ocr.ScannedItem>()
                pages.forEach { p ->
                    val strokes = strokesInRegion(p.strokes, p.region)
                    var text = ""
                    if (ready && strokes.isNotEmpty()) text = ink.recognize(strokes)
                    if (text.isBlank() && p.bg != null && w > 0 && h > 0) {
                        text = ocrPageBitmap(ctx, p, w, h, " ", com.billing.pos.data.AppPrefs.OCR_ENGLISH)
                    }
                    numRegex.find(text)?.value?.replace(",", ".")?.toDoubleOrNull()?.let { if (it > 0.0) out.add(com.billing.pos.ocr.ScannedItem("", it)) }
                }
                out
            }
            ink.close()
            message.value = when {
                result.isNotEmpty() -> null
                !ready -> "Handwriting model still downloading — connect to the internet once and retry"
                else -> "No number recognised — write the price larger"
            }
            if (result.isNotEmpty()) onResult(result)
        }
    }

    /** Recognises the handwriting TEXT of every page; each page becomes one paragraph. */
    fun ocrTextAllPages(pages: List<PageData>, w: Int, h: Int, lang: String? = null, onResult: (String) -> Unit) {
        val ctx = getApplication<Application>()
        message.value = "Reading text…"
        viewModelScope.launch {
            val ink = com.billing.pos.ink.InkRecognizer(
                if (lang == com.billing.pos.data.AppPrefs.OCR_MALAYALAM) com.billing.pos.ink.InkLang.MALAYALAM
                else com.billing.pos.ink.InkLang.ENGLISH
            )
            val ready = ink.ensureReady()
            val paragraphs = withContext(Dispatchers.IO) {
                val out = ArrayList<String>()
                pages.forEach { p ->
                    val strokes = strokesInRegion(p.strokes, p.region)
                    var text = ""
                    if (ready && strokes.isNotEmpty()) text = ink.recognize(strokes)
                    if (text.isBlank() && p.bg != null && w > 0 && h > 0) {
                        text = ocrPageBitmap(ctx, p, w, h, "\n", lang)
                    }
                    if (text.isNotBlank()) out.add(text.trim())
                }
                out
            }
            ink.close()
            val combined = paragraphs.joinToString("\n\n")
            message.value = when {
                combined.isNotBlank() -> null
                !ready -> "Handwriting model still downloading — connect to the internet once and retry"
                else -> "No text recognised"
            }
            if (combined.isNotBlank()) onResult(combined)
        }
    }

    /** Keeps only strokes whose majority of points fall inside the selection box (all strokes if none). */
    private fun strokesInRegion(strokes: List<StrokePts>, region: androidx.compose.ui.geometry.Rect?): List<StrokePts> {
        if (region == null) return strokes
        return strokes.filter { st -> st.isNotEmpty() && st.count { region.contains(it) } * 2 >= st.size }
    }

    /** Renders the page, crops to the selection box (if any), OCRs it, and returns the joined text. */
    private suspend fun ocrPageBitmap(ctx: android.content.Context, p: PageData, w: Int, h: Int, sep: String, lang: String? = null): String {
        var bmp = renderPage(p, w, h)
        p.region?.let { r ->
            val left = r.left.toInt().coerceIn(0, w - 1)
            val top = r.top.toInt().coerceIn(0, h - 1)
            val right = r.right.toInt().coerceIn(left + 1, w)
            val bottom = r.bottom.toInt().coerceIn(top + 1, h)
            val cropped = Bitmap.createBitmap(bmp, left, top, right - left, bottom - top)
            if (cropped !== bmp) bmp.recycle()
            bmp = cropped
        }
        val f = File(ctx.cacheDir, "ocr_${System.nanoTime()}.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bmp.recycle()
        val text = com.billing.pos.ocr.TextOcr.lines(ctx, android.net.Uri.fromFile(f), lang).joinToString(sep)
        f.delete()
        return text
    }

    private fun renderPage(p: PageData, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = AndroidCanvas(bmp)
        c.drawColor(0xFFFFFFFF.toInt())
        p.bg?.let { path -> runCatching { BitmapFactory.decodeFile(path) }.getOrNull()?.let { c.drawBitmap(it, null, Rect(0, 0, w, h), null); it.recycle() } }
        val paint = AndroidPaint().apply { color = 0xFF111111.toInt(); isAntiAlias = true; style = AndroidPaint.Style.STROKE; strokeWidth = 4f; strokeCap = AndroidPaint.Cap.ROUND; strokeJoin = AndroidPaint.Join.ROUND }
        p.strokes.forEach { pts ->
            val path = AndroidPath(); pts.forEachIndexed { i, pt -> if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y) }
            c.drawPath(path, paint)
        }
        drawNoteTexts(c, p.texts)
        return bmp
    }
}

/**
 * Typed labels, drawn the same way for the saved page, the OCR bitmap and the share image —
 * a share that left them out would send a picture missing whatever was typed on the note.
 */
fun drawNoteTexts(c: AndroidCanvas, texts: List<NoteTextData>) {
    texts.forEach { t ->
        val tp = AndroidPaint().apply {
            color = t.colorInt
            isAntiAlias = true
            textSize = t.sizePx
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        // y is the top of the label on screen, so shift down by one ascent to match.
        var ty = t.y - tp.fontMetrics.ascent
        t.text.split('\n').forEach { line ->
            c.drawText(line, t.x, ty, tp)
            ty += tp.textSize * 1.2f
        }
    }
}

@Composable
fun StickyNoteScreen(onClose: () -> Unit, onOcrToSales: () -> Unit = {}, vm: StickyNoteViewModel = viewModel()) {
    val context = LocalContext.current
    val message by vm.message.collectAsState()

    val pages = remember { mutableStateListOf(NotePage()) }
    var pageIndex by remember { mutableStateOf(0) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val current = remember { mutableStateListOf<Offset>() }
    val images = remember { mutableStateListOf<String>() }   // non-background photos
    val audios = remember { mutableStateListOf<String>() }
    val videos = remember { mutableStateListOf<String>() }
    var bgMode by remember { mutableStateOf(true) }
    // When on, dragging draws an OCR selection box (instead of ink); Read reads only inside it.
    var regionMode by remember { mutableStateOf(false) }
    // Text mode: drags move the nearest label instead of drawing ink.
    var textMode by remember { mutableStateOf(false) }
    var editingText by remember { mutableStateOf<NoteText?>(null) }
    var addingText by remember { mutableStateOf(false) }
    var draggingText by remember { mutableStateOf<NoteText?>(null) }
    var ocrResult by remember { mutableStateOf<List<com.billing.pos.ocr.ScannedItem>?>(null) }
    var ocrModeAsk by remember { mutableStateOf(false) }
    var ocrText by remember { mutableStateOf<String?>(null) }
    var addToCustomer by remember { mutableStateOf(false) }
    val customers by vm.customers.collectAsState()

    LaunchedEffect(message) { message?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show(); vm.consumeMessage() } }

    // Route a picked image either to the page background or to attachments.
    fun handleImage(uri: android.net.Uri) {
        val dest = AttachmentStore.newFile(context, "jpg")
        val ok = runCatching { AttachmentStore.compressImageTo(context, uri, dest) }.getOrDefault(false)
        if (!ok) return
        if (bgMode) pages.getOrNull(pageIndex)?.bg = dest.absolutePath else images.add(dest.absolutePath)
    }
    val camera = com.billing.pos.ocr.rememberImageCamera { uri -> handleImage(uri) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) handleImage(uri) }

    // Video capture.
    var pendingVideo by remember { mutableStateOf<File?>(null) }
    val takeVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        val f = pendingVideo; pendingVideo = null
        if (ok == true && f != null && f.exists() && f.length() > 0) videos.add(f.absolutePath) else f?.delete()
    }
    fun launchVideo() {
        val f = File(AttachmentStore.dir(context), "note_vid_${System.nanoTime()}.mp4"); pendingVideo = f
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", f)
        runCatching { takeVideo.launch(uri) }.onFailure { pendingVideo = null }
    }
    val camPermForVideo = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) launchVideo() }
    fun startVideo() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) launchVideo()
        else camPermForVideo.launch(Manifest.permission.CAMERA)
    }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) vm.startRecording() }
    fun toggleRecord() {
        if (vm.recording) vm.stopRecording { audios.add(it) }
        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) vm.startRecording()
        else micPerm.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun shareCurrent() {
        if (canvasSize.width <= 0) return
        val page = pages.getOrNull(pageIndex) ?: return
        val bmp = Bitmap.createBitmap(canvasSize.width, canvasSize.height, Bitmap.Config.ARGB_8888)
        AndroidCanvas(bmp).apply {
            drawColor(0xFFFFFFFF.toInt())
            page.bg?.let { p -> runCatching { BitmapFactory.decodeFile(p) }.getOrNull()?.let { drawBitmap(it, null, Rect(0, 0, canvasSize.width, canvasSize.height), null) } }
            val paint = AndroidPaint().apply { color = 0xFF111111.toInt(); isAntiAlias = true; style = AndroidPaint.Style.STROKE; strokeWidth = 4f; strokeCap = AndroidPaint.Cap.ROUND }
            page.strokes.forEach { pts -> val pa = AndroidPath(); pts.forEachIndexed { i, pt -> if (i == 0) pa.moveTo(pt.x, pt.y) else pa.lineTo(pt.x, pt.y) }; drawPath(pa, paint) }
            drawNoteTexts(this, page.texts.map { it.snapshot() })
        }
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val f = File(dir, "stickynote_${System.nanoTime()}.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", f)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            // The attached customer travels with the note, so the receiver knows who it is about.
            vm.attachedCustomer?.let { c ->
                putExtra(Intent.EXTRA_TEXT, c.name + if (c.phone.isNotBlank()) " - ${c.phone}" else "")
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, "Share note").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    // Background image of the current page (loaded for the canvas).
    val curBgPath = pages.getOrNull(pageIndex)?.bg
    val bgImage: ImageBitmap? = remember(curBgPath) { curBgPath?.let { runCatching { BitmapFactory.decodeFile(it)?.asImageBitmap() }.getOrNull() } }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Sticky note", style = MaterialTheme.typography.titleMedium)
            Text("   Page ${pageIndex + 1}/${pages.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            Row(Modifier.padding(start = 12.dp).weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = bgMode, onCheckedChange = { bgMode = it; if (it) regionMode = false })
                Text("Photo bg", style = MaterialTheme.typography.labelSmall)
                Checkbox(checked = regionMode, onCheckedChange = { regionMode = it; if (it) bgMode = false })
                Text("Read in box", style = MaterialTheme.typography.labelSmall)
            }
            // Text tool: add typed labels, then drag them into place.
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Switch(
                    checked = textMode,
                    onCheckedChange = { textMode = it; if (it) regionMode = false }
                )
                Text("Text", style = MaterialTheme.typography.labelSmall)
            }
            if (textMode) {
                androidx.compose.material3.TextButton(onClick = { addingText = true }) {
                    Text("+ Add", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (vm.recording) Text("● REC", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        }
        Canvas(
            Modifier.weight(1f).fillMaxWidth().background(Color.White)
                .onSizeChanged { canvasSize = it }
                .pointerInput(pageIndex, regionMode, textMode) {
                    detectDragGestures(
                        onDragStart = { off ->
                            when {
                                textMode -> draggingText = nearestText(pages.getOrNull(pageIndex), off)
                                regionMode -> pages.getOrNull(pageIndex)?.let { it.selStart = off; it.selEnd = off }
                                else -> { current.clear(); current.add(off) }
                            }
                        },
                        onDrag = { change, drag ->
                            when {
                                textMode -> draggingText?.let { t -> t.x += drag.x; t.y += drag.y }
                                regionMode -> pages.getOrNull(pageIndex)?.selEnd = change.position
                                else -> current.add(change.position)
                            }
                            change.consume()
                        },
                        onDragEnd = {
                            if (!textMode && !regionMode && current.isNotEmpty()) {
                                while (pages.size <= pageIndex) pages.add(NotePage())
                                pages[pageIndex].strokes.add(current.toList())
                            }
                            draggingText = null
                            current.clear()
                        }
                    )
                }
                .pointerInput(pageIndex, textMode) {
                    // In text mode a tap opens the label under the finger for editing.
                    detectTapGestures { off ->
                        if (textMode) nearestText(pages.getOrNull(pageIndex), off)?.let { editingText = it }
                    }
                }
        ) {
            bgImage?.let { drawImage(it, srcOffset = IntOffset.Zero, srcSize = IntSize(it.width, it.height), dstOffset = IntOffset.Zero, dstSize = IntSize(size.width.toInt(), size.height.toInt())) }
            val ink = Color.Black
            pages.getOrNull(pageIndex)?.strokes?.forEach { pts ->
                val path = Path().apply { pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
                drawPath(path, ink, style = Stroke(width = 4f, cap = StrokeCap.Round))
            }
            if (current.isNotEmpty()) {
                val path = Path().apply { current.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
                drawPath(path, ink, style = Stroke(width = 4f, cap = StrokeCap.Round))
            }
            // Typed labels.
            pages.getOrNull(pageIndex)?.texts?.forEach { t ->
                drawIntoCanvas { canvas ->
                    val tp = android.graphics.Paint().apply {
                        color = t.colorInt
                        isAntiAlias = true
                        textSize = t.sizePx
                        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                    }
                    var ty = t.y - tp.fontMetrics.ascent
                    t.text.split('\n').forEach { line ->
                        canvas.nativeCanvas.drawText(line, t.x, ty, tp)
                        ty += tp.textSize * 1.2f
                    }
                }
            }
            // Selection box overlay for region OCR.
            pages.getOrNull(pageIndex)?.let { pg ->
                val s = pg.selStart; val e = pg.selEnd
                if (s != null && e != null) {
                    val l = minOf(s.x, e.x); val t = minOf(s.y, e.y)
                    drawRect(Color(0xFF00B0FF), topLeft = Offset(l, t), size = androidx.compose.ui.geometry.Size(maxOf(s.x, e.x) - l, maxOf(s.y, e.y) - t), style = Stroke(width = 3f))
                }
            }
        }
        // Row 1: page nav + clear
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { if (pageIndex > 0) pageIndex-- }, enabled = pageIndex > 0, modifier = Modifier.weight(1f)) { Text("Prev") }
            OutlinedButton(onClick = { if (pageIndex == pages.lastIndex) pages.add(NotePage()); pageIndex++ }, modifier = Modifier.weight(1f)) { Text("Next") }
            OutlinedButton(onClick = { pages.getOrNull(pageIndex)?.let { it.strokes.clear(); it.bg = null; it.clearSelection() } }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Clear, null); Text("Clear") }
        }
        // Row 2: attachments (bigger icons so they're easy to see)
        val bigIcon = Modifier.size(30.dp)
        val actBtn = Modifier.weight(1f).height(56.dp)
        val noPad = androidx.compose.foundation.layout.PaddingValues(2.dp)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { camera() }, modifier = actBtn, contentPadding = noPad) { Icon(Icons.Filled.PhotoCamera, "Camera", modifier = bigIcon) }
            OutlinedButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = actBtn, contentPadding = noPad) { Icon(Icons.Filled.PhotoLibrary, "Gallery", modifier = bigIcon) }
            OutlinedButton(onClick = { toggleRecord() }, modifier = actBtn, contentPadding = noPad) { Icon(if (vm.recording) Icons.Filled.Stop else Icons.Filled.Mic, "Voice", modifier = bigIcon) }
            OutlinedButton(onClick = { startVideo() }, modifier = actBtn, contentPadding = noPad) { Icon(Icons.Filled.Videocam, "Video", modifier = bigIcon) }
            OutlinedButton(onClick = {
                shareCurrent()
                vm.save(pages.map { PageData(it.strokes.toList(), it.bg, null, it.texts.map { t -> t.snapshot() }) }, canvasSize.width, canvasSize.height, images.toList(), audios.toList(), videos.toList()) { }
            }, modifier = actBtn, contentPadding = noPad) { Icon(Icons.Filled.Share, "Share", modifier = bigIcon) }
            OutlinedButton(onClick = { ocrModeAsk = true }, modifier = actBtn, contentPadding = noPad) { Icon(Icons.Filled.TextFields, "Read", modifier = bigIcon) }
            OutlinedButton(onClick = { addToCustomer = true }, modifier = actBtn, contentPadding = noPad) { Icon(Icons.Filled.PersonAdd, "Add to customer", modifier = bigIcon) }
        }
        // Row 3: close / save
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("Close") }
            Button(onClick = { vm.save(pages.map { PageData(it.strokes.toList(), it.bg, null, it.texts.map { t -> t.snapshot() }) }, canvasSize.width, canvasSize.height, images.toList(), audios.toList(), videos.toList()) { onClose() } }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Save, null); Text("Save") }
        }
    }

    // Add-to-customer: pick or create a customer, then file the whole note against them.
    if (addToCustomer) {
        var picked by remember { mutableStateOf<com.billing.pos.data.Customer?>(null) }
        var typed by remember { mutableStateOf("") }
        var newCust by remember { mutableStateOf(false) }
        var newName by remember { mutableStateOf("") }
        var newPhone by remember { mutableStateOf("") }
        var newCType by remember { mutableStateOf("General") }
        val scope = androidx.compose.runtime.rememberCoroutineScope()

        fun pagesSnapshot() = pages.map { PageData(it.strokes.toList(), it.bg, null, it.texts.map { t -> t.snapshot() }) }

        fun commit(share: Boolean) {
            val cust = picked ?: customers.firstOrNull { it.name.equals(typed.trim(), true) }
            if (cust == null) { vm.message.value = "Pick a customer, or use + to add one"; return }
            vm.attachedCustomer = cust
            vm.attachToCustomer(
                cust.id, pagesSnapshot(), canvasSize.width, canvasSize.height,
                images.toList(), audios.toList(), videos.toList()
            ) { paths ->
                if (share) {
                    val caption = if (cust.phone.isNotBlank()) cust.name + "\n" + cust.phone else cust.name
                    shareNoteFiles(context, caption, paths)
                }
            }
            addToCustomer = false
        }

        if (newCust) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { newCust = false },
                title = { Text("New customer") },
                text = {
                    var drawNm by remember { mutableStateOf(false) }
                    if (drawNm) {
                        com.billing.pos.ui.common.HandwriteTextDialog(
                            onResult = { if (it.isNotBlank()) newName = it; drawNm = false },
                            onDismiss = { drawNm = false }
                        )
                    }
                    Column {
                        OutlinedTextField(
                            value = newName, onValueChange = { newName = it }, label = { Text("Name") }, singleLine = true,
                            trailingIcon = {
                                androidx.compose.material3.IconButton(onClick = { drawNm = true }) {
                                    androidx.compose.material3.Icon(Icons.Filled.Gesture, "Write name")
                                }
                            }
                        )
                        OutlinedTextField(
                            value = newPhone, onValueChange = { newPhone = it.filter { c -> c.isDigit() } },
                            label = { Text("Mobile (optional — blank if none)") }, singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        com.billing.pos.ui.common.CustomerTypeField(value = newCType, onValue = { newCType = it }, modifier = Modifier.padding(top = 8.dp))
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        val nm = newName.trim()
                        if (nm.isNotBlank()) scope.launch {
                            val c = vm.addCustomer(nm, newPhone.trim(), newCType)
                            picked = c; typed = c.name; newCust = false
                        }
                    }) { Text("Add") }
                },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { newCust = false }) { Text("Cancel") } }
            )
        }

        androidx.compose.material3.AlertDialog(
            onDismissRequest = { addToCustomer = false },
            title = { Text("Add note to customer") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        com.billing.pos.ui.common.CustomerPickField(
                            customers = customers,
                            selectedName = picked?.name ?: typed,
                            onPick = { c -> picked = c; typed = c.name },
                            allowFreeText = true,
                            onTyped = { typed = it; picked = null },
                            modifier = Modifier.weight(1f)
                        )
                        androidx.compose.material3.IconButton(onClick = { newName = typed; newCust = true }) {
                            Icon(Icons.Filled.PersonAdd, "New customer", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text(
                        "Saves every page, photo, voice and video as attachments on the customer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { commit(false) }) { Text("Save") } },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    androidx.compose.material3.TextButton(onClick = { commit(true) }) { Text("Save & share") }
                    androidx.compose.material3.TextButton(onClick = { addToCustomer = false }) { Text("Cancel") }
                }
            }
        )
    }

    // OCR result — a list of scanned amounts with a big total, shareable, addable to a sale.
    ocrResult?.let { list ->
        val total = list.sumOf { it.price }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { ocrResult = null },
            title = { Text("Scanned amounts (${list.size})") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                        items(list.size) { i ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${i + 1}.")
                                Text(Format.money(list[i].price), style = MaterialTheme.typography.titleMedium)
                            }
                            androidx.compose.material3.Divider()
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("TOTAL", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text(Format.money(total), style = MaterialTheme.typography.headlineSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val text = list.joinToString("\n") { Format.money(it.price) } + "\n\nTotal: ${Format.money(total)}"
                            val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                            runCatching { context.startActivity(Intent.createChooser(intent, "Share amounts").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Share, null); Text(" Share") }
                        Button(onClick = { StickyOcrLink.items = list; StickyOcrLink.review = false; ocrResult = null; onOcrToSales() }, modifier = Modifier.weight(1f)) { Text("Add to Sale") }
                    }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { ocrResult = null }) { Text("Close") } }
        )
    }

    // Add / edit a typed label.
    if (addingText || editingText != null) {
        val existing = editingText
        StickyTextDialog(
            initial = existing,
            onDismiss = { addingText = false; editingText = null },
            onDelete = {
                existing?.let { pages.getOrNull(pageIndex)?.texts?.remove(it) }
                addingText = false; editingText = null
            },
            onSave = { text, colorInt, sizePx ->
                if (existing != null) {
                    existing.text = text; existing.colorInt = colorInt; existing.sizePx = sizePx
                } else {
                    while (pages.size <= pageIndex) pages.add(NotePage())
                    // Drop it near the top-left so it is always on screen, then drag it.
                    pages[pageIndex].texts.add(NoteText(text, 60f, 80f, colorInt, sizePx))
                }
                addingText = false; editingText = null
            }
        )
    }

    // Ask: read as numbers or text? The chips also decide which engine reads the text.
    var ocrLang by remember { mutableStateOf(com.billing.pos.ui.common.OcrLang.default(context)) }
    if (ocrModeAsk) {
        val pageData = pages.map { PageData(it.strokes.toList(), it.bg, it.regionRect(), it.texts.map { t -> t.snapshot() }) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { ocrModeAsk = false },
            title = { Text("Read handwriting as") },
            text = {
                Column {
                    Text("Numbers — each page's amount for a sale. Text — combine all pages into editable text.")
                    Text(
                        "Text is read in this language (numbers are always digits):",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                    com.billing.pos.ui.common.OcrLanguageChips(
                        selected = ocrLang, onSelect = { ocrLang = it },
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            },
            confirmButton = {
                Button(onClick = { ocrModeAsk = false; vm.ocrAllPages(pageData, canvasSize.width, canvasSize.height) { items -> ocrResult = items } }) { Text("Numbers") }
            },
            dismissButton = {
                OutlinedButton(onClick = { ocrModeAsk = false; vm.ocrTextAllPages(pageData, canvasSize.width, canvasSize.height, ocrLang) { t -> ocrText = t } }) { Text("Text") }
            }
        )
    }

    // Text result — big editable box, share to WhatsApp, add to invoice as item names.
    ocrText?.let { initial ->
        var text by remember(initial) { mutableStateOf(initial) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { ocrText = null },
            title = { Text("Recognised text") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 360.dp),
                        minLines = 6
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                            val ok = runCatching { context.startActivity(Intent(send).apply { setPackage("com.whatsapp") }) }.isSuccess ||
                                runCatching { context.startActivity(Intent(send).apply { setPackage("com.whatsapp.w4b") }) }.isSuccess
                            if (!ok) android.widget.Toast.makeText(context, "WhatsApp not found", android.widget.Toast.LENGTH_SHORT).show()
                        }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Share, null); Text(" WhatsApp") }
                        Button(onClick = {
                            // Each paragraph -> an item name (price blank), reviewed in the sale.
                            val items = text.split(Regex("\\n\\s*\\n")).map { it.trim().replace("\n", " ") }.filter { it.isNotBlank() }
                                .map { com.billing.pos.ocr.ScannedItem(it, 0.0) }
                            if (items.isNotEmpty()) { StickyOcrLink.items = items; StickyOcrLink.review = true; ocrText = null; onOcrToSales() }
                        }, modifier = Modifier.weight(1f)) { Text("Add to invoice") }
                    }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { ocrText = null }) { Text("Close") } }
        )
    }
}

/** The label nearest [off], within a generous radius so it is easy to grab. */
/**
 * Sends the note's files (rendered pages + media) straight to WhatsApp, with the customer's
 * name and — when it exists — phone number as the caption. Falls back to the share sheet
 * only if WhatsApp is not installed.
 */
private fun shareNoteFiles(context: android.content.Context, caption: String, paths: List<String>) {
    if (paths.isEmpty()) return
    runCatching {
        val uris = ArrayList<android.net.Uri>()
        paths.forEach { p ->
            val f = File(p)
            if (f.exists()) uris.add(
                FileProvider.getUriForFile(context, "${context.packageName}.provider", f)
            )
        }
        if (uris.isEmpty()) return
        val base = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_TEXT, caption)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            val direct = Intent(base).setPackage(pkg)
            if (direct.resolveActivity(context.packageManager) != null) {
                runCatching { context.startActivity(direct) }.onSuccess { return }
            }
        }
        context.startActivity(Intent.createChooser(base, "Share note").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun nearestText(page: NotePage?, off: Offset): NoteText? {
    val texts = page?.texts ?: return null
    return texts.minByOrNull { t ->
        val dx = off.x - t.x
        val dy = off.y - (t.y + t.sizePx / 2f)
        dx * dx + dy * dy
    }?.takeIf { t ->
        // Roughly the label's box: width guessed from character count.
        val w = t.sizePx * 0.62f * t.text.length.coerceAtLeast(1) + 40f
        off.x >= t.x - 40f && off.x <= t.x + w && off.y >= t.y - 40f && off.y <= t.y + t.sizePx + 40f
    }
}

/** Colours offered for a sticky-note label. */
private val TEXT_COLORS = listOf(
    0xFF111111.toInt(), 0xFFD32F2F.toInt(), 0xFF1976D2.toInt(),
    0xFF388E3C.toInt(), 0xFFF57C00.toInt(), 0xFF7B1FA2.toInt(), 0xFFFFFFFF.toInt()
)

@Composable
private fun StickyTextDialog(
    initial: NoteText?,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSave: (String, Int, Float) -> Unit
) {
    var text by remember { mutableStateOf(initial?.text ?: "") }
    var colorInt by remember { mutableStateOf(initial?.colorInt ?: TEXT_COLORS.first()) }
    var sizePx by remember { mutableStateOf(initial?.sizePx ?: 48f) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add text" else "Edit text") },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("Text") },
                    minLines = 2, maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Colour", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 10.dp))
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TEXT_COLORS.forEach { c ->
                        Box(
                            Modifier.size(30.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(Color(c))
                                .border(
                                    if (colorInt == c) 3.dp else 1.dp,
                                    if (colorInt == c) MaterialTheme.colorScheme.primary else Color.Gray,
                                    androidx.compose.foundation.shape.CircleShape
                                )
                                .clickable { colorInt = c }
                        )
                    }
                }
                Text(
                    "Size ${sizePx.toInt()}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 10.dp)
                )
                androidx.compose.material3.Slider(
                    value = sizePx,
                    onValueChange = { sizePx = it },
                    valueRange = 20f..160f
                )
                Text(
                    "Turn the Text switch on and drag the label to place it.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { if (text.isNotBlank()) onSave(text.trim(), colorInt, sizePx) },
                enabled = text.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (initial != null) {
                    androidx.compose.material3.TextButton(onClick = onDelete) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
