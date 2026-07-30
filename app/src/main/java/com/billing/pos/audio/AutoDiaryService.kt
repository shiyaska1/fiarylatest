package com.billing.pos.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.DiaryRepository
import com.billing.pos.diary.AttachmentStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hourly background voice diary (APK build only).
 *
 * While enabled, this foreground service voice-records continuously via [VoiceRecorder] and, once
 * an hour, rolls the recording over into a new diary entry — titled with the date-time, of type
 * "voice", with the recording attached as an AUDIO block. Silent hours (no voice detected) are
 * discarded rather than saved as empty entries.
 *
 * It is declared only in src/debug/AndroidManifest.xml, so the Play (release) AAB never ships it.
 */
class AutoDiaryService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    @Volatile private var rec: VoiceRecorder? = null
    @Volatile private var segFile: File? = null
    @Volatile private var segStart = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                AppPrefs(this).autoVoiceDiary = false
                stopEverything()
                return START_NOT_STICKY
            }
            else -> {
                AppPrefs(this).autoVoiceDiary = true
                val notif = buildNotification()
                if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } else {
                    startForeground(NOTIF_ID, notif)
                }
                if (loopJob == null || loopJob?.isActive != true) loopJob = scope.launch { loop() }
            }
        }
        return START_STICKY
    }

    private suspend fun CoroutineScope.loop() {
        val repo = DiaryRepository(applicationContext)
        while (isActive) {
            beginSegment()
            var elapsed = 0L
            while (isActive && elapsed < HOUR_MS) { delay(STEP_MS); elapsed += STEP_MS }
            if (isActive) finalizeSegment(repo, save = true) // hourly rollover
        }
    }

    private fun beginSegment() {
        val f = File(AttachmentStore.dir(applicationContext), "autovoice_${System.currentTimeMillis()}.m4a")
        segFile = f
        segStart = System.currentTimeMillis()
        rec = try { VoiceRecorder(f.absolutePath).also { it.start() } } catch (e: Exception) { null }
    }

    private suspend fun finalizeSegment(repo: DiaryRepository, save: Boolean) {
        val r = rec ?: return
        val f = segFile
        val start = segStart
        rec = null
        segFile = null
        runCatching { r.stop() } // blocks until the file is flushed
        val dur = System.currentTimeMillis() - start
        if (save && r.capturedAudio && f != null && f.exists() && f.length() > 0) {
            val title = TITLE_FMT.format(Date(start))
            runCatching {
                repo.saveAutoVoiceEntry(title, start, f.absolutePath, "Voice ${FILE_FMT.format(Date(start))}.m4a", dur)
            }
        } else {
            f?.let { runCatching { it.delete() } } // silent hour → nothing worth keeping
        }
    }

    private fun stopEverything() {
        loopJob?.cancel()
        loopJob = null
        // Save whatever was captured this partial hour, then tear the service down.
        CoroutineScope(Dispatchers.IO).launch {
            finalizeSegment(DiaryRepository(applicationContext), save = true)
            withContext(Dispatchers.Main) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
                else @Suppress("DEPRECATION") stopForeground(true)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        loopJob?.cancel()
        runCatching { rec?.stop() }
        rec = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "Voice diary", NotificationManager.IMPORTANCE_LOW)
            ch.setSound(null, null)
            mgr.createNotificationChannel(ch)
        }
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("Voice diary on")
            .setContentText("Recording voice hourly into your diary. Turn off in My Diary.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 5121
        private const val CHANNEL = "voice_diary"
        private const val ACTION_STOP = "com.billing.pos.audio.AUTODIARY_STOP"
        private const val HOUR_MS = 60L * 60L * 1000L
        private const val STEP_MS = 60L * 1000L
        private val TITLE_FMT = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        private val FILE_FMT = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())

        fun start(context: Context) {
            val i = Intent(context, AutoDiaryService::class.java)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            val i = Intent(context, AutoDiaryService::class.java).setAction(ACTION_STOP)
            runCatching { ContextCompat.startForegroundService(context, i) }
        }
    }
}
