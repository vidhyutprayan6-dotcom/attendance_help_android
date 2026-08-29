# Attendance Help

Dual-phone attendance control over a **virtual hub server** (can run on one phone).

## Flow

1. Connect to server (or host hub on this phone)
2. Set mode: **Remote** / **Control** / **Nothing (clear)**
3. Control selects a Remote from the online list
4. Live session: both cameras ON; Control sees Remote video; Remote sees Control video
5. Persistent status-bar notification while mode is active

## Build

Open in Android Studio → **Sync** → **Build → Build APK(s)**.

With ABI splits enabled, install the correct APK per target:

| Target | APK |
|--------|-----|
| LDPlayer (x86_64) | `app/build/outputs/apk/debug/app-x86_64-debug.apk` |
| Physical phone (arm64) | `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` |

Or build one ABI from terminal:

```powershell
.\gradlew.bat :app:assembleX86_64Debug
```

## Hub + TURN (LDPlayer testing)

Start the hub with TURN configured (development OpenRelay):

```powershell
cd backend
.\start-hub.bat
```

The batch file sets `TURN_URLS`, `TURN_USER`, and `TURN_CRED` read by `server.js`.

## WebRTC debug

Debug builds show a **WebRTC Diagnostics** panel on the session screen and log tag `WEBRTC_DIAG` in logcat.

To test relay-only ICE, set in `app/build.gradle.kts` debug block:

`buildConfigField("boolean", "FORCE_RELAY_ONLY", "true")`
