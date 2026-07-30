package com.billing.pos.ui.common

import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.billing.pos.data.AppPrefs
import com.billing.pos.pdf.DocumentPdf
import com.billing.pos.pdf.PdfDoc

/**
 * A "PDF" TopAppBar action offering **Share / Print** and **Save to Downloads** for an A4 document.
 * [buildDoc] returns the current document to render, or null (with a message) if it's not ready.
 */
@Composable
fun DocumentPdfAction(onMessage: (String) -> Unit, buildDoc: () -> PdfDoc?) {
    val context = LocalContext.current
    val download = rememberPdfDownloader(onMessage)
    var menu by remember { mutableStateOf(false) }

    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.PictureAsPdf, contentDescription = "PDF") }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        DropdownMenuItem(text = { Text("Share / Print PDF") }, onClick = {
            menu = false
            val doc = buildDoc()
            if (doc != null) {
                val company = AppPrefs(context).company
                val uri = DocumentPdf.make(context, company, doc)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching {
                    context.startActivity(Intent.createChooser(intent, "Share ${doc.docTitle}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { onMessage("Could not open share sheet") }
            }
        })
        DropdownMenuItem(text = { Text("Save to Downloads") }, onClick = {
            menu = false
            val doc = buildDoc()
            if (doc != null) download { DocumentPdf.file(context, AppPrefs(context).company, doc) }
        })
    }
}
