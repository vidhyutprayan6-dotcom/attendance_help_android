package attendance.help.device.presentation.pairing

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import attendance.help.device.R
import attendance.help.device.domain.model.ConnectionState
import attendance.help.device.domain.model.DeviceRole

@Composable
fun PairingScreen(
    uiState: PairingUiState,
    onStartWaiting: () -> Unit,
    onRefreshIp: () -> Unit,
    onConnect: (ip: String, code: String) -> Unit,
    onBack: () -> Unit,
    onGoSession: () -> Unit
) {
    var ipInput by remember { mutableStateOf(uiState.remoteIpInput) }
    var codeInput by remember { mutableStateOf(uiState.remoteCodeInput) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.pairing_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )

        when (uiState.role) {
            DeviceRole.CONTROLLER -> {
                Text(stringResource(R.string.pairing_controller_hint))
                Text("${stringResource(R.string.your_ip_label)}: ${uiState.localIp ?: stringResource(R.string.ip_missing)}")
                Text("${stringResource(R.string.pairing_code_label)}: ${uiState.pairingCode ?: "—"}")
                OutlinedButton(onClick = onRefreshIp, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.refresh_ip))
                }
                Button(onClick = onStartWaiting, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.start_waiting))
                }
            }

            DeviceRole.REMOTE -> {
                Text(stringResource(R.string.pairing_remote_hint))
                OutlinedTextField(
                    value = ipInput,
                    onValueChange = { ipInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.controller_ip_label)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = codeInput,
                    onValueChange = { codeInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.pairing_code_label)) },
                    singleLine = true
                )
                Button(
                    onClick = { onConnect(ipInput, codeInput) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = ipInput.isNotBlank() && codeInput.length >= 4
                ) {
                    Text(stringResource(R.string.connect_to_controller))
                }
            }

            null -> Text("Select a role first.")
        }

        if (uiState.statusMessage.isNotBlank()) {
            Text(uiState.statusMessage)
        }
        uiState.lastError?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (uiState.connectionState == ConnectionState.CONNECTED) {
            Button(onClick = onGoSession, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.go_session))
            }
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back")
        }
    }
}
