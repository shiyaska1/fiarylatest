package com.billing.pos.diary

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.billing.pos.data.AppDatabase
import com.billing.pos.data.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Re-schedules diary reminders after a device reboot, and restarts the hourly voice diary. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val appContext = context.applicationContext
        // Resume the background voice diary if it was on (APK build only).
        if (com.billing.pos.BuildConfig.DEBUG && AppPrefs(appContext).autoVoiceDiary) {
            runCatching { com.billing.pos.audio.AutoDiaryService.start(appContext) }
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AppDatabase.get(appContext).diaryDao().allWithReminder().forEach {
                    ReminderScheduler.schedule(appContext, it)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
