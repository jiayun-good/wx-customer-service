const express = require('express');
const http = require('http');
const { Server: WebSocketServer } = require('ws');

// Mocked USB camera video/audio interaction (since Node.js cannot natively grab USB cam video for WebRTC)
// You would replace this with appropriate bindings for your hardware, e.g. node-webrtc, node-gstreamer, etc.

const DEVICE_NAME = process.env.DEVICE_NAME || "store-front-cam-01";
const DEVICE_MODEL = process.env.DEVICE_MODEL || "store-front-cam-01";
const MANUFACTURER = process.env.MANUFACTURER || "sda";
const DEVICE_TYPE = process.env.DEVICE_TYPE || "USB Camera";

const DEVICE_RESOLUTION = process.env.DEVICE_RESOLUTION || "1920x1080";
const FRAME_RATE = process.env.FRAME_RATE || "30";
const CAMERA_LOCATION = process.env.CAMERA_LOCATION || "商店正门";
const AUDIO_ENABLED = (process.env.AUDIO_ENABLED === "true") ? true : false;
const STREAM_PATH = process.env.STREAM_PATH || "/store-front";
const PURPOSE = process.env.PURPOSE || "用于监控顾客进出情况，配合安防系统进行实时分析";

const SERVER_HOST = process.env.SERVER_HOST || "0.0.0.0";
const HTTP_PORT = process.env.HTTP_PORT || 8080;
const SIGNALING_PORT = process.env.SIGNALING_PORT || 8081; // Used for WebSocket signaling for WebRTC

// Internal Device State
let deviceConfig = {
  resolution: DEVICE_RESOLUTION,
  frame_rate: FRAME_RATE,
  location: CAMERA_LOCATION,
  audio_enabled: AUDIO_ENABLED
};

// Express HTTP API
const app = express();
app.use(express.json());

// GET /cam/info
app.get('/cam/info', (req, res) => {
  res.json({
    device_name: DEVICE_NAME,
    device_model: DEVICE_MODEL,
    manufacturer: MANUFACTURER,
    device_type: DEVICE_TYPE,
    resolution: deviceConfig.resolution,
    frame_rate: deviceConfig.frame_rate,
    location: deviceConfig.location,
    audio_enabled: deviceConfig.audio_enabled,
    purpose: PURPOSE,
    status: "online"
  });
});

// POST /cam/command
app.post('/cam/command', (req, res) => {
  const { command } = req.body;
  if (!command) {
    return res.status(400).json({ error: "Missing 'command' in request body" });
  }
  if (command === "restart") {
    // Simulate restart
    setTimeout(() => {}, 200);
    return res.json({ status: "success", message: "Camera restarted." });
  }
  // Add more commands as needed
  return res.status(400).json({ status: "fail", message: `Unknown command: ${command}` });
});

// PUT /cam/config
app.put('/cam/config', (req, res) => {
  const { resolution, frame_rate, location, audio_enabled } = req.body;
  if (resolution) deviceConfig.resolution = resolution;
  if (frame_rate) deviceConfig.frame_rate = frame_rate;
  if (location) deviceConfig.location = location;
  if (typeof audio_enabled === "boolean") deviceConfig.audio_enabled = audio_enabled;
  res.json({
    status: "success",
    new_config: deviceConfig
  });
});

// GET /cam/stream
app.get('/cam/stream', (req, res) => {
  // Provide a JSON with WebRTC signaling info and ws signaling URL
  res.json({
    stream_type: "WebRTC",
    signaling_url: `ws://${SERVER_HOST}:${SIGNALING_PORT}${STREAM_PATH}`,
    stream_path: STREAM_PATH,
    description: "Connect via WebRTC using the signaling server."
  });
});

// Start HTTP server
const httpServer = http.createServer(app);
httpServer.listen(HTTP_PORT, SERVER_HOST, () => {
  console.log(`HTTP API server running at http://${SERVER_HOST}:${HTTP_PORT}`);
});

// WebRTC Simulated Signaling Server (WebSocket)
const wss = new WebSocketServer({ port: SIGNALING_PORT, path: STREAM_PATH });

let peerConnections = [];

// This is a placeholder for WebRTC signaling. In practice, you would use native node-webrtc or gstreamer
wss.on('connection', (ws) => {
  ws.on('message', (message) => {
    // naive echo forwarding for signaling (offer/answer/ice-candidate)
    try {
      const data = JSON.parse(message);
      // Broadcast signaling messages to all other clients for demonstration purposes
      peerConnections.forEach(pc => {
        if (pc !== ws && pc.readyState === ws.OPEN) {
          pc.send(JSON.stringify(data));
        }
      });
    } catch (err) {}
  });
  ws.send(JSON.stringify({ 
    type: "info", 
    msg: "WebRTC signaling server ready. Please initiate negotiation." 
  }));
  peerConnections.push(ws);
  ws.on('close', () => {
    peerConnections = peerConnections.filter(pc => pc !== ws);
  });
});

// For CLI: allow curl or browser access to /cam/info, /cam/stream, /cam/config, /cam/command
// All ports (HTTP_PORT, SIGNALING_PORT) are configurable via environment

module.exports = { app, httpServer, wss };