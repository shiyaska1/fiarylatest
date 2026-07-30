package com.billing.pos.ui.auth

import android.app.Application
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.auth.Session
import com.billing.pos.data.Repository
import kotlinx.coroutines.launch

class LoginViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    private val prefs = com.billing.pos.data.AppPrefs(app)

    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    /** Show the printed credentials only while the factory password is still in place. */
    var showDefaults by mutableStateOf(false); private set

    init {
        viewModelScope.launch {
            repo.ensureDefaults()
            showDefaults = repo.usesDefaultPassword()
        }
    }

    /** [onSuccess] receives true when the user must set a new password before continuing. */
    fun login(username: String, password: String, onSuccess: (mustChangePassword: Boolean) -> Unit) {
        if (username.isBlank() || password.isBlank()) { error = "Enter username and password"; return }
        loading = true; error = null
        viewModelScope.launch {
            val user = repo.login(username, password)
            loading = false
            if (user == null) {
                error = "Invalid username or password"
            } else {
                Session.login(user)
                prefs.loggedInUserId = user.id   // persist so login survives app restart
                onSuccess(repo.isDefaultAccount(user))
            }
        }
    }
}

@Composable
fun LoginScreen(
    onLoggedIn: (mustChangePassword: Boolean) -> Unit,
    vm: LoginViewModel = viewModel()
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("POS Billing", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Sign in to continue",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )

        if (vm.showDefaults) {
            // First run only. Disappears for good once the password is changed.
            Text(
                "Username: ${com.billing.pos.data.Repository.DEFAULT_USERNAME}    Password: ${com.billing.pos.data.Repository.DEFAULT_PASSWORD}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 12.dp)
            )
            Text(
                "You will be asked to set your own password after signing in.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }

        vm.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Button(
            onClick = { vm.login(username.trim(), password) { must -> onLoggedIn(must) } },
            enabled = !vm.loading,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
        ) {
            if (vm.loading) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
            Text("Login")
        }
    }
}
