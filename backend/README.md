# Attendance Help Hub (Bongsagi)

Virtual WebSocket server for phone testing. Same protocol as the Android app hub.

## Requirements

- Node.js 18+ ([https://nodejs.org](https://nodejs.org))

## Start the server

```bat
cd backend
npm install
npm start
```

Or double-click `start-hub.bat`.

## Server address for phones

- **Port:** `8765`
- **IP:** use the address printed when the server starts (this PC LAN IP).
- In the app: **Host virtual server = OFF**, enter that IP, then **Connect to server**.

Phones and this PC must be on the **same Wi‑Fi / LAN** (or reachable via VPN/Tailscale).

## Firewall

If phones cannot connect, allow inbound TCP **8765** in Windows Firewall for Node.js / Private networks.
