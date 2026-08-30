/**
 * Attendance Help — Virtual Hub Server (Bongsagi)
 * WebSocket registry + signaling relay for Android phones.
 * Protocol must stay in sync with app HubProtocol / HubServer.
 *
 * Port: 8765
 * Default bind: 0.0.0.0 (all interfaces)
 *
 * Busy rule: a Remote with boundPeerId is unavailable until unbound
 * (or peer disconnect / unregister / re-register releases the pair).
 */
const http = require("http");
const os = require("os");
const { WebSocketServer } = require("ws");

const PORT = Number(process.env.PORT || 8765);
/** Minimum time (ms) a Control↔Remote bind is kept before automatic server-side release. */
const MIN_SESSION_MS = 15_000;

function turnConfigFromEnv() {
  const urls = process.env.TURN_URLS
    ? process.env.TURN_URLS.split(",").map((s) => s.trim()).filter(Boolean)
    : [];
  if (urls.length > 0) {
    return {
      urls,
      username: process.env.TURN_USER || "",
      credential: process.env.TURN_CRED || "",
    };
  }
  // Development fallback so phones always receive TURN when env is unset.
  console.log("[turn] env empty — using OpenRelay development fallback");
  return {
    urls: [
      "turn:openrelay.metered.ca:80",
      "turn:openrelay.metered.ca:443",
      "turn:openrelay.metered.ca:443?transport=tcp",
    ],
    username: "openrelayproject",
    credential: "openrelayproject",
  };
}

function newSessionId(controlId, remoteId) {
  return `${controlId}_${remoteId}_${Date.now()}`;
}

/** @type {Map<import('ws').WebSocket, ClientInfo>} */
const clients = new Map();
/** @type {Map<string, ClientInfo>} */
const byDeviceId = new Map();

/**
 * @typedef {Object} ClientInfo
 * @property {import('ws').WebSocket} socket
 * @property {string} deviceId
 * @property {string} displayName
 * @property {string} mode
 * @property {string|null} boundPeerId
 * @property {number} boundAt
 */

function isExplicitUnbindReason(reason) {
  return (
    reason === "released" ||
    reason === "unregistered" ||
    reason === "switched_remote" ||
    reason === "device_replaced"
  );
}

function bindingStillValid(info) {
  if (!info || !info.boundPeerId) return false;
  const peer = byDeviceId.get(info.boundPeerId);
  return peer != null && peer.boundPeerId === info.deviceId;
}

function markSessionBound(control, remote) {
  const now = Date.now();
  control.boundPeerId = remote.deviceId;
  remote.boundPeerId = control.deviceId;
  control.boundAt = now;
  remote.boundAt = now;
}

function clearBoundAt(info) {
  if (info) info.boundAt = 0;
}

function send(socket, obj) {
  if (socket && socket.readyState === 1) {
    socket.send(JSON.stringify(obj));
  }
}

function currentRemotes() {
  const list = [];
  for (const info of byDeviceId.values()) {
    if (info.mode === "REMOTE" && info.deviceId) {
      list.push({
        deviceId: info.deviceId,
        displayName: info.displayName,
        mode: "REMOTE",
        available: !info.boundPeerId,
      });
    }
  }
  return list;
}

function broadcastRemotes() {
  const payload = { type: "remotes_list", remotes: currentRemotes() };
  for (const info of clients.values()) {
    send(info.socket, payload);
  }
}

function relay(toId, message) {
  const target = byDeviceId.get(toId);
  if (target) send(target.socket, message);
}

/** Clear Control↔Remote bind and notify both sides. */
function releaseBinding(info, reason) {
  if (!info || !info.boundPeerId) return false;
  if (!isExplicitUnbindReason(reason)) {
    const elapsed = Date.now() - (info.boundAt || 0);
    if (elapsed < MIN_SESSION_MS) {
      console.log(
        `[unbind-blocked] ${info.deviceId || "?"} reason=${reason} elapsed=${elapsed}ms (min=${MIN_SESSION_MS}ms)`
      );
      return false;
    }
  }
  const peerId = info.boundPeerId;
  const peer = byDeviceId.get(peerId);
  info.boundPeerId = null;
  clearBoundAt(info);
  if (peer) {
    peer.boundPeerId = null;
    clearBoundAt(peer);
  }
  const unbound = { type: "session_unbound", reason: reason || "released" };
  send(info.socket, unbound);
  if (peer) send(peer.socket, unbound);
  console.log(`[unbind] ${info.deviceId || "?"} <-> ${peerId} (${reason})`);
  return true;
}

