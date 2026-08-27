package attendance.help.device.presentation.remotelist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import attendance.help.device.R
import attendance.help.device.domain.model.AppLinkSnapshot
import attendance.help.device.domain.model.HubDevice

@Composable
fun RemoteListScreen(
    state: AppLinkSnapshot,
    onRefresh: () -> Unit,
    onSelect: (HubDevice) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(
            text = stringResource(R.string.remote_list_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.refresh_list))
        }
        Spacer(modifier = Modifier.height(12.dp))

        if (state.availableRemotes.isEmpty()) {
            Text(stringResource(R.string.remote_list_empty))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.availableRemotes, key = { it.deviceId }) { remote ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(remote.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(remote.deviceId, style = MaterialTheme.typography.bodySmall)
                            Text(
                                if (remote.available) stringResource(R.string.remote_available)
                                else stringResource(R.string.remote_busy)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { onSelect(remote) },
                                enabled = remote.available,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.select_remote))
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.back_action))
        }
    }
}
