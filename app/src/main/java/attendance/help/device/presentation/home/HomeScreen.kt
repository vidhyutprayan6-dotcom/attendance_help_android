package attendance.help.device.presentation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import attendance.help.device.R
import attendance.help.device.domain.model.AppLinkSnapshot
import attendance.help.device.domain.model.DeviceMode
import attendance.help.device.domain.model.ServerLinkState
import attendance.help.device.domain.model.SessionLinkState

@Composable
fun HomeScreen(
    state: AppLinkSnapshot,
    onModeSettings: () -> Unit,
    onConnectSettings: () -> Unit,
    onRemoteList: () -> Unit,
    onOpenControl: () -> Unit,
    onDisconnect: () -> Unit
) {
    val serverOk = state.serverLinkState == ServerLinkState.CONNECTED

    // Remote: when a Control phone connects, open control view automatically (no Camera Session menu).
    LaunchedEffect(state.mode, state.boundPeer, state.sessionLinkState) {
        if (state.mode == DeviceMode.REMOTE &&
            state.boundPeer != null &&
            (state.sessionLinkState == SessionLinkState.BOUND ||
                state.sessionLinkState == SessionLinkState.STREAMING)
        ) {
            onOpenControl()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.home_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )

        Text(stringResource(R.string.status_section), style = MaterialTheme.typography.titleMedium)
        Text(
            text = if (serverOk) {
                stringResource(R.string.server_connected) +
                    " · ${state.serverHost}:${state.serverPort}"
            } else {
                stringResource(R.string.server_disconnected)
            },
            color = if (serverOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        Text(
            text = "${stringResource(R.string.mode_current)}: " + when (state.mode) {
                DeviceMode.REMOTE -> stringResource(R.string.mode_remote_label)
                DeviceMode.CONTROL -> stringResource(R.string.mode_control_label)
                DeviceMode.NONE -> stringResource(R.string.mode_none_label)
            }
        )
        if (state.mode == DeviceMode.REMOTE) {
            Text(stringResource(R.string.waiting_as_remote))
        }
        state.boundPeer?.let {
            Text("${stringResource(R.string.bound_with)}: ${it.displayName}")
        }
        if (state.statusMessage.isNotBlank()) {
            Text(state.statusMessage, style = MaterialTheme.typography.bodyMedium)
        }
        Text("${stringResource(R.string.device_id_label)}: ${state.localDeviceId}")

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Text(stringResource(R.string.actions_section), style = MaterialTheme.typography.titleMedium)

        OutlinedButton(onClick = onConnectSettings, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.connect_title))
        }
        Button(onClick = onModeSettings, modifier = Modifier.fillMaxWidth(), enabled = serverOk) {
            Text(stringResource(R.string.open_mode_settings))
        }

        if (state.mode == DeviceMode.CONTROL) {
            Button(
                onClick = onRemoteList,
                modifier = Modifier.fillMaxWidth(),
                enabled = serverOk
            ) {
                Text(stringResource(R.string.open_remote_list))
            }
        }

        if (serverOk) {
            OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.disconnect_server))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.display_rule),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}
