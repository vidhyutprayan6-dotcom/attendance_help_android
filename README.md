# Attendance Help

Private dual-phone Android control system (Kotlin, Clean Architecture, Jetpack Compose).

## Product display rules (v1)

- Both cameras start/stop together.
- **Controller** screen shows the controller's **own** live camera.
- **Remote** screen shows the **controller's** live camera stream.
- Login / restrictions deferred; unrestricted peer control first.

## Step 1 status

Foundation only: Gradle project, package layout, Hilt, bilingual shell UI (EN/AR), role selection, device ID bootstrap. Network / WebRTC / CameraX are stubbed for expansion.

## Open in Android Studio

1. Finish Android SDK install if the setup wizard is still running.
2. Welcome → **Open** → select this folder: `attendance_app_pass_android`
3. Trust the project and wait for Gradle sync.
4. If sync fails on SDK path, edit `local.properties` `sdk.dir` to your real SDK location.
5. Connect a phone (USB debugging on) → Run ▶

See chat instructions for the full Step 1 test checklist.
