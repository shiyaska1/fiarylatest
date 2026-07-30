package com.billing.pos.ui.common

import android.app.Activity
import android.app.KeyguardManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity

/** Unlocked for this app run; cleared when the process dies (so a fresh launch asks again). */
object AppLockGate { var unlocked = false }

/**
 * Shows [content] only after the user confirms the phone's own lock (fingerprint / PIN /
 * pattern). Uses BiometricPrompt where available and falls back to the system
 * confirm-credential screen. No separate app password is stored anywhere.
 */
@Composable
fun AppLockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var prompting by remember { mutableStateOf(false) }

    // Fallback: the system's "confirm your PIN/pattern" screen.
    val credentialLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        prompting = false
        if (result.resultCode == Activity.RESULT_OK) onUnlocked()
        else error = "Unlock cancelled"
    }

    fun confirmWithKeyguard() {
        val km = context.getSystemService(KeyguardManager::class.java)
        if (km == null || !km.isDeviceSecure) {
            // Phone has no lock set — nothing to check against, so let the user in.
            onUnlocked(); return
        }
        @Suppress("DEPRECATION")
        val intent = km.createConfirmDeviceCredentialIntent("Unlock POS Billing", "Confirm your phone lock to continue")
        if (intent == null) onUnlocked()
        else { prompting = true; runCatching { credentialLauncher.launch(intent) }.onFailure { prompting = false; error = "Could not open the lock screen" } }
    }

    fun authenticate() {
        error = null
        val activity = context as? FragmentActivity
        val canBiometric = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && activity != null &&
            BiometricManager.from(context).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            ) == BiometricManager.BIOMETRIC_SUCCESS

        if (!canBiometric) { confirmWithKeyguard(); return }

        prompting = true
        val prompt = BiometricPrompt(
            activity!!,
            androidx.core.content.ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    prompting = false; onUnlocked()
                }
                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    prompting = false
                    error = if (code == BiometricPrompt.ERROR_USER_CANCELED || code == BiometricPrompt.ERROR_NEGATIVE_BUTTON)
                        "Unlock cancelled" else msg.toString()
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock POS Billing")
            .setSubtitle("Use your phone's fingerprint, PIN or pattern")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        runCatching { prompt.authenticate(info) }.onFailure { prompting = false; confirmWithKeyguard() }
    }

    // Ask as soon as the gate appears.
    LaunchedEffect(Unit) { authenticate() }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.Lock, contentDescription = null,
            modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary
        )
        Text(
            "POS Billing is locked",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            "Confirm your phone lock to continue",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
        error?.let {
            Text(
                it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
        Button(onClick = { authenticate() }, enabled = !prompting, modifier = Modifier.padding(top = 20.dp)) {
            Text(if (prompting) "Waiting…" else "Unlock")
        }
    }
}
