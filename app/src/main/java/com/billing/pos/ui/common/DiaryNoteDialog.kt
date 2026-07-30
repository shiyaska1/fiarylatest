package com.billing.pos.ui.common

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Full-screen note editor that saves straight into the diary.
 *
 * The note can be typed, handwritten, or read off a photo (camera or gallery, drawing a box
 * around the text) — the same three inputs used everywhere else in the app.
 *
 * [heading] is shown above the box and becomes the start of the diary title; the caller
 * appends the date and time.
 */
@Composable
fun DiaryNoteDialog(
    heading: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var note by remember { mutableStateOf("") }
    var drawing by remember { mutableStateOf(false) }
    var regionUri by remember { mutableStateOf<android.net.Uri?>(null) }

    fun append(text: String) {
        if (text.isBlank()) return
        note = if (note.isBlank()) text.trim() else (note.trimEnd() + "\n" + text.trim())
    }

    val camera = com.billing.pos.ocr.rememberImageCamera { uri -> regionUri = uri }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) regionUri = uri
    }

    if (drawing) {
        HandwriteTextDialog(
            onResult = { append(it); drawing = false },
            onDismiss = { drawing = false }
        )
    }
    regionUri?.let { u ->
        RegionOcrDialog(
            uri = u,
            onResult = { append(it); regionUri = null },
            onDismiss = { regionUri = null }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Column(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .safeDrawingPadding()
                .imePadding()
        ) {
            // Actions on top, clear of the phone's navigation bar.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = { onSave(note.trim()); onDismiss() },
                    modifier = Modifier.weight(1.4f)
                ) { Text("Save to diary") }
            }
            Divider()

            Text(
                heading,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
            )

            // Write by hand, or read the note off a photo.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { drawing = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Draw, contentDescription = null); Text(" Draw")
                }
                OutlinedButton(onClick = { camera() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.PhotoCamera, contentDescription = null); Text(" Camera")
                }
                OutlinedButton(
                    onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.PhotoLibrary, contentDescription = null); Text(" Gallery")
                }
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                placeholder = { Text("Write the note…") },
                modifier = Modifier.fillMaxSize().padding(14.dp)
            )
        }
    }
}
