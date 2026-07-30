package com.billing.pos.ui.printer

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.billing.pos.data.AppPrefs
import com.billing.pos.print.ThermalPrinter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterSetupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val prefs = remember { AppPrefs(context) }

    var selectedAddr by remember { mutableStateOf(prefs.printerAddress) }
    var devices by remember { mutableStateOf(ThermalPrinter.bondedPrinters(context)) }
    var problem by remember { mutableStateOf(ThermalPrinter.bluetoothProblem(context)) }
    var busy by remember { mutableStateOf(false) }

    fun refresh() {
        devices = ThermalPrinter.bondedPrinters(context)
        problem = ThermalPrinter.bluetoothProblem(context)
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) refresh() else scope.launch { snackbar.showSnackbar("Bluetooth permission denied") }
    }
    fun ensurePermThen(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !ThermalPrinter.hasConnectPermission(context)) {
            permLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else action()
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !ThermalPrinter.hasConnectPermission(context)) {
            permLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    fun openBluetoothSettings() {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun doTest() {
        ensurePermThen {
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { ThermalPrinter.testPrint(context, AppPrefs(context).company) }
                }
                busy = false
                result.onSuccess { snackbar.showSnackbar("Test slip sent to printer") }
                    .onFailure { snackbar.showSnackbar(it.message ?: "Print failed") }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Thermal Printer") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    IconButton(onClick = { ensurePermThen { refresh() } }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
            Text(
                "Pair your printer once in Android Bluetooth settings, then pick it here and tap Test print.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
            )

            problem?.let { p ->
                Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(p, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (p.contains("permission", true)) {
                                Button(onClick = { permLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) }) { Text("Grant") }
                            }
                            OutlinedButton(onClick = { openBluetoothSettings() }) {
                                Icon(Icons.Filled.Bluetooth, null); Text("  Bluetooth settings")
                            }
                        }
                    }
                }
            }

            Text("Paired devices", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
            if (devices.isEmpty()) {
                Text(
                    "No paired Bluetooth devices found. Turn the printer on, pair it in Bluetooth settings, then tap Refresh.",
                    color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 8.dp)
                )
                OutlinedButton(onClick = { openBluetoothSettings() }, modifier = Modifier.padding(top = 8.dp)) {
                    Icon(Icons.Filled.Bluetooth, null); Text("  Open Bluetooth settings")
                }
            } else {
                devices.forEach { d ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            selectedAddr = d.address
                            prefs.printerAddress = d.address
                            prefs.printerName = d.name
                        }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selectedAddr == d.address, onClick = {
                            selectedAddr = d.address
                            prefs.printerAddress = d.address
                            prefs.printerName = d.name
                        })
                        Column(Modifier.weight(1f)) {
                            Text(d.name, fontWeight = FontWeight.SemiBold)
                            Text(d.address, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    Divider()
                }
            }

            Button(
                onClick = { doTest() },
                enabled = !busy && devices.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
            ) { Icon(Icons.Filled.Print, null); Text("  Test print") }

            if (selectedAddr.isBlank() && devices.isNotEmpty()) {
                Text(
                    "No printer selected — the app will use the first paired printer. Select one above to be sure.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
