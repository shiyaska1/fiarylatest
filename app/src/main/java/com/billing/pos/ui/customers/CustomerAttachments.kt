package com.billing.pos.ui.customers

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.billing.pos.data.CustomerAttachment
import com.billing.pos.data.CustomerAttachmentStore
import com.billing.pos.data.DownloadSaver
import java.io.File

/**
 * Documents filed against a customer — photos or any file.
 *
 * The list belongs to the caller: a brand-new customer has no id yet, so the rows are
 * written afterwards with the id the save returned.
 */
@Composable
fun CustomerAttachments(
    attachments: SnapshotStateList<CustomerAttachment>,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    // Images open in the app's own zoomable viewer rather than handing off to a gallery app.
    // The index matters: without it every row opened the first picture.
    var viewing by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }

    fun addFrom(uri: android.net.Uri?) {
        if (uri == null) return
        CustomerAttachmentStore.copyIn(context, uri)?.let { attachments.add(it) }
    }

    val camera = com.billing.pos.ocr.rememberImageCamera { uri -> addFrom(uri) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { addFrom(it) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { addFrom(it) }

    viewing?.let { (paths, start) ->
        com.billing.pos.ui.common.ImageViewerDialog(
            paths = paths,
            startIndex = start,
            onDismiss = { viewing = null }
        )
    }

    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text("Attachments", style = MaterialTheme.typography.labelLarge)
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedButton(onClick = { camera() }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.PhotoCamera, "Camera", Modifier.size(18.dp))
            }
            OutlinedButton(
                onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                modifier = Modifier.weight(1f)
            ) { Icon(Icons.Filled.PhotoLibrary, "Gallery", Modifier.size(18.dp)) }
            OutlinedButton(
                onClick = { filePicker.launch(arrayOf("*/*")) },
                modifier = Modifier.weight(1f)
            ) { Icon(Icons.Filled.UploadFile, "File", Modifier.size(18.dp)) }
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
                        // Images stay in the app so they can be pinched and zoomed. The
                        // viewer opens on the one that was tapped, not the first one.
                        if (att.isImage) {
                            val images = attachments.filter { it.isImage }
                            viewing = images.map { it.path } to images.indexOf(att).coerceAtLeast(0)
                        } else openAttachment(context, att)
                    },
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
                IconButton(onClick = { onMessage(downloadAttachment(context, att)) }) {
                    Icon(Icons.Filled.Download, "Download", Modifier.size(20.dp))
                }
                IconButton(onClick = { shareAttachment(context, att) }) {
                    Icon(Icons.Filled.Share, "Share", Modifier.size(20.dp))
                }
                IconButton(onClick = { attachments.removeAt(i) }) {
                    Icon(Icons.Filled.Delete, "Remove", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** Copies the file into the phone's Downloads folder. */
private fun downloadAttachment(context: android.content.Context, att: CustomerAttachment): String {
    val f = File(att.path)
    if (!f.exists()) return "File is missing"
    return if (DownloadSaver.save(context, f, att.name, att.mime.ifBlank { "*/*" }))
        "Saved to Downloads: ${att.name}" else "Could not save the file"
}

private fun shareAttachment(context: android.content.Context, att: CustomerAttachment) {
    runCatching {
        val f = File(att.path)
        if (!f.exists()) return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", f)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = att.mime.ifBlank { "*/*" }
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            )
        }
        context.startActivity(
            android.content.Intent.createChooser(send, "Share attachment")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** Opens a non-image attachment with whatever app handles that type. */
private fun openAttachment(context: android.content.Context, att: CustomerAttachment) {
    runCatching {
        val f = File(att.path)
        if (!f.exists()) return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", f)
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, att.mime.ifBlank { "*/*" })
                addFlags(
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                )
            }
        )
    }
}
