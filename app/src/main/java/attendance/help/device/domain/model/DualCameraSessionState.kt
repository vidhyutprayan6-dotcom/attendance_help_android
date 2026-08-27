package attendance.help.device.domain.model

/**
 * Dual-camera product rules (locked for implementation):
 *
 * 1. Cameras on Controller and Remote start/stop together.
 * 2. Controller UI shows the Controller's own live camera (local "actual" preview).
 * 3. Remote UI shows the Controller's live camera stream (same face the controller sees of themselves).
 * 4. Remote camera still runs while the session is active (ready for later features / full control),
 *    even though v1 display on Controller is local preview, not the remote feed.
 *
 * Login / restriction gates are intentionally out of scope for early steps.
 */
data class DualCameraSessionState(
    val isActive: Boolean = false,
    val controllerCameraOn: Boolean = false,
    val remoteCameraOn: Boolean = false,
    val controllerShowsLocalPreview: Boolean = true,
    val remoteShowsControllerStream: Boolean = true
)
