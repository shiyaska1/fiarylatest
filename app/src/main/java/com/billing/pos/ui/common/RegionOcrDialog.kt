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
import com.billing.pos.ocr.TextOcr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Shows [uri] full-screen, lets the user drag a rectangle over it, and on OK OCRs ONLY the
 * text inside that rectangle (so just the item name is read). Falls back to the whole image
 * if no box is drawn.
 */
@Composable
fun RegionOcrDialog(uri: Uri, onResult: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bitmap = remember(uri) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt)
                var s = 1
                while (opt.outWidth / s > 1600 || opt.outHeight / s > 1600) s *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
            }
        }.getOrNull()
    }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var start by remember { mutableStateOf<Offset?>(null) }
    var end by remember { mutableStateOf<Offset?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Asked per scan: the chip row below the actions decides which engine reads the crop.
    var ocrLang by remember { mutableStateOf(OcrLang.default(context)) }

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
                        busy = true
                        scope.launch {
                            val text = withContext(Dispatchers.IO) {
                                val cropped = cropToSelection(bmp, start, end, canvasSize)
                                val f = File(context.cacheDir, "region_ocr.jpg")
                                f.outputStream().use { cropped.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                                if (cropped !== bmp) cropped.recycle()
                                TextOcr.singleLine(context, Uri.fromFile(f), ocrLang).also { f.delete() }
                            }
                            busy = false
                            onResult(text)
                        }
                    },
                    enabled = !busy, modifier = Modifier.weight(1f)
                ) { Text(if (busy) "Reading…" else "OK") }
            }
            OcrLanguageChips(selected = ocrLang, onSelect = { ocrLang = it }, enabled = !busy)
            Text(
                "Drag a box around the item name, then tap OK. Wrong language? Switch above and tap OK again.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp, bottom = 6.dp)
            )
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

/**
 * Like [RegionOcrDialog] but returns every text line inside the box (for reading a list of
 * items). Falls back to the whole image if no box is drawn.
 */
@Composable
fun RegionLinesOcrDialog(uri: Uri, onResult: (List<String>) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bitmap = remember(uri) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt)
                var s = 1
                while (opt.outWidth / s > 1600 || opt.outHeight / s > 1600) s *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
            }
        }.getOrNull()
    }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var start by remember { mutableStateOf<Offset?>(null) }
    var end by remember { mutableStateOf<Offset?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Asked per scan: the chip row below the actions decides which engine reads the crop.
    var ocrLang by remember { mutableStateOf(OcrLang.default(context)) }

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
                        busy = true
                        scope.launch {
                            val lines = withContext(Dispatchers.IO) {
                                val cropped = cropToSelection(bmp, start, end, canvasSize)
                                val f = File(context.cacheDir, "region_ocr_lines.jpg")
                                f.outputStream().use { cropped.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                                if (cropped !== bmp) cropped.recycle()
                                TextOcr.lines(context, Uri.fromFile(f), ocrLang).also { f.delete() }
                            }
                            busy = false
                            onResult(lines)
                        }
                    },
                    enabled = !busy, modifier = Modifier.weight(1f)
                ) { Text(if (busy) "Reading…" else "OK") }
            }
            OcrLanguageChips(selected = ocrLang, onSelect = { ocrLang = it }, enabled = !busy)
            Text(
                "Drag a box around the items, then tap OK. Wrong language? Switch above and tap OK again.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp, bottom = 6.dp)
            )
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
