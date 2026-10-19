package com.al.pluginsecure

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Keep-alive foreground service used while realtime location updates are active.
 *
 * Android throttles location delivery for background apps, so a foreground
 * service of type "location" keeps the process (and the WebView) alive while the
 * app is multitasking. The actual location callbacks are owned by
 * [GPSVerificationHelper] in the plugin process; this service only provides the
 * ongoing notification required by the platform.
 */
class LocationTrackingService : Service() {
    companion object {
        const val CHANNEL_ID = "pluginsecure_location"
        const val NOTIFICATION_ID = 9417
        const val ACTION_START = "com.al.pluginsecure.action.START_LOCATION"
        const val ACTION_STOP = "com.al.pluginsecure.action.STOP_LOCATION"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.pluginsecure_location_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
                channel.setShowBadge(false)
                manager.createNotificationChannel(channel)
            }
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.pluginsecure_location_title))
            .setContentText(getString(R.string.pluginsecure_location_text))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}
