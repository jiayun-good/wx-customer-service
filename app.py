# -*- coding: utf-8 -*-
# filename: handle.py
 
from flask import Flask, request
import hashlib
 
app = Flask(__name__)
 
@app.route('/wx', methods=['GET'])
def handle_get():
    try:
        data = request.args
        if not data:
            return "hello, this is handle view"
       
        signature = data.get('signature')
        timestamp = data.get('timestamp')
        nonce = data.get('nonce')
        echostr = data.get('echostr')
        token = "6637testsdajasjdksjadaiskuh"
 
        list_str = [token, timestamp, nonce]
        list_str.sort()
        sha1 = hashlib.sha1()
        sha1.update(''.join(list_str).encode('utf-8'))
        hashcode = sha1.hexdigest()
       
        print("handle/GET func: hashcode, signature: ", hashcode, signature)
        if hashcode == signature:
            return echostr
        else:
            return ""
    except Exception as e:
        return str(e)
 
if __name__ == '__main__':
    app.run(host='0.0.0.0', port=80)
 
 
 
 
