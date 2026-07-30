package com.billing.pos.ui.diary

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.billing.pos.data.AttachmentType
import com.billing.pos.data.BlockType
import com.billing.pos.data.DiaryAttachment
import com.billing.pos.data.DiaryBlock
import com.billing.pos.data.DiaryEntry
import com.billing.pos.data.DiaryRepository
import com.billing.pos.diary.AttachmentStore
import com.billing.pos.diary.ReminderScheduler
import com.billing.pos.ocr.TextOcr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One editable block in the diary body. Text blocks hold typed text; media blocks a file. */
class BlockUi(
    val id: Long,
    val type: BlockType,
    text: String = "",
    val path: String = "",
    val name: String = "",
    val mime: String = "",
    val durationMs: Long = 0
) {
    var text by mutableStateOf(text)
    val isMedia: Boolean get() = type != BlockType.TEXT && type != BlockType.LOCATION
}

class DiaryEditViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = DiaryRepository(app)
    private val posRepo = com.billing.pos.data.Repository(app)

    var loadedId by mutableStateOf(0L); private set
    var title by mutableStateOf("")

    /** Diary category (master row id); 0 = none. */
    var typeId by mutableStateOf(0L)

    /** Optional customer this note is about; blank = personal note. */
    var customerId by mutableStateOf(0L)
    var customerName by mutableStateOf("")
    val customers: kotlinx.coroutines.flow.StateFlow<List<com.billing.pos.data.Customer>> =
        posRepo.customers.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())
    var reminderEnabled by mutableStateOf(false)
    var reminderDaily by mutableStateOf(false)
    var reminderAt by mutableStateOf(System.currentTimeMillis())

    // Text formatting (color 0 = default).
    var titleSize by mutableStateOf(20)
    var titleColor by mutableStateOf(0)
    var titleBold by mutableStateOf(true)
    var titleItalic by mutableStateOf(false)
    var bodySize by mutableStateOf(15)
    var bodyColor by mutableStateOf(0)
    var bodyBold by mutableStateOf(false)
    var bodyItalic by mutableStateOf(false)

    /** The ordered body: text / image / voice / … blocks. */
    val blocks: SnapshotStateList<BlockUi> = mutableStateListOf()
    private val removedFiles = mutableListOf<String>()

    var recording by mutableStateOf(false); private set
    private var voiceRec: com.billing.pos.audio.VoiceRecorder? = null
    private var recordFile: File? = null
    private var recordStart = 0L
    /** When true, the current recording is owned by the background foreground service (APK build). */
    private var recordViaService = false
    private var createdAt = System.currentTimeMillis()
    private var loaded = false

    /** Diary type master, for the searchable dropdown. */
    val types: kotlinx.coroutines.flow.StateFlow<List<com.billing.pos.data.DiaryType>> =
        repo.types.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())

    fun addType(name: String, onAdded: (Long) -> Unit = {}) {
        val n = name.trim()
        if (n.isBlank()) return
        viewModelScope.launch {
            val existing = types.value.firstOrNull { it.name.equals(n, true) }
            val id = existing?.id ?: repo.addType(n)
            typeId = id
            onAdded(id)
        }
    }

    fun renameType(type: com.billing.pos.data.DiaryType, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch { repo.renameType(type, newName) }
    }

    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    /** Combined text of all text blocks, for the read-only search view and previews. */
    fun notesText(): String = blocks.filter { it.type == BlockType.TEXT }.joinToString("\n") { it.text }

    fun load(id: Long) {
        if (loaded) return
        loaded = true
        if (id <= 0) { blocks.add(BlockUi(0, BlockType.TEXT)); return }
        viewModelScope.launch {
            val entry = repo.byId(id) ?: run { blocks.add(BlockUi(0, BlockType.TEXT)); return@launch }
            loadedId = entry.id
            title = entry.title
            typeId = entry.typeId
            customerId = entry.customerId
            customerName = entry.customerName
            reminderEnabled = entry.reminderEnabled
            reminderDaily = entry.reminderDaily
            reminderAt = if (entry.reminderAt > 0) entry.reminderAt else System.currentTimeMillis()
            titleSize = entry.titleSize; titleColor = entry.titleColor
            titleBold = entry.titleBold; titleItalic = entry.titleItalic
            bodySize = entry.bodySize; bodyColor = entry.bodyColor
            bodyBold = entry.bodyBold; bodyItalic = entry.bodyItalic
            createdAt = entry.createdAt

            blocks.clear()
            val stored = repo.blocksFor(entry.id)
            if (stored.isNotEmpty()) {
                stored.forEach { blocks.add(it.toUi()) }
            } else {
                // Legacy entry (pre-blocks): seed from remarks + any old attachments.
                if (entry.remarks.isNotBlank()) blocks.add(BlockUi(0, BlockType.TEXT, entry.remarks))
                repo.attachmentsFor(entry.id).forEach { att ->
                    blocks.add(BlockUi(0, attToBlockType(att.type), if (att.type == AttachmentType.LOCATION) att.path else "", att.path, att.name, att.mime))
                }
            }
            if (blocks.isEmpty()) blocks.add(BlockUi(0, BlockType.TEXT))
        }
    }

    // ---- block edits ----
    fun addTextBlock() { blocks.add(BlockUi(0, BlockType.TEXT)) }

    fun moveUp(index: Int) { if (index > 0) blocks.add(index - 1, blocks.removeAt(index)) }
    fun moveDown(index: Int) { if (index < blocks.size - 1) blocks.add(index + 1, blocks.removeAt(index)) }

    fun removeBlock(index: Int) {
        val b = blocks.getOrNull(index) ?: return
        blocks.removeAt(index)
        if (b.path.isNotBlank()) removedFiles.add(b.path)
        if (blocks.isEmpty()) blocks.add(BlockUi(0, BlockType.TEXT))
    }

    /**
     * Compressed images are added here (from camera or gallery).
     * When [ocr] is true the image is also read with on-device OCR and the recognized
     * text is appended as a text block right below the photo.
     */
    fun addImageUri(context: Context, uri: Uri, ocr: Boolean = false, lang: String? = null) {
        viewModelScope.launch {
            val dest = AttachmentStore.newFile(context, "jpg")
            val ok = withContext(Dispatchers.IO) { AttachmentStore.compressImageTo(context, uri, dest) }
            if (!ok) { message.value = "Could not add image"; return@launch }
            blocks.add(BlockUi(0, BlockType.IMAGE, path = dest.absolutePath, name = "Photo.jpg", mime = "image/jpeg"))
            if (!ocr) return@launch

            message.value = "Reading text…"
            // Read the original (full-resolution) image; fall back to the stored copy.
            var text = TextOcr.lines(context, uri, lang).joinToString("\n").trim()
            if (text.isBlank()) text = TextOcr.lines(context, Uri.fromFile(dest), lang).joinToString("\n").trim()
            if (text.isNotBlank()) {
                blocks.add(BlockUi(0, BlockType.TEXT, text))
                message.value = "Text added to note"
            } else message.value = "No text found in the image"
        }
    }

    fun addFileUris(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) { uris.mapNotNull { AttachmentStore.copyIn(context, it) } }
            added.forEach { att ->
                blocks.add(BlockUi(0, attToBlockType(att.type), path = att.path, name = att.name, mime = att.mime))
            }
        }
    }

    // ---- Attach from a direct file link -------------------------------------------------
    /** Name of the file currently downloading, else null. */
    var downloadName by mutableStateOf<String?>(null); private set
    /** 0f..1f, or -1f when the server didn't report a size (indeterminate). */
    var downloadProgress by mutableStateOf(0f); private set
    var downloading by mutableStateOf(false); private set
    private var downloadJob: kotlinx.coroutines.Job? = null

    /**
     * Downloads a direct file URL (mp3/mp4/pdf/image/…) into the diary as a block.
     * This is a plain HTTP download — the same thing a browser does with a download link.
     */
    fun downloadFromUrl(context: Context, rawUrl: String) {
        val url = rawUrl.trim()
        if (url.isBlank()) return
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            message.value = "Enter a link starting with http:// or https://"
            return
        }
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            downloading = true; downloadProgress = 0f; downloadName = null
            val result = runCatching { withContext(Dispatchers.IO) { fetchToAttachment(context, url) } }
            downloading = false; downloadProgress = 0f
            result.onSuccess { att ->
                blocks.add(BlockUi(0, attToBlockType(att.type), path = att.path, name = att.name, mime = att.mime))
                message.value = "Saved ${att.name}"
            }.onFailure { e ->
                if (e !is kotlinx.coroutines.CancellationException)
                    message.value = e.message ?: "Download failed"
            }
            downloadName = null
        }
    }

    fun cancelDownload() { downloadJob?.cancel(); downloading = false; downloadName = null }

    private suspend fun fetchToAttachment(context: Context, url: String): DiaryAttachment {
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "POSBilling")
        }
        try {
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) throw Exception("Server returned $code")
            val mime = (conn.contentType ?: "application/octet-stream").substringBefore(';').trim()
            if (mime.startsWith("text/html"))
                throw Exception("That link is a web page, not a file. Paste a direct link to the file itself (ending in .mp3, .mp4, .pdf …).")
            val len = conn.contentLengthLong
            val name = fileNameFor(conn.getHeaderField("Content-Disposition"), url, mime)
            val ext = name.substringAfterLast('.', "").ifBlank { "bin" }
            val dest = AttachmentStore.newFile(context, ext)
            downloadName = name
            try {
                conn.inputStream.use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            val r = input.read(buf)
                            if (r == -1) break
                            out.write(buf, 0, r)
                            total += r
                            downloadProgress = if (len > 0) (total.toFloat() / len).coerceIn(0f, 1f) else -1f
                        }
                    }
                }
            } catch (e: Exception) {
                dest.delete(); throw e
            }
            if (!dest.exists() || dest.length() == 0L) { dest.delete(); throw Exception("Nothing was downloaded") }
            return AttachmentStore.fromFile(dest, name, mime)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** Best available file name: Content-Disposition, else the URL path, else by type. */
    private fun fileNameFor(disposition: String?, url: String, mime: String): String {
        disposition?.let { d ->
            Regex("filename\\*?=(?:UTF-8''|\")?([^\";]+)", RegexOption.IGNORE_CASE).find(d)
                ?.groupValues?.get(1)?.trim()?.trim('"')
                ?.let { if (it.isNotBlank()) return java.net.URLDecoder.decode(it, "UTF-8").substringAfterLast('/') }
        }
        val fromPath = runCatching { java.net.URL(url).path.substringAfterLast('/') }.getOrNull().orEmpty()
        if (fromPath.isNotBlank() && fromPath.contains('.')) return fromPath
        val ext = when {
            mime.startsWith("audio/") -> "mp3"; mime.startsWith("video/") -> "mp4"
            mime.startsWith("image/") -> "jpg"; mime == "application/pdf" -> "pdf"; else -> "bin"
        }
        return "download_${System.currentTimeMillis()}.$ext"
    }

    /** Registers a file the camera just wrote (e.g. a video) as a block. */
    fun addCapturedFile(file: File, name: String, mime: String) {
        if (file.exists() && file.length() > 0) {
            blocks.add(BlockUi(0, attToBlockType(AttachmentStore.typeOf(mime)), path = file.absolutePath, name = name, mime = mime))
        } else file.delete()
    }

    /** Appends dictated text to the last text block, or starts a new one. */
    fun appendSpokenToBody(spoken: String) {
        val last = blocks.lastOrNull { it.type == BlockType.TEXT }
        if (last != null) last.text = if (last.text.isBlank()) spoken else "${last.text} $spoken"
        else blocks.add(BlockUi(0, BlockType.TEXT, spoken))
    }

    fun addLocation(lat: Double, lng: Double) {
        val url = "https://maps.google.com/?q=$lat,$lng"
        val label = "Location " + String.format("%.5f, %.5f", lat, lng)
        blocks.add(BlockUi(0, BlockType.LOCATION, text = url, name = label, mime = "text/uri-list"))
    }

    fun setReminderDateTime(millis: Long) { reminderAt = millis }

    fun startRecording(context: Context) {
        if (recording) return
        val file = File(AttachmentStore.dir(context), "voice_${System.nanoTime()}.m4a")
        // In the APK (debug) build a foreground microphone service keeps recording alive while the
        // app is minimised or the screen is off. The Play (release) AAB ships no such service, so it
        // records in-process only — recording ends when the OS suspends the app, as before.
        if (com.billing.pos.BuildConfig.DEBUG) {
            recordFile = file
            recordStart = System.currentTimeMillis()
            recordViaService = true
            recording = true
            com.billing.pos.audio.AudioRecordService.start(context.applicationContext, file.absolutePath)
            return
        }
        try {
            voiceRec = com.billing.pos.audio.VoiceRecorder(file.absolutePath).also { it.start() }
            recordFile = file
            recordStart = System.currentTimeMillis()
            recordViaService = false
            recording = true
        } catch (e: Exception) {
            voiceRec = null
            file.delete()
            message.value = "Could not start recording"
        }
    }

    fun stopRecording() {
        if (recordViaService) {
            val f = recordFile
            val dur = System.currentTimeMillis() - recordStart
            recording = false
            recordViaService = false
            recordFile = null
            com.billing.pos.audio.AudioRecordService.stop(getApplication())
            // The service finalises the file asynchronously; wait briefly for it to flush before adding.
            viewModelScope.launch {
                var tries = 0
                while (com.billing.pos.audio.AudioRecordService.recording && tries < 30) {
                    kotlinx.coroutines.delay(100); tries++
                }
                f?.let {
                    if (it.exists() && it.length() > 0) {
                        blocks.add(BlockUi(0, BlockType.AUDIO, path = it.absolutePath, name = "Voice note.m4a", mime = "audio/mp4", durationMs = dur))
                    } else {
                        message.value = "No voice detected"
                    }
                }
            }
            return
        }
        val rec = voiceRec ?: return
        val f = recordFile
        val dur = System.currentTimeMillis() - recordStart
        recording = false
        voiceRec = null
        recordFile = null
        // VoiceRecorder.stop() blocks until the encoder flushes, so finalise off the main thread.
        viewModelScope.launch {
            withContext(Dispatchers.IO) { rec.stop() }
            f?.let {
                if (it.exists() && it.length() > 0) {
                    blocks.add(BlockUi(0, BlockType.AUDIO, path = it.absolutePath, name = "Voice note.m4a", mime = "audio/mp4", durationMs = dur))
                } else {
                    message.value = "No voice detected"
                }
            }
        }
    }

    /** True while a voice note is being converted, so the UI can show progress. */
    var transcribing by mutableStateOf(false); private set

    /**
     * Converts a recorded voice note to text and inserts it as a text block directly
     * below the recording.
     *
     * This runs on the saved file, not on the microphone: Android silences one of two
     * concurrent capture clients, so listening while MediaRecorder holds the mic would
     * produce either a silent recording or an empty transcript.
     */
    fun transcribeAudio(context: Context, block: BlockUi, languageTag: String) {
        if (transcribing) return
        if (!com.billing.pos.speech.AudioTranscriber.isSupported) {
            message.value = "This phone needs Android 13 to read a saved recording. " +
                "Use the microphone button to dictate instead."
            return
        }
        transcribing = true
        message.value = "Converting speech to text…"
        viewModelScope.launch {
            when (val r = com.billing.pos.speech.AudioTranscriber.transcribe(context, block.path, languageTag)) {
                is com.billing.pos.speech.AudioTranscriber.Result.Text -> {
                    val at = blocks.indexOf(block)
                    val textBlock = BlockUi(0, BlockType.TEXT, r.value)
                    if (at >= 0) blocks.add(at + 1, textBlock) else blocks.add(textBlock)
                    message.value = "Text added below the recording"
                }
                is com.billing.pos.speech.AudioTranscriber.Result.Failed -> message.value = r.reason
            }
            transcribing = false
        }
    }

    fun save(context: Context, onDone: () -> Unit) {
        val remarks = notesText().trim()
        val hasMedia = blocks.any { it.type != BlockType.TEXT }
        if (title.isBlank() && remarks.isBlank() && !hasMedia) {
            message.value = "Nothing to save"
            return
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val entry = DiaryEntry(
                id = loadedId,
                title = title.trim(),
                remarks = remarks,
                createdAt = if (loadedId == 0L) now else createdAt,
                updatedAt = now,
                typeId = typeId,
                customerId = customerId,
                customerName = customerName.trim(),
                reminderEnabled = reminderEnabled,
                reminderAt = if (reminderEnabled) reminderAt else 0L,
                reminderDaily = reminderDaily,
                titleSize = titleSize, titleColor = titleColor, titleBold = titleBold, titleItalic = titleItalic,
                bodySize = bodySize, bodyColor = bodyColor, bodyBold = bodyBold, bodyItalic = bodyItalic
            )
            val id = repo.upsert(entry)
            loadedId = id
            // Drop empty trailing text blocks so we don't persist blank paragraphs.
            val toSave = blocks.filter { it.type != BlockType.TEXT || it.text.isNotBlank() }
            repo.replaceBlocks(id, toSave.mapIndexed { index, b -> b.toEntity(index) })
            withContext(Dispatchers.IO) { removedFiles.forEach { runCatching { File(it).delete() } } }
            removedFiles.clear()
            val scheduled = runCatching { ReminderScheduler.schedule(context, entry.copy(id = id)) }
                .getOrDefault(false)
            message.value =
                if (entry.reminderEnabled && !scheduled) "Saved (reminder could not be set)" else "Saved"
            onDone()
        }
    }

    fun delete(context: Context, onDone: () -> Unit) {
        val id = loadedId
        if (id == 0L) { onDone(); return }
        viewModelScope.launch {
            repo.byId(id)?.let { repo.deleteEntry(it) }
            ReminderScheduler.cancel(context, id)
            onDone()
        }
    }

    override fun onCleared() {
        val rec = voiceRec
        voiceRec = null
        // viewModelScope is already cancelled here; finalise on a detached IO coroutine.
        if (rec != null) kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { runCatching { rec.stop() } }
    }

    private fun DiaryBlock.toUi() = BlockUi(id, type, text, path, name, mime, durationMs)
    private fun BlockUi.toEntity(index: Int) =
        DiaryBlock(id = 0, entryId = 0, position = index, type = type, text = text, path = path, name = name, mime = mime, durationMs = durationMs)

    private fun attToBlockType(t: AttachmentType): BlockType = when (t) {
        AttachmentType.IMAGE -> BlockType.IMAGE
        AttachmentType.VIDEO -> BlockType.VIDEO
        AttachmentType.AUDIO -> BlockType.AUDIO
        AttachmentType.DOCUMENT -> BlockType.DOCUMENT
        AttachmentType.LOCATION -> BlockType.LOCATION
    }
}
