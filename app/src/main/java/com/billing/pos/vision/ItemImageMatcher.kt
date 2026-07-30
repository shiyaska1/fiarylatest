package com.billing.pos.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.billing.pos.data.AppDatabase
import com.billing.pos.data.ItemAttachment
import com.billing.pos.data.ItemPhotoVector
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * "Find this item by photo": compares a photo against the photos attached to items.
 *
 * Each photo is turned into a fingerprint (an embedding) by a small on-device MobileNet, and
 * matching is the cosine similarity between fingerprints. Fingerprints are cached in the
 * database keyed by file path + modified time, so the model only runs on photos it has not
 * seen — the first search after adding photos is the slow one.
 *
 * Everything runs on the phone; no image leaves the device.
 */
object ItemImageMatcher {

    private const val MODEL = "models/item_embedder.tflite"

    /** Below this the match is too weak to be worth showing. */
    const val MIN_SCORE = 0.55f

    data class Match(val itemId: Long, val score: Float)

    @Volatile private var embedder: ImageEmbedder? = null

    private fun embedder(context: Context): ImageEmbedder? {
        embedder?.let { return it }
        return synchronized(this) {
            embedder ?: runCatching {
                val options = ImageEmbedder.ImageEmbedderOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
                    .setQuantize(false)
                    .setRunningMode(RunningMode.IMAGE)
                    .build()
                ImageEmbedder.createFromOptions(context.applicationContext, options)
            }.getOrNull()?.also { embedder = it }
        }
    }

    /** Fingerprints one bitmap. Null when the model could not be loaded. */
    private fun embed(context: Context, bitmap: Bitmap): FloatArray? = runCatching {
        val e = embedder(context) ?: return null
        val result = e.embed(BitmapImageBuilder(bitmap).build())
        result.embeddingResult().embeddings().firstOrNull()?.floatEmbedding()
    }.getOrNull()

    /** Decodes at a modest size — the model works on a small square anyway. */
    private fun decode(context: Context, uri: Uri): Bitmap? = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        decodeBytes(bytes)
    }.getOrNull()

    private fun decodeFile(path: String): Bitmap? =
        runCatching { decodeBytes(File(path).readBytes()) }.getOrNull()

    private fun decodeBytes(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 640) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }

    /**
     * Brings the fingerprint cache up to date for [attachments] (image ones only), then
     * ranks items against [photo]. [onProgress] reports indexing as done/total.
     */
    suspend fun search(
        context: Context,
        photo: Uri,
        attachments: List<ItemAttachment>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): List<Match> = withContext(Dispatchers.IO) {
        val query = decode(context, photo)?.let { embed(context, it) } ?: return@withContext emptyList()

        val dao = AppDatabase.get(context).itemPhotoVectorDao()
        val cached = dao.all().associateBy { it.path }
        val photos = attachments.filter { it.mime.startsWith("image/") && it.path.isNotBlank() }

        val stale = photos.filter { att ->
            val f = File(att.path)
            f.exists() && cached[att.path]?.stamp != f.lastModified()
        }
        stale.forEachIndexed { i, att ->
            onProgress(i, stale.size)
            val bmp = decodeFile(att.path) ?: return@forEachIndexed
            val v = embed(context, bmp) ?: return@forEachIndexed
            bmp.recycle()
            dao.upsert(ItemPhotoVector(att.path, att.itemId, toBytes(v), File(att.path).lastModified()))
        }
        onProgress(stale.size, stale.size)

        // Best score per item — an item can have several photos.
        val best = HashMap<Long, Float>()
        dao.all().forEach { row ->
            val score = cosine(query, toFloats(row.vec))
            val prev = best[row.itemId]
            if (prev == null || score > prev) best[row.itemId] = score
        }
        best.entries
            .filter { it.value >= MIN_SCORE }
            .sortedByDescending { it.value }
            .map { Match(it.key, it.value) }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || a.size != b.size) return 0f
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 0f
        return (dot / (Math.sqrt(na) * Math.sqrt(nb))).toFloat()
    }

    private fun toBytes(v: FloatArray): ByteArray {
        val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { bb.putFloat(it) }
        return bb.array()
    }

    private fun toFloats(b: ByteArray): FloatArray {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.size / 4) { bb.float }
    }
}
