const express = require('express');
const crypto = require('crypto');
const xml2js = require('xml2js');
const app = express();
const server = require('http').createServer(app);

// 配置微信公众号的token
const TOKEN = "6637testsdajasjdksjadaiskuh";

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