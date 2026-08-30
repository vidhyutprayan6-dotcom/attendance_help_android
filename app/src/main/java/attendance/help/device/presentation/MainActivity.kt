package attendance.help.device.presentation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import attendance.help.device.device.control.ScreenShareCoordinator
import attendance.help.device.domain.model.ServerLinkState
import attendance.help.device.domain.repository.SessionRepository
import attendance.help.device.presentation.navigation.AppNavHost
import attendance.help.device.presentation.navigation.Routes
import attendance.help.device.presentation.theme.AttendanceHelpTheme
import attendance.help.device.utils.AppLocaleHelper
import attendance.help.device.webrtc.SessionController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var sessionRepository: SessionRepository
    @Inject lateinit var sessionController: SessionController
    @Inject lateinit var screenShareCoordinator: ScreenShareCoordinator

    /** Show welcome immediately — never block the UI thread waiting for preferences. */
    private var startRoute by mutableStateOf(Routes.Welcome)

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            screenShareCoordinator.emitGranted(result.data!!)
        } else {
            screenShareCoordinator.emitDenied()
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* ongoing notification may still work as FGS on some OEMs */ }

    override fun attachBaseContext(newBase: Context) {
        AppLocaleHelper.syncDelegateWithStoredPreference(newBase)
        super.attachBaseContext(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        screenShareCoordinator.bindActivity(this)
        screenShareCoordinator.setCaptureLauncher {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureLauncher.launch(mgr.createScreenCaptureIntent())
        }
        maybeRequestNotificationPermission()
        sessionController.restoreStatusBarIfNeeded()

        lifecycleScope.launch {
            val link = withContext(Dispatchers.IO) {
                sessionRepository.serverLinkState.first()
            }
            if (link == ServerLinkState.CONNECTED && AppLocaleHelper.hasSavedLanguage(this@MainActivity)) {
                startRoute = Routes.Home
            }
        }

        setContent {
            AttendanceHelpTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavHost(startDestination = startRoute)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        screenShareCoordinator.bindActivity(this)
        screenShareCoordinator.setCaptureLauncher {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureLauncher.launch(mgr.createScreenCaptureIntent())
        }
        sessionController.announcePresence()
    }

    override fun onDestroy() {
        screenShareCoordinator.clearCaptureLauncher()
        screenShareCoordinator.unbindActivity(this)
        super.onDestroy()
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
