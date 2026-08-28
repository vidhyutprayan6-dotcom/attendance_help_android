package attendance.help.device.service

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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference

/**
 * Foreground service required while MediaProjection / screen share is active.
 * Android 14+ requires this service to be in the foreground BEFORE capture starts.
 */
class ScreenShareService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Timber.tag("SCREEN_CAPTURE").i("Stop requested from notification")
            stopCallback?.invoke()
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel()
        val stopIntent = Intent(this, ScreenShareService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.screen_share_notif_title))
            .setContentText(getString(R.string.screen_share_notif_text))
            .setSmallIcon(R.drawable.ic_stat_link)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.stop_screen_share), stopPending)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
        foregroundReady.getAndSet(null)?.complete(Unit)
        Timber.tag("SCREEN_CAPTURE").i("Foreground service active")
        return START_STICKY
    }

    override fun onDestroy() {
        foregroundReady.getAndSet(null)?.complete(Unit)
        super.onDestroy()
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.screen_share_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.screen_share_channel_desc)
        }
        mgr.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "ah_screen_share"
        private const val NOTIF_ID = 4202
        const val ACTION_STOP = "attendance.help.device.action.STOP_SCREEN_SHARE"
        private val foregroundReady = AtomicReference<CompletableDeferred<Unit>?>(null)

        @Volatile
        var stopCallback: (() -> Unit)? = null

        suspend fun startAndAwait(context: Context) {
            val deferred = CompletableDeferred<Unit>()
            foregroundReady.set(deferred)
            context.startForegroundService(Intent(context, ScreenShareService::class.java))
            withTimeout(10_000) { deferred.await() }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenShareService::class.java))
        }
    }
}
