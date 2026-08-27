package attendance.help.device.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import attendance.help.device.R
import attendance.help.device.domain.model.DeviceMode
import attendance.help.device.presentation.MainActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * Persistent status bar ("main bar") entry.
 * Stays visible after the app UI is closed until mode is cleared / service stopped.
 */
@AndroidEntryPoint
class LinkStatusService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val modeName = intent?.getStringExtra(EXTRA_MODE) ?: DeviceMode.NONE.name
        val server = intent?.getStringExtra(EXTRA_SERVER).orEmpty()
        val detail = intent?.getStringExtra(EXTRA_DETAIL).orEmpty()
        val mode = runCatching { DeviceMode.valueOf(modeName) }.getOrDefault(DeviceMode.NONE)

        ensureChannel()
        val notification = buildNotification(mode, server, detail)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    private fun buildNotification(mode: DeviceMode, server: String, detail: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val (title, text) = when (mode) {
            DeviceMode.REMOTE -> getString(R.string.notif_remote_title) to
                getString(R.string.notif_remote_text, server)
            DeviceMode.CONTROL -> getString(R.string.notif_control_title) to
                getString(R.string.notif_control_text, server)
            DeviceMode.NONE -> getString(R.string.notif_server_title) to
                getString(R.string.notif_server_text, server)
        }
        val body = if (detail.isBlank()) text else "$text\n$detail"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "attendance_link_status"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_MODE = "mode"
        const val EXTRA_SERVER = "server"
        const val EXTRA_DETAIL = "detail"

        fun start(context: Context, mode: DeviceMode, server: String, detail: String = "") {
            val intent = Intent(context, LinkStatusService::class.java).apply {
                putExtra(EXTRA_MODE, mode.name)
                putExtra(EXTRA_SERVER, server)
                putExtra(EXTRA_DETAIL, detail)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LinkStatusService::class.java))
        }

        fun update(context: Context, mode: DeviceMode, server: String, detail: String = "") {
            start(context, mode, server, detail)
        }
    }
}
