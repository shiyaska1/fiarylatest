package com.billing.pos.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.billing.pos.data.AppPrefs
import kotlinx.coroutines.launch

/** Copies a picked file (image or PDF) into app storage, keeping a sensible extension. */
private fun copyToAppFiles(context: android.content.Context, uri: android.net.Uri, baseName: String): String? {
    val type = context.contentResolver.getType(uri) ?: ""
    val ext = when { type.contains("pdf") -> "pdf"; type.contains("png") -> "png"; else -> "jpg" }
    val dest = java.io.File(context.filesDir, "$baseName.$ext")
    // Remove any older copy of this asset with a different extension.
    listOf("pdf", "png", "jpg").forEach { e -> if (e != ext) java.io.File(context.filesDir, "$baseName.$e").delete() }
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
        dest.absolutePath
    }.getOrNull()
}

val BUSINESS_TYPES = listOf(
    "Personal",
    "General", "Textiles", "Mobile shop", "Electrical & plumbing",
    "Automobiles", "Grocery", "Medical store", "Restaurant", "Rental", "Medical lab", "Bulk SMS", "Gym", "Coaching Center", "Service Center"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenPrinter: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { AppPrefs(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var name by remember { mutableStateOf(prefs.companyName) }
    var address by remember { mutableStateOf(prefs.companyAddress) }
    var phone by remember { mutableStateOf(prefs.companyPhone) }
    var gstin by remember { mutableStateOf(prefs.companyGstin) }
    var upiId by remember { mutableStateOf(prefs.upiId) }
    var upiName by remember { mutableStateOf(prefs.upiName) }
    var upiQrOnPrint by remember { mutableStateOf(prefs.showUpiQrOnPrint) }
    var requireBatch by remember { mutableStateOf(prefs.requireItemBatch) }
    var businessType by remember { mutableStateOf(prefs.businessType) }
    var receiptWidth by remember { mutableStateOf(prefs.receiptWidth) }
    var ocrLanguage by remember { mutableStateOf(prefs.ocrLanguage) }
    var logoPath by remember { mutableStateOf(prefs.logoPath) }
    var logoFull by remember { mutableStateOf(prefs.logoFullWidth) }
    val logoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val dest = java.io.File(context.filesDir, "company_logo.jpg")
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.billing.pos.diary.AttachmentStore.compressImageTo(context, uri, dest, 900, 88)
            }
            if (ok) { prefs.logoPath = dest.absolutePath; logoPath = dest.absolutePath; snackbar.showSnackbar("Logo saved") }
            else snackbar.showSnackbar("Could not read image")
        }
    }

    // ---- medical lab print assets ----
    var sealPath by remember { mutableStateOf(prefs.labSealPath) }
    var signPath by remember { mutableStateOf(prefs.labSignaturePath) }
    var letterheadPath by remember { mutableStateOf(prefs.labLetterheadPath) }
    var topSkip by remember { mutableStateOf(prefs.labTopSkipLines.toString()) }
    var bottomSkip by remember { mutableStateOf(prefs.labBottomSkipLines.toString()) }
    var stickyNote by remember { mutableStateOf(prefs.stickyNoteOnLaunch) }
    var autoVoiceDiary by remember { mutableStateOf(prefs.autoVoiceDiary) }
    // ---- LAN sync (two-counter over WiFi) ----
    var deviceTag by remember { mutableStateOf(prefs.deviceTag) }
    var hostMode by remember { mutableStateOf(com.billing.pos.sync.SyncManager.isHosting()) }
    var hostIp by remember { mutableStateOf(prefs.syncHostIp) }
    var autoSync by remember { mutableStateOf(prefs.syncAuto) }
    val syncStatus by com.billing.pos.sync.SyncManager.status.collectAsState()
    val myWifiIp = remember { com.billing.pos.sync.SyncManager.wifiIp(context) }
    val micPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) { autoVoiceDiary = true; com.billing.pos.audio.AutoDiaryService.start(context) }
    }
    var appLock by remember { mutableStateOf(prefs.appLock) }
    var expiryAlert by remember { mutableStateOf(prefs.expiryAlert) }
    var expiryDays by remember { mutableStateOf(prefs.expiryAlertDays.toString()) }
    // Warn if the lock is switched on but the phone itself has no lock to check against.
    val deviceSecure = remember {
        context.getSystemService(android.app.KeyguardManager::class.java)?.isDeviceSecure == true
    }
    val sealPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val path = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { copyToAppFiles(context, uri, "lab_seal") }
            if (path != null) { prefs.labSealPath = path; sealPath = path; snackbar.showSnackbar("Seal saved") } else snackbar.showSnackbar("Could not read image")
        }
    }
    val signPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val path = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { copyToAppFiles(context, uri, "lab_sign") }
            if (path != null) { prefs.labSignaturePath = path; signPath = path; snackbar.showSnackbar("Signature saved") } else snackbar.showSnackbar("Could not read image")
        }
    }
    val letterheadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val path = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { copyToAppFiles(context, uri, "lab_letterhead") }
            if (path != null) { prefs.labLetterheadPath = path; letterheadPath = path; snackbar.showSnackbar("Letterhead saved") } else snackbar.showSnackbar("Could not read file")
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Invoice / Company Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Device ID (used for licensing / activation).
            Text(
                "Device ID",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline
            )
            Text(
                com.billing.pos.data.License.deviceId(context),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            com.billing.pos.ui.license.SupportContactBlock(
                deviceId = com.billing.pos.data.License.deviceId(context),
                compact = true
            )
            Divider(Modifier.padding(vertical = 12.dp))

            Text(
                "Shown on printed bills and PDF invoices.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Company name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            )
            OutlinedTextField(
                value = address, onValueChange = { address = it },
                label = { Text("Address") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            OutlinedTextField(
                value = phone, onValueChange = { phone = it },
                label = { Text("Phone") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            OutlinedTextField(
                value = gstin, onValueChange = { gstin = it },
                label = { Text("GSTIN / TIN") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            OutlinedTextField(
                value = upiId, onValueChange = { upiId = it },
                label = { Text("UPI ID for payment QR (e.g. name@okaxis)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            OutlinedTextField(
                value = upiName, onValueChange = { upiName = it },
                label = { Text("Payee name on QR") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Checkbox(checked = upiQrOnPrint, onCheckedChange = { upiQrOnPrint = it; prefs.showUpiQrOnPrint = it })
                Text("Print UPI QR at the bottom of invoices")
            }
            Button(
                onClick = {
                    prefs.companyName = name.trim().ifBlank { "My Shop" }
                    prefs.companyAddress = address.trim()
                    prefs.companyPhone = phone.trim()
                    prefs.companyGstin = gstin.trim()
                    prefs.upiId = upiId.trim()
                    prefs.upiName = upiName.trim()
                    prefs.showUpiQrOnPrint = upiQrOnPrint
                    scope.launch { snackbar.showSnackbar("Settings saved") }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            ) { Text("Save") }

            Divider(Modifier.padding(vertical = 16.dp))
            Text("Company logo (A4 invoice)", style = MaterialTheme.typography.titleSmall)
            if (logoPath.isNotBlank()) {
                val bmp = com.billing.pos.ui.common.rememberThumbnail(logoPath, 400)
                if (bmp != null) {
                    Box(Modifier.fillMaxWidth().height(90.dp).padding(top = 6.dp)) {
                        Image(bmp, contentDescription = "Logo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(84.dp))
                    }
                }
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { logoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.weight(1f)) {
                    Text(if (logoPath.isBlank()) "Upload logo" else "Change logo")
                }
                if (logoPath.isNotBlank()) {
                    Spacer(Modifier.size(8.dp))
                    OutlinedButton(onClick = { prefs.logoPath = ""; logoPath = "" }) { Text("Remove") }
                }
            }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Logo is the full header", style = MaterialTheme.typography.bodyMedium)
                    Text("Turn on if your logo image already includes name, address & phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                Checkbox(checked = logoFull, onCheckedChange = { logoFull = it; prefs.logoFullWidth = it })
            }

            Divider(Modifier.padding(vertical = 16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sticky note on launch", style = MaterialTheme.typography.titleSmall)
                    Text("Open a full-screen handwriting canvas each time the app starts; Save stores each page as a picture in My Diary.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                Checkbox(checked = stickyNote, onCheckedChange = { stickyNote = it; prefs.stickyNoteOnLaunch = it })
            }

            if (com.billing.pos.BuildConfig.DEBUG) {
                Divider(Modifier.padding(vertical = 16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto voice diary (hourly)", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Records voice in the background and, once an hour, saves a diary entry (titled with the date-time, type \"voice\") with the recording attached. Only the parts where a voice is heard are kept. A notification stays on while it records.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Checkbox(checked = autoVoiceDiary, onCheckedChange = { on ->
                        if (!on) {
                            autoVoiceDiary = false
                            com.billing.pos.audio.AutoDiaryService.stop(context)
                        } else if (androidx.core.content.ContextCompat.checkSelfPermission(
                                context, android.Manifest.permission.RECORD_AUDIO
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            autoVoiceDiary = true
                            com.billing.pos.audio.AutoDiaryService.start(context)
                        } else {
                            micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                        }
                    })
                }
            }

            Divider(Modifier.padding(vertical = 16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("App lock", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Ask for this phone's own fingerprint / PIN / pattern each time the app is opened. No separate password is stored.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                    )
                }
                Checkbox(checked = appLock, onCheckedChange = { appLock = it; prefs.appLock = it })
            }
            if (appLock && !deviceSecure) {
                Text(
                    "This phone has no screen lock set. Set a PIN/pattern/fingerprint in Android Settings, or the app will open without asking.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
                )
            }

            Divider(Modifier.padding(vertical = 16.dp))
            Text("Share over WiFi (two counters)", style = MaterialTheme.typography.titleSmall)
            Text(
                "Sync items, customers, sales, receipts and expenses between two phones on the same WiFi — fully offline. " +
                    "One phone is the host (keep its app open); the other connects to it. Give each phone a short letter so " +
                    "bill numbers don't clash (e.g. A makes A-INV-0001).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
            )
            OutlinedTextField(
                value = deviceTag,
                onValueChange = { deviceTag = it.take(6); prefs.deviceTag = deviceTag },
                label = { Text("This phone's letter/name (e.g. A)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Act as host (server)", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (hostMode) "Others connect to ${myWifiIp ?: "this phone"}:${prefs.syncPort}"
                        else "Turn on for the main phone. Keep this app open while the other syncs.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                    )
                }
                Switch(checked = hostMode, onCheckedChange = { on ->
                    hostMode = on
                    if (on) com.billing.pos.sync.SyncManager.startHost(context)
                    else com.billing.pos.sync.SyncManager.stopHost(context)
                })
            }
            OutlinedTextField(
                value = hostIp,
                onValueChange = { hostIp = it.trim() },
                label = { Text("Host phone IP (from the host's screen above)") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = {
                    prefs.syncHostIp = hostIp
                    com.billing.pos.sync.SyncManager.syncWithHost(context, hostIp, prefs.syncPort)
                }, enabled = hostIp.isNotBlank()) { Text("Sync now") }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Auto-sync", style = MaterialTheme.typography.bodyMedium)
                    Text("every 20s while open", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                Spacer(Modifier.weight(1f))
                Switch(checked = autoSync, onCheckedChange = { on ->
                    autoSync = on; prefs.syncAuto = on
                    if (on && hostIp.isNotBlank()) { prefs.syncHostIp = hostIp; com.billing.pos.sync.SyncManager.startAuto(context, hostIp, prefs.syncPort) }
                    else com.billing.pos.sync.SyncManager.stopAuto()
                })
            }
            Text(syncStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))

            Divider(Modifier.padding(vertical = 16.dp))
            // Which script the camera/gallery OCR reads. Applies to every scan in the app.
            Text("Text language (camera & handwriting)", style = MaterialTheme.typography.titleSmall)
            Text(
                "Used when reading a photo and when reading what you draw. English is fast and " +
                    "accurate. Malayalam photo reading works offline but is slower and less accurate, " +
                    "so check the text before saving. Auto tries English first, then Malayalam. " +
                    "On the drawing pad you can switch language at any time.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
            )
            var ocrMenu by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = ocrMenu, onExpandedChange = { ocrMenu = !ocrMenu }) {
                OutlinedTextField(
                    readOnly = true, value = ocrLanguage, onValueChange = {},
                    label = { Text("OCR language") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(ocrMenu) },
                    modifier = Modifier.menuAnchor().fillMaxWidth().padding(top = 4.dp)
                )
                ExposedDropdownMenu(expanded = ocrMenu, onDismissRequest = { ocrMenu = false }) {
                    AppPrefs.OCR_LANGUAGES.forEach { l ->
                        DropdownMenuItem(
                            text = { Text(l) },
                            onClick = { ocrLanguage = l; prefs.ocrLanguage = l; ocrMenu = false }
                        )
                    }
                }
            }
            if (ocrLanguage != AppPrefs.OCR_ENGLISH) {
                // Checks the Malayalam reader can actually load, so a problem shows up here
                // rather than as a silently empty item name after taking a photo.
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            snackbar.showSnackbar("Checking Malayalam reader…")
                            val err = com.billing.pos.ocr.TesseractOcr.selfTest(context)
                            snackbar.showSnackbar(
                                if (err == null) "Malayalam reader is ready" else "Malayalam reader failed: $err"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("Check Malayalam reader") }
            }

            Divider(Modifier.padding(vertical = 16.dp))
            Text("Printer", style = MaterialTheme.typography.titleSmall)
            Text(
                "Receipt widths (58mm / 80mm) print on thermal receipt printers; A4 makes a full-page PDF for a normal printer.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
            )
            var widthMenu by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = widthMenu, onExpandedChange = { widthMenu = !widthMenu }) {
                OutlinedTextField(
                    readOnly = true, value = receiptWidth, onValueChange = {},
                    label = { Text("Receipt / print width") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(widthMenu) },
                    modifier = Modifier.menuAnchor().fillMaxWidth().padding(top = 4.dp)
                )
                ExposedDropdownMenu(expanded = widthMenu, onDismissRequest = { widthMenu = false }) {
                    AppPrefs.RECEIPT_WIDTHS.forEach { w ->
                        DropdownMenuItem(text = { Text(w) }, onClick = { receiptWidth = w; prefs.receiptWidth = w; widthMenu = false })
                    }
                }
            }
            OutlinedButton(onClick = onOpenPrinter, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Icon(Icons.Filled.Print, contentDescription = null)
                Text("  Thermal printer setup & test")
            }

            Divider(Modifier.padding(vertical = 16.dp))
            // Business type: drives medical (chemical content) and restaurant (sizes).
            Text("Business type", style = MaterialTheme.typography.titleSmall)
            var typeMenu by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = typeMenu, onExpandedChange = { typeMenu = !typeMenu }) {
                OutlinedTextField(
                    readOnly = true, value = businessType.ifBlank { "General" }, onValueChange = {},
                    label = { Text("Type") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeMenu) },
                    modifier = Modifier.menuAnchor().fillMaxWidth().padding(top = 4.dp)
                )
                ExposedDropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                    BUSINESS_TYPES.forEach { t ->
                        DropdownMenuItem(text = { Text(t) }, onClick = {
                            businessType = t; prefs.businessType = t; typeMenu = false
                            if (t == "Medical store") { requireBatch = true; prefs.requireItemBatch = true }
                        })
                    }
                }
            }
            if (com.billing.pos.data.SampleData.itemsFor(businessType).isNotEmpty()) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val repo = com.billing.pos.data.Repository(context)
                            val samples = com.billing.pos.data.SampleData.itemsFor(businessType)
                            var added = 0
                            samples.forEach { s ->
                                if (repo.itemByName(s.name) == null) {
                                    repo.addItem(s.name, s.price, 0.0, "", "", s.category, 0.0, s.unit, "", s.chemical)
                                    added++
                                }
                            }
                            snackbar.showSnackbar("Loaded $added sample $businessType item(s)")
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) { Text("Load sample items for $businessType") }
            }

            // Medical store: warn about medicines nearing expiry.
            if (businessType == "Medical store") {
                Divider(Modifier.padding(vertical = 16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Expiry alert", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Notify once a day about medicines nearing expiry, and show the list when the app opens. Repeats daily until the batch is sold, removed or purchase-returned.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Checkbox(
                        checked = expiryAlert,
                        onCheckedChange = {
                            expiryAlert = it
                            prefs.expiryAlert = it
                            com.billing.pos.medical.ExpiryAlarm.sync(context)
                        }
                    )
                }
                if (expiryAlert) {
                    OutlinedTextField(
                        value = expiryDays,
                        onValueChange = { v ->
                            expiryDays = v.filter { it.isDigit() }.take(4)
                            expiryDays.toIntOrNull()?.let { d ->
                                if (d > 0) { prefs.expiryAlertDays = d; com.billing.pos.medical.ExpiryAlarm.sync(context) }
                            }
                        },
                        label = { Text("Warn how many days before expiry") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            }

            if (businessType == "Medical lab") {
                Divider(Modifier.padding(vertical = 16.dp))
                Text("Lab result print", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Seal + signature print above the technician line. A letterhead (JPG/PNG/PDF) prints as the page background and hides the app header — set how many blank lines to skip below the printed header and above the printed footer.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { sealPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.weight(1f)) {
                        Text(if (sealPath.isBlank()) "Upload seal" else "Change seal")
                    }
                    if (sealPath.isNotBlank()) OutlinedButton(onClick = { prefs.labSealPath = ""; sealPath = "" }) { Text("Remove") }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { signPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.weight(1f)) {
                        Text(if (signPath.isBlank()) "Upload signature" else "Change signature")
                    }
                    if (signPath.isNotBlank()) OutlinedButton(onClick = { prefs.labSignaturePath = ""; signPath = "" }) { Text("Remove") }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { letterheadPicker.launch(arrayOf("application/pdf", "image/*")) }, modifier = Modifier.weight(1f)) {
                        Text(if (letterheadPath.isBlank()) "Upload letter pad" else "Change letter pad")
                    }
                    if (letterheadPath.isNotBlank()) OutlinedButton(onClick = { prefs.labLetterheadPath = ""; letterheadPath = "" }) { Text("Remove") }
                }
                if (letterheadPath.isNotBlank()) {
                    Text("Letter pad set: ${java.io.File(letterheadPath).name}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = topSkip, onValueChange = { topSkip = it.filter { c -> c.isDigit() }; prefs.labTopSkipLines = topSkip.toIntOrNull() ?: 0 },
                            label = { Text("Top lines to skip") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = bottomSkip, onValueChange = { bottomSkip = it.filter { c -> c.isDigit() }; prefs.labBottomSkipLines = bottomSkip.toIntOrNull() ?: 0 },
                            label = { Text("Bottom lines to skip") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Divider(Modifier.padding(vertical = 16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Item batch tracking", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Track batch numbers + expiry dates per item; enable the expiry report.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                    )
                }
                Switch(checked = requireBatch, onCheckedChange = { requireBatch = it; prefs.requireItemBatch = it })
            }
        }
    }
}
