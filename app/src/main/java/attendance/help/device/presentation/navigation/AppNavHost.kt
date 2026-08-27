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
import attendance.help.device.presentation.role.RoleSelectScreen
import attendance.help.device.presentation.role.RoleSelectViewModel
import attendance.help.device.presentation.welcome.WelcomeScreen

@Composable
fun AppNavHost() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.Welcome
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
            HomeScreen(uiState = uiState)
        }
    }
}
