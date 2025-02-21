const express = require('express');
const crypto = require('crypto');
const xml2js = require('xml2js');
const axios = require('axios');
const path = require('path');
const fs = require('fs');
const app = express();
const server = require('http').createServer(app);

// 企业微信配置信息
const config = {
    corpId: 'ww26ba103d4950e0cb',
    corpSecret: 'zUShp6BmRYb8O8o9INoTP0R2141RRh2Jdky5Q2R56A0',
    token: 'FR7jRcTe8XuERyaDlAE8x1O',
    encodingAESKey: '5Q23GoLfUradvWbxWoaNEXJ3OU1Q2js88THWoufo9M7'
};

// 解密消息
function decrypt(encrypted, key) {
    try {
        const aesKey = Buffer.from(key + '=', 'base64');
        const decipher = crypto.createDecipheriv('aes-256-cbc', aesKey.slice(0, 32), aesKey.slice(0, 16));
        decipher.setAutoPadding(false);

        let decrypted = decipher.update(encrypted, 'base64', 'utf8');
        decrypted += decipher.final('utf8');

        const pad = decrypted[decrypted.length - 1].charCodeAt(0);
        decrypted = decrypted.slice(0, -pad);

        const randomBytes = decrypted.slice(0, 16);
        const msgLen = decrypted.slice(16, 20).split('').map(c => c.charCodeAt(0));
        const len = msgLen[3] | msgLen[2] << 8 | msgLen[1] << 16 | msgLen[0] << 24;
        const msg = decrypted.slice(20, len + 20);
        const receivedCorpId = decrypted.slice(len + 20);

        if (receivedCorpId !== config.corpId) {
            throw new Error('CorpId不匹配');
        }

        return msg;
    } catch (error) {
        throw error;
    }
}

// 加密消息
function encrypt(message, key, corpId) {
    const aesKey = Buffer.from(key + '=', 'base64');
    const random = crypto.randomBytes(16).toString('utf8');
    const msg = Buffer.from(message);
    const msgLength = Buffer.alloc(4);
    msgLength.writeUInt32BE(msg.length, 0);

    const buffer = Buffer.concat([
        Buffer.from(random),
        msgLength,
        msg,
        Buffer.from(corpId)
    ]);

    // 补位
    const padding = 32 - (buffer.length % 32);
    const padBuffer = Buffer.alloc(padding, padding);
    const finalBuffer = Buffer.concat([buffer, padBuffer]);

    // 加密
    const cipher = crypto.createCipheriv('aes-256-cbc', aesKey.slice(0, 32), aesKey.slice(0, 16));
    let encrypted = cipher.update(finalBuffer);
    encrypted = Buffer.concat([encrypted, cipher.final()]);

    return encrypted.toString('base64');
}

// 添加一个格式化消息的辅助函数
function formatMessage(message) {
    let output = '\n=== 企业微信消息详情 ===\n';
    output += `时间: ${new Date(parseInt(message.CreateTime) * 1000).toLocaleString()}\n`;
    output += `消息类型: ${message.MsgType}\n`;

    switch (message.MsgType) {
        case 'text':
            output += `发送者: ${message.FromUserName}\n`;
            output += `接收者: ${message.ToUserName}\n`;
            output += `内容: ${message.Content}\n`;
            output += `消息ID: ${message.MsgId}\n`;
            break;

        case 'image':
            output += `发送者: ${message.FromUserName}\n`;
            output += `图片链接: ${message.PicUrl}\n`;
            output += `媒体ID: ${message.MediaId}\n`;
            break;

        case 'voice':
            output += `发送者: ${message.FromUserName}\n`;
            output += `媒体ID: ${message.MediaId}\n`;
            output += `格式: ${message.Format}\n`;
            if (message.Recognition) {
                output += `语音识别结果: ${message.Recognition}\n`;
            }
            break;

        case 'video':
            output += `发送者: ${message.FromUserName}\n`;
            output += `媒体ID: ${message.MediaId}\n`;
            output += `缩略图媒体ID: ${message.ThumbMediaId}\n`;
            break;

        case 'location':
            output += `发送者: ${message.FromUserName}\n`;
            output += `纬度: ${message.Location_X}\n`;
            output += `经度: ${message.Location_Y}\n`;
            output += `缩放级别: ${message.Scale}\n`;
            output += `位置信息: ${message.Label}\n`;
            break;

        case 'link':
            output += `发送者: ${message.FromUserName}\n`;
            output += `标题: ${message.Title}\n`;
            output += `描述: ${message.Description}\n`;
            output += `链接: ${message.Url}\n`;
            break;

        case 'event':
            output += `事件类型: ${message.Event}\n`;
            switch (message.Event) {
                case 'kf_msg_or_event':
                    output += `客服消息Token: ${message.Token}\n`;
                    output += `客服ID: ${message.OpenKfId}\n`;
                    break;
                case 'subscribe':
                    output += `订阅用户: ${message.FromUserName}\n`;
                    break;
                case 'unsubscribe':
                    output += `取消订阅用户: ${message.FromUserName}\n`;
                    break;
                case 'click':
                    output += `点击的菜单KEY: ${message.EventKey}\n`;
                    break;
                // 可以根据需要添加更多事件类型的处理
            }
            break;
    }
    output += '=========================\n';
    return output;
}

