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
 * Optional CameraX preview helper for future local-only UI tooling.
 * Live dual-camera WebRTC session uses [LocalCameraSource] (single camera owner).
 */
@Singleton
class CameraXPreviewHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val running = AtomicBoolean(false)

    suspend fun bindFrontPreview(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
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
        }.onFailure { Timber.e(it, "CameraX preview bind failed") }
    }

    fun unbind() {
        running.set(false)
        runCatching {
            ProcessCameraProvider.getInstance(context).get().unbindAll()
        }
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
