package com.billing.pos.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.billing.pos.util.RichText
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Full-screen description for one line of a voucher.
 *
 * Close and "Add to item list" sit in the top bar, clear of the phone's navigation bar.
 * The text is not shown inline in the item list once saved — the line keeps a note icon
 * instead, and tapping it reopens this editor with the text still there.
 */
@Composable
fun ItemNoteDialog(
    itemName: String,
    initialNote: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialNote) }

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
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Close") }
                Button(
                    onClick = { onSave(text.trim()); onDismiss() },
                    modifier = Modifier.weight(1.6f)
                ) { Text("Add to item list") }
            }
            Divider()

            Text(
                itemName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
            )
            Text(
                "This description prints under the item name on the quotation and its PDF.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 14.dp)
            )

            RichTextEditor(
                value = text,
                onValueChange = { text = it },
                placeholder = "Write the description…",
                modifier = Modifier.fillMaxSize().padding(14.dp)
            )
        }
    }
}

/**
 * Full-screen formatted-text editor, used for anything longer than one line — the
 * quotation's terms and conditions, for instance.
 */
@Composable
fun RichTextFullScreen(
    heading: String,
    hint: String,
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }

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
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = { onSave(text.trim()); onDismiss() },
                    modifier = Modifier.weight(1.6f)
                ) { Text("Done") }
            }
            Divider()
            Text(
                heading,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
            )
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 14.dp)
            )
            RichTextEditor(
                value = text,
                onValueChange = { text = it },
                placeholder = "Write here…",
                modifier = Modifier.fillMaxSize().padding(14.dp)
            )
        }
    }
}

/**
 * A text box with bold / italic / bigger / smaller buttons that act on whatever is selected.
 *
 * The formatting is kept as markers in the text itself (see [com.billing.pos.util.RichText]),
 * so the preview underneath shows what will actually be printed.
 */
@Composable
fun RichTextEditor(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    var field by remember { mutableStateOf(TextFieldValue(value)) }
    // Keep in step when the caller replaces the text (loading a saved note, for instance).
    if (field.text != value && field.text.isBlank() && value.isNotBlank()) {
        field = TextFieldValue(value)
    }

    fun apply(transform: (String, Int, Int) -> Pair<String, IntRange>) {
        val sel = field.selection
        val (out, range) = transform(field.text, minOf(sel.start, sel.end), maxOf(sel.start, sel.end))
        field = TextFieldValue(out, TextRange(range.first, range.last))
        onValueChange(out)
    }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = { apply { t, s, e -> RichText.toggleWrap(t, s, e, "*") } },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) { Text("B", fontWeight = FontWeight.Bold) }
            OutlinedButton(
                onClick = { apply { t, s, e -> RichText.toggleWrap(t, s, e, "_") } },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) { Text("I", fontStyle = FontStyle.Italic) }
            OutlinedButton(
                onClick = { apply { t, s, e -> RichText.stepSize(t, s, e, 3f) } },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) { Text("A+") }
            OutlinedButton(
                onClick = { apply { t, s, e -> RichText.stepSize(t, s, e, -3f) } },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) { Text("A−") }
            Text(
                "select text first",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }

        OutlinedTextField(
            value = field,
            onValueChange = { field = it; onValueChange(it.text) },
            placeholder = { Text(placeholder) },
            modifier = Modifier.fillMaxWidth().weight(1f)
        )

        if (value.isNotBlank()) {
            Text(
                "Preview",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 8.dp)
            )
            Column(Modifier.fillMaxWidth().weight(0.6f).verticalScroll(rememberScrollState())) {
                Text(RichText.annotated(value), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
