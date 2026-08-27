package attendance.help.device.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import attendance.help.device.presentation.connect.ConnectScreen
import attendance.help.device.presentation.connect.ConnectViewModel
import attendance.help.device.presentation.home.HomeScreen
import attendance.help.device.presentation.home.HomeViewModel
import attendance.help.device.presentation.mode.ModeScreen
import attendance.help.device.presentation.mode.ModeViewModel
import attendance.help.device.presentation.remotelist.RemoteListScreen
import attendance.help.device.presentation.remotelist.RemoteListViewModel
import attendance.help.device.presentation.session.SessionScreen
import attendance.help.device.presentation.session.SessionViewModel
import attendance.help.device.presentation.welcome.WelcomeScreen

@Composable
fun AppNavHost(startDestination: String) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.Welcome) {
            WelcomeScreen(
                onContinue = {
                    navController.navigate(Routes.Connect) {
                        popUpTo(Routes.Welcome) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.Connect) {
            val vm: ConnectViewModel = hiltViewModel()
            val state by vm.ui.collectAsStateWithLifecycle()
            ConnectScreen(
                state = state,
                onHostChange = vm::onHostChange,
                onNameChange = vm::onNameChange,
                onConnect = vm::connect,
                onDisconnect = vm::disconnect,
                onConnectedNext = {
                    navController.navigate(Routes.Mode) {
                        popUpTo(Routes.Connect) { inclusive = false }
                    }
                },
                onGoHome = {
                    navController.navigate(Routes.Home) {
                        popUpTo(Routes.Welcome) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.Mode) {
            val vm: ModeViewModel = hiltViewModel()
            val state by vm.ui.collectAsStateWithLifecycle()
            ModeScreen(
                state = state,
                onSetRemote = { vm.setRemote() },
                onSetControl = { vm.setControl() },
                onSetNothing = { vm.setNothing() },
                onDone = {
                    navController.navigate(Routes.Home) {
                        popUpTo(Routes.Connect) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.Home) {
            val vm: HomeViewModel = hiltViewModel()
            val state by vm.ui.collectAsStateWithLifecycle()
            HomeScreen(
                state = state,
                onModeSettings = { navController.navigate(Routes.Mode) },
                onConnectSettings = { navController.navigate(Routes.Connect) },
                onRemoteList = { navController.navigate(Routes.RemoteList) },
                onSession = { navController.navigate(Routes.Session) },
                onDisconnect = {
                    vm.disconnect()
                    navController.navigate(Routes.Connect) {
                        popUpTo(Routes.Home) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.RemoteList) {
            val vm: RemoteListViewModel = hiltViewModel()
            val state by vm.ui.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) { vm.refresh() }
            RemoteListScreen(
                state = state,
                onRefresh = vm::refresh,
                onSelect = { remote ->
                    vm.select(remote)
                    navController.navigate(Routes.Session)
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.Session) {
            val vm: SessionViewModel = hiltViewModel()
            val state by vm.ui.collectAsStateWithLifecycle()
            SessionScreen(
                state = state,
                mode = vm.mode,
                onBindRenderers = vm::bindRenderers,
                onUnbindRenderers = vm::unbindRenderers,
                onOpenCamera = vm::openCameras,
                onCloseCamera = vm::closeCameras,
                onPing = vm::ping,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
