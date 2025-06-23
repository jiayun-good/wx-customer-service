import os
import io
import threading
import time
from flask import Flask, Response, jsonify, request

app = Flask(__name__)

# Environment Variables
DEVICE_NAME = os.getenv("DEVICE_NAME", "高清摄像机11")
DEVICE_MODEL = os.getenv("DEVICE_MODEL", "DS-2CE16D0T-IRF")
DEVICE_MANUFACTURER = os.getenv("DEVICE_MANUFACTURER", "海康威视")
DEVICE_TYPE = os.getenv("DEVICE_TYPE", "模拟高清摄像机")
SERVER_HOST = os.getenv("SERVER_HOST", "0.0.0.0")
SERVER_PORT = int(os.getenv("SERVER_PORT", "8080"))
VIDEO_FRAME_RATE = int(os.getenv("CAMERA_FRAME_RATE", "10"))
VIDEO_RESOLUTION = os.getenv("CAMERA_RESOLUTION", "1920x1080")
IR_NIGHT_VISION = os.getenv("CAMERA_NIGHT_VISION", "on").lower() == "on"

# Simulated camera state
camera_state = {
    "resolution": VIDEO_RESOLUTION,
    "ir_night_vision": IR_NIGHT_VISION,
    "video_stream_health": "ok",
    "last_command": None
}

# Simulate a video stream as MJPEG (fake frames for demonstration)
def generate_fake_frame(counter):
    from PIL import Image, ImageDraw
    img = Image.new('RGB', (1920, 1080), color = (73, 109, 137))
    d = ImageDraw.Draw(img)
    text = f"海康威视模拟高清摄像机\nModel: {DEVICE_MODEL}\nFrame: {counter}\nIR Night Vision: {'ON' if camera_state['ir_night_vision'] else 'OFF'}"
    d.text((10,10), text, fill=(255,255,0))
    buf = io.BytesIO()
    img.save(buf, format='JPEG')
    return buf.getvalue()

def mjpeg_stream():
    counter = 0
    while True:
        frame = generate_fake_frame(counter)
        yield (b'--frame\r\n'
               b'Content-Type: image/jpeg\r\n\r\n' + frame + b'\r\n')
        counter += 1
        time.sleep(1.0 / VIDEO_FRAME_RATE)

@app.route('/camera/stream')
def camera_stream():
    return Response(mjpeg_stream(), mimetype='multipart/x-mixed-replace; boundary=frame')

@app.route('/camera/info', methods=['GET'])
def camera_info():
    return jsonify({
        "device_name": DEVICE_NAME,
        "device_model": DEVICE_MODEL,
        "manufacturer": DEVICE_MANUFACTURER,
        "device_type": DEVICE_TYPE
    })

@app.route('/camera/status', methods=['GET'])
def camera_status():
    query = request.args
    status = {}
    if not query or 'all' in query:
        status = camera_state.copy()
    else:
        for k in query:
            if k in camera_state:
                status[k] = camera_state[k]
    return jsonify(status)

@app.route('/camera/cmd', methods=['POST'])
def camera_cmd():
    payload = request.get_json(force=True)
    if not isinstance(payload, dict):
        return jsonify({"error": "Invalid payload"}), 400

    response = {}
    for cmd, value in payload.items():
        if cmd == "restart":
            camera_state["video_stream_health"] = "restarting"
            def delayed_restart():
                time.sleep(2)
                camera_state["video_stream_health"] = "ok"
            threading.Thread(target=delayed_restart).start()
            response["result"] = "Camera restarting..."
        elif cmd == "toggle_ir":
            camera_state["ir_night_vision"] = not camera_state["ir_night_vision"]
            response["ir_night_vision"] = camera_state["ir_night_vision"]
        elif cmd == "set_resolution":
            camera_state["resolution"] = str(value)
            response["resolution"] = camera_state["resolution"]
        else:
            response[cmd] = f"Unknown command"
    camera_state["last_command"] = payload
    return jsonify(response)

if __name__ == '__main__':
    try:
        from PIL import Image, ImageDraw
    except ImportError:
        import sys
        print("This driver requires the Pillow library. Please install with 'pip install pillow'")
        sys.exit(1)
    app.run(host=SERVER_HOST, port=SERVER_PORT, threaded=True)