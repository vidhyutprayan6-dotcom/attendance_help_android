package attendance.help.device.presentation.session

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
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
import attendance.help.device.control.VideoCoordinateMapper
import attendance.help.device.device.command.CommandTypes
import attendance.help.device.domain.model.AppLinkSnapshot
import attendance.help.device.domain.model.DeviceMode
import attendance.help.device.domain.model.RemoteSessionState
import attendance.help.device.domain.model.SessionLinkState
import org.webrtc.SurfaceViewRenderer

@Composable
fun SessionScreen(
    state: AppLinkSnapshot,
    mode: DeviceMode,
    onBindRenderer: (SurfaceViewRenderer?) -> Unit,
    onUnbindRenderer: () -> Unit,
    onBindCameraRenderer: (SurfaceViewRenderer?) -> Unit,
    onUnbindCameraRenderer: () -> Unit,
    onBindLocalCameraPreview: (SurfaceViewRenderer?) -> Unit,
    onUnbindLocalCameraPreview: () -> Unit,
    onReleaseRemote: () -> Unit,
    onRequestScreenShare: () -> Unit,
    onStopScreenShare: () -> Unit,
    onStartCamera: () -> Unit,
    onStopCamera: () -> Unit,
    onRefreshAccessibility: () -> Unit,
    onTap: (x: Float, y: Float) -> Unit,
    onSwipe: (points: List<Pair<Float, Float>>, durationMs: Long) -> Unit,
    onRemoteKey: (type: String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var screenRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var cameraRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var localPreviewRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var immersiveRemoteControl by remember { mutableStateOf(false) }
    val sessionActive = state.boundPeer != null &&
        (state.sessionLinkState == SessionLinkState.BOUND ||
            state.sessionLinkState == SessionLinkState.STREAMING)
    val camerasOn = state.dualCamera.isActive
    val controlSessionActive = mode == DeviceMode.CONTROL && sessionActive

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onStartCamera()
    }

    fun requestStartCamera() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) onStartCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    DisposableEffect(screenRenderer) {
        onBindRenderer(screenRenderer)
        onDispose { onUnbindRenderer() }
    }
    DisposableEffect(cameraRenderer) {
        onBindCameraRenderer(cameraRenderer)
        onDispose { onUnbindCameraRenderer() }
    }
    DisposableEffect(localPreviewRenderer) {
        onBindLocalCameraPreview(localPreviewRenderer)
        onDispose { onUnbindLocalCameraPreview() }
    }

    LaunchedEffect(Unit) { onRefreshAccessibility() }
    LaunchedEffect(state.boundPeer, state.remoteSessionState) {
        if (mode == DeviceMode.REMOTE && state.boundPeer != null) {
            onRefreshAccessibility()
        }
    }

    LaunchedEffect(sessionActive) {
        if (!sessionActive) immersiveRemoteControl = false
    }

    BackHandler(enabled = immersiveRemoteControl) {
        immersiveRemoteControl = false
    }

    var wasBound by remember { mutableStateOf(false) }
    LaunchedEffect(state.boundPeer) {
        if (state.boundPeer != null) {
            wasBound = true
        } else if (wasBound) {
            onBack()
        }
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (immersiveRemoteControl) Modifier else Modifier.verticalScroll(scrollState)
            )
            .padding(if (immersiveRemoteControl) 0.dp else 16.dp)
    ) {
        if (!immersiveRemoteControl) {
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
                Text(
                    text = "${stringResource(R.string.bound_with)}: ${it.displayName}",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            if (state.statusMessage.isNotBlank()) {
                Text(state.statusMessage, style = MaterialTheme.typography.bodyMedium)
            }
            if (state.transportConnected) {
                Text(
                    text = stringResource(R.string.transport_connected),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

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
            if (!state.screenShareActive) {
                Text(stringResource(R.string.screen_share_required))
                Button(
                    onClick = onRequestScreenShare,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.accessibilityEnabled &&
                        state.boundPeer != null &&
                        state.remoteSessionState != RemoteSessionState.REQUESTING_SCREEN_PERMISSION &&
                        state.remoteSessionState != RemoteSessionState.STARTING_STREAM
                ) { Text(stringResource(R.string.start_screen_share)) }
                Spacer(modifier = Modifier.height(8.dp))
            } else {
                Text(
                    text = stringResource(R.string.screen_share_active),
                    color = MaterialTheme.colorScheme.primary
                )
                OutlinedButton(
                    onClick = onStopScreenShare,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.stop_screen_share)) }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.camera_feed_control_video), style = MaterialTheme.typography.titleMedium)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceViewRenderer(ctx).also { cameraRenderer = it }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = {
                        runCatching {
                            cameraRenderer?.release()
                        }
                        cameraRenderer = null
                    }
                )
                if (!camerasOn) {
                    Text(
                        text = stringResource(R.string.waiting_control_camera),
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
            if (camerasOn) {
                Text(
                    text = stringResource(R.string.remote_camera_on_note),
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(
                    onClick = onStopCamera,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.stop_cameras)) }
            } else {
                Button(
                    onClick = { requestStartCamera() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.transportConnected
                ) { Text(stringResource(R.string.start_cameras)) }
            }
        }

        if (controlSessionActive) {
            val videoW = state.captureGeometry.captureWidth.takeIf { it > 0 } ?: 1280
            val videoH = state.captureGeometry.captureHeight.takeIf { it > 0 } ?: 720
            val touchPath = remember { mutableStateListOf<Pair<Float, Float>>() }
            var downTime by remember { mutableLongStateOf(0L) }
            val streamReady = state.screenShareActive ||
                state.sessionLinkState == SessionLinkState.STREAMING

            if (!immersiveRemoteControl) {
                Text(
                    text = stringResource(R.string.remote_screen_label),
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (immersiveRemoteControl) {
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        } else {
                            Modifier.height(400.dp)
                        }
                    )
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceViewRenderer(ctx).also { renderer ->
                            screenRenderer = renderer
                        }
                    },
                    update = { renderer ->
                        attachRemoteScreenTouchListener(
                            view = renderer,
                            videoW = videoW,
                            videoH = videoH,
                            touchPath = touchPath,
                            getDownTime = { downTime },
                            setDownTime = { downTime = it },
                            immersive = immersiveRemoteControl,
                            streamReady = streamReady,
                            onTap = onTap,
                            onSwipe = onSwipe,
                            onEnterImmersive = { immersiveRemoteControl = true }
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = {
                        runCatching {
                            screenRenderer?.setOnTouchListener(null)
                            screenRenderer?.release()
                        }
                        screenRenderer = null
                    }
                )
                when {
                    !streamReady -> {
                        Text(
                            text = stringResource(R.string.waiting_remote_screen),
                            color = Color.White,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    immersiveRemoteControl -> {
                        Text(
                            text = stringResource(R.string.remote_screen_immersive_hint),
                            color = Color.White,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(12.dp)
                        )
                        OutlinedButton(
                            onClick = { immersiveRemoteControl = false },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(12.dp)
                        ) {
                            Text(stringResource(R.string.exit_fullscreen))
                        }
                    }
                    else -> {
                        Text(
                            text = stringResource(R.string.session_main_feed),
                            color = Color.White,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (immersiveRemoteControl) Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        else Modifier
                    ),
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

            if (!immersiveRemoteControl) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(stringResource(R.string.session_control_camera), style = MaterialTheme.typography.titleMedium)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .background(Color.DarkGray)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            SurfaceViewRenderer(ctx).also { localPreviewRenderer = it }
                        },
                        modifier = Modifier.fillMaxSize(),
                        onRelease = {
                            runCatching {
                                localPreviewRenderer?.release()
                            }
                            localPreviewRenderer = null
                        }
                    )
                    if (!camerasOn) {
                        Text(
                            text = stringResource(R.string.camera_preview_placeholder),
                            color = Color.White,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
                if (!camerasOn) {
                    Button(
                        onClick = { requestStartCamera() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.transportConnected
                    ) { Text(stringResource(R.string.start_cameras)) }
                } else {
                    OutlinedButton(
                        onClick = onStopCamera,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.stop_cameras)) }
                }
            }
        }

        if (!immersiveRemoteControl) {
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
}

private fun attachRemoteScreenTouchListener(
    view: SurfaceViewRenderer,
    videoW: Int,
    videoH: Int,
    touchPath: MutableList<Pair<Float, Float>>,
    getDownTime: () -> Long,
    setDownTime: (Long) -> Unit,
    immersive: Boolean,
    streamReady: Boolean,
    onTap: (x: Float, y: Float) -> Unit,
    onSwipe: (points: List<Pair<Float, Float>>, durationMs: Long) -> Unit,
    onEnterImmersive: () -> Unit
) {
    view.setOnTouchListener { v, event ->
        val rendered = VideoCoordinateMapper.computeRenderedVideoRect(
            v.width,
            v.height,
            videoW,
            videoH
        )
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchPath.clear()
                setDownTime(System.currentTimeMillis())
                VideoCoordinateMapper.touchToNormalized(
                    event.x,
                    event.y,
                    rendered
                )?.let { (nx, ny) ->
                    touchPath.add(nx to ny)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                VideoCoordinateMapper.touchToNormalized(
                    event.x,
                    event.y,
                    rendered
                )?.let { (nx, ny) ->
                    if (touchPath.isEmpty() || touchPath.last() != nx to ny) {
                        touchPath.add(nx to ny)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val norm = VideoCoordinateMapper.touchToNormalized(
                    event.x,
                    event.y,
                    rendered
                )
                if (norm != null) {
                    val duration = (System.currentTimeMillis() - getDownTime())
                        .coerceAtLeast(50L)
                    if (!immersive && streamReady && touchPath.size <= 1) {
                        onEnterImmersive()
                    } else if (immersive) {
                        if (touchPath.size <= 1) {
                            onTap(norm.first, norm.second)
                        } else {
                            touchPath.add(norm)
                            onSwipe(touchPath.toList(), duration)
                        }
                    }
                }
                touchPath.clear()
            }
        }
        true
    }
}
