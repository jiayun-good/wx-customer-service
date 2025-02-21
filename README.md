一、调研背景
信息收集需求
二、调研结论
1.微信公众号
服务器可以接收来自微信用户向公众号发送的消息并自动回复,但语音识别功能需正式的公众号
2.企业微信客服
服务器可以接收来自微信用户向客服发送的消息并自动回复,语言消息需通过企业微信的 media_id,向企业微信的 media.get 发送请求,会获得音频文件的 URL，之后可以下载音频文件
三、功能详情
1.微信公众号
实现条件:
1.一个能访问到的公网ip
2.服务器环境:node,python
3.注册公众号
实现:
1.接入指南
关于填写服务器配置,在服务器中要添加以下代码main.py
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
 
 
 
 

并运行 python3 app.py ,之后可在本地测试访问http://服务器地址/wx ,能访问,并且页面显示 hello, this is handle view ,这样才证明此URL可用.
[图片]
2.服务器写一个简单的node服务(可回复用户发送的内容),启用 node app.js
const express = require('express');
const crypto = require('crypto');
const xml2js = require('xml2js');
const app = express();
const server = require('http').createServer(app);

// 配置微信公众号的token
const TOKEN = "your-token";

// 处理微信服务器的认证请求
app.get('/wx', (req, res) => {
  console.log(req.query);
  
    const { signature, timestamp, nonce, echostr } = req.query;
    
    // 将token、timestamp、nonce三个参数进行字典序排序
    const array = [TOKEN, timestamp, nonce];
    array.sort();
    
    // 将三个参数字符串拼接成一个字符串进行sha1加密
    const tempStr = array.join('');
    const hashCode = crypto.createHash('sha1').update(tempStr).digest('hex');
  console.log(hashCode);
    // 开发者获得加密后的字符串可与signature对比，标识该请求来源于微信
    if (hashCode === signature) {
        res.send(echostr);
    } else {
        res.send('Invalid signature');
    }
});

// 处理微信用户发送的消息
app.post('/wx', (req, res) => {
    let body = '';
    req.on('data', chunk => {
        body += chunk;
        console.log('接收数据长度:', chunk.length);
        console.log('当前数据内容:', chunk);
    });

    req.on('end', () => {
        console.log('数据接收完成，总长度:', body.length);
        if (!body) {
            console.error('没有接收到数据');
            res.send('success');
            return;
        }

        // 解析XML数据
        xml2js.parseString(body, {trim: true}, (err, result) => {
            if (err) {
                console.error('解析XML失败:', err);
                res.send('success');
                return;
            }

            console.log('解析后的消息对象:', JSON.stringify(result, null, 2));
            
            try {
                const message = result.xml;
                const msgType = message.MsgType[0];
                const fromUser = message.FromUserName[0];
                const toUser = message.ToUserName[0];

                // 根据消息类型处理不同的消息
                let replyMessage = '';
                if (msgType === 'text') {
                    const content = message.Content[0];
                    replyMessage = `
                        <xml>
                            <ToUserName><![CDATA[${fromUser}]]></ToUserName>
                            <FromUserName><![CDATA[${toUser}]]></FromUserName>
                            <CreateTime>${Date.now()}</CreateTime>
                            <MsgType><![CDATA[text]]></MsgType>
                            <Content><![CDATA[你发送的消息是: ${content}]]></Content>
                        </xml>
                    `;
                } else if (msgType === 'voice') {
                    // 处理语音消息
                    const mediaId = message.MediaId[0];
                    console.log('接收到语音消息，MediaId:', mediaId);
                    
                    // 检查是否有语音识别结果
                    if (message.Recognition && message.Recognition[0]) {
                        console.log('语音识别结果:', message.Recognition[0]);
                        replyMessage = `
                            <xml>
                                <ToUserName><![CDATA[${fromUser}]]></ToUserName>
                                <FromUserName><![CDATA[${toUser}]]></FromUserName>
                                <CreateTime>${Date.now()}</CreateTime>
                                <MsgType><![CDATA[text]]></MsgType>
                                <Content><![CDATA[收到你的语音消息，识别结果是: ${message.Recognition[0]}]]></Content>
                            </xml>
                        `;
                    } else {
                        console.log('未开启语音识别功能或识别失败');
                        replyMessage = `
                            <xml>
                                <ToUserName><![CDATA[${fromUser}]]></ToUserName>
                                <FromUserName><![CDATA[${toUser}]]></FromUserName>
                                <CreateTime>${Date.now()}</CreateTime>
                                <MsgType><![CDATA[text]]></MsgType>
                                <Content><![CDATA[收到你的语音消息。由于未开启语音识别功能，无法显示语音内容。]]></Content>
                            </xml>
                        `;
                    }
                }

                res.type('application/xml');
                res.send(replyMessage || 'success');
            } catch (error) {
                console.error('处理消息时发生错误:', error);
                res.send('success');
            }
        });
    });

});

// 启动服务器
server.listen(80, '0.0.0.0', () => {
    console.log('微信公众号服务器运行在端口 80');
}); 
3.启动服务器配置,用一个公众平台测试账号,接口配置信息这里,写入服务器的配置信息,接下来使用微信扫码关注测试公众号
[图片]

4.实际效果
[图片]
测试号能实现的语音识别
JS-SDK配置中,有关于语言识别的功能,但需要在微信浏览器中使用,通过微信内置浏览器访问页面
点击"开始录音"按钮开始录音
点击"停止录音"按钮结束录音并获取识别结果
识别结果会显示在页面上
[图片]
2.企业微信客服
实现条件:
1.前两项与微信公众号要求一致
2.注册一个企业微信号,登录企业微信管理后台,注册一个自己的应用,注册完成后接下来要配置企业可信IP,添加上你的公网IP
[图片]
3.创建一个微信客服
[图片]
实现:
1.和微信公众号的服务器认证类似,需验证URL地址有效性,在服务器创建一个work_app.js,内容如下,运行python3 work_app.js ,具体验证规则可翻阅官方手册 ,这个运行成功没有页面对应输出,直接使用即可
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
CORP_ID = "your-CORP_ID" //应用的CORP_ID
TOKEN = "your-TOKEN" //你自己设置的token
ENCODING_AES_KEY = "your-ENCODING_AES_KEY" //你自己设置的EncodingAESKey

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
2.进入自建应用,点击设置API接收,按照要求输入对应信息(认证的py中写了/work,所以URL要带/work),点击保存,应用成功
[图片]
3.将创建的客服号管理自建应用
[图片]
3.服务器创建一个node服务,进行企业内部开发
4.最终效果,并且服务器端接收到消息
[图片]
未认证企业微信的客服API权限限制通常为 1个客服账号 可以通过API回复消息,但不会限制接收信息的客服账号数量,累计可接待100位客户,认证后可提高数量
