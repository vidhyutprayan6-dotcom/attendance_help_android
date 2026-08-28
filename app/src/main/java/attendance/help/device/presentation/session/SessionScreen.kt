package attendance.help.device.presentation.session

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import attendance.help.device.R
import attendance.help.device.device.command.CommandTypes
import attendance.help.device.domain.model.AppLinkSnapshot
import attendance.help.device.domain.model.DeviceMode
import org.webrtc.SurfaceViewRenderer

/**
 * Bound session UI.
 *
 * - Full control: Control sees Remote screen and sends touches/keys.
 * - Camera rule: both cameras ON; both phones show the Control camera feed.
 */
@Composable
fun SessionScreen(
    state: AppLinkSnapshot,
    mode: DeviceMode,
    onBindRenderers: (remote: SurfaceViewRenderer?, localCameraFeed: SurfaceViewRenderer?) -> Unit,
    onUnbindRenderers: () -> Unit,
    onReleaseRemote: () -> Unit,
    onRequestScreenShare: () -> Unit,
    onRefreshAccessibility: () -> Unit,
    onTouch: (action: String, x: Float, y: Float) -> Unit,
    onRemoteKey: (type: String) -> Unit,
    onStartRemotePhysicalCamera: (PreviewView, androidx.lifecycle.LifecycleOwner) -> Unit,
    onStopRemotePhysicalCamera: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
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

    var peerRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var controlCameraFeedRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    DisposableEffect(peerRenderer, controlCameraFeedRenderer) {
        // Control: peer = remote screen, local = control camera feed.
        // Remote: peer = control camera (inbound); local unused for WebRTC.
        onBindRenderers(peerRenderer, controlCameraFeedRenderer)
        onDispose { onUnbindRenderers() }
    }

    LaunchedEffect(Unit) { onRefreshAccessibility() }

    var wasBound by remember { mutableStateOf(false) }
    LaunchedEffect(state.boundPeer) {
        if (state.boundPeer != null) {
            wasBound = true
        } else if (wasBound) {
            onBack()
        }
    }

    val camerasOn = state.dualCamera.bothCamerasOn || state.dualCamera.isActive ||
        state.screenShareActive || state.boundPeer != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.control_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = when (mode) {
                DeviceMode.CONTROL -> stringResource(R.string.control_hint_control)
                DeviceMode.REMOTE -> stringResource(R.string.control_hint_remote)
                else -> ""
            }
        )
        state.boundPeer?.let {
            Text("${stringResource(R.string.bound_with)}: ${it.displayName}")
        }
        if (state.statusMessage.isNotBlank()) {
            Text(state.statusMessage, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (mode == DeviceMode.REMOTE) {
            if (!state.accessibilityEnabled) {
                Text(stringResource(R.string.a11y_required))
                Button(
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.open_accessibility_settings)) }
                OutlinedButton(
                    onClick = onRefreshAccessibility,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.refresh_accessibility)) }
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (state.needsScreenSharePermission || !state.screenShareActive) {
                Text(stringResource(R.string.screen_share_required))
                Button(
                    onClick = onRequestScreenShare,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.allow_screen_share)) }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

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
            // ——— Camera feed (Control video on BOTH phones) ———
            Text(
                text = stringResource(R.string.camera_feed_label),
                style = MaterialTheme.typography.titleMedium
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(Color.Black)
            ) {
                if (mode == DeviceMode.CONTROL) {
                    // Local Control camera (same face Remote sees).
                    AndroidView(
                        factory = { ctx ->
                            SurfaceViewRenderer(ctx).also { controlCameraFeedRenderer = it }
                        },
                        modifier = Modifier.fillMaxSize(),
                        onRelease = {
                            controlCameraFeedRenderer?.release()
                            controlCameraFeedRenderer = null
                        }
                    )
                } else {
                    // Inbound Control camera stream.
                    AndroidView(
                        factory = { ctx ->
                            SurfaceViewRenderer(ctx).also { peerRenderer = it }
                        },
                        modifier = Modifier.fillMaxSize(),
                        onRelease = {
                            peerRenderer?.release()
                            peerRenderer = null
                        }
                    )
                }
                Text(
                    text = stringResource(R.string.camera_feed_control_video),
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                )
            }

            // Keep Remote hardware camera ON while session cameras are active (hidden preview).
            if (mode == DeviceMode.REMOTE && camerasOn) {
                RemotePhysicalCameraSlot(
                    enabled = true,
                    onStart = { preview -> onStartRemotePhysicalCamera(preview, lifecycleOwner) },
                    onStop = onStopRemotePhysicalCamera
                )
                Text(
                    text = stringResource(R.string.remote_camera_on_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            // ——— Full control surface (Control only): Remote screen ———
            if (mode == DeviceMode.CONTROL) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.remote_screen_label),
                    style = MaterialTheme.typography.titleMedium
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .background(Color.DarkGray)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            SurfaceViewRenderer(ctx).also { renderer ->
                                peerRenderer = renderer
                                renderer.setOnTouchListener { view, event ->
                                    val w = view.width.coerceAtLeast(1).toFloat()
                                    val h = view.height.coerceAtLeast(1).toFloat()
                                    val nx = (event.x / w).coerceIn(0f, 1f)
                                    val ny = (event.y / h).coerceIn(0f, 1f)
                                    when (event.actionMasked) {
                                        MotionEvent.ACTION_DOWN -> onTouch("down", nx, ny)
                                        MotionEvent.ACTION_MOVE -> onTouch("move", nx, ny)
                                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                                            onTouch("up", nx, ny)
                                    }
                                    true
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        onRelease = {
                            peerRenderer?.setOnTouchListener(null)
                            peerRenderer?.release()
                            peerRenderer = null
                        }
                    )
                    Text(
                        text = stringResource(R.string.session_main_feed),
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onRemoteKey(CommandTypes.KEY_BACK) },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.key_back)) }
                    OutlinedButton(
                        onClick = { onRemoteKey(CommandTypes.KEY_HOME) },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.key_home)) }
                    OutlinedButton(
                        onClick = { onRemoteKey(CommandTypes.KEY_RECENTS) },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.key_recents)) }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onReleaseRemote,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.boundPeer != null
        ) {
            Text(
                if (mode == DeviceMode.CONTROL) {
                    stringResource(R.string.release_remote)
                } else {
                    stringResource(R.string.release_from_control)
                }
            )
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.back_action))
        }
    }
}

@Composable
private fun RemotePhysicalCameraSlot(
    enabled: Boolean,
    onStart: (PreviewView) -> Unit,
    onStop: () -> Unit
) {
    if (!enabled) return
    DisposableEffect(Unit) {
        onDispose { onStop() }
    }
    // 1dp surface keeps CameraX bound without showing Remote's face as the feed.
    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).also { onStart(it) }
        },
        modifier = Modifier.size(1.dp),
        onRelease = { onStop() }
    )
}
