package com.awaz.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.awaz.app.R

/**
 * Foreground service for microphone and voice processing.
 * Started once from the visible Activity when the user first enables AWAZ.
 * Stays running as an idle foreground service (no microphone capture while idle).
 * Audio sessions are started and stopped inside this running service.
 * Never started from the AccessibilityService.
 */
class VoiceForegroundService : Service() {

    companion object {
        private const val TAG = "VoiceFgService"
        private const val CHANNEL_ID = "awaz_voice_channel"
        private const val NOTIFICATION_ID = 2001

        const val ACTION_START = "com.awaz.app.action.START_VOICE"
        const val ACTION_STOP = "com.awaz.app.action.STOP_VOICE"

        private val _isRunning = kotlinx.coroutines.flow.MutableStateFlow(false)
        val isRunning: kotlinx.coroutines.flow.StateFlow<Boolean> = _isRunning.kotlinx.coroutines.flow.asStateFlow()

        fun isServiceRunning(): Boolean = _isRunning.value

        fun start(context: Context) {
            val intent = Intent(context, VoiceForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, VoiceForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
        }

        fun playErrorTone() {
            try {
                ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
                    .startTone(ToneGenerator.TONE_PROP_BEEP2, 350)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to play error tone: ${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val notification = buildOngoingNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        _isRunning.value = true
        try {
            com.awaz.app.AwazApplication.instance.navigationStateManager.onServiceStarted()
        } catch (_: Exception) {}
        Log.i(TAG, "VoiceForegroundService running in idle foreground state")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        _isRunning.value = false
        try {
            com.awaz.app.AwazApplication.instance.navigationStateManager.onServiceStopped()
        } catch (_: Exception) {}
        Log.i(TAG, "VoiceForegroundService stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildOngoingNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentTitle("")
            .setContentText("")
            .build()
    }
}
