package com.billing.pos.ui.auth

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.auth.Session
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.License
import com.billing.pos.data.Repository
import kotlinx.coroutines.launch

class BootViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    private val prefs = AppPrefs(app)

    /** Resolves the start route: "license", "dashboard" or "login". */
    fun resolve(onResolved: (route: String) -> Unit) {
        viewModelScope.launch {
            repo.ensureDefaults()
            // Start the trial clock automatically on first launch (no registration needed).
            if (prefs.installDateMillis <= 0L) prefs.installDateMillis = System.currentTimeMillis()
            when {
                // Licensing is staged: month 1, then 6, 12, 36 and 48. Each renewal needs
                // its own key, so this asks again whenever a later milestone falls due.
                License.dueMilestone(prefs.installDateMillis) > prefs.licensedMilestone ->
                    onResolved("license")
                else -> {
                    // Single-user app: log in the super-admin automatically. No login screen,
                    // no password change, no logout — the app opens straight to the dashboard.
                    val user = repo.superAdmin()
                    if (user != null) {
                        Session.login(user)
                        prefs.loggedInUserId = user.id
                    }
                    // Ask the business type once, on first launch only.
                    onResolved(if (!prefs.onboarded) "chooseBusiness" else "dashboard")
                }
            }
        }
    }
}

/** Splash that checks trial/license, restores a session, then routes onward. */
@Composable
fun BootScreen(
    onResolved: (route: String) -> Unit,
    vm: BootViewModel = viewModel()
) {
    LaunchedEffect(Unit) { vm.resolve(onResolved) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
