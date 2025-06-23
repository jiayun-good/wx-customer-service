import os
import json
from flask import Flask, request, jsonify

app = Flask(__name__)

# Environment variables for configuration
DEVICE_NAME = os.environ.get("DEVICE_NAME", "海康威视模拟高清摄像机")
DEVICE_MODEL = os.environ.get("DEVICE_MODEL", "DS-2CE16D0T-IRF")
MANUFACTURER = os.environ.get("MANUFACTURER", "海康威视")
DEVICE_TYPE = os.environ.get("DEVICE_TYPE", "监控摄像机")
SERVER_HOST = os.environ.get("SERVER_HOST", "0.0.0.0")
SERVER_PORT = int(os.environ.get("SERVER_PORT", 8080))

# Camera characteristics (static for this analog device)
CAMERA_STATUS = {
    "device_name": DEVICE_NAME,
    "device_model": DEVICE_MODEL,
    "manufacturer": MANUFACTURER,
    "device_type": DEVICE_TYPE,
    "operational_state": "online"
}
CAMERA_CONFIG = {
    "resolution": "1920x1080",
    "infrared_range_m": 20,
    "lens_options_mm": [2.8, 3.6, 6],
    "protection_rating": "IP66",
    "video_output": "TVI",
    "power_input": "12V DC ±25%"
}

@app.route("/camera/status", methods=["GET"])
def camera_status():
    return jsonify({
        "device_model": CAMERA_STATUS["device_model"],
        "manufacturer": CAMERA_STATUS["manufacturer"],
        "device_type": CAMERA_STATUS["device_type"],
        "operational_state": CAMERA_STATUS["operational_state"]
    })

@app.route("/camera/config", methods=["GET"])
def camera_config():
    query = request.args
    if query:
        filtered = {k: v for k, v in CAMERA_CONFIG.items() if k in query}
        return jsonify(filtered)
    return jsonify(CAMERA_CONFIG)

@app.route("/cam/info", methods=["GET"])
def cam_info():
    info = {
        "device_model": CAMERA_STATUS["device_model"],
        "manufacturer": CAMERA_STATUS["manufacturer"],
        "device_type": CAMERA_STATUS["device_type"],
        "resolution": CAMERA_CONFIG["resolution"],
        "infrared_range_m": CAMERA_CONFIG["infrared_range_m"],
        "lens_options_mm": CAMERA_CONFIG["lens_options_mm"],
        "protection_rating": CAMERA_CONFIG["protection_rating"],
        "video_output": CAMERA_CONFIG["video_output"],
        "power_input": CAMERA_CONFIG["power_input"]
    }
    return jsonify(info)

@app.route("/cam/config", methods=["GET"])
def cam_config():
    query = request.args
    if query:
        filtered = {k: v for k, v in CAMERA_CONFIG.items() if k in query}
        return jsonify(filtered)
    return jsonify(CAMERA_CONFIG)

@app.route("/cam/restart", methods=["POST"])
def cam_restart():
    # As an analog camera, there's no remote API to restart; respond as if command accepted.
    return jsonify({"success": True, "message": "Camera restart command accepted (simulated, analog device)."}), 200

@app.route("/commands/restart", methods=["POST"])
def commands_restart():
    # As an analog camera, there's no remote API to restart; respond as if command accepted.
    return jsonify({"success": True, "message": "Camera restart command accepted (simulated, analog device)."}), 200

if __name__ == "__main__":
    app.run(host=SERVER_HOST, port=SERVER_PORT)