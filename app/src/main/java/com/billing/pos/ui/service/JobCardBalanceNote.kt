package com.billing.pos.ui.service

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.billing.pos.data.AppDatabase
import com.billing.pos.data.ServiceJobCard
import com.billing.pos.util.Format

/**
 * One line under a customer picker: what this customer still owes on open job cards.
 * Shows nothing when there is no balance (or the service module isn't used), so it is
 * safe to drop into the sales entry and receipt dialogs for every business type.
 */
@Composable
fun JobCardBalanceNote(customerId: Long, customerName: String, modifier: Modifier = Modifier) {
    if (customerId == 0L && customerName.isBlank()) return
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).serviceDao() }
    val cards by dao.cards().collectAsState(initial = emptyList())
    val open = cards.filter { c ->
        (if (customerId != 0L) c.customerId == customerId else c.customerName.equals(customerName.trim(), true)) &&
            !c.status.equals("Rejected", true) && c.balance > 0.005
    }
    if (open.isEmpty()) return
    Text(
        "Job card balance: ${Format.rupee(open.sumOf(ServiceJobCard::balance))}  (${open.joinToString { it.jobNo }})",
        color = MaterialTheme.colorScheme.error,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier.fillMaxWidth().padding(top = 4.dp)
    )
}
