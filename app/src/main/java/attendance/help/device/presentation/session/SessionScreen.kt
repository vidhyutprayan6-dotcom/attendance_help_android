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
import attendance.help.device.device.command.CommandTypes
import attendance.help.device.domain.model.AppLinkSnapshot
import attendance.help.device.domain.model.DeviceMode
import attendance.help.device.domain.model.SessionLinkState
import org.webrtc.SurfaceViewRenderer

/**
 * Bound session UI (screen + touch control only).
 *
 * - Control: sees Remote screen, sends touches and system keys.
 * - Remote: shows connected Control name, allows screen share, enables Accessibility.
 */
@Composable
fun SessionScreen(
    state: AppLinkSnapshot,
    mode: DeviceMode,
    onBindRenderer: (SurfaceViewRenderer?) -> Unit,
    onUnbindRenderer: () -> Unit,
    onReleaseRemote: () -> Unit,
    onRequestScreenShare: () -> Unit,
    onRefreshAccessibility: () -> Unit,
    onTouch: (action: String, x: Float, y: Float) -> Unit,
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
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.allow_screen_share)) }
                Spacer(modifier = Modifier.height(8.dp))
            } else {
                Text(
                    text = stringResource(R.string.screen_share_active),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (mode == DeviceMode.CONTROL && sessionActive) {
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
