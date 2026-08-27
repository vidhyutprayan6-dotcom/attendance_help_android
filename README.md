# Attendance Help

Professional dual-phone Android control system over Tailscale + WebRTC.

## Product rules

- Same APK on both phones (Controller / Remote roles).
- Both cameras start and stop together.
- **Controller screen** shows the controller’s **own** live camera.
- **Remote screen** shows the **controller’s** live camera stream.
- No cloud backend in v1. Pairing via Tailscale IP + code.
- Login / access restrictions deferred.

## Stack

Kotlin, Jetpack Compose, Hilt, DataStore + EncryptedSharedPreferences, Room, OkHttp, Java-WebSocket, Stream WebRTC, CameraX (helper), Coroutines.

## Build APK (other PC)

1. Open this folder in Android Studio.
2. Sync Gradle.
3. **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
4. APK: `app/build/outputs/apk/debug/app-debug.apk`
5. Install the same APK on both phones.

If `local.properties` SDK path is wrong, set `sdk.dir` to that PC’s Android SDK path.

## Full test process

See sections below after install.
