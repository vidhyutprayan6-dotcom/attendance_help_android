# Checkpoint: WebRTC screen share working

**Date:** 2026-08-29  
**Git restore points:**
- Branch: `checkpoint/webrtc-screen-ok`
- Tag: `checkpoint-webrtc-screen-ok`

**Restore commands:**
```bash
git checkout checkpoint/webrtc-screen-ok
# or
git checkout checkpoint-webrtc-screen-ok
```

## What worked
- Hub signaling (WebSocket :8765)
- REMOTE = WebRTC offerer, CONTROL = answerer (initial session)
- MediaProjection screen share REMOTE → CONTROL
- PeerConnection CONNECTED, DataChannel OPEN, transportConnected=true
- Touch / Back / Home / Recents via Accessibility
- ICE often succeeds with host/srflx even when relay=0 and STUN 701 timeouts appear

## Do not regress
- Do not make CONTROL the initial offerer
- Do not fake STREAMING before PeerConnection CONNECTED
- Do not remove TURN fallback
- Keep MediaProjection and Accessibility paths intact when adding camera
