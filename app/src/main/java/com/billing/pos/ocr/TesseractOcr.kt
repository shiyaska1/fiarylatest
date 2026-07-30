package com.billing.pos.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Malayalam OCR.
 *
 * ML Kit has no Malayalam model — its text recognizer only covers Latin, Chinese,
 * Devanagari, Japanese and Korean — so Malayalam goes through Tesseract instead. The
 * `mal.traineddata` model ships inside the APK (assets/tessdata) and is copied to app
 * storage on first use, because Tesseract reads it from a real path, not from assets.
 * Nothing is downloaded and nothing leaves the phone.
 */
object TesseractOcr {
    private const val LANG = "mal"
    /** Guards the copy + the API itself; TessBaseAPI is not safe to share across threads. */
    private val lock = Any()
    @Volatile private var dataDir: File? = null

    /** Set when the model could not be prepared, so the UI can say why instead of going quiet. */
    @Volatile var lastError: String? = null
        private set

    /**
     * True once the model is on disk and usable. Copies it out of assets on first call.
     *
     * Deliberately avoids AssetManager.openFd() — that throws for any asset the build
     * compressed, which is how this silently returned nothing on every scan before.
     * A marker file records which app build did the copy, so an updated model replaces
     * an older one without an expensive length check on every call.
     */
    private fun ensureModel(context: Context): File? = synchronized(lock) {
        dataDir?.let { return it }
        return runCatching {
            // Tesseract wants <dir>/tessdata/<lang>.traineddata and is given <dir>.
            val root = File(context.filesDir, "tesseract")
            val tessdata = File(root, "tessdata").apply { mkdirs() }
            val target = File(tessdata, "$LANG.traineddata")
            val marker = File(root, "$LANG.version")

            val build = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
            }.getOrDefault("")
            val copied = marker.takeIf { it.exists() }?.readText().orEmpty()

            if (!target.exists() || target.length() < 1_000_000L || copied != build) {
                context.assets.open("tessdata/$LANG.traineddata").use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
                marker.writeText(build)
            }
            if (target.length() < 1_000_000L) error("model copy failed (${target.length()} bytes)")
            lastError = null
            root.also { dataDir = it }
        }.onFailure { lastError = it.message ?: it.javaClass.simpleName }.getOrNull()
    }

    /**
     * Prepares the model and runs a no-op init, so Settings can report a real result
     * instead of the user discovering the problem through a blank item name.
     */
    suspend fun selfTest(context: Context): String? = withContext(Dispatchers.IO) {
        val root = ensureModel(context) ?: return@withContext lastError ?: "model not available"
        synchronized(lock) {
            val api = TessBaseAPI()
            try {
                if (!api.init(root.absolutePath, LANG)) "Tesseract could not load the Malayalam model"
                else null
            } catch (t: Throwable) {
                t.message ?: t.javaClass.simpleName
            } finally {
                runCatching { api.recycle() }
            }
        }
    }

    /**
     * Recognises Malayalam text in [uri].
     *
     * [singleLine] picks the page-segmentation mode: a cropped box holding one item name
     * reads far better as SINGLE_LINE, while a whole printed list needs AUTO.
     */
    suspend fun text(context: Context, uri: Uri, singleLine: Boolean): List<String> =
        withContext(Dispatchers.IO) {
            val root = ensureModel(context) ?: return@withContext emptyList()
            val bitmap = decodeUpright(context, uri) ?: return@withContext emptyList()
            synchronized(lock) {
                val api = TessBaseAPI()
                try {
                    if (!api.init(root.absolutePath, LANG)) return@withContext emptyList()
                    api.pageSegMode =
                        if (singleLine) TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
                        else TessBaseAPI.PageSegMode.PSM_AUTO
                    api.setImage(bitmap)
                    val out = api.utF8Text.orEmpty()
                    out.split('\n').map { it.trim() }.filter { it.isNotBlank() }
                } catch (t: Throwable) {
                    emptyList()
                } finally {
                    runCatching { api.recycle() }
                    bitmap.recycle()
                }
            }
        }

    /**
     * Decodes [uri] at a size Tesseract works well with and applies the EXIF rotation —
     * a sideways photo otherwise recognises as nothing at all.
     */
    private fun decodeUpright(context: Context, uri: Uri): Bitmap? = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        // Tesseract likes roughly 1500px on the long edge; bigger is slower, not better.
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null

        val degrees = runCatching {
            when (bytes.inputStream().use { ExifInterface(it) }
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)

        val upright =
            if (degrees == 0f) bmp
            else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
                .also { if (it != bmp) bmp.recycle() }

        upscaleIfSmall(upright)
    }.getOrNull()

    /**
     * A hand-drawn box around one item name can be only a couple of hundred pixels wide.
     * Tesseract needs roughly 300 DPI worth of pixels per character and reads almost
     * nothing below that, so small crops are enlarged before recognition. Costs a little
     * time on an image that is small by definition.
     */
    private fun upscaleIfSmall(bmp: Bitmap): Bitmap {
        val longEdge = maxOf(bmp.width, bmp.height)
        if (longEdge >= 1000 || longEdge == 0) return bmp
        val factor = minOf(4f, 1000f / longEdge)
        val scaled = Bitmap.createScaledBitmap(
            bmp, (bmp.width * factor).toInt().coerceAtLeast(1),
            (bmp.height * factor).toInt().coerceAtLeast(1), true
        )
        if (scaled != bmp) bmp.recycle()
        return scaled
    }
}
