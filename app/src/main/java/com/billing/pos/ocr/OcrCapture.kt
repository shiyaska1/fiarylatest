package com.billing.pos.ocr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.billing.pos.data.AppPrefs
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Offline OCR. Latin goes through ML Kit's bundled recognizer; Malayalam goes through
 * Tesseract, because ML Kit has no Malayalam model. Which one runs is the user's
 * "OCR language" setting — every OCR screen in the app calls through here, so the
 * setting applies everywhere at once.
 */
object TextOcr {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** ML Kit only — the Latin path. */
    private suspend fun latinLines(context: Context, uri: Uri): List<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val image = InputImage.fromFilePath(context, uri)
                val result = Tasks.await(recognizer.process(image))
                result.textBlocks.flatMap { block -> block.lines.map { it.text } }
            }.getOrDefault(emptyList())
        }

    /**
     * Latin OCR on a Malayalam photo doesn't fail — it returns a few stray marks it
     * mistook for letters. Treat that as "nothing found" so Auto falls through to
     * Tesseract instead of filling the box with rubbish.
     */
    private fun looksEmpty(lines: List<String>): Boolean =
        lines.sumOf { line -> line.count { it.isLetterOrDigit() } } < 3

    /**
     * All recognized text lines (roughly top-to-bottom), or empty on failure.
     *
     * [lang] forces a language for this one call — that is what the per-scan language
     * chooser passes. Null falls back to the Settings default.
     */
    suspend fun lines(context: Context, uri: Uri, lang: String? = null): List<String> =
        readLines(context, uri, singleLine = false, lang = lang)

    /** The whole recognized text collapsed to a single trimmed line. */
    suspend fun singleLine(context: Context, uri: Uri, lang: String? = null): String =
        readLines(context, uri, singleLine = true, lang = lang)
            .joinToString(" ").replace(Regex("\\s+"), " ").trim()

    private suspend fun readLines(
        context: Context,
        uri: Uri,
        singleLine: Boolean,
        lang: String? = null
    ): List<String> =
        when (lang ?: AppPrefs(context).ocrLanguage) {
            AppPrefs.OCR_MALAYALAM -> TesseractOcr.text(context, uri, singleLine)
            AppPrefs.OCR_AUTO -> {
                val latin = latinLines(context, uri)
                if (looksEmpty(latin)) TesseractOcr.text(context, uri, singleLine) else latin
            }
            else -> latinLines(context, uri)
        }
}

/**
 * Camera capture that writes a full-resolution photo into app storage and hands back its Uri.
 * Requests CAMERA permission first. Returns a lambda that launches the flow.
 */
@Composable
fun rememberImageCamera(onImage: (Uri) -> Unit): () -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Uri?>(null) }
    val capture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = pending; pending = null
        if (ok && u != null) onImage(u)
    }
    fun launchCamera() {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "cap_${System.nanoTime()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        pending = uri
        runCatching { capture.launch(uri) }
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera()
    }
    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            launchCamera()
        else perm.launch(Manifest.permission.CAMERA)
    }
}

/**
 * Photograph an item name → OCR → single line of text.
 *
 * Asks which language to read before the camera opens. These flows fill the field the
 * moment the photo is taken — there is no box to draw and no review — so the language
 * has to be settled up front.
 */
@Composable
fun rememberNameScanner(onText: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var lang by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf(false) }

    val camera = rememberImageCamera { uri ->
        scope.launch {
            val text = TextOcr.singleLine(context, uri, lang)
            if (text.isNotBlank()) onText(text)
        }
    }
    if (asking) {
        com.billing.pos.ui.common.OcrLanguageAskDialog(
            onPick = { picked -> lang = picked; asking = false; camera() },
            onDismiss = { asking = false }
        )
    }
    return { asking = true }
}

/** Photograph a printed list → OCR → all lines (for the bulk item-list import). */
@Composable
fun rememberListScanner(onLines: (List<String>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var lang by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf(false) }

    val camera = rememberImageCamera { uri ->
        scope.launch { onLines(TextOcr.lines(context, uri, lang)) }
    }
    if (asking) {
        com.billing.pos.ui.common.OcrLanguageAskDialog(
            onPick = { picked -> lang = picked; asking = false; camera() },
            onDismiss = { asking = false }
        )
    }
    return { asking = true }
}
