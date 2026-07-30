package com.billing.pos.ui.billing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/**
 * Full-screen mobile-number confirmation board.
 *
 * You type the number in the box at the bottom (your side); the top half shows it in a very
 * large font rotated 180°, so the customer sitting opposite reads it the right way up and can
 * confirm it. Copy puts it on the clipboard; "Add to diary" opens a note (typed, handwritten
 * or read from a photo) and files the number with it.
 */
@Composable
fun MobileNumberDialog(
    initial: String = "",
    onDismiss: () -> Unit
) {
    var number by remember { mutableStateOf(initial.filter { it.isDigit() }) }
    var noteFor by remember { mutableStateOf<String?>(null) }
    var saveCustomerFor by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // "Add to diary": note popup, then one diary entry titled with the number and the time.
    noteFor?.let { num ->
        com.billing.pos.ui.common.DiaryNoteDialog(
            heading = "Mobile number $num",
            onSave = { note ->
                val stamp = com.billing.pos.util.Format.dateTime(System.currentTimeMillis())
                scope.launch {
                    val cust = com.billing.pos.data.Repository(context).customerByPhone(num)
                    com.billing.pos.diary.QuickDiaryNote.save(
                        context,
                        title = (cust?.name?.let { "$it — " } ?: "") + "$num — $stamp",
                        body = if (note.isBlank()) num else "$num\n$note",
                        customerId = cust?.id ?: 0,
                        customerName = cust?.name ?: ""
                    )
                }
                android.widget.Toast.makeText(context, "Saved to diary", android.widget.Toast.LENGTH_SHORT).show()
                noteFor = null
                onDismiss()
            },
            onDismiss = { noteFor = null }
        )
    }

    // "Save customer": the number is already known, so only the name is asked for. If this
    // number already belongs to a customer, its name is filled in and there is nothing to save.
    saveCustomerFor?.let { num ->
        var name by remember(num) { mutableStateOf("") }
        var existing by remember(num) { mutableStateOf<com.billing.pos.data.Customer?>(null) }
        var draw by remember(num) { mutableStateOf(false) }
        var ctype by remember(num) { mutableStateOf("General") }
        LaunchedEffect(num) {
            val c = com.billing.pos.data.Repository(context).customerByPhone(num)
            if (c != null) { existing = c; name = c.name }
        }
        if (draw) {
            com.billing.pos.ui.common.HandwriteTextDialog(
                onResult = { t -> if (t.isNotBlank()) name = t; draw = false },
                onDismiss = { draw = false }
            )
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { saveCustomerFor = null },
            title = { Text(if (existing != null) "Customer" else "Save as customer") },
            text = {
                Column {
                    Text(num, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (existing != null) Text(
                        "Already in your customers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = name, onValueChange = { name = it },
                            label = { Text("Customer name") }, singleLine = true,
                            modifier = Modifier.weight(1f).padding(top = 8.dp)
                        )
                        // Handwrite the name.
                        androidx.compose.material3.IconButton(onClick = { draw = true }) {
                            Icon(Icons.Filled.Draw, "Handwrite name")
                        }
                    }
                    if (existing == null) {
                        com.billing.pos.ui.common.CustomerTypeField(
                            value = ctype, onValue = { ctype = it },
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                if (existing == null) androidx.compose.material3.TextButton(onClick = {
                    val typed = name.trim()
                    if (typed.isNotBlank()) {
                        scope.launch { com.billing.pos.data.Repository(context).addCustomer(typed, num, "", customerType = ctype) }
                        android.widget.Toast.makeText(context, "$typed saved to customers", android.widget.Toast.LENGTH_SHORT).show()
                        saveCustomerFor = null
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { saveCustomerFor = null }) {
                    Text(if (existing != null) "Close" else "Cancel")
                }
            }
        )
    }

    // decorFitsSystemWindows = false so the dialog gets real insets; safeDrawingPadding then keeps
    // the buttons clear of the phone's navigation bar and the keyboard.
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
            // ---- TOP BAR: actions always reachable (never under the navigation bar) ----
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        if (number.isNotBlank()) {
                            clipboard.setText(AnnotatedString(number))
                            // Also kept in the diary; a known customer's note links to them.
                            val saved = number
                            scope.launch {
                                val cust = com.billing.pos.data.Repository(context).customerByPhone(saved)
                                com.billing.pos.diary.QuickDiaryNote.save(
                                    context,
                                    title = if (cust != null) "${cust.name} — $saved" else "Mobile number $saved",
                                    body = if (cust != null) "${cust.name}\n$saved" else saved,
                                    customerId = cust?.id ?: 0,
                                    customerName = cust?.name ?: ""
                                )
                            }
                            android.widget.Toast.makeText(
                                context, "Copied and saved to diary", android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(4.dp)
                ) { Icon(Icons.Filled.ContentCopy, "Copy", Modifier.size(22.dp)) }
                OutlinedButton(
                    // Shares the contact — name (if known) and number — and quietly files the
                    // customer if this number is not in the master yet.
                    onClick = {
                        if (number.isNotBlank()) scope.launch {
                            val repo = com.billing.pos.data.Repository(context)
                            val existing = repo.customerByPhone(number)
                            val label = existing?.name ?: number
                            if (existing == null) repo.addCustomer(label, number, "")
                            val text = if (existing?.name?.isNotBlank() == true)
                                existing.name + "\n" + number else number
                            com.billing.pos.util.ShareText.share(context, text)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(4.dp)
                ) { Icon(Icons.Filled.Share, "Share", Modifier.size(22.dp)) }
                OutlinedButton(
                    // Opens a WhatsApp chat to this number with "hi" ready to send.
                    onClick = {
                        if (number.isNotBlank()) runCatching {
                            val wa = com.billing.pos.marketing.MarketingMedia.withCountryCode(number)
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://wa.me/" + wa + "?text=hi")
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(4.dp)
                ) { Icon(Icons.Filled.Chat, "WhatsApp", Modifier.size(22.dp)) }
                OutlinedButton(
                    // Opens the phone's dialer with the number filled in — the call itself is
                    // still started by you, so the app needs no calling permission.
                    onClick = {
                        if (number.isNotBlank()) runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_DIAL,
                                    android.net.Uri.parse("tel:" + number)
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(4.dp)
                ) { Icon(Icons.Filled.Call, "Call", Modifier.size(22.dp)) }
                OutlinedButton(
                    onClick = { if (number.isNotBlank()) saveCustomerFor = number },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(4.dp)
                ) { Icon(Icons.Filled.PersonAdd, "Save customer", Modifier.size(22.dp)) }
                Button(
                    onClick = { if (number.isNotBlank()) noteFor = number },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(4.dp)
                ) { Icon(Icons.Filled.NoteAdd, "Add to diary", Modifier.size(22.dp)) }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(4.dp)
                ) { Icon(Icons.Filled.Close, "Close", Modifier.size(22.dp)) }
            }
            Divider()

            // ---- Customer side: upside-down so it reads correctly from the opposite seat ----
            // Capped at 70% of the height so the entry box below is always on screen.
            Box(
                Modifier.fillMaxWidth().fillMaxHeight(0.70f).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    Modifier.fillMaxWidth().graphicsLayer { rotationZ = 180f },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val shown = number.ifBlank { "----------" }
                    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        // Auto-fit: shrink the font so the whole number always fits on one line.
                        val fs = (maxWidth.value / (shown.length.coerceAtLeast(1) * 0.62f)).coerceIn(20f, 110f)
                        Text(
                            shown,
                            modifier = Modifier.fillMaxWidth(),
                            fontSize = fs.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Text(
                        "Please check your number",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
            }
            Divider()

            // ---- Your side: type the number ----
            Column(
                Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it.filter { c -> c.isDigit() }.take(15) },
                    label = { Text("Mobile number") },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 34.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
