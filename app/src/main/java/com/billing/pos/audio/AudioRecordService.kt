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

/**
 * Records a voice note from a foreground service, so recording keeps going when the diary is
 * minimised or the screen turns off — Android 10+ blocks the microphone in the background
 * unless a foreground service (with a visible notification) holds it.
 *
 * This service is declared only in the debug/APK build's manifest, so the Play (release) AAB
 * never ships a foreground service. The diary starts it only when [com.billing.pos.BuildConfig.DEBUG]
 * is true; the release build keeps recording only while the screen is open, as before.
 */
class AudioRecordService : Service() {

    private var recorder: VoiceRecorder? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val notif = buildNotification()
                if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } else {
                    startForeground(NOTIF_ID, notif)
                }
                begin(intent.getStringExtra(EXTRA_PATH))
            }
            ACTION_STOP -> {
                end()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
                else @Suppress("DEPRECATION") stopForeground(true)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun begin(path: String?) {
        if (path == null) { recording = false; return }
        try {
            recorder = VoiceRecorder(path).also { it.start() }
            recording = true
        } catch (e: Exception) {
            recording = false
        }
    }

    private fun end() {
        val rec = recorder ?: run { recording = false; return }
        runCatching { rec.stop() }   // blocks until the file is finalised
        recorder = null
        recording = false            // set only after stop() has flushed the file
    }

    override fun onDestroy() {
        runCatching { recorder?.stop() }
        recorder = null
        recording = false
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "Voice recording", NotificationManager.IMPORTANCE_LOW)
            ch.setSound(null, null)
            mgr.createNotificationChannel(ch)
        }
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("Recording voice note")
            .setContentText("Recording continues in the background. Return to the diary to stop.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 5120
        private const val CHANNEL = "voice_recording"
        private const val ACTION_START = "com.billing.pos.audio.START"
        private const val ACTION_STOP = "com.billing.pos.audio.STOP"
        private const val EXTRA_PATH = "path"

        /** True while the service is actively recording; the diary polls this after stop. */
        @Volatile var recording: Boolean = false

        fun start(context: Context, path: String) {
            recording = true
            val i = Intent(context, AudioRecordService::class.java).setAction(ACTION_START).putExtra(EXTRA_PATH, path)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            val i = Intent(context, AudioRecordService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(i) }
        }
    }
}