function scheduleDeferredUnbind(deviceId, peerId, boundAt, reason) {
  const deferMs = Math.max(0, MIN_SESSION_MS - (Date.now() - (boundAt || 0)));
  const attempt = () => {
    if (deviceId && byDeviceId.get(deviceId)) return;
    const peer = byDeviceId.get(peerId);
    if (peer && peer.boundPeerId === deviceId) {
      releaseBinding(peer, reason);
      broadcastRemotes();
    }
  };
  if (deferMs > 0) {
    console.log(`[unbind-deferred] ${deviceId || "?"} in ${deferMs}ms (${reason})`);
    setTimeout(attempt, deferMs);
  } else {
    attempt();
  }
}

function detachSocket(socket) {
  const info = clients.get(socket);
  if (!info) return;
  const deviceId = info.deviceId;
  const peerId = info.boundPeerId;
  const boundAt = info.boundAt || 0;
  clients.delete(socket);
  if (info.deviceId) {
    const current = byDeviceId.get(info.deviceId);
    if (current && current.socket === socket) {
      byDeviceId.delete(info.deviceId);
    }
  }
  if (peerId) {
    scheduleDeferredUnbind(deviceId, peerId, boundAt, "peer_disconnected");
  }
  broadcastRemotes();
}

function handleMessage(socket, raw) {
  let msg;
  try {
    msg = JSON.parse(String(raw));
  } catch {
    send(socket, { type: "error", message: "Invalid JSON" });
    return;
  }

  const info = clients.get(socket);
  if (!info) return;
  const type = msg.type;

  switch (type) {
    case "register": {
      const deviceId = String(msg.deviceId || "");
      const displayName = String(msg.displayName || "Phone");
      const mode = String(msg.mode || "NONE");
      if (!deviceId) {
        send(socket, { type: "error", message: "deviceId required" });
        return;
      }
      const preservingBind =
        info.deviceId === deviceId &&
        info.boundPeerId != null &&
        bindingStillValid(info);
      const old = byDeviceId.get(deviceId);
      if (old && old.socket !== socket) {
        if (old.boundPeerId) {
          releaseBinding(old, "device_replaced");
        }
        clients.delete(old.socket);
        try {
          old.socket.close();
        } catch (_) {}
      }
      let keepingBind = preservingBind;
      if (info.boundPeerId && !keepingBind) {
        const released = releaseBinding(info, "re_registered");
        if (!released && bindingStillValid(info)) {
          keepingBind = true;
        }
      }
      info.deviceId = deviceId;
      info.displayName = displayName;
      info.mode = mode;
      if (!keepingBind) {
        info.boundPeerId = null;
        clearBoundAt(info);
      }
      byDeviceId.set(deviceId, info);
      send(socket, {
        type: "register_ack",
        ok: true,
        message: keepingBind ? `registered as ${mode} (session kept)` : `registered as ${mode}`,
        turn: turnConfigFromEnv(),
      });
      const turn = turnConfigFromEnv();
      console.log(
        `[register] ${displayName} (${deviceId}) mode=${mode} bind=${keepingBind ? "kept" : "none"} remotes=${currentRemotes().length} turnUrls=${turn.urls.length}`
      );
      broadcastRemotes();
      break;
    }
    case "request_remotes": {
      const list = currentRemotes();
      console.log(`[request_remotes] from ${info.deviceId || "?"} -> ${list.length} remote(s)`);
      send(socket, { type: "remotes_list", remotes: list });
      break;
    }
    case "select_remote": {
      const control = byDeviceId.get(String(msg.controlDeviceId || ""));
      const remote = byDeviceId.get(String(msg.remoteDeviceId || ""));
      if (!control || !remote || remote.mode !== "REMOTE") {
        send(socket, { type: "error", message: "Remote not available" });
        return;
      }
      if (remote.boundPeerId && remote.boundPeerId !== control.deviceId) {
        send(socket, { type: "error", message: "Remote already bound" });
        return;
      }
      // Control may only bind one remote at a time.
      if (control.boundPeerId && control.boundPeerId !== remote.deviceId) {
        releaseBinding(control, "switched_remote");
      }
      markSessionBound(control, remote);
      const bound = {
        type: "session_bound",
        sessionId: newSessionId(control.deviceId, remote.deviceId),
        controlDeviceId: control.deviceId,
        remoteDeviceId: remote.deviceId,
        controlName: control.displayName,
        remoteName: remote.displayName,
      };
      send(control.socket, bound);
      send(remote.socket, bound);
      console.log(
        `[bound] control=${control.displayName} <-> remote=${remote.displayName}`
      );
      broadcastRemotes();
      break;
    }
    case "session_unbind": {
      const fromId = String(msg.fromId || info.deviceId || "");
      const from = byDeviceId.get(fromId) || info;
      const peerId = String(msg.peerId || (from && from.boundPeerId) || "");
      if (from && from.boundPeerId) {
        releaseBinding(from, "released");
      } else if (peerId) {
        const peer = byDeviceId.get(peerId);
        if (peer && peer.boundPeerId) releaseBinding(peer, "released");
      }
      broadcastRemotes();
      break;
    }
    case "unregister": {
      releaseBinding(info, "unregistered");
      info.mode = "NONE";
      info.boundPeerId = null;
      broadcastRemotes();
      break;
    }
    case "offer":
    case "answer":
    case "ice":
    case "camera_start":
    case "camera_stop":
    case "screen_ready":
    case "webrtc_reconnect": {
      const toId = String(msg.toId || "");
      if (!toId) return;
      relay(toId, msg);
      break;
    }
    default:
      send(socket, { type: "error", message: `Unknown type: ${type}` });
  }
}

