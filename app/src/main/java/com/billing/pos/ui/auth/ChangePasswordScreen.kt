package com.billing.pos.ui.auth

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.auth.Session
import com.billing.pos.data.Repository
import kotlinx.coroutines.launch

class ChangePasswordViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    var saving by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    fun save(newPassword: String, confirm: String, onDone: () -> Unit) {
        val pw = newPassword.trim()
        when {
            pw.length < 4 -> { error = "Password must be at least 4 characters"; return }
            pw != confirm.trim() -> { error = "The two passwords do not match"; return }
            pw == Repository.DEFAULT_PASSWORD -> { error = "Choose a password other than the default one"; return }
        }
        val user = Session.current ?: run { error = "Not signed in"; return }
        saving = true; error = null
        viewModelScope.launch {
            val res = repo.updateUser(user, pw)
            saving = false
            if (res.isSuccess) {
                // Keep the in-memory session in step with the new hash.
                repo.userById(user.id)?.let { Session.login(it) }
                onDone()
            } else error = "Could not save the password"
        }
    }
}

/**
 * Forced on the first login, while the factory account still has its printed password.
 * There is no way past it except setting a new password — back is disabled on purpose.
 */
@Composable
fun ChangePasswordScreen(
    onDone: () -> Unit,
    vm: ChangePasswordViewModel = viewModel()
) {
    BackHandler(enabled = true) { /* must set a password before continuing */ }

    var pw by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Set a new password", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "You are still using the default password. Choose your own now — after this, the login screen will no longer show the default username and password.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 12.dp)
        )
        Text(
            "User: ${Session.current?.username.orEmpty()}",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )

        OutlinedTextField(
            value = pw, onValueChange = { pw = it },
            label = { Text("New password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        )
        OutlinedTextField(
            value = confirm, onValueChange = { confirm = it },
            label = { Text("Confirm new password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
        vm.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }
        Button(
            onClick = { vm.save(pw, confirm, onDone) },
            enabled = !vm.saving,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        ) {
            if (vm.saving) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
            Text("Save password")
        }
    }
}
