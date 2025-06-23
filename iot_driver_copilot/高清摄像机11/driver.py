import os
import io
import threading
import time
from flask import Flask, Response, jsonify, request

app = Flask(__name__)

# Device info (static as per provided data)
DEVICE_INFO = {
    "device_name": "高清摄像机11",
    "device_model": "DS-2CE16D0T-IRF",
    "manufacturer": "海康威视",
    "device_type": "模拟高清摄像机"
}

# Environment variable configuration
HTTP_SERVER_HOST = os.environ.get("HTTP_SERVER_HOST", "0.0.0.0")
HTTP_SERVER_PORT = int(os.environ.get("HTTP_SERVER_PORT", "8080"))
CAMERA_RESOLUTION = os.environ.get("CAMERA_RESOLUTION", "1920x1080")
INFRARED_MODE = os.environ.get("CAMERA_INFRARED_MODE", "auto")  # auto/on/off
STREAM_FPS = int(os.environ.get("CAMERA_STREAM_FPS", "15"))

# Simulated camera state (since only TVI analog output, not real HTTP/RTSP streaming)
CAMERA_STATE = {
    "stream_health": "OK",
    "resolution": CAMERA_RESOLUTION,
    "infrared": INFRARED_MODE,
    "last_command": None,
    "last_command_time": None
}

# Simulated video frame generator (JPEG MJPEG stream)
def generate_mjpeg_stream():
    from PIL import Image, ImageDraw, ImageFont
    import numpy as np

    frame_counter = 0
    width, height = map(int, CAMERA_RESOLUTION.split("x"))

    while True:
        # Create fake frame (black background, text overlay)
        img = Image.new('RGB', (width, height), color=(0,0,0))
        draw = ImageDraw.Draw(img)
        try:
            font = ImageFont.truetype("arial.ttf", 32)
        except:
            font = ImageFont.load_default()
        text = f"模拟高清摄像机\n{DEVICE_INFO['device_model']}\n帧: {frame_counter}\n红外: {CAMERA_STATE['infrared']}\n分辨率: {CAMERA_STATE['resolution']}"
        draw.multiline_text((50, 50), text, fill=(255,255,255), font=font, spacing=10)

        # Draw IR status
        if CAMERA_STATE["infrared"] in ["on", "auto"]:
            draw.ellipse([(width-100, height-100), (width-20, height-20)], fill=(255,0,0))

        # Encode to JPEG
        buf = io.BytesIO()
        img.save(buf, format='JPEG')
        frame = buf.getvalue()
        buf.close()

        yield (b'--frame\r\n'
               b'Content-Type: image/jpeg\r\n\r\n' + frame + b'\r\n')
        frame_counter += 1
        time.sleep(1.0 / STREAM_FPS)

@app.route("/camera/info", methods=["GET"])
def camera_info():
    return jsonify(DEVICE_INFO)

@app.route("/camera/status", methods=["GET"])
def camera_status():
    # Allow filtering by query params
    status_keys = request.args.getlist("field")
    filtered = {}
    if status_keys:
        for k in status_keys:
            if k in CAMERA_STATE:
                filtered[k] = CAMERA_STATE[k]
        return jsonify(filtered)
    # Full status
    return jsonify(CAMERA_STATE)

@app.route("/camera/cmd", methods=["POST"])
def camera_cmd():
    try:
        payload = request.get_json(force=True)
        if not isinstance(payload, dict):
            return jsonify({"error": "Payload must be a JSON object"}), 400
    except Exception as e:
        return jsonify({"error": str(e)}), 400

    response = {}
    # Supported commands: restart, set_infrared, set_resolution
    if "restart" in payload:
        CAMERA_STATE["stream_health"] = "Restarting"
        time.sleep(0.5)
        CAMERA_STATE["stream_health"] = "OK"
        response["result"] = "Camera restarted"

    if "infrared" in payload:
        val = payload["infrared"]
        if val in ["on", "off", "auto"]:
            CAMERA_STATE["infrared"] = val
            response["result"] = f"Infrared mode set to {val}"
        else:
            return jsonify({"error": "Invalid infrared mode"}), 400

    if "resolution" in payload:
        val = payload["resolution"]
        if val in ["1920x1080", "1280x720"]:
            CAMERA_STATE["resolution"] = val
            response["result"] = f"Resolution set to {val}"
        else:
            return jsonify({"error": "Unsupported resolution"}), 400

    CAMERA_STATE["last_command"] = payload
    CAMERA_STATE["last_command_time"] = time.strftime('%Y-%m-%d %H:%M:%S')

    if not response:
        return jsonify({"error": "No supported command found"}), 400
    return jsonify(response)

@app.route("/camera/stream", methods=["GET"])
def camera_stream():
    # HTTP MJPEG stream
    return Response(generate_mjpeg_stream(), mimetype='multipart/x-mixed-replace; boundary=frame')

if __name__ == "__main__":
    app.run(host=HTTP_SERVER_HOST, port=HTTP_SERVER_PORT, threaded=True)