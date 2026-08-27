package attendance.help.device.presentation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import attendance.help.device.R
import attendance.help.device.domain.model.ConnectionState
import attendance.help.device.domain.model.DeviceRole

@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onPairing: () -> Unit,
    onSession: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.home_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = when (uiState.role) {
                DeviceRole.CONTROLLER -> stringResource(R.string.role_controller)
                DeviceRole.REMOTE -> stringResource(R.string.role_remote)
                null -> "-"
            },
            style = MaterialTheme.typography.titleMedium
        )
        Text(text = "${stringResource(R.string.device_id_label)}: ${uiState.deviceId}")
        Text(
            text = "${stringResource(R.string.connection_status_label)}: ${
                when (uiState.connectionState) {
                    ConnectionState.CONNECTED -> stringResource(R.string.connected)
                    ConnectionState.WAITING_FOR_PEER -> stringResource(R.string.waiting_peer)
                    else -> stringResource(R.string.not_paired)
                }
            }"
        )
        if (uiState.liveStatus.isNotBlank()) {
            Text(text = uiState.liveStatus, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.display_rule_controller),
            color = MaterialTheme.colorScheme.secondary
        )
        Text(
            text = stringResource(R.string.display_rule_remote),
            color = MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.weight(1f))
        Button(onClick = onPairing, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.pairing_title))
        }
        OutlinedButton(
            onClick = onSession,
            modifier = Modifier.fillMaxWidth(),
            enabled = uiState.connectionState == ConnectionState.CONNECTED ||
                uiState.connectionState == ConnectionState.WAITING_FOR_PEER ||
                uiState.connectionState == ConnectionState.RECONNECTING
        ) {
            Text(stringResource(R.string.go_session))
        }
    }
}
