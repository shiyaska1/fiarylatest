package com.billing.pos.diary

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.billing.pos.MainActivity
import com.billing.pos.data.DiaryEntry
import java.util.Calendar

/** Schedules diary reminders via AlarmManager.setAlarmClock (exact, no special permission). */
object ReminderScheduler {

    /** Returns true if the alarm was scheduled. Never throws (scheduling must not crash a save). */
    fun schedule(context: Context, entry: DiaryEntry): Boolean {
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return try {
            if (!entry.reminderEnabled || entry.reminderAt <= 0L) {
                am.cancel(pendingFor(context, entry.id))
                return false
            }
            val triggerAt =
                if (entry.reminderDaily) nextDaily(entry.reminderAt) else entry.reminderAt
            // Skip a one-time reminder whose time has already passed.
            if (!entry.reminderDaily && triggerAt <= System.currentTimeMillis()) return false

            Notifications.ensureChannel(context)
            val pi = pendingFor(context, entry)
            // setAlarmClock is exempt from the exact-alarm permission; if an OEM still
            // rejects it, fall back so a save never crashes.
            try {
                val show = PendingIntent.getActivity(
                    context, entry.id.toInt(),
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), pi)
            } catch (e: Exception) {
                try {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                } catch (e2: Exception) {
                    am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Re-arms the reminder to nag again after [REPEAT_INTERVAL_MS]. Never throws. */
    fun scheduleRepeat(context: Context, entry: DiaryEntry) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = System.currentTimeMillis() + REPEAT_INTERVAL_MS
        val pi = pendingFor(context, entry)
        try {
            val show = PendingIntent.getActivity(
                context, entry.id.toInt(),
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), pi)
        } catch (e: Exception) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } catch (e2: Exception) {
                runCatching { am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi) }
            }
        }
    }

    fun cancel(context: Context, entryId: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pendingFor(context, entryId))
    }

    private fun pendingFor(context: Context, entry: DiaryEntry): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_ID, entry.id)
            putExtra(EXTRA_TITLE, entry.title)
            putExtra(EXTRA_TEXT, entry.remarks)
            putExtra(EXTRA_DAILY, entry.reminderDaily)
            putExtra(EXTRA_AT, entry.reminderAt)
        }
        return PendingIntent.getBroadcast(
            context, entry.id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun pendingFor(context: Context, entryId: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
        return PendingIntent.getBroadcast(
            context, entryId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Next occurrence of the time-of-day encoded in [base]. */
    fun nextDaily(base: Long): Long {
        val src = Calendar.getInstance().apply { timeInMillis = base }
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, src.get(Calendar.HOUR_OF_DAY))
            set(Calendar.MINUTE, src.get(Calendar.MINUTE))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (next.timeInMillis <= System.currentTimeMillis()) {
            next.add(Calendar.DAY_OF_MONTH, 1)
        }
        return next.timeInMillis
    }

    /** How often a set reminder re-notifies until it is turned off or the entry is deleted. */
    const val REPEAT_INTERVAL_MS = 5 * 60 * 1000L

    const val EXTRA_ID = "id"
    const val EXTRA_TITLE = "title"
    const val EXTRA_TEXT = "text"
    const val EXTRA_DAILY = "daily"
    const val EXTRA_AT = "at"
}
