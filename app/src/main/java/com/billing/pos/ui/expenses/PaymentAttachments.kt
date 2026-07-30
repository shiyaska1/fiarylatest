package com.billing.pos.ui.expenses

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.billing.pos.data.ExpenseAttachment
import com.billing.pos.expenses.ExpenseAttachmentStore
import java.io.File

/**
 * Voice / photo / file attachments for a payment.
 *
 * The list belongs to the caller so it can be saved with the payment: a new payment has no
 * id until it is saved, so the rows are written afterwards using the id the save returned.
 */
@Composable
fun PaymentAttachments(
    attachments: SnapshotStateList<ExpenseAttachment>,
    enabled: Boolean
) {
    val context = LocalContext.current
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordFile by remember { mutableStateOf<File?>(null) }
    var recording by remember { mutableStateOf(false) }
    var playingPath by remember { mutableStateOf<String?>(null) }
    val player = remember { MediaPlayer() }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { player.stop() }
            runCatching { player.release() }
            runCatching { recorder?.release() }
        }
    }

    fun addFrom(uri: android.net.Uri?) {
        if (uri == null) return
        ExpenseAttachmentStore.copyIn(context, uri)?.let { attachments.add(it) }
    }

    val camera = com.billing.pos.ocr.rememberImageCamera { uri -> addFrom(uri) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { addFrom(it) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { addFrom(it) }

    fun startRecording() {
        val file = ExpenseAttachmentStore.newVoiceFile(context)
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
        else @Suppress("DEPRECATION") MediaRecorder()
        runCatching {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            recordFile = file
            recording = true
        }.onFailure {
            runCatching { rec.release() }
            file.delete()
        }
    }

    fun stopRecording() {
        val rec = recorder ?: return
        runCatching { rec.stop() }
        runCatching { rec.release() }
        recorder = null
        recording = false
        recordFile?.let { f ->
            if (f.exists() && f.length() > 0) {
                attachments.add(ExpenseAttachmentStore.fromFile(f, "Voice note.m4a", "audio/mp4"))
            }
        }
        recordFile = null
    }

    fun togglePlay(path: String) {
        runCatching {
            if (playingPath == path) {
                player.stop(); player.reset(); playingPath = null
                return
            }
            player.reset()
            player.setDataSource(path)
            player.prepare()
            player.start()
            playingPath = path
            player.setOnCompletionListener { playingPath = null }
        }
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startRecording() }

    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text("Attachments", style = MaterialTheme.typography.labelLarge)
        if (enabled) {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        if (recording) stopRecording()
                        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED
                        ) startRecording()
                        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(if (recording) "Stop" else "Voice", style = MaterialTheme.typography.labelMedium) }

                OutlinedButton(onClick = { camera() }, modifier = Modifier.weight(1f)) {
                    Text("Camera", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.weight(1f)
                ) { Text("Image", style = MaterialTheme.typography.labelMedium) }
                OutlinedButton(
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                    modifier = Modifier.weight(1f)
                ) { Text("File", style = MaterialTheme.typography.labelMedium) }
            }
            if (recording) {
                Text(
                    "● Recording…",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        attachments.forEachIndexed { i, att ->
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    att.name,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).clickable {
                        if (att.isAudio) togglePlay(att.path) else openAttachment(context, att)
                    },
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
                if (att.isAudio) {
                    IconButton(onClick = { togglePlay(att.path) }) {
                        Icon(
                            if (playingPath == att.path) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = if (playingPath == att.path) "Stop" else "Play"
                        )
                    }
                }
                if (enabled) {
                    IconButton(onClick = { attachments.removeAt(i) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

/** Opens a non-audio attachment with whatever app handles that type. */
private fun openAttachment(context: android.content.Context, att: ExpenseAttachment) {
    runCatching {
        val f = File(att.path)
        if (!f.exists()) return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", f)
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, att.mime.ifBlank { "*/*" })
            addFlags(
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            )
        }
        context.startActivity(intent)
    }
}
