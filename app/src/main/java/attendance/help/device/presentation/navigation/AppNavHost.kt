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
                onOpenControl = {
                    navController.navigate(Routes.Session) {
                        launchSingleTop = true
                    }
                },
                onDisconnect = {
                    vm.disconnect()
                    navController.navigate(Routes.Connect) {
                        popUpTo(Routes.Home) { inclusive = true }
                    }
                },
                onAnnouncePresence = vm::announcePresence
            )
        }
        composable(Routes.RemoteList) {
            val vm: RemoteListViewModel = hiltViewModel()
            val state by vm.ui.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) { vm.refresh() }
            LaunchedEffect(state.sessionLinkState) {
                if (state.sessionLinkState == attendance.help.device.domain.model.SessionLinkState.SELECTING_REMOTE) {
                    vm.refresh()
                }
            }
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
                onBindRenderer = vm::bindRenderer,
                onUnbindRenderer = vm::unbindRenderer,
                onBindCameraRenderer = vm::bindCameraRenderer,
                onUnbindCameraRenderer = vm::unbindCameraRenderer,
                onBindLocalCameraPreview = vm::bindLocalCameraPreview,
                onUnbindLocalCameraPreview = vm::unbindLocalCameraPreview,
                onReleaseRemote = {
                    vm.releaseRemote()
                    navController.popBackStack()
                },
                onRequestScreenShare = vm::requestScreenShare,
                onStopScreenShare = vm::stopScreenShare,
                onStartCamera = vm::startCamera,
                onStopCamera = vm::stopCamera,
                onRefreshAccessibility = vm::refreshAccessibility,
                onTap = vm::sendTap,
                onSwipe = vm::sendSwipe,
                onRemoteKey = vm::sendRemoteKey,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
