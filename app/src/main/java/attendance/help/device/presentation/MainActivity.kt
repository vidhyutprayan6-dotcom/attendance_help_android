package attendance.help.device.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import attendance.help.device.domain.repository.SessionRepository
import attendance.help.device.presentation.navigation.AppNavHost
import attendance.help.device.presentation.navigation.Routes
import attendance.help.device.presentation.theme.AttendanceHelpTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var sessionRepository: SessionRepository

    private var startRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            val setupDone = sessionRepository.setupComplete.first()
            startRoute = if (setupDone) Routes.Home else Routes.Welcome
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
}
