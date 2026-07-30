package com.billing.pos.medical

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.billing.pos.MainActivity
import com.billing.pos.R
import com.billing.pos.data.AppDatabase
import com.billing.pos.data.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Daily "medicine expiring" check for medical stores. Repeats every day while the setting is
 * on, so it keeps reminding until the batch is sold, removed or purchase-returned — once no
 * batch qualifies, the daily check simply posts nothing.
 */
object ExpiryAlarm {

    const val CHANNEL_ID = "expiry_alerts"
    private const val REQUEST = 90210
    private const val NOTIFICATION_ID = 9021
    /** Time of day the daily check runs. */
    private const val HOUR = 9

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Expiry alerts", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Medicines nearing their expiry date" }
            )
        }
    }

    /** Turns the daily check on, or cancels it when the setting is off. Never throws. */
    fun sync(context: Context) {
        val prefs = AppPrefs(context)
        val on = prefs.expiryAlert && prefs.businessType == "Medical store"
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(context)
        if (!on) { runCatching { am.cancel(pi) }; return }
        ensureChannel(context)
        runCatching {
            am.setInexactRepeating(AlarmManager.RTC_WAKEUP, nextRun(), AlarmManager.INTERVAL_DAY, pi)
        }
    }

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST, Intent(context, ExpiryReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Next [HOUR]:00 — today if it hasn't passed, else tomorrow. */
    private fun nextRun(): Long {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, HOUR); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis
    }

    /** How many in-stock batches fall inside the warning window, and the soonest one's name. */
    suspend fun expiringCount(context: Context): Pair<Int, String?> {
        val prefs = AppPrefs(context)
        val db = AppDatabase.get(context)
        val cutoff = System.currentTimeMillis() + prefs.expiryAlertDays.toLong() * 86_400_000L
        val batches = db.itemBatchDao().all().filter { it.expiryMillis in 1..cutoff && it.quantity > 0 }
        if (batches.isEmpty()) return 0 to null
        val names = db.itemDao().all().associate { it.id to it.name }
        val soonest = batches.minByOrNull { it.expiryMillis }
        return batches.size to names[soonest?.itemId ?: 0L]
    }

    fun notify(context: Context, count: Int, firstName: String?, days: Int) {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (count == 1 && firstName != null)
            "$firstName is expiring within $days days"
        else
            "$count batches expiring within $days days" + (firstName?.let { " — soonest: $it" } ?: "")
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Medicines nearing expiry")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n) }
    }
}

/** Fires once a day: posts a notification if anything is near expiry. */
class ExpiryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = AppPrefs(context)
        if (!prefs.expiryAlert || prefs.businessType != "Medical store") return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val (count, firstName) = ExpiryAlarm.expiringCount(context)
                if (count > 0) ExpiryAlarm.notify(context, count, firstName, prefs.expiryAlertDays)
            } catch (_: Exception) {
                // A failed check must never crash the app.
            } finally {
                pending.finish()
            }
        }
    }
}