// 添加获取access_token的函数
async function getAccessToken() {
    try {
        const url = `https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid=${config.corpId}&corpsecret=${config.corpSecret}`;
        const response = await axios.get(url);
        if (response.data.errcode === 0) {
            return response.data.access_token;
        }
        throw new Error(`获取access_token失败: ${response.data.errmsg}`);
    } catch (error) {
        console.error('获取access_token错误:', error);
        throw error;
    }
}

// 修改获取客服消息的函数
async function getCustomerServiceMessage(token, openKfId) {
    try {
        const accessToken = await getAccessToken();
        const url = `https://qyapi.weixin.qq.com/cgi-bin/kf/sync_msg?access_token=${accessToken}`;

        const response = await axios.post(url, {
            token: token,
            open_kfid: openKfId
        });

        if (response.data.errcode === 0 && response.data.msg_list && response.data.msg_list.length > 0) {
            // 按时间排序，获取最新消息
            const sortedMessages = response.data.msg_list.sort((a, b) => b.send_time - a.send_time);
            return [sortedMessages[0]]; // 返回最新的一条消息
        }
        return [];
    } catch (error) {
        return [];
    }
}

// 添加获取媒体文件函数
async function getMediaFile(mediaId) {
    try {
        const accessToken = await getAccessToken();
        const url = `https://qyapi.weixin.qq.com/cgi-bin/media/get?access_token=${accessToken}&media_id=${mediaId}`;

        console.log('正在获取媒体文件...');
        const response = await axios.get(url, {
            responseType: 'arraybuffer'  // 获取二进制数据
        });

        return response.data;
    } catch (error) {
        console.error('获取媒体文件失败:', error);
        throw error;
    }
}

// 修改语音处理函数
async function getVoiceFile(mediaId) {
    try {
        const accessToken = await getAccessToken();
        const mediaUrl = `https://qyapi.weixin.qq.com/cgi-bin/media/get?access_token=${accessToken}&media_id=${mediaId}`;
        console.log(`语音文件下载地址: ${mediaUrl}`);
        return mediaUrl;
    } catch (error) {
        console.error('获取语音文件URL失败:', error);
        return null;
    }
}

// 修改检查会话状态的函数
async function checkSessionStatus(openKfId, externalUserId) {
    try {
        const accessToken = await getAccessToken();
        const url = `https://qyapi.weixin.qq.com/cgi-bin/kf/customer/batchget?access_token=${accessToken}`;

        const response = await axios.post(url, {
            external_userid_list: [externalUserId]
        });

        if (response.data.errcode === 0 && response.data.customer_list) {
            const customer = response.data.customer_list.find(c => c.external_userid === externalUserId);
            // 检查客户是否存在且有会话
            return customer && customer.service_state && customer.service_state !== 0;
        }
        return false;
    } catch (error) {
        console.error('检查会话状态失败:', error.message);
        return false;
    }
}

// 修改启动会话的函数
async function startKfSession(openKfId, externalUserId) {
    try {
        const accessToken = await getAccessToken();
        // 使用客服接待的API
        const url = `https://qyapi.weixin.qq.com/cgi-bin/kf/customer/batchget?access_token=${accessToken}`;

        // 先获取客服账号列表
        const response = await axios.post(url, {
            external_userid_list: [externalUserId]
        });

        if (response.data.errcode === 0) {
            // 如果获取成功，使用接待API
            const startUrl = `https://qyapi.weixin.qq.com/cgi-bin/kf/customer/reception/create?access_token=${accessToken}`;
            const startResponse = await axios.post(startUrl, {
                open_kfid: openKfId,
                external_userid: externalUserId
            });

            if (startResponse.data.errcode === 0) {
                console.log('会话启动成功');
                return true;
            }
            console.error('启动接待失败:', startResponse.data);
            return false;
        }

        console.error('获取客服账号失败:', response.data);
        return false;
    } catch (error) {
        console.error('启动会话出错:', error.message);
        return false;
    }
}

