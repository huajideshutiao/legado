# 在线朗读规则说明

* 在线朗读规则为url规则,同书源url,并发率、请求头、登录URL/登录UI/登录检查JS等设置也与书源相同
* js参数
```
speakText //朗读文本,已过滤不可读字符
speakSpeed //朗读速度,0-45,显示速率为(speakSpeed+5)/10,即0.5x~5.0x
```
* Content-Type 期望响应的Content-Type正则；响应为json/text时一律按错误处理
* 例:
```
http://tts.baidu.com/text2audio,{
    "method": "POST",
    "body": "tex={{java.encodeURI(java.encodeURI(speakText))}}&spd={{String((speakSpeed + 5) / 10 + 4)}}&per=5003&cuid=baidu_speech_demo&idx=1&cod=2&lan=zh&ctp=1&pdt=1&vol=5&pit=5&_res_tag_=audio"
}
```