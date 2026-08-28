package attendance.help.device.service

import android.app.NotificationChannel
import android.app.NotificationManager
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
        ensureChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.screen_share_notif_title))
            .setContentText(getString(R.string.screen_share_notif_text))
            .setSmallIcon(R.drawable.ic_stat_link)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
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
        Timber.i("ScreenShareService foreground active")
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
        private val foregroundReady = AtomicReference<CompletableDeferred<Unit>?>(null)

        /** Starts FGS and waits until [startForeground] has run (required before MediaProjection). */
        suspend fun startAndAwait(context: Context) {
            val deferred = CompletableDeferred<Unit>()
            foregroundReady.set(deferred)
            val intent = Intent(context, ScreenShareService::class.java)
            context.startForegroundService(intent)
            withTimeout(10_000) {
                deferred.await()
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, ScreenShareService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenShareService::class.java))
        }
    }
}
