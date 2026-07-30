package com.billing.pos.ui.common

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

/** Decodes an image file into a downsampled ImageBitmap, or null if it can't be read. */
fun decodeThumbnail(path: String, reqPx: Int = 600): ImageBitmap? {
    val file = File(path)
    if (!file.exists() || file.length() == 0L) return null
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        var sample = 1
        while (longest / sample > reqPx) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
    } catch (e: Exception) {
        null
    }
}

/** Remembers a decoded thumbnail keyed by path so it decodes once per file. */
@Composable
fun rememberThumbnail(path: String, reqPx: Int = 600): ImageBitmap? =
    remember(path, reqPx) { decodeThumbnail(path, reqPx) }
