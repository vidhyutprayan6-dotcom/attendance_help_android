package attendance.help.device.presentation.role

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import attendance.help.device.R
import attendance.help.device.domain.model.DeviceRole

@Composable
fun RoleSelectScreen(
    onRoleConfirmed: () -> Unit,
    viewModel: RoleSelectViewModel
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.choose_role_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )

        RoleCard(
            title = stringResource(R.string.role_controller),
            description = stringResource(R.string.role_controller_desc),
            selected = uiState.selectedRole == DeviceRole.CONTROLLER,
            onClick = { viewModel.selectRole(DeviceRole.CONTROLLER) }
        )
        RoleCard(
            title = stringResource(R.string.role_remote),
            description = stringResource(R.string.role_remote_desc),
            selected = uiState.selectedRole == DeviceRole.REMOTE,
            onClick = { viewModel.selectRole(DeviceRole.REMOTE) }
        )

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = { viewModel.confirm(onRoleConfirmed) },
            enabled = uiState.selectedRole != null && !uiState.isSaving,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.continue_action))
        }
    }
}

@Composable
private fun RoleCard(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
