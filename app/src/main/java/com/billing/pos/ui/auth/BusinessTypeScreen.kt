package com.billing.pos.ui.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.billing.pos.data.AppPrefs
import com.billing.pos.ui.settings.BUSINESS_TYPES

/**
 * One-time question on first launch: what is this app for?
 *
 * The choice drives which features the dashboard shows. "Personal" strips it back to the
 * everyday tools; the shop types show the full billing app.
 */
@Composable
fun BusinessTypeScreen(onChosen: () -> Unit) {
    val context = LocalContext.current
    val prefs = androidx.compose.runtime.remember { AppPrefs(context) }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text(
            "Welcome",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 24.dp)
        )
        Text(
            "How will you use the app? This sets up your home screen and can be changed later in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 6.dp, bottom = 16.dp)
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(BUSINESS_TYPES) { type ->
                Card(
                    Modifier.fillMaxWidth().clickable {
                        prefs.businessType = type
                        if (type == "Medical store") prefs.requireItemBatch = true
                        prefs.onboarded = true
                        onChosen()
                    }
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(type, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (type == "Personal") {
                            Text(
                                "Diary, sticky notes, calculator, payments, receipts, cash book — no shop billing.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }
    }
}
