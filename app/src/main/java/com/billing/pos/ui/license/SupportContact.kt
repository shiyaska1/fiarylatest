package com.billing.pos.ui.license

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.billing.pos.data.License

/**
 * "To get a licence key, contact us" block — shown on every screen that asks for an
 * activation key, so the customer always has the number and email in front of them.
 */
@Composable
fun SupportContactBlock(deviceId: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    val context = LocalContext.current

    fun open(uri: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }

    Column(modifier.fillMaxWidth().padding(top = if (compact) 8.dp else 14.dp)) {
        Text(
            "To buy or renew, contact us for a licence key:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline
        )
        Text(
            License.SUPPORT_PHONE,
            style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 2.dp)
        )
        Text(
            License.SUPPORT_EMAIL,
            style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { open(License.buyUrlFor(deviceId)) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.Phone, contentDescription = null)
                Text("  WhatsApp")
            }
            OutlinedButton(
                onClick = {
                    open(
                        "mailto:${License.SUPPORT_EMAIL}?subject=" +
                            Uri.encode("POS Billing licence") + "&body=" +
                            Uri.encode("My Device ID is $deviceId")
                    )
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.Email, contentDescription = null)
                Text("  Email")
            }
        }
    }
}
