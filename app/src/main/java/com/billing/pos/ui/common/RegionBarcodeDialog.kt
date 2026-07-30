package com.billing.pos.ui.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Shows [uri] full-screen, lets the user drag a rectangle over the barcode, and on OK decodes
 * ONLY the barcode inside that rectangle. Falls back to the whole image if no box is drawn.
 * Reading from a still photo is more reliable than a fast live-camera scan for worn/curved labels.
 */
@Composable
fun RegionBarcodeDialog(uri: Uri, onResult: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bitmap = remember(uri) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt)
                var s = 1
                while (opt.outWidth / s > 2000 || opt.outHeight / s > 2000) s *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
            }
        }.getOrNull()
    }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var start by remember { mutableStateOf<Offset?>(null) }
    var end by remember { mutableStateOf<Offset?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).safeDrawingPadding().padding(8.dp)) {
            // Actions on top so the phone's navigation bar can never cover them.
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = {
                        val bmp = bitmap ?: run { onDismiss(); return@Button }
                        busy = true; error = null
                        scope.launch {
                            val code = withContext(Dispatchers.IO) {
                                val cropped = cropToSelection(bmp, start, end, canvasSize)
                                val text = decodeBarcode(cropped)
                                if (cropped !== bmp) cropped.recycle()
                                text
                            }
                            busy = false
                            if (code != null) onResult(code) else error = "No barcode found — try a tighter box or clearer photo"
                        }
                    },
                    enabled = !busy, modifier = Modifier.weight(1f)
                ) { Text(if (busy) "Reading…" else "OK") }
            }
            Text("Drag a box around the barcode, then tap OK", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp)) }
            Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                if (bitmap == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Could not open image", color = Color.White) }
                else Canvas(
                    Modifier.fillMaxSize()
                        .onSizeChanged { canvasSize = it }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { start = it; end = it },
                                onDrag = { change, _ -> end = change.position; change.consume() }
                            )
                        }
                ) {
                    val bw = bitmap.width.toFloat(); val bh = bitmap.height.toFloat()
                    val scale = min(size.width / bw, size.height / bh)
                    val dw = bw * scale; val dh = bh * scale
                    val ox = (size.width - dw) / 2f; val oy = (size.height - dh) / 2f
                    drawImage(bitmap.asImageBitmap(), srcOffset = IntOffset.Zero, srcSize = IntSize(bitmap.width, bitmap.height),
                        dstOffset = IntOffset(ox.toInt(), oy.toInt()), dstSize = IntSize(dw.toInt(), dh.toInt()))
                    val s = start; val e = end
                    if (s != null && e != null) {
                        val l = min(s.x, e.x); val t = min(s.y, e.y); val r = max(s.x, e.x); val b = max(s.y, e.y)
                        drawRect(Color(0xFF00E5FF), topLeft = Offset(l, t), size = Size(r - l, b - t), style = Stroke(width = 4f))
                    }
                }
            }
        }
    }
}

/** Decodes a 1D/2D barcode from [bmp] with ZXing; tries a few scales and inverted colours. */
private fun decodeBarcode(bmp: Bitmap): String? {
    val hints = mapOf(DecodeHintType.TRY_HARDER to true)
    // Try the crop as-is, then a 2x upscale (helps small/low-res captures).
    for (factor in intArrayOf(1, 2)) {
        val scaled = if (factor == 1) bmp else Bitmap.createScaledBitmap(bmp, bmp.width * factor, bmp.height * factor, true)
        val w = scaled.width; val h = scaled.height
        if (w > 0 && h > 0) {
            val pixels = IntArray(w * h)
            scaled.getPixels(pixels, 0, w, 0, 0, w, h)
            val source = RGBLuminanceSource(w, h, pixels)
            val direct = runCatching { MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text }.getOrNull()
            val text = direct ?: runCatching { MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source.invert())), hints).text }.getOrNull()
            if (scaled !== bmp) scaled.recycle()
            if (!text.isNullOrBlank()) return text
        } else if (scaled !== bmp) scaled.recycle()
    }
    return null
}

/** Maps the on-screen selection rectangle back to bitmap pixels and crops; whole image if none. */
private fun cropToSelection(bmp: Bitmap, start: Offset?, end: Offset?, canvas: IntSize): Bitmap {
    if (start == null || end == null || canvas.width == 0) return bmp
    val bw = bmp.width.toFloat(); val bh = bmp.height.toFloat()
    val scale = min(canvas.width / bw, canvas.height / bh)
    val dw = bw * scale; val dh = bh * scale
    val ox = (canvas.width - dw) / 2f; val oy = (canvas.height - dh) / 2f
    fun toBx(x: Float) = ((x - ox) / scale).coerceIn(0f, bw)
    fun toBy(y: Float) = ((y - oy) / scale).coerceIn(0f, bh)
    val left = min(toBx(start.x), toBx(end.x)).toInt()
    val top = min(toBy(start.y), toBy(end.y)).toInt()
    val right = max(toBx(start.x), toBx(end.x)).toInt()
    val bottom = max(toBy(start.y), toBy(end.y)).toInt()
    val w = (right - left); val h = (bottom - top)
    if (w < 8 || h < 8) return bmp
    return Bitmap.createBitmap(bmp, left, top, w, h)
}
