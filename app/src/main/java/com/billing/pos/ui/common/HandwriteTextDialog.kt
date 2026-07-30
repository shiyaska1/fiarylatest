package com.billing.pos.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.FilterChip
import com.billing.pos.ink.InkLang
import com.billing.pos.ink.InkRecognizer
import kotlinx.coroutines.launch

/**
 * Full-screen handwriting pad. The user writes; OK recognises the strokes with ML Kit
 * digital ink and returns the text via [onResult]. Used to fill a search box by hand.
 */
@Composable
fun HandwriteTextDialog(onResult: (String) -> Unit, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    // Starts on the language from Settings, but stays switchable here — with handwriting
    // you always know which language you are about to write.
    var lang by remember { mutableStateOf(InkLang.default(context)) }
    val recognizer = remember(lang) { InkRecognizer(lang) }
    DisposableEffect(lang) { onDispose { recognizer.close() } }

    var ready by remember(lang) { mutableStateOf(false) }
    var failed by remember(lang) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    LaunchedEffect(lang) {
        ready = false; failed = false
        ready = recognizer.ensureReady()
        failed = !ready
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).safeDrawingPadding().padding(12.dp)) {
            // Actions on top so the nav bar never covers them.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { strokes.clear() }, enabled = !busy) { Text("Clear") }
                OutlinedButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = {
                        if (strokes.isEmpty()) { onDismiss(); return@Button }
                        busy = true
                        val snapshot = strokes.map { it }
                        scope.launch {
                            val text = recognizer.recognize(snapshot)
                            busy = false
                            onResult(text.trim())
                        }
                    },
                    enabled = ready && !busy, modifier = Modifier.weight(1f)
                ) { Text(if (busy) "Reading…" else "OK") }
            }
            // Language switch — what you write is read as this language, nothing else.
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(InkLang.ENGLISH, InkLang.MALAYALAM).forEach { tag ->
                    FilterChip(
                        selected = lang == tag,
                        onClick = { if (lang != tag && !busy) { strokes.clear(); lang = tag } },
                        enabled = !busy,
                        label = { Text(InkLang.label(tag)) }
                    )
                }
            }
            Text(
                when {
                    failed -> "Could not prepare ${InkLang.label(lang)} handwriting. It needs internet " +
                        "the first time only — connect once, then it works offline."
                    !ready -> "Preparing ${InkLang.label(lang)} handwriting… (first time needs internet)"
                    else -> "Write in ${InkLang.label(lang)}, then tap OK"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(vertical = 6.dp)
            )
            Box(
                Modifier.fillMaxWidth().weight(1f)
                    .background(Color(0xFFF7F7F7), RoundedCornerShape(10.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
            ) {
                var live by remember { mutableStateOf<List<Offset>>(emptyList()) }
                Canvas(
                    Modifier.fillMaxSize().pointerInput(ready) {
                        if (!ready) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            var pts = listOf(down.position)
                            live = pts; down.consume()
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull() ?: break
                                if (c.pressed) { pts = pts + c.position; live = pts; c.consume() }
                                else { strokes.add(pts); live = emptyList(); break }
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
                            drawPath(path, color = Color(0xFF111111), style = Stroke(width = 5f))
                        } else if (pts.size == 1) drawCircle(Color(0xFF111111), radius = 2.5f, center = pts[0])
                    }
                }
                if (busy) Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.padding(16.dp)) }
            }
        }
    }
}
