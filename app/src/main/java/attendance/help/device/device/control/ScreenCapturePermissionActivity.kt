package attendance.help.device.device.control

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * One-shot Activity that requests MediaProjection so the Remote can share its screen.
 */
@AndroidEntryPoint
class ScreenCapturePermissionActivity : ComponentActivity() {

    @Inject lateinit var coordinator: ScreenShareCoordinator

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            coordinator.emitGranted(data)
        } else {
            coordinator.emitDenied()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        launcher.launch(mgr.createScreenCaptureIntent())
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, ScreenCapturePermissionActivity::class.java)
    }
}
