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
import androidx.compose.foundation.layout.width
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
import attendance.help.device.domain.model.AppLinkSnapshot
import attendance.help.device.domain.model.DeviceMode
import org.webrtc.SurfaceViewRenderer

@Composable
fun SessionScreen(
    state: AppLinkSnapshot,
    mode: DeviceMode,
    onBindRenderers: (remote: SurfaceViewRenderer?, localPip: SurfaceViewRenderer?) -> Unit,
    onUnbindRenderers: () -> Unit,
    onOpenCamera: () -> Unit,
    onCloseCamera: () -> Unit,
    onPing: () -> Unit,
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
    ) { result -> hasPermissions = result.values.all { it } }

    var remoteRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var localPip by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    DisposableEffect(remoteRenderer, localPip) {
        onBindRenderers(remoteRenderer, localPip)
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
        Text(state.statusMessage)
        Text("WebRTC: ${state.webrtcState}")
        Text(stringResource(R.string.display_rule), style = MaterialTheme.typography.bodySmall)
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
            ) { Text(stringResource(R.string.grant_permissions)) }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black)
            ) {
                // MAIN: peer feed (Control sees Remote, Remote sees Control)
                AndroidView(
                    factory = { ctx -> SurfaceViewRenderer(ctx).also { remoteRenderer = it } },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = {
                        remoteRenderer?.release()
                        remoteRenderer = null
                    }
                )
                Text(
                    text = stringResource(R.string.session_main_feed),
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                )
                // PIP: local camera confirmation (both cameras on)
                AndroidView(
                    factory = { ctx -> SurfaceViewRenderer(ctx).also { localPip = it } },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                        .width(120.dp)
                        .height(160.dp),
                    onRelease = {
                        localPip?.release()
                        localPip = null
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onOpenCamera,
                enabled = hasPermissions && mode == DeviceMode.CONTROL,
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.open_camera)) }
            OutlinedButton(
                onClick = onCloseCamera,
                enabled = hasPermissions,
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.close_camera)) }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPing, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.send_ping))
            }
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.back_action))
            }
        }
    }
}
