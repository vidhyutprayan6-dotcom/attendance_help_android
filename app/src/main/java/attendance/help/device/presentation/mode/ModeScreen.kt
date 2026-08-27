package attendance.help.device.presentation.mode

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import attendance.help.device.R
import attendance.help.device.domain.model.AppLinkSnapshot
import attendance.help.device.domain.model.DeviceMode

@Composable
fun ModeScreen(
    state: AppLinkSnapshot,
    onSetRemote: () -> Unit,
    onSetControl: () -> Unit,
    onSetNothing: () -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit
) {
    val nothingEnabled = state.mode != DeviceMode.NONE

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.mode_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(stringResource(R.string.mode_hint))

        Text(
            text = "${stringResource(R.string.mode_current)}: " + when (state.mode) {
                DeviceMode.REMOTE -> stringResource(R.string.mode_remote_label)
                DeviceMode.CONTROL -> stringResource(R.string.mode_control_label)
                DeviceMode.NONE -> stringResource(R.string.mode_none_label)
            },
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = if (state.serverLinkState.name.contains("CONNECTED")) {
                stringResource(R.string.server_connected) + " · ${state.serverHost}"
            } else {
                stringResource(R.string.server_disconnected)
            }
        )

        ModeCard(
            title = stringResource(R.string.mode_remote),
            description = stringResource(R.string.mode_remote_desc),
            selected = state.mode == DeviceMode.REMOTE,
            enabled = true,
            onClick = onSetRemote
        )
        ModeCard(
            title = stringResource(R.string.mode_control),
            description = stringResource(R.string.mode_control_desc),
            selected = state.mode == DeviceMode.CONTROL,
            enabled = true,
            onClick = onSetControl
        )
        ModeCard(
            title = stringResource(R.string.mode_nothing),
            description = stringResource(R.string.mode_nothing_desc),
            selected = false,
            enabled = nothingEnabled,
            onClick = onSetNothing
        )

        if (state.statusMessage.isNotBlank()) Text(state.statusMessage)

        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.mode != DeviceMode.NONE
        ) {
            Text(stringResource(R.string.continue_action))
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.back_action))
        }
    }
}

@Composable
private fun ModeCard(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else -> MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(6.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
