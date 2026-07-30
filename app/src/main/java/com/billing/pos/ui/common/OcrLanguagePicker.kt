package com.billing.pos.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.billing.pos.data.AppPrefs

/** Shared naming for the two OCR languages, so every screen asks the same way. */
object OcrLang {
    const val ENGLISH = AppPrefs.OCR_ENGLISH
    const val MALAYALAM = AppPrefs.OCR_MALAYALAM

    val choices = listOf(ENGLISH, MALAYALAM)

    fun label(lang: String): String = if (lang == MALAYALAM) "മലയാളം Malayalam" else "English"

    /** Which language the chooser should start on: the Settings default, English for Auto. */
    fun default(context: android.content.Context): String =
        if (AppPrefs(context).ocrLanguage == MALAYALAM) MALAYALAM else ENGLISH
}

/**
 * Asks which language to read before the camera or gallery opens.
 *
 * Used by the scan flows that have no review step — they capture and fill the field
 * straight away, so the language has to be chosen up front.
 */
@Composable
fun OcrLanguageAskDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var lang by remember { mutableStateOf(OcrLang.default(context)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which language?") },
        text = {
            Column {
                Text(
                    "The photo will be read in this language.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                OcrLanguageChips(
                    selected = lang,
                    onSelect = { lang = it },
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        },
        confirmButton = { Button(onClick = { onPick(lang) }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * The same choice as a chip row, for the draw-a-box dialogs. Those already show the
 * photo, so the language sits next to it and can be switched to re-read the same
 * crop without taking the picture again.
 */
@Composable
fun OcrLanguageChips(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OcrLang.choices.forEach { lang ->
            FilterChip(
                selected = selected == lang,
                onClick = { if (enabled) onSelect(lang) },
                enabled = enabled,
                label = { Text(OcrLang.label(lang)) }
            )
        }
    }
}
