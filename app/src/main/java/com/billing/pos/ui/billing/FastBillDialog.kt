package com.billing.pos.ui.billing

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.billing.pos.util.Format
import kotlinx.coroutines.launch

/**
 * Fast bill — a calculator tape. Type an amount and press Enter (Enter acts as "+"); each amount
 * is added to a big running tape the customer can read, with "=" total at the bottom. Save drops
 * every amount into the bill as its own price-only line (no item name), ready to print.
 *
 * Close/Save live in the TOP bar: the phone's navigation bar can never cover them.
 */
/** One-shot hand-off of a calculator tape from the dashboard into a new sale. */
object FastBillLink {
    @Volatile var amounts: List<Double> = emptyList()
    fun take(): List<Double> { val v = amounts; amounts = emptyList(); return v }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FastBillDialog(
    onSave: (List<Double>) -> Unit,
    onDismiss: () -> Unit
) {
    val entries = remember { mutableStateListOf<Double>() }
    var input by remember { mutableStateOf("") }
    // Id of the saved tape being edited, or 0 while this is a fresh calculation.
    var savedId by remember { mutableStateOf(0L) }
    var showSaved by remember { mutableStateOf(false) }
    // The save popup, and what it collects.
    var askSave by remember { mutableStateOf(false) }
    var custName by remember { mutableStateOf(com.billing.pos.data.SavedCalc.DEFAULT_CUSTOMER) }
    var custId by remember { mutableStateOf(0L) }
    var custPhone by remember { mutableStateOf("") }
    var narration by remember { mutableStateOf("") }
    // Which customer the saved list is filtered to; blank means all of them.
    var listFilter by remember { mutableStateOf("") }
    // Date range, off by default. When switched on it starts at one month back.
    var dateRangeOn by remember { mutableStateOf(false) }
    var editReceipt by remember { mutableStateOf<com.billing.pos.data.Receipt?>(null) }
    var qrAmount by remember { mutableStateOf<Double?>(null) }
    var fromMillis by remember { mutableStateOf(defaultFromMillis()) }
    var toMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var editIndex by remember { mutableStateOf(-1) }
    val focus = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val total = entries.sum()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    /** [sign] is +1 for the "+" key and -1 for "−"; a minus entry is stored negative. */
    fun addNow(sign: Int = 1) {
        val v = input.toDoubleOrNull()
        if (v != null && v > 0.0) entries.add(v * sign)
        input = ""
        focus.requestFocus()
    }

    /** The tape as plain text, for sharing and for the diary copy. */
    fun tapeText(): String = buildString {
        entries.forEachIndexed { i, v ->
            val sign = if (v < 0) "-" else if (i == 0) " " else "+"
            append(sign).append(' ').append(Format.money(kotlin.math.abs(v))).append('\n')
        }
        append("= ").append(Format.money(entries.sum()))
    }

    fun saveToDiary() {
        if (entries.isEmpty()) return
        val body = tapeText()
        val sum = Format.money(entries.sum())
        scope.launch {
            com.billing.pos.diary.QuickDiaryNote.save(context, "Fast bill $sum", body)
        }
    }

    val repo = remember { com.billing.pos.data.Repository(context) }
    val downloadCalcPdf = com.billing.pos.ui.common.rememberPdfDownloader { msg ->
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
    val savedCalcs: List<com.billing.pos.data.SavedCalc> by
        repo.savedCalcs.collectAsState(initial = emptyList())

    /** Stores the tape, updating the one being edited rather than piling up copies. */
    fun storeTape(onDone: (String) -> Unit) {
        val pending = input.toDoubleOrNull()
        val all = if (pending != null && pending > 0.0) entries + pending else entries.toList()
        if (all.isEmpty()) { onDone("Nothing to save"); return }
        scope.launch {
            val id = repo.saveCalc(
                com.billing.pos.data.SavedCalc(
                    id = savedId,
                    dateMillis = System.currentTimeMillis(),
                    amounts = com.billing.pos.data.SavedCalc.pack(all),
                    total = all.sum(),
                    customerId = custId,
                    customerName = custName.ifBlank { com.billing.pos.data.SavedCalc.DEFAULT_CUSTOMER },
                    narration = narration.trim()
                )
            )
            val fresh = savedId == 0L
            savedId = id
            onDone(if (fresh) "Calculation saved" else "Calculation updated")
        }
    }

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(entries.size) { scroll.animateScrollTo(scroll.maxValue) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Column(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .safeDrawingPadding()
        ) {
            // ---- TOP BAR: icon-only, evenly spread so it never crowds ----
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
                IconButton(onClick = { showSaved = true }) {
                    Icon(Icons.Filled.ListAlt, contentDescription = "Saved calculations")
                }
                IconButton(
                    onClick = { askSave = true },
                    enabled = entries.isNotEmpty() || (input.toDoubleOrNull() ?: 0.0) > 0.0
                ) { Icon(Icons.Filled.Save, contentDescription = "Save calculation") }
                IconButton(
                    onClick = {
                        if (entries.isNotEmpty()) {
                            saveToDiary()
                            // The customer (with mobile number) goes out with the tape.
                            val header = if (custName.isNotBlank() && custName != com.billing.pos.data.SavedCalc.DEFAULT_CUSTOMER)
                                custName + (if (custPhone.isNotBlank()) " - $custPhone" else "") + "\n\n" else ""
                            shareTapeToWhatsApp(context, header + tapeText())
                        }
                    },
                    enabled = entries.isNotEmpty()
                ) { Icon(Icons.Filled.Share, contentDescription = "Share on WhatsApp") }
                IconButton(
                    // A UPI payment QR for the current total; the customer scans to pay.
                    onClick = {
                        val pending = input.toDoubleOrNull() ?: 0.0
                        val amt = entries.sum() + if (pending > 0.0) pending else 0.0
                        if (amt > 0.0) qrAmount = amt
                    },
                    enabled = entries.isNotEmpty() || (input.toDoubleOrNull() ?: 0.0) > 0.0
                ) { Icon(Icons.Filled.QrCode2, contentDescription = "UPI QR") }
                // Save the tape into the bill — the primary action, tinted so it stands out.
                IconButton(
                    onClick = {
                        val pending = input.toDoubleOrNull()
                        val all = if (pending != null && pending > 0.0) entries + pending else entries.toList()
                        saveToDiary()
                        if (all.isNotEmpty()) onSave(all)
                        onDismiss()
                    },
                    enabled = entries.isNotEmpty() || (input.toDoubleOrNull() ?: 0.0) > 0.0
                ) { Icon(Icons.Filled.PointOfSale, contentDescription = "Save to bill", tint = MaterialTheme.colorScheme.primary) }
            }
            Divider()

            // ---- The tape: every amount on its own line, with + signs, then = total ----
            Box(
                Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    if (entries.isEmpty()) {
                        Text(
                            "Type an amount and press Enter",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                    entries.forEachIndexed { i, v ->
                        // Long-press an amount to edit or delete it; the total recalculates.
                        Row(
                            Modifier.fillMaxWidth()
                                .combinedClickable(onClick = {}, onLongClick = { editIndex = i })
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (v < 0) "-" else if (i == 0) " " else "+",
                                fontSize = 30.sp, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.65f)
                            )
                            Text(
                                Format.money(kotlin.math.abs(v)),
                                modifier = Modifier.weight(1f),
                                fontSize = 34.sp, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold, textAlign = TextAlign.End,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    if (entries.isNotEmpty()) {
                        Divider(
                            Modifier.padding(vertical = 8.dp), thickness = 3.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                        )
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "=",
                                fontSize = 40.sp, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                Format.money(total),
                                modifier = Modifier.weight(1f),
                                fontSize = 52.sp, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold, textAlign = TextAlign.End,
                                maxLines = 1, softWrap = false,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            Divider()

            // ---- Entry + running total ----
            Column(Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Amount") },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(
                            fontSize = 30.sp, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold, textAlign = TextAlign.End
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { addNow() }),
                        modifier = Modifier.weight(1f).focusRequester(focus)
                    )
                    IconButton(onClick = { if (input.isNotEmpty()) input = "" else if (entries.isNotEmpty()) entries.removeAt(entries.lastIndex) }) {
                        Icon(Icons.Filled.Backspace, contentDescription = "Remove last")
                    }
                    OutlinedButton(onClick = { addNow(-1) }) {
                        Text("−", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(onClick = { addNow(1) }) { Text("+", fontSize = 26.sp, fontWeight = FontWeight.Bold) }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("TOTAL  (${entries.size})", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(
                        Format.money(total),
                        fontSize = 34.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    // ---- Save popup: who it is for, and why ----
    if (askSave) {
        val customers by repo.customers.collectAsState(initial = emptyList<com.billing.pos.data.Customer>())
        var custMenu by remember { mutableStateOf(false) }
        var newCust by remember { mutableStateOf(false) }
        var newCustName by remember { mutableStateOf("") }

        if (newCust) {
            var newCustPhone by remember { mutableStateOf("") }
            var newCustType by remember { mutableStateOf("General") }
            var drawCustName by remember { mutableStateOf(false) }
            if (drawCustName) {
                com.billing.pos.ui.common.HandwriteTextDialog(
                    onResult = { if (it.isNotBlank()) newCustName = it; drawCustName = false },
                    onDismiss = { drawCustName = false }
                )
            }
            AlertDialog(
                onDismissRequest = { newCust = false },
                title = { Text("New customer") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = newCustName, onValueChange = { newCustName = it },
                            label = { Text("Customer name") }, singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = { drawCustName = true }) {
                                    Icon(Icons.Filled.Gesture, "Write name")
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = newCustPhone,
                            onValueChange = { v -> newCustPhone = v.filter { it.isDigit() } },
                            label = { Text("Mobile number (optional)") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                        com.billing.pos.ui.common.CustomerTypeField(
                            value = newCustType, onValue = { newCustType = it },
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val name = newCustName.trim()
                        if (name.isNotBlank()) scope.launch {
                            // Added to the customer list proper, so it is there next time too.
                            val c = repo.addCustomerReturning(name, newCustPhone.trim(), newCustType)
                            custId = c.id; custName = c.name; custPhone = c.phone
                        }
                        newCustName = ""; newCust = false
                    }) { Text("Add") }
                },
                dismissButton = { TextButton(onClick = { newCust = false }) { Text("Cancel") } }
            )
        }

        AlertDialog(
            onDismissRequest = { askSave = false },
            title = { Text("Save calculation") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        com.billing.pos.ui.common.CustomerPickField(
                            customers = customers,
                            selectedName = custName,
                            onPick = { c -> custName = c.name; custId = c.id; custPhone = c.phone },
                            allowFreeText = true,
                            onTyped = { custName = it; custId = 0L; custPhone = "" },
                            extraOptions = listOf(com.billing.pos.data.SavedCalc.DEFAULT_CUSTOMER),
                            onPickExtra = { custName = it; custId = 0L; custPhone = "" },
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { newCust = true }) {
                            Icon(Icons.Filled.Add, "Add customer", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    OutlinedTextField(
                        value = narration,
                        onValueChange = { narration = it },
                        label = { Text("Narration") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    askSave = false
                    storeTape { msg ->
                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { askSave = false }) { Text("Cancel") } }
        )
    }

    // Saved tapes: newest first, tap one to carry on adding to it.
    if (showSaved) {
        val shown = savedCalcs.filter {
            (listFilter.isBlank() || it.customerName.contains(listFilter.trim(), ignoreCase = true)) &&
                (!dateRangeOn || (it.dateMillis >= startOfDayMillis(fromMillis) && it.dateMillis <= endOfDayMillis(toMillis)))
        }
        val receipts by repo.allReceipts.collectAsState(initial = emptyList<com.billing.pos.data.Receipt>())
        val savedCustomers by repo.customers.collectAsState(initial = emptyList<com.billing.pos.data.Customer>())
        val shownTotal = shown.sumOf { it.total }

        // Money already received from a customer since a tape was saved, up to the next
        // tape for that same customer, so a receipt is counted against one calculation only.
        fun receiptsFor(calc: com.billing.pos.data.SavedCalc): List<com.billing.pos.data.Receipt> {
            val nextForCustomer = shown
                .filter { it.customerName.equals(calc.customerName, true) && it.dateMillis > calc.dateMillis }
                .minByOrNull { it.dateMillis }?.dateMillis ?: Long.MAX_VALUE
            return receipts.filter { r ->
                val who = r.payFrom.ifBlank { r.customerName }
                who.equals(calc.customerName, ignoreCase = true) &&
                    r.dateMillis >= calc.dateMillis && r.dateMillis < nextForCustomer
            }
        }
        val shownReceived = shown.sumOf { c -> receiptsFor(c).sumOf { it.amount } }
        var filterMenu by remember { mutableStateOf(false) }

        Dialog(
            onDismissRequest = { showSaved = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            Column(
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .safeDrawingPadding()
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Saved calculations",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { shareCalcList(context, shown, listFilter) },
                        enabled = shown.isNotEmpty()
                    ) { Icon(Icons.Filled.Share, "Share list") }
                    IconButton(
                        onClick = { downloadCalcPdf { buildCalcListPdf(context, shown, listFilter) } },
                        enabled = shown.isNotEmpty()
                    ) { Icon(Icons.Filled.PictureAsPdf, "Save as PDF") }
                    OutlinedButton(onClick = { showSaved = false }) { Text("Close") }
                }

                // Filter by customer; "All customers" is the default.
                Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    // Type to narrow, or pick from the arrow — both work on the same box.
                    OutlinedTextField(
                        value = listFilter,
                        onValueChange = { listFilter = it; filterMenu = it.isNotBlank() },
                        label = { Text("Customer (all)") },
                        placeholder = { Text("All customers") },
                        singleLine = true,
                        trailingIcon = {
                            Row {
                                if (listFilter.isNotBlank()) IconButton(onClick = { listFilter = ""; filterMenu = false }) {
                                    Icon(Icons.Filled.Close, "Show all")
                                }
                                IconButton(onClick = { filterMenu = !filterMenu }) {
                                    Icon(Icons.Filled.ArrowDropDown, "Pick customer")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    val names = savedCalcs.map { it.customerName }.distinct()
                        .filter { listFilter.isBlank() || it.contains(listFilter.trim(), ignoreCase = true) }
                        .sorted()
                    androidx.compose.material3.DropdownMenu(
                        expanded = filterMenu, onDismissRequest = { filterMenu = false }
                    ) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("All customers") },
                            onClick = { listFilter = ""; filterMenu = false }
                        )
                        names.forEach { name ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(name) },
                                onClick = { listFilter = name; filterMenu = false }
                            )
                        }
                    }
                }
                // Date range, off by default so the list shows everything until asked.
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = dateRangeOn,
                        onCheckedChange = { dateRangeOn = it }
                    )
                    Text("Date range", style = MaterialTheme.typography.labelLarge)
                    if (dateRangeOn) {
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = { pickCalcDate(context, fromMillis) { fromMillis = it } }) {
                            Text(Format.date(fromMillis), style = MaterialTheme.typography.labelSmall)
                        }
                        Text(" — ", style = MaterialTheme.typography.labelSmall)
                        OutlinedButton(onClick = { pickCalcDate(context, toMillis) { toMillis = it } }) {
                            Text(Format.date(toMillis), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Divider()

                if (shown.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("Nothing saved yet", color = MaterialTheme.colorScheme.outline)
                    }
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(
                        Modifier.fillMaxHeight(0.75f).fillMaxWidth()
                    ) {
                        items(shown, key = { it.id }) { calc ->
                            val paid = receiptsFor(calc)
                            val received = paid.sumOf { it.amount }
                            Row(
                                Modifier.fillMaxWidth()
                                    .combinedClickable(onClick = {
                                        // Open in edit mode: the tape, its customer and its
                                        // narration all come back, and saving updates this entry.
                                        entries.clear()
                                        entries.addAll(calc.amountList)
                                        savedId = calc.id
                                        custId = calc.customerId
                                        custName = calc.customerName
                                        custPhone = savedCustomers.firstOrNull { it.id == calc.customerId }?.phone ?: ""
                                        narration = calc.narration
                                        input = ""
                                        showSaved = false
                                    })
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(calc.customerName, fontWeight = FontWeight.Bold)
                                    Text(
                                        Format.dateTime(calc.dateMillis) + "  •  " + calc.amountList.size + " amount(s)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    if (calc.narration.isNotBlank()) Text(
                                        calc.narration,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        Format.money(calc.total),
                                        fontSize = 22.sp, fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        textDecoration = if (received > 0.0)
                                            androidx.compose.ui.text.style.TextDecoration.LineThrough else null
                                    )
                                    if (received > 0.0) Text(
                                        "Balance " + Format.money(calc.total - received),
                                        fontSize = 18.sp, fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                IconButton(onClick = { scope.launch { repo.deleteCalc(calc.id) } }) {
                                    Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            // Each receipt is its own line: tap to correct the amount.
                            paid.forEach { r ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable { editReceipt = r }
                                        .padding(start = 24.dp, end = 14.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Received " + r.receiptNo + "  " + Format.date(r.dateMillis),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        "- " + Format.money(r.amount),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Divider()
                        }
                    }
                }

                // Total of exactly what is listed, so it follows the filter.
                Divider(thickness = 2.dp)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)
                        .navigationBarsPadding(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "TOTAL",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        // The money, big. What is still outstanding once receipts are taken
                        // off is the figure that matters, with the gross above it.
                        if (shownReceived > 0.0) Text(
                            Format.money(shownTotal) + "  −  " + Format.money(shownReceived),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            Format.money(shownTotal - shownReceived),
                            fontSize = 40.sp, fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1, softWrap = false,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        "${shown.size} saved",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }

    // Correcting a receipt straight from the calculation it was received against.
    qrAmount?.let { amt ->
        com.billing.pos.ui.common.UpiQrDialog(amount = amt, onDismiss = { qrAmount = null })
    }

    editReceipt?.let { r ->
        var amountText by remember(r.id) { mutableStateOf(Format.money(r.amount)) }
        var mode by remember(r.id) { mutableStateOf(runCatching { com.billing.pos.data.PayMode.valueOf(r.paymentMode.uppercase()) }.getOrDefault(com.billing.pos.data.PayMode.CASH)) }
        AlertDialog(
            onDismissRequest = { editReceipt = null },
            title = { Text("Receipt " + r.receiptNo) },
            text = {
                Column {
                    Text(
                        r.payFrom.ifBlank { r.customerName } + "  •  " + Format.date(r.dateMillis),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Amount") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        com.billing.pos.data.PayMode.values().forEach { m ->
                            androidx.compose.material3.FilterChip(
                                selected = mode == m,
                                onClick = { mode = m },
                                label = { Text(m.label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = amountText.toDoubleOrNull()
                    if (v != null && v > 0.0) scope.launch { repo.updateReceipt(r, v, mode) }
                    editReceipt = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editReceipt = null }) { Text("Cancel") } }
        )
    }

    // Long-press edit: change or delete one amount, total recalculates.
    if (editIndex in entries.indices) {
        val idx = editIndex
        var text by remember(idx) { mutableStateOf(Format.money(kotlin.math.abs(entries[idx]))) }
        // Sign is edited here too, so a line entered as + can be switched to − and the
        // total recalculates without deleting and re-typing it.
        var plus by remember(idx) { mutableStateOf(entries[idx] >= 0) }
        AlertDialog(
            onDismissRequest = { editIndex = -1 },
            title = { Text("Edit amount ${idx + 1}") },
            text = {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.FilterChip(
                            selected = plus,
                            onClick = { plus = true },
                            label = { Text("+ Add", fontWeight = FontWeight.Bold) }
                        )
                        androidx.compose.material3.FilterChip(
                            selected = !plus,
                            onClick = { plus = false },
                            label = { Text("− Subtract", fontWeight = FontWeight.Bold) }
                        )
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' } },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(
                            fontSize = 30.sp, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold, textAlign = TextAlign.End
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = text.toDoubleOrNull()
                    if (v != null && v > 0.0) entries[idx] = if (plus) v else -v
                    editIndex = -1
                }) { Text("Save") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { entries.removeAt(idx); editIndex = -1 }) { Text("Delete") }
                    TextButton(onClick = { editIndex = -1 }) { Text("Cancel") }
                }
            }
        )
    }
}

/** Shares the calculator tape as text, preferring WhatsApp and falling back to a chooser. */
private fun shareTapeToWhatsApp(context: android.content.Context, text: String) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, text)
    }
    for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
        val direct = android.content.Intent(send).setPackage(pkg)
        if (direct.resolveActivity(context.packageManager) != null) {
            runCatching { context.startActivity(direct) }.onSuccess { return }
        }
    }
    runCatching {
        context.startActivity(
            android.content.Intent.createChooser(send, "Share total")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** The saved-calculation list as a PDF: one row per tape, filtered exactly as shown. */
private fun buildCalcListPdf(
    context: android.content.Context,
    rows: List<com.billing.pos.data.SavedCalc>,
    customerFilter: String
): java.io.File {
    val cols = listOf(
        com.billing.pos.pdf.TablePdf.Col("Date", 1.5f),
        com.billing.pos.pdf.TablePdf.Col("Customer", 1.6f),
        com.billing.pos.pdf.TablePdf.Col("Narration", 2.4f),
        com.billing.pos.pdf.TablePdf.Col("Amounts", 0.8f, right = true),
        com.billing.pos.pdf.TablePdf.Col("Total", 1.2f, right = true)
    )
    val data = rows.map {
        listOf(
            Format.dateTime(it.dateMillis), it.customerName, it.narration,
            it.amountList.size.toString(), Format.money(it.total)
        )
    }
    return com.billing.pos.pdf.TablePdf.generate(
        context,
        com.billing.pos.data.AppPrefs(context).company,
        "Saved Calculations",
        if (customerFilter.isBlank()) "All customers" else "Customer: " + customerFilter,
        cols, data,
        listOf("Total" to Format.money(rows.sumOf { it.total }))
    )
}

/** Shares that same list as a PDF attachment. */
private fun shareCalcList(
    context: android.content.Context,
    rows: List<com.billing.pos.data.SavedCalc>,
    customerFilter: String
) {
    runCatching {
        val file = buildCalcListPdf(context, rows, customerFilter)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, context.packageName + ".provider", file
        )
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            )
        }
        context.startActivity(
            android.content.Intent.createChooser(send, "Share calculations")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** The date the range starts on when it is first switched on: one month back. */
private fun defaultFromMillis(): Long = java.util.Calendar.getInstance().apply {
    add(java.util.Calendar.MONTH, -1)
}.timeInMillis

private fun startOfDayMillis(m: Long): Long = java.util.Calendar.getInstance().apply {
    timeInMillis = m
    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis

private fun endOfDayMillis(m: Long): Long = startOfDayMillis(m) + 24L * 60 * 60 * 1000 - 1

private fun pickCalcDate(context: android.content.Context, current: Long, onPicked: (Long) -> Unit) {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = current }
    android.app.DatePickerDialog(
        context,
        { _, y, mo, d ->
            c.set(java.util.Calendar.YEAR, y); c.set(java.util.Calendar.MONTH, mo)
            c.set(java.util.Calendar.DAY_OF_MONTH, d)
            onPicked(c.timeInMillis)
        },
        c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH)
    ).show()
}
