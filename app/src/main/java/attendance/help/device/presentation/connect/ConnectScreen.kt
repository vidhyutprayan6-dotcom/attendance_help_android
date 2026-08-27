package attendance.help.device.presentation.connect

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import attendance.help.device.R
import attendance.help.device.domain.model.AppLinkSnapshot
import attendance.help.device.domain.model.ServerLinkState

@Composable
fun ConnectScreen(
    state: Pair<ConnectForm, AppLinkSnapshot>,
    onHostChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onConnectedNext: () -> Unit,
    onGoHome: () -> Unit
) {
    val (form, snap) = state
    val connected = snap.serverLinkState == ServerLinkState.CONNECTED
    val connecting = snap.serverLinkState == ServerLinkState.CONNECTING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.connect_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(stringResource(R.string.connect_hint))
        Text(
            text = stringResource(R.string.connect_ip_example),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )

        Text(
            text = if (connected) {
                stringResource(R.string.server_connected) +
                    " · ${snap.serverHost}:${snap.serverPort}"
            } else if (connecting) {
                stringResource(R.string.server_connecting)
            } else {
                stringResource(R.string.server_disconnected)
            },
            color = when {
                connected -> MaterialTheme.colorScheme.primary
                connecting -> MaterialTheme.colorScheme.secondary
                else -> MaterialTheme.colorScheme.error
            },
            style = MaterialTheme.typography.titleMedium
        )

        OutlinedTextField(
            value = form.name,
            onValueChange = onNameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.phone_name_label)) },
            singleLine = true,
            enabled = !connected && !connecting
        )

        OutlinedTextField(
            value = form.host,
            onValueChange = onHostChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.server_ip_label)) },
            placeholder = { Text(stringResource(R.string.server_ip_placeholder)) },
            singleLine = true,
            enabled = !connected && !connecting
        )

        if (snap.statusMessage.isNotBlank()) Text(snap.statusMessage)
        snap.lastError?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (!connected) {
            Button(
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth(),
                enabled = !connecting && form.host.isNotBlank()
            ) {
                Text(
                    if (connecting) stringResource(R.string.server_connecting)
                    else stringResource(R.string.connect_action)
                )
            }
        } else {
            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.disconnect_server))
            }
            Button(onClick = onConnectedNext, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.mode_title))
            }
            OutlinedButton(onClick = onGoHome, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.home_title))
            }
        }
    }
}
