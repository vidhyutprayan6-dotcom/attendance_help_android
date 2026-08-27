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
import androidx.compose.material3.Switch
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
    onHostLocallyChange: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onConnectedNext: () -> Unit,
    onGoHome: () -> Unit
) {
    val (form, snap) = state
    val connected = snap.serverLinkState == ServerLinkState.CONNECTED ||
        snap.serverLinkState == ServerLinkState.HOSTING_AND_CONNECTED

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

        StatusChip(
            ok = connected,
            text = if (connected) {
                stringResource(R.string.server_connected) + " · ${snap.serverHost}"
            } else {
                stringResource(R.string.server_disconnected)
            }
        )

        OutlinedTextField(
            value = form.name,
            onValueChange = onNameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.phone_name_label)) },
            singleLine = true
        )

        RowSwitch(
            checked = form.hostLocally,
            onCheckedChange = onHostLocallyChange,
            label = stringResource(R.string.host_server_here)
        )

        if (form.hostLocally) {
            Text("${stringResource(R.string.your_hosted_ip)}: ${form.detectedIp ?: "—"}")
        } else {
            OutlinedTextField(
                value = form.host,
                onValueChange = onHostChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.server_ip_label)) },
                singleLine = true,
                enabled = !form.hostLocally
            )
        }

        if (snap.statusMessage.isNotBlank()) Text(snap.statusMessage)
        snap.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onConnect,
            modifier = Modifier.fillMaxWidth(),
            enabled = !connected
        ) {
            Text(stringResource(R.string.connect_action))
        }
        if (connected) {
            Button(onClick = onConnectedNext, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.mode_title))
            }
            OutlinedButton(onClick = onGoHome, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.home_title))
            }
        }
    }
}

@Composable
private fun StatusChip(ok: Boolean, text: String) {
    Text(
        text = text,
        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.titleMedium
    )
}

@Composable
private fun RowSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
