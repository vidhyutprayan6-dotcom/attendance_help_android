package attendance.help.device.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import attendance.help.device.presentation.home.HomeScreen
import attendance.help.device.presentation.home.HomeViewModel
import attendance.help.device.presentation.pairing.PairingScreen
import attendance.help.device.presentation.pairing.PairingViewModel
import attendance.help.device.presentation.role.RoleSelectScreen
import attendance.help.device.presentation.role.RoleSelectViewModel
import attendance.help.device.presentation.session.SessionScreen
import attendance.help.device.presentation.session.SessionViewModel
import attendance.help.device.presentation.welcome.WelcomeScreen

@Composable
fun AppNavHost(
    startDestination: String
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Routes.Welcome) {
            WelcomeScreen(
                onContinue = { navController.navigate(Routes.RoleSelect) }
            )
        }
        composable(Routes.RoleSelect) {
            val viewModel: RoleSelectViewModel = hiltViewModel()
            RoleSelectScreen(
                onRoleConfirmed = {
                    navController.navigate(Routes.Home) {
                        popUpTo(Routes.Welcome) { inclusive = true }
                    }
                },
                viewModel = viewModel
            )
        }
        composable(Routes.Home) {
            val viewModel: HomeViewModel = hiltViewModel()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            HomeScreen(
                uiState = uiState,
                onPairing = { navController.navigate(Routes.Pairing) },
                onSession = { navController.navigate(Routes.Session) }
            )
        }
        composable(Routes.Pairing) {
            val viewModel: PairingViewModel = hiltViewModel()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            PairingScreen(
                uiState = uiState,
                onStartWaiting = viewModel::startControllerWaiting,
                onRefreshIp = viewModel::refreshIp,
                onConnect = viewModel::connectRemote,
                onBack = { navController.popBackStack() },
                onGoSession = { navController.navigate(Routes.Session) }
            )
        }
        composable(Routes.Session) {
            val viewModel: SessionViewModel = hiltViewModel()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val role by viewModel.role.collectAsStateWithLifecycle()
            SessionScreen(
                uiState = uiState,
                role = role,
                onBindRenderers = viewModel::bindRenderers,
                onUnbindRenderers = viewModel::unbindRenderers,
                onOpenCamera = viewModel::openCameras,
                onCloseCamera = viewModel::closeCameras,
                onPing = viewModel::ping,
                onDisconnect = {
                    viewModel.disconnect()
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
