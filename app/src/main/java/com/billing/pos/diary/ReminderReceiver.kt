package com.billing.pos.diary

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.billing.pos.MainActivity
import com.billing.pos.data.DiaryEntry
import com.billing.pos.data.DiaryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

const val EXTRA_OPEN_DIARY_ID = "open_diary_id"

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(ReminderScheduler.EXTRA_ID, 0L)
        if (id <= 0L) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val entry = runCatching { DiaryRepository(app).byId(id) }.getOrNull()
                // Entry gone or reminder switched off → stop nagging for good.
                if (entry == null || !entry.reminderEnabled) {
                    ReminderScheduler.cancel(app, id)
                    return@launch
                }
                showNotification(app, entry)
                // Nag again in 5 minutes until the user turns it off or deletes the entry.
                ReminderScheduler.scheduleRepeat(app, entry)
            } catch (e: Exception) {
                // never crash on a reminder
            } finally {
                pending.finish()
            }
        }
    }

    private fun showNotification(context: Context, entry: DiaryEntry) {
        Notifications.ensureChannel(context)
        val title = entry.title.ifBlank { "Diary reminder" }
        val text = entry.remarks

        // Tapping opens the app straight into this diary entry in edit mode.
        val open = PendingIntent.getActivity(
            context, entry.id.toInt(),
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_OPEN_DIARY_ID, entry.id)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()

        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (allowed) {
            runCatching { NotificationManagerCompat.from(context).notify(entry.id.toInt(), notification) }
        }
    }
}
