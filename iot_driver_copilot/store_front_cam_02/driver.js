const http = require('http');

const SERVER_HOST = process.env.SERVER_HOST || '0.0.0.0';
const SERVER_PORT = parseInt(process.env.SERVER_PORT || '8080', 10);

const DEVICE_METADATA = {
  device_name: process.env.DEVICE_NAME || 'store-front-cam-02',
  device_model: process.env.DEVICE_MODEL || 'store-front-cam-02',
  manufacturer: process.env.MANUFACTURER || 'weda',
  device_type: process.env.DEVICE_TYPE || 'USB Camera',
  resolution: process.env.RESOLUTION || '1920x1080',
  frame_rate: process.env.FRAME_RATE || '30FPS',
  location: process.env.LOCATION || '商店正门',
  audio_enabled: (process.env.AUDIO_ENABLED || 'false').toLowerCase() === 'true',
  stream_url: process.env.STREAM_URL || 'webrtc://camera.local/store-front'
};

function handleCamMeta(req, res) {
  res.writeHead(200, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({
    device_name: DEVICE_METADATA.device_name,
    resolution: DEVICE_METADATA.resolution,
    frame_rate: DEVICE_METADATA.frame_rate,
    location: DEVICE_METADATA.location,
    audio_enabled: DEVICE_METADATA.audio_enabled
  }));
}

function notFound(res) {
  res.writeHead(404, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ error: 'Not found' }));
}

const server = http.createServer((req, res) => {
  if (req.method === 'GET' && req.url === '/cam/meta') {
    handleCamMeta(req, res);
  } else {
    notFound(res);
  }
});

server.listen(SERVER_PORT, SERVER_HOST, () => {
  // Optionally log server start (not required by spec)
});