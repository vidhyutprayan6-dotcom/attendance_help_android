package attendance.help.device.presentation.session

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import attendance.help.device.R
import attendance.help.device.domain.model.DeviceRole
import attendance.help.device.webrtc.LiveSessionUi
import org.webrtc.SurfaceViewRenderer

@Composable
fun SessionScreen(
    uiState: LiveSessionUi,
    role: DeviceRole?,
    onBindRenderers: (local: SurfaceViewRenderer?, remote: SurfaceViewRenderer?) -> Unit,
    onUnbindRenderers: () -> Unit,
    onOpenCamera: () -> Unit,
    onCloseCamera: () -> Unit,
    onPing: () -> Unit,
    onDisconnect: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var hasPermissions by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasPermissions = result.values.all { it }
    }

    var localRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var remoteRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    DisposableEffect(localRenderer, remoteRenderer, role) {
        if (role == DeviceRole.CONTROLLER) {
            onBindRenderers(localRenderer, null)
        } else {
            onBindRenderers(null, remoteRenderer)
        }
        onDispose { onUnbindRenderers() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.session_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(text = uiState.statusMessage)
        Text(text = "WebRTC: ${uiState.webrtcState}")
        Spacer(modifier = Modifier.height(8.dp))

        if (!hasPermissions) {
            Text(stringResource(R.string.permission_camera_required))
            Button(
                onClick = {
                    permissionLauncher.launch(
                        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.grant_permissions))
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (role == DeviceRole.CONTROLLER) {
                    AndroidView(
                        factory = { ctx ->
                            SurfaceViewRenderer(ctx).also { localRenderer = it }
                        },
                        modifier = Modifier.fillMaxSize(),
                        onRelease = {
                            localRenderer?.release()
                            localRenderer = null
                        }
                    )
                } else {
                    AndroidView(
                        factory = { ctx ->
                            SurfaceViewRenderer(ctx).also { remoteRenderer = it }
                        },
                        modifier = Modifier.fillMaxSize(),
                        onRelease = {
                            remoteRenderer?.release()
                            remoteRenderer = null
                        }
                    )
                }

                if (!uiState.dualCamera.isActive) {
                    Text(
                        text = if (role == DeviceRole.CONTROLLER) {
                            stringResource(R.string.display_rule_controller)
                        } else {
                            stringResource(R.string.display_rule_remote)
                        },
                        color = Color.White,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onOpenCamera,
                enabled = hasPermissions && role == DeviceRole.CONTROLLER,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.open_camera))
            }
            OutlinedButton(
                onClick = onCloseCamera,
                enabled = hasPermissions,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.close_camera))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onPing, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.send_ping))
            }
            OutlinedButton(onClick = onDisconnect, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.disconnect))
            }
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back")
        }
    }
}
