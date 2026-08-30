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
import timber.log.Timber

/**
 * Camera foreground service for while-in-use CAMERA on Android 14+.
 * Started from a visible Activity before opening the physical camera.
 */
class CameraCaptureService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
            return START_NOT_STICKY
        }
        return try {
            ensureChannel()
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.camera_capture_notif_title))
                .setContentText(getString(R.string.camera_capture_notif_text))
                .setSmallIcon(R.drawable.ic_stat_link)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, 0)
            } else {
                startForeground(NOTIF_ID, notification)
            }
            Timber.tag("CAMERA_CAPTURE").i("CAMERA_FGS_STARTED")
            START_STICKY
        } catch (error: Throwable) {
            Timber.tag("CAMERA_CAPTURE").e(error, "CameraCaptureService startForeground failed")
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        Timber.tag("CAMERA_CAPTURE").i("CAMERA_FGS_STOPPED")
        super.onDestroy()
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.camera_capture_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    companion object {
        private const val CHANNEL_ID = "camera_capture"
        private const val NOTIF_ID = 42
        private const val ACTION_STOP = "attendance.help.device.STOP_CAMERA_FGS"

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, CameraCaptureService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { error ->
                Timber.tag("CAMERA_CAPTURE").e(error, "CameraCaptureService.start failed")
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, CameraCaptureService::class.java).apply { action = ACTION_STOP }
                )
            }.onFailure { error ->
                Timber.tag("CAMERA_CAPTURE").e(error, "CameraCaptureService.stop failed")
            }
        }
    }
}
