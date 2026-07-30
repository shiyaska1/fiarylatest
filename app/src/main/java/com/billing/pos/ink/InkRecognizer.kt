package com.billing.pos.ink

import android.content.Context
import androidx.compose.ui.geometry.Offset
import com.billing.pos.data.AppPrefs
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The handwriting languages offered on the drawing pad. */
object InkLang {
    const val ENGLISH = "en-US"
    const val MALAYALAM = "ml"

    /**
     * Which language the pad should start on, taken from the OCR language setting.
     * "Auto" can't apply to handwriting — nothing has been written yet to detect — so it
     * starts on English and the user can switch on the pad itself.
     */
    fun default(context: Context): String =
        if (AppPrefs(context).ocrLanguage == AppPrefs.OCR_MALAYALAM) MALAYALAM else ENGLISH

    fun label(tag: String): String = if (tag == MALAYALAM) "മലയാളം" else "English"
}

/**
 * Thin wrapper around ML Kit Digital Ink.
 *
 * Unlike the camera OCR — where ML Kit has no Malayalam model at all — digital ink does
 * support Malayalam, so handwriting works in both languages through the same API; only
 * the model identifier changes.
 *
 * Works fully offline once the model for [languageTag] has been downloaded. The first
 * use of each language fetches its model (~20 MB), which needs internet that one time.
 */
class InkRecognizer(private val languageTag: String = InkLang.ENGLISH) {

    private val model: DigitalInkRecognitionModel? =
        DigitalInkRecognitionModelIdentifier.fromLanguageTag(languageTag)
            ?.let { DigitalInkRecognitionModel.builder(it).build() }

    private val recognizer: DigitalInkRecognizer? = model?.let {
        DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(it).build())
    }

    private val remote = RemoteModelManager.getInstance()

    /** True when this language's model is already on the device (no internet needed). */
    suspend fun isDownloaded(): Boolean = withContext(Dispatchers.IO) {
        val m = model ?: return@withContext false
        runCatching { Tasks.await(remote.isModelDownloaded(m)) }.getOrDefault(false)
    }

    /** Downloads the recognition model if needed. Returns true when ready to recognize. */
    suspend fun ensureReady(): Boolean = withContext(Dispatchers.IO) {
        val m = model ?: return@withContext false
        if (runCatching { Tasks.await(remote.isModelDownloaded(m)) }.getOrDefault(false)) {
            return@withContext true
        }
        runCatching {
            Tasks.await(remote.download(m, DownloadConditions.Builder().build()))
        }.isSuccess
    }

    /** Recognizes the best text candidate for the given strokes (empty on failure). */
    suspend fun recognize(strokes: List<List<Offset>>): String = withContext(Dispatchers.IO) {
        val r = recognizer ?: return@withContext ""
        val usable = strokes.filter { it.isNotEmpty() }
        if (usable.isEmpty()) return@withContext ""
        val ink = buildInk(usable)
        runCatching {
            val result = Tasks.await(r.recognize(ink))
            result.candidates.firstOrNull()?.text ?: ""
        }.getOrDefault("")
    }

    fun close() { runCatching { recognizer?.close() } }

    private fun buildInk(strokes: List<List<Offset>>): Ink {
        val ink = Ink.builder()
        strokes.forEach { pts ->
            val stroke = Ink.Stroke.builder()
            pts.forEach { p -> stroke.addPoint(Ink.Point.create(p.x, p.y)) }
            ink.addStroke(stroke.build())
        }
        return ink.build()
    }
}
