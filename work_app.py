# -*- coding: utf-8 -*-
from flask import Flask, request
import base64
import socket
import struct
import urllib.parse
import xml.etree.ElementTree as ET
from Crypto.Cipher import AES
import hashlib

app = Flask(__name__)

# 企业微信配置
CORP_ID = "ww26ba103d4950e0cb"
TOKEN = "FR7jRcTe8XuERyaDlAE8x1O"
ENCODING_AES_KEY = "5Q23GoLfUradvWbxWoaNEXJ3OU1Q2js88THWoufo9M7"

class WXBizMsgCrypt:
    def __init__(self, encoding_aes_key):
        self.key = base64.b64decode(encoding_aes_key + "=")
        assert len(self.key) == 32

    def decrypt(self, encrypted_data):
        aes = AES.new(self.key, AES.MODE_CBC, self.key[:16])
        decrypted_data = aes.decrypt(base64.b64decode(encrypted_data))
        
        pad_length = decrypted_data[-1]
        if pad_length < 1 or pad_length > 32:
            pad_length = 0
        content = decrypted_data[16:-pad_length]
        
        xml_length = socket.ntohl(struct.unpack("I", content[: 4])[0])
        xml_content = content[4: xml_length + 4]
        
        return xml_content.decode('utf-8')

    def encrypt(self, text):
        # 生成随机16字节的字符串
        random_str = os.urandom(16)
        text_bytes = text.encode('utf-8')
        
        # 生成xml长度的网络字节序
        xml_length = len(text_bytes)
        xml_length_bytes = struct.pack("I", socket.htonl(xml_length))
        
        # 生成需要加密的字符串
        text_bytes = random_str + xml_length_bytes + text_bytes + CORP_ID.encode('utf-8')
        
        # 使用PKCS7补位
        amount_to_pad = AES.block_size - (len(text_bytes) % AES.block_size)
        if amount_to_pad == 0:
            amount_to_pad = AES.block_size
        pad = chr(amount_to_pad).encode('utf-8') * amount_to_pad
        text_bytes += pad
        
        # 加密
        aes = AES.new(self.key, AES.MODE_CBC, self.key[:16])
        encrypted = aes.encrypt(text_bytes)
        return base64.b64encode(encrypted).decode('utf-8')

def verify_signature(msg_signature, timestamp, nonce, echo_str):
    """验证签名"""
    sort_list = [TOKEN, timestamp, nonce, echo_str]
    sort_list.sort()
    str_to_sign = ''.join(sort_list)
    hash_obj = hashlib.sha1(str_to_sign.encode('utf-8'))
    signature = hash_obj.hexdigest()
    return signature == msg_signature

@app.route('/work', methods=['GET'])
def verify_url():
    """验证URL有效性"""
    try:
        # 获取参数
        msg_signature = request.args.get('msg_signature', '')
        timestamp = request.args.get('timestamp', '')
        nonce = request.args.get('nonce', '')
        echostr = request.args.get('echostr', '')
        
        # URL解码
        # 字典排序
        params = [TOKEN, timestamp, nonce, echostr]
        params.sort()
        
        # 拼接字符串
        str_to_sign = ''.join(params)
        
        # 计算sha1签名
        sha1 = hashlib.sha1()
        sha1.update(str_to_sign.encode('utf-8'))
        calculated_signature = sha1.hexdigest()
        
        print("Calculated signature:", calculated_signature)
        # 验证签名
        if calculated_signature != msg_signature:
            print("Signature verification failed")
            return "Signature verification failed", 403
            
        # Base64解码echostr
        try:
            echo_str_decoded = base64.b64decode(echostr)
        except:
            print("Invalid base64 echostr")
            return "Invalid base64 echostr", 400
            
        # 解密AES密钥
        encoding_aes_key_with_equal = ENCODING_AES_KEY + "="
        aes_key = base64.b64decode(encoding_aes_key_with_equal)
        
        # 使用AES-CBC模式解密
        try:
            cipher = AES.new(aes_key, AES.MODE_CBC, aes_key[:16])
            rand_msg = cipher.decrypt(echo_str_decoded)
            
            # 去除PKCS7填充
            pad_length = rand_msg[-1]
            if not isinstance(pad_length, int):
                pad_length = ord(pad_length)
            rand_msg = rand_msg[:-pad_length]
            
            # 解析消息
            content = rand_msg[16:]  # 去掉前16字节随机字符串
            msg_len = socket.ntohl(struct.unpack("I", content[:4])[0])  # 获取4字节msg长度
            msg = content[4:msg_len+4]  # 截取msg_len长度的消息内容
            
            print(f"Decrypted message: {msg.decode('utf-8')}")
            return msg.decode('utf-8')
            
        except Exception as e:
            print(f"Decryption failed: {str(e)}")
            return "Decryption failed", 400
    except Exception as e:
        print(f"Error: {str(e)}")
        return str(e), 500  # 添加错误处理返回

if __name__ == '__main__':
    # 添加缺少的导入
    import os
    import time
    import random
    
    app.run(host='0.0.0.0', port=80) 