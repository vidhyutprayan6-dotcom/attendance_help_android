package attendance.help.device.presentation.session

import android.content.Intent
import android.provider.Settings
import android.view.MotionEvent
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
    onReleaseRemote: () -> Unit,
    onRequestScreenShare: () -> Unit,
    onStopScreenShare: () -> Unit,
    onRefreshAccessibility: () -> Unit,
    onTap: (x: Float, y: Float) -> Unit,
    onSwipe: (points: List<Pair<Float, Float>>, durationMs: Long) -> Unit,
    onRemoteKey: (type: String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var screenRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    val sessionActive = state.boundPeer != null &&
        (state.sessionLinkState == SessionLinkState.BOUND ||
            state.sessionLinkState == SessionLinkState.STREAMING)

    DisposableEffect(screenRenderer) {
        onBindRenderer(screenRenderer)
        onDispose { onUnbindRenderer() }
    }

    LaunchedEffect(Unit) { onRefreshAccessibility() }
    LaunchedEffect(state.boundPeer, state.remoteSessionState) {
        if (mode == DeviceMode.REMOTE && state.boundPeer != null) {
            onRefreshAccessibility()
        }
    }

    var wasBound by remember { mutableStateOf(false) }
    LaunchedEffect(state.boundPeer) {
        if (state.boundPeer != null) {
            wasBound = true
        } else if (wasBound) {
            onBack()
        }
    }

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
            Text(
                text = "${stringResource(R.string.bound_with)}: ${it.displayName}",
                style = MaterialTheme.typography.titleMedium
            )
        }
        if (state.remoteSessionState != RemoteSessionState.DISCONNECTED) {
            Text(
                text = "Session: ${state.remoteSessionState.name.replace('_', ' ')}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
        if (state.statusMessage.isNotBlank()) {
            Text(state.statusMessage, style = MaterialTheme.typography.bodyMedium)
        }
        if (state.webrtcState.isNotBlank() && state.webrtcState != "CLOSED") {
            Text(
                text = "WebRTC: ${state.webrtcState}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
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
        }

        if (mode == DeviceMode.CONTROL && sessionActive) {
            val videoW = state.captureGeometry.captureWidth.takeIf { it > 0 } ?: 1280
            val videoH = state.captureGeometry.captureHeight.takeIf { it > 0 } ?: 720
            val touchPath = remember { mutableStateListOf<Pair<Float, Float>>() }
            var downTime by remember { mutableLongStateOf(0L) }

            Text(
                text = stringResource(R.string.remote_screen_label),
                style = MaterialTheme.typography.titleMedium
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp)
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceViewRenderer(ctx).also { renderer ->
                            screenRenderer = renderer
                            renderer.setOnTouchListener { view, event ->
                                val rendered = VideoCoordinateMapper.computeRenderedVideoRect(
                                    view.width,
                                    view.height,
                                    videoW,
                                    videoH
                                )
                                when (event.actionMasked) {
                                    MotionEvent.ACTION_DOWN -> {
                                        touchPath.clear()
                                        downTime = System.currentTimeMillis()
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
                                            val duration = (System.currentTimeMillis() - downTime)
                                                .coerceAtLeast(50L)
                                            if (touchPath.size <= 1) {
                                                onTap(norm.first, norm.second)
                                            } else {
                                                norm.let { touchPath.add(it) }
                                                onSwipe(touchPath.toList(), duration)
                                            }
                                        }
                                        touchPath.clear()
                                    }
                                }
                                true
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = {
                        screenRenderer?.setOnTouchListener(null)
                        screenRenderer?.release()
                        screenRenderer = null
                    }
                )
                if (!state.screenShareActive && state.sessionLinkState != SessionLinkState.STREAMING) {
                    Text(
                        text = stringResource(R.string.waiting_remote_screen),
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    Text(
                        text = stringResource(R.string.session_main_feed),
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                    )
                }
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
