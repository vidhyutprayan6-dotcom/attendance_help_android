package attendance.help.device.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import attendance.help.device.domain.model.ServerLinkState
import attendance.help.device.domain.repository.SessionRepository
import attendance.help.device.presentation.navigation.AppNavHost
import attendance.help.device.presentation.navigation.Routes
import attendance.help.device.presentation.theme.AttendanceHelpTheme
import attendance.help.device.webrtc.SessionController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var sessionRepository: SessionRepository
    @Inject lateinit var sessionController: SessionController

    private var startRoute by mutableStateOf<String?>(null)

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* ongoing notification may still work as FGS on some OEMs */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeRequestNotificationPermission()
        sessionController.restoreStatusBarIfNeeded()

        lifecycleScope.launch {
            val link = sessionRepository.serverLinkState.first()
            startRoute = when {
                link == ServerLinkState.CONNECTED -> Routes.Home
                else -> Routes.Welcome
            }
        }

        setContent {
            AttendanceHelpTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val route = startRoute
                    if (route != null) {
                        AppNavHost(startDestination = route)
                    }
                }
            }
        }
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