// 修改发送消息的函数
async function sendKfMessage(openKfId, externalUserId, msgtype, content) {
    const accessToken = await getAccessToken();
    const url = `https://qyapi.weixin.qq.com/cgi-bin/kf/send_msg?access_token=${accessToken}`;

    const message = {
        touser: externalUserId,
        open_kfid: openKfId,
        msgtype: msgtype
    };

    if (msgtype === 'text') {
        message.text = { content: content };
    }

    // console.log('发送消息:', {
    //     url: url,
    //     message: message
    // });

    const response = await axios.post(url, message);

    if (response.data.errcode === 0) {
        console.log('消息发送成功');
        return true;
    } else {
        console.log('消息发送失败:', response.data);
        return false;
    }
}

// 验证URL有效性
app.get('/work', (req, res) => {
    const { msg_signature, timestamp, nonce, echostr } = req.query;

    // 验证签名
    const array = [config.token, timestamp, nonce, echostr].sort();
    const str = array.join('');
    const signature = crypto.createHash('sha1').update(str).digest('hex');

    if (msg_signature === signature) {
        // 解密echostr
        try {
            const decrypted = decrypt(echostr, config.encodingAESKey);
            res.send(decrypted);
        } catch (err) {
            console.error('解密失败:', err);
            res.status(401).send('Invalid signature');
        }
    } else {
        res.status(401).send('Invalid signature');
    }
});

// 修改消息处理部分
app.post('/work', (req, res) => {
    let buffer = '';
    req.on('data', chunk => {
        buffer += chunk;
    });

    req.on('end', async () => {
        try {
            const result = await new Promise((resolve, reject) => {
                xml2js.parseString(buffer, { trim: true }, (err, result) => {
                    if (err) reject(err);
                    else resolve(result);
                });
            });

            if (!result?.xml?.Encrypt) {
                throw new Error('无效的消息格式');
            }

            const encrypt = result.xml.Encrypt[0];
            const decrypted = decrypt(encrypt, config.encodingAESKey);

            const decryptedResult = await new Promise((resolve, reject) => {
                xml2js.parseString(decrypted, {
                    trim: true,
                    explicitArray: false,
                    cdata: true
                }, (err, result) => {
                    if (err) reject(err);
                    else resolve(result);
                });
            });

            if (!decryptedResult?.xml) {
                throw new Error('解密后的消息格式无效');
            }

            const message = decryptedResult.xml;

            if (message.MsgType === 'event' && message.Event === 'kf_msg_or_event') {
                try {
                    const messages = await getCustomerServiceMessage(
                        message.Token,
                        message.OpenKfId
                    );

                    if (messages.length > 0) {
                        const msg = messages[0];
                        const time = new Date(msg.send_time * 1000).toLocaleString();

                        if (msg.msgtype === 'text' || msg.msgtype === 'image' || msg.msgtype === 'voice') {
                            let content = '';
                            switch (msg.msgtype) {
                                case 'text':
                                    content = msg.text.content;
                                    break;
                                case 'image':
                                    content = '[图片消息]';
                                    break;
                                case 'voice':
                                    content = '[语音消息]';
                                    break;
                            }
                            console.log(`[${time}] ${content}`);

                            try {
                                // 检查会话状态并发送回复
                                const result = await sendKfMessage(
                                    message.OpenKfId,
                                    msg.external_userid,
                                    'text',
                                    '已收到，感谢您的反馈'
                                );
                                if (!result) {
                                    console.log('无法发送回复消息 - 会话可能已结束');
                                }
                            } catch (error) {
                                console.error('发送回复消息时出错:', error.message);
                            }
                        }
                    }
                } catch (error) {
                    // 静默处理错误
                }
            }

            res.send('success');
        } catch (error) {
            res.send('success');
        }
    });
});

// 启动服务器
server.listen(80, '0.0.0.0', () => {
    console.log('企业微信回调服务器运行在端口 80');
}); 