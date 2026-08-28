package attendance.help.device.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Keeps the Remote phone's front camera physically ON during a dual-camera session.
 * Preview may be tiny/hidden — product rule requires the camera hardware on, while
 * the visible "camera feed" on both phones shows the Control phone's video.
 */
@Singleton
class RemotePhysicalCamera @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val running = AtomicBoolean(false)

    val isRunning: Boolean get() = running.get()

    suspend fun start(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        if (running.get()) return
        val provider = awaitCameraProvider()
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }
        val selector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
            .build()
        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, preview)
            running.set(true)
            Timber.i("Remote physical camera ON")
        }.onFailure {
            running.set(false)
            Timber.e(it, "Remote physical camera failed")
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching {
            ProcessCameraProvider.getInstance(context).get().unbindAll()
        }
        Timber.i("Remote physical camera OFF")
    }

    private suspend fun awaitCameraProvider(): ProcessCameraProvider =
        suspendCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                { cont.resume(future.get()) },
                ContextCompat.getMainExecutor(context)
            )
        }
}