function listLanIps() {
  const nets = os.networkInterfaces();
  const ips = [];
  for (const name of Object.keys(nets)) {
    for (const net of nets[name] || []) {
      if (net.family === "IPv4" && !net.internal) {
        ips.push({ name, address: net.address });
      }
    }
  }
  return ips;
}

const server = http.createServer((_req, res) => {
  res.writeHead(200, { "Content-Type": "text/plain; charset=utf-8" });
  res.end(
    "Attendance Help Hub (Bongsagi) is running.\n" +
      "Phones: LAN ws://<IP>:8765  ·  Cloud wss://<your-app.onrender.com>\n"
  );
});

const wss = new WebSocketServer({ server });

wss.on("connection", (socket, req) => {
  const remote = req.socket.remoteAddress;
  console.log(`[open] ${remote}`);
  clients.set(socket, {
    socket,
    deviceId: "",
    displayName: "",
    mode: "NONE",
    boundPeerId: null,
    boundAt: 0,
  });

  socket.on("message", (data) => handleMessage(socket, data));
  socket.on("close", () => {
    console.log(`[close] ${remote}`);
    detachSocket(socket);
  });
  socket.on("error", (err) => {
    console.error(`[socket-error]`, err.message);
    detachSocket(socket);
  });
});

server.listen(PORT, "0.0.0.0", () => {
  const ips = listLanIps();
  console.log("");
  console.log("========================================");
  console.log("  Attendance Help Hub (Bongsagi)");
  console.log("  Protocol : v2 (screen_ready + ICE relay)");
  console.log("  Status   : RUNNING");
  console.log(`  Port   : ${PORT}`);
  console.log("========================================");
  console.log("  Use this IP in the Android app:");
  if (ips.length === 0) {
    console.log("  (no LAN IP found — check network)");
  } else {
    for (const ip of ips) {
      console.log(`  → ${ip.address}   (${ip.name})`);
      console.log(`     ws://${ip.address}:${PORT}`);
    }
  }
  console.log("========================================");
  console.log("");
});
