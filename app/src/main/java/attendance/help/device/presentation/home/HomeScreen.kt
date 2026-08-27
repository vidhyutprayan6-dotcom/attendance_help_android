package attendance.help.device.presentation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier.modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import attendance.help.device.R
import attendance.help.device.domain.model.DeviceRole

@Composable
fun HomeScreen(
    uiState: HomeUiState
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            text = stringResource(R.string.home_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))

        val hint = when (uiState.role) {
            DeviceRole.CONTROLLER -> stringResource(R.string.home_controller_hint)
            DeviceRole.REMOTE -> stringResource(R.string.home_remote_hint)
            null -> stringResource(R.string.connection_status_not_paired)
        }
        Text(text = hint, style = MaterialTheme.typography.bodyLarge)

        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.connection_status_label) + ": " +
                stringResource(R.string.connection_status_not_paired),
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Device ID: ${uiState.deviceId}",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = stringResource(R.string.display_rule_controller),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.display_rule_remote),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary
        )

        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.step1_banner),
            style = MaterialTheme.typography.labelLarge
        )
    }
}
