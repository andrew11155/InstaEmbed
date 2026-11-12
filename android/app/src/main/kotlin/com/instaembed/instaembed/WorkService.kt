package com.instaembed.instaembed

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that keeps the process running while a video is downloaded or compressed.
 * Without it, Samsung's app freezer (and Android's cached-app freezer) can pause InstaEmbed
 * mid-compression as soon as the screen turns off or the user switches apps.
 *
 * Callers bracket work with [begin] / [end]; nested or concurrent work is reference-counted and
 * the service stops when the last caller ends. It owns the progress notification ([update]).
 */
class WorkService : Service() {
    companion object {
        private const val TAG = "InstaEmbed"
        const val CHANNEL_ID = "instaembed_progress"
        const val NOTIFICATION_ID = 1001
        private const val ACTION_UPDATE = "com.instaembed.instaembed.WORK_UPDATE"
        private const val ACTION_STOP = "com.instaembed.instaembed.WORK_STOP"
        private const val EXTRA_TEXT = "text"

        private var activeCount = 0
        private var lastText = "Preparing video..."

        /** Starts (or joins) foreground work, showing [text] in the progress notification. */
        @Synchronized
        fun begin(context: Context, text: String) {
            activeCount++
            lastText = text
            send(context, ACTION_UPDATE)
        }

        /** Changes the progress notification text while work is running. */
        @Synchronized
        fun update(context: Context, text: String) {
            lastText = text
            if (activeCount == 0) return
            try {
                context.getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(context, text))
            } catch (_: Exception) {}
        }

        /** Ends one unit of work; the service stops once nothing is running. */
        @Synchronized
        fun end(context: Context) {
            if (activeCount == 0) return
            activeCount--
            if (activeCount == 0) send(context, ACTION_STOP)
        }

        // Every start goes through startForegroundService and every onStartCommand calls
        // startForeground, so a stop that races the first start can't trip the 10s FGS deadline
        private fun send(context: Context, action: String) {
            try {
                val intent = Intent(context, WorkService::class.java).setAction(action)
                ContextCompat.startForegroundService(context.applicationContext, intent)
            } catch (e: Exception) {
                // Work still runs without the service; it just loses its freeze protection
                Log.w(TAG, "WorkService start failed: ${e.message}")
            }
        }

        fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, "InstaEmbed", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Video processing progress"
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        private fun buildNotification(context: Context, text: String): Notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("InstaEmbed")
                .setContentText(text)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel(this)
        val notification = buildNotification(this, lastText)
        try {
            when {
                Build.VERSION.SDK_INT >= 35 ->
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
                Build.VERSION.SDK_INT >= 29 ->
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                else -> startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        // Checked instead of trusting ACTION_STOP: new work may have begun since the stop was sent
        val idle = synchronized(Companion) { activeCount == 0 }
        if (idle) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    // Android 15+ caps media processing at 6 hours a day; nothing here should get close
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "WorkService hit the foreground time limit")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
