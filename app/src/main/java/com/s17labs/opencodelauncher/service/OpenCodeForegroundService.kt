package com.s17labs.opencodelauncher.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.s17labs.opencodelauncher.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Phase 0 stub. Phase 3 will:
 * - start `opencode web --hostname 127.0.0.1 --port <port>` via proot exec
 * - capture stdout to find the bound URL/port
 * - auto-restart on unexpected death, not on user-initiated stop
 *
 * Pattern mirrors RevNotify's persistent-notification approach:
 * explicit start/stop, restart-on-crash but not restart-on-user-stop.
 */
class OpenCodeForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "opencode_launcher_status"
        const val NOTIF_ID = 1001
        const val ACTION_START = "com.s17labs.opencodelauncher.action.START"
        const val ACTION_STOP = "com.s17labs.opencodelauncher.action.STOP"

        fun startIntent(ctx: Context): Intent =
            Intent(ctx, OpenCodeForegroundService::class.java).setAction(ACTION_START)

        fun stopIntent(ctx: Context): Intent =
            Intent(ctx, OpenCodeForegroundService::class.java).setAction(ACTION_STOP)
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var userStopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                userStopped = true
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                userStopped = false
                startForeground(NOTIF_ID, buildNotification("Starting OpenCode…"))
                scope.launch {
                    // Phase 3: exec proot + opencode web here, update notification with URL.
                }
            }
        }
        // Restart on crash, but onStartCommand re-entry after user stop does nothing
        // because userStopped is only reset on explicit START.
        return if (userStopped) START_NOT_STICKY else START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "OpenCode status",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("OpenCode Launcher")
            .setContentText(text)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .setOngoing(true)
            .build()
    }
}
