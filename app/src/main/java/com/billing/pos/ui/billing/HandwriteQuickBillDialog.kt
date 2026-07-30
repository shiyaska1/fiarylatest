package com.billing.pos.ui.billing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.billing.pos.ink.InkRecognizer
import kotlinx.coroutines.launch

private enum class ModelState { PREPARING, READY, ERROR }

/**
 * Full-screen handwriting Quick Bill. Two boxes — item name and price. Write both,
 * tap Next to recognize + add the line (creating a new master item if needed), then
 * the boxes clear for the next item. Done closes the canvas with the bill ready.
 */
@Composable
fun HandwriteQuickBillDialog(
    onDismiss: () -> Unit,
    onReview: (List<com.billing.pos.ocr.ScannedItem>) -> Unit
) {
    val scope = rememberCoroutineScope()
    val handwriteContext = androidx.compose.ui.platform.LocalContext.current
    val pending = remember { mutableStateListOf<com.billing.pos.ocr.ScannedItem>() }
    // Switchable here, starting from the Settings default — you know which language you
    // are about to write, and each language has its own recognition model.
    var inkLang by remember { mutableStateOf(com.billing.pos.ink.InkLang.default(handwriteContext)) }
    val recognizer = remember(inkLang) { InkRecognizer(inkLang) }
    DisposableEffect(inkLang) { onDispose { recognizer.close() } }

    var modelState by remember(inkLang) { mutableStateOf(ModelState.PREPARING) }
    LaunchedEffect(inkLang) {
        modelState = ModelState.PREPARING
        modelState = if (recognizer.ensureReady()) ModelState.READY else ModelState.ERROR
    }

    val itemStrokes = remember { mutableStateListOf<List<Offset>>() }
    var status by remember { mutableStateOf("Write the item name, then tap Next") }
    var busy by remember { mutableStateOf(false) }

    fun recognizeAndAdd() {
        if (busy) return
        if (itemStrokes.isEmpty()) { status = "Write the item name first"; return }
        busy = true
        status = "Reading your writing…"
        val itemSnapshot = itemStrokes.map { it }
        scope.launch {
            val name = recognizer.recognize(itemSnapshot)
            if (name.isBlank()) { status = "Couldn't read the item name — write it again"; busy = false; return@launch }
            // Price is left blank here and filled in on the review screen.
            pending.add(com.billing.pos.ocr.ScannedItem(name.trim(), 0.0))
            status = "Added \"${name.trim()}\"   •   ${pending.size} to review"
            itemStrokes.clear()
            busy = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        // decorFitsSystemWindows = false so real insets reach the content and the buttons
        // stay clear of the phone's navigation bar.
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .safeDrawingPadding()
                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 24.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Handwrite Bill", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${pending.size} to review", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            // Which language the handwriting is read as.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(com.billing.pos.ink.InkLang.ENGLISH, com.billing.pos.ink.InkLang.MALAYALAM).forEach { tag ->
                    androidx.compose.material3.FilterChip(
                        selected = inkLang == tag,
                        onClick = { if (inkLang != tag && !busy) { itemStrokes.clear(); inkLang = tag } },
                        enabled = !busy,
                        label = { Text(com.billing.pos.ink.InkLang.label(tag)) }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))

            when (modelState) {
                ModelState.PREPARING -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Preparing handwriting… (first time needs internet)", style = MaterialTheme.typography.bodySmall)
                }
                ModelState.ERROR -> Text(
                    "Couldn't download the handwriting model. Connect to the internet once and reopen.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall
                )
                ModelState.READY -> Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            Spacer(Modifier.height(6.dp))

            // One big ITEM canvas — 70% of the height. Price is typed on the review screen.
            DrawSection(
                label = "ITEM  (write the item name)",
                strokes = itemStrokes,
                enabled = modelState == ModelState.READY && !busy,
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.70f)
            )

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { itemStrokes.clear(); status = "Cleared — write again" }, enabled = !busy) {
                    Text("Clear")
                }
                Button(onClick = { recognizeAndAdd() }, enabled = modelState == ModelState.READY && !busy, modifier = Modifier.weight(1f)) {
                    Text(if (busy) "Reading…" else "Next  →")
                }
                TextButton(onClick = { if (pending.isNotEmpty()) onReview(pending.toList()) else onDismiss() }) {
                    Text(if (pending.isNotEmpty()) "Review (${pending.size})" else "Done")
                }
            }
        }
    }
}

@Composable
private fun DrawSection(
    label: String,
    strokes: SnapshotStateList<List<Offset>>,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val strokeColor = MaterialTheme.colorScheme.onSurface
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(2.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFFF7F7F7), RoundedCornerShape(10.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
        ) {
            var live by remember { mutableStateOf<List<Offset>>(emptyList()) }
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(enabled) {
                        if (!enabled) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            var pts = listOf(down.position)
                            live = pts
                            down.consume()
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (change.pressed) {
                                    pts = pts + change.position
                                    live = pts
                                    change.consume()
                                } else {
                                    strokes.add(pts)
                                    live = emptyList()
                                    break
                                }
                            }
                        }
                    }
            ) {
                val all = if (live.isNotEmpty()) strokes + listOf(live) else strokes.toList()
                all.forEach { pts ->
                    if (pts.size >= 2) {
                        val path = Path().apply {
                            moveTo(pts[0].x, pts[0].y)
                            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                        }
                        drawPath(path, color = strokeColor, style = Stroke(width = 5f))
                    } else if (pts.size == 1) {
                        drawCircle(strokeColor, radius = 2.5f, center = pts[0])
                    }
                }
            }
        }
    }
}
