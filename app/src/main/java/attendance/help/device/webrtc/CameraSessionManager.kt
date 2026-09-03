package attendance.help.device.webrtc

import timber.log.Timber
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

enum class CameraSessionState {
    OFF,
    REQUESTED,
    PREPARING,
    ACTIVE,
    STOPPING,
    ERROR
}

/**
 * Idempotent camera sync state for REMOTE ↔ CONTROL sessions.
 * CONTROL is always the published camera video source.
 */
@Singleton
class CameraSessionManager @Inject constructor() {
    private val state = AtomicReference(CameraSessionState.OFF)
    private var activeCommandId: String = ""
    private var lastError: String = ""

    fun currentState(): CameraSessionState = state.get()
    fun lastErrorReason(): String = lastError
    fun activeCommandId(): String = activeCommandId

    fun newCommandId(): String = UUID.randomUUID().toString().take(12)

    /** Returns false if a start is already in progress or active (idempotent). */
    fun beginStart(commandId: String): Boolean {
        while (true) {
            val cur = state.get()
            when (cur) {
                CameraSessionState.ACTIVE, CameraSessionState.REQUESTED, CameraSessionState.PREPARING -> {
                    Timber.tag("CAMERA_SYNC").i("CAMERA_START ignored already=%s cmd=%s", cur, commandId)
                    return false
                }
                // STOPPING/ERROR must not block a clean second start after stop.
                CameraSessionState.STOPPING, CameraSessionState.OFF, CameraSessionState.ERROR -> {
                    if (state.compareAndSet(cur, CameraSessionState.REQUESTED)) {
                        activeCommandId = commandId
                        lastError = ""
                        Timber.tag("CAMERA_SYNC").i("CAMERA_START_REQUEST cmd=%s from=%s", commandId, cur)
                        return true
                    }
                }
            }
        }
    }

    fun markPreparing() {
        state.compareAndSet(CameraSessionState.REQUESTED, CameraSessionState.PREPARING)
    }

    fun markActive() {
        state.set(CameraSessionState.ACTIVE)
        Timber.tag("CAMERA_SYNC").i("CAMERA_SESSION_ACTIVE cmd=%s", activeCommandId)
    }

    fun markError(reason: String) {
        lastError = reason
        state.set(CameraSessionState.ERROR)
        Timber.tag("CAMERA_SYNC").w("CAMERA_ERROR reason=%s cmd=%s", reason, activeCommandId)
    }

    /** Returns false if already stopping/off (idempotent). */
    fun beginStop(commandId: String): Boolean {
        while (true) {
            val cur = state.get()
            when (cur) {
                CameraSessionState.OFF, CameraSessionState.STOPPING -> {
                    Timber.tag("CAMERA_SYNC").i("CAMERA_STOP ignored already=%s", cur)
                    return false
                }
                else -> {
                    if (state.compareAndSet(cur, CameraSessionState.STOPPING)) {
                        activeCommandId = commandId
                        Timber.tag("CAMERA_SYNC").i("CAMERA_STOPPING cmd=%s", commandId)
                        return true
                    }
                }
            }
        }
    }

    fun markStopped() {
        state.set(CameraSessionState.OFF)
        Timber.tag("CAMERA_SYNC").i("CAMERA_STOPPED")
    }

    fun reset() {
        state.set(CameraSessionState.OFF)
        activeCommandId = ""
        lastError = ""
    }
}
