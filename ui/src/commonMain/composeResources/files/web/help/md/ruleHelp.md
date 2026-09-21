# 源规则帮助

* [阅读3.0(Legado)规则说明](https://mgz0227.github.io/The-tutorial-of-Legado/)
* [书源帮助文档](https://mgz0227.github.io/The-tutorial-of-Legado/Rule/source.html)
* [订阅源帮助文档](https://mgz0227.github.io/The-tutorial-of-Legado/Rule/rss.html)
* 辅助键盘❓中可插入URL参数模板,打开帮助,js教程,正则教程,选择文件
* 规则标志, {{......}}内使用规则必须有明显的规则标志,没有规则标志当作js执行
```
@@ 默认规则,直接写时可以省略@@
@XPath: xpath规则,直接写时以//开头可省略@XPath
@Json: json规则,直接写时以$.开头可省略@Json
: regex规则,不可省略,只可以用在书籍列表和目录列表
```
* jsLib

>
注入JavaScript到JS引擎中，支持两种格式，可实现[函数共用](https://github.com/huajideshutiao/legado/wiki/JavaScript%E5%87%BD%E6%95%B0%E5%85%B1%E7%94%A8)

> `JavaScript Code` 直接填写JavaScript片段  
> `{"example":"https://www.example.com/js/example.js", ...}` 自动复用已经下载的js文件

> jsLib只编译执行一次并加载进该源的共享作用域，之后各规则都在其子作用域中调用其中的函数（详见js帮助的「jsLib 共享函数库」章节）
> 注意：不同线程各有独立的共享作用域，jsLib顶层的全局变量不跨线程共享，请勿用它在规则间传递状态；修改源后作用域自动失效重建

* 并发率
> 并发限制，单位ms，可填写两种格式

> `1000` 访问间隔1s  
> `20/60000` 60s内访问次数20  

* 书源类型: 文件
> 对于类似知轩藏书提供文件整合下载的网站，可以在书源详情的下载URL规则获取文件链接

> 通过截取下载链接或文件响应头头获取文件信息，获取失败会自动拼接`书名` `作者`和下载链接的`UrlOption`的`type`字段

> 压缩文件解压缓存会在下次启动后自动清理，不会占用额外空间  

* CookieJar
> 启用后会自动保存每次返回头中的Set-Cookie中的值，适用于验证码图片一类需要session的网站

* 登录UI
> 不使用内置webView登录网站，需要使用`登录URL`规则实现登录逻辑，可使用`登录检查JS`检查登录结果  
> 版本20221113重要更改：按钮支持调用`登录URL`规则里面的函数，必须实现`login`函数
```
规则填写示范
[
    {
        "name": "telephone",
        "type": "text"
    },
    {
        "name": "password",
        "type": "password"
    },
    {
        "name": "注册",
        "type": "button",
        "action": "http://www.yooike.com/xiaoshuo/#/register?title=%E6%B3%A8%E5%86%8C"
    },
    {
        "name": "获取验证码",
        "type": "button",
        "action": "getVerificationCode()",
        "style": {
            "cols": 2
        }
    }
]
```

> `style.cols` 表示该项所在行的总份数（一行放几项），取值 1-4，默认按各场景内置（登录 UI 文本/密码框默认
> 整行，按钮/开关/下拉等默认 2；发现分类默认 3）。  
> 旧字段 `layout_flexBasisPercent` 仍兼容解析，未填 `cols` 时按其数值推断列数；
`layout_flexGrow / layout_flexShrink / layout_alignSelf / layout_wrapBefore` 已不再生效。
* 登录URL
> 可填写登录链接或者实现登录UI的登录逻辑的JavaScript
```
示范填写
function login() {
    java.log("模拟登录请求");
    java.log(source.getLoginInfoMap());
}
function getVerificationCode() {
    java.log("登录UI按钮：获取到手机号码"+result.get("telephone"))
}

登录按钮函数获取登录信息
result.get("telephone")
login函数获取登录信息
source.getLoginInfo()
source.getLoginInfoMap().get("telephone")
source登录相关方法,可在js内通过source.调用,可以参考阿里云语音登录
login()
getHeaderMap(hasLoginHeader: Boolean = false)
getLoginHeader(): String?
getLoginHeaderMap(): Map<String, String>?
putLoginHeader(header: String)
removeLoginHeader()
setVariable(variable: String?)
getVariable(): String?
AnalyzeUrl相关函数,js中通过java.调用
initUrl() //重新解析url,可以用于登录检测js登录后重新解析url重新访问
getHeaderMap().putAll(source.getHeaderMap(true)) //重新设置登录头
getStrResponse(jsStr: String? = null, sourceRegex: String? = null, allowWebView: Boolean = true) //返回访问结果,文本类型,书源内部重新登录后可调用此方法重新返回结果
getResponse(): Response //返回访问结果,网络朗读引擎采用的是这个,调用登录后在调用这方法可以重新访问,参考阿里云登录检测
```

* 发现url格式
```json
[
  {
    "title": "全部小说",
    "url": "",
    "style": {
      "cols": 1
    }
  },
  {
    "title": "男频",
    "url": "",
    "style": {
      "cols": 2
    }
  },
  {
    "title": "女频",
    "url": "",
    "style": {
      "cols": 2
    }
  },
  {
    "title": "玄幻",
    "url": ""
  },
  {
    "title": "都市",
    "url": ""
  },
  {
    "title": "武侠",
    "url": ""
  }
]
```

> `cols` 取值 1-4：1=整行、2=一行两个、3=一行三个（默认）、4=一行四个。  
> 同一行内各项 `cols` 应一致；不同行可不同，列线会基于 12 列基底严格对齐。  
> 旧字段 `layout_flexBasisPercent` 仍兼容解析；
`layout_flexGrow / layout_flexShrink / layout_alignSelf / layout_wrapBefore` 已不再生效。

* 请求头,支持http代理,socks4 socks5代理设置
> 注意请求头的key是区分大小写的  
> 正确格式 User-Agent Referer  
> 错误格式 user-agent referer
```
socks5代理
{
  "proxy":"socks5://127.0.0.1:1080"
}
不支持需要验证的socks代理
http代理
{
  "proxy":"http://127.0.0.1:1080"
}
支持http代理服务器验证
{
  "proxy":"http://127.0.0.1:1080@用户名@密码"
}
注意:这些请求头是无意义的,会被忽略掉
```

* url添加js参数,解析url时执行,可在访问url时处理url,例
```
https://www.baidu.com,{"js":"java.headerMap.put('xxx', 'yyy')"}
https://www.baidu.com,{"js":"java.url=java.url+'yyyy'"}
```

* 增加js方法，用于重定向拦截
  * `java.get(urlStr: String, headers: Map<String, String>)`
  * `java.post(urlStr: String, body: String, headers: Map<String, String>)`
* 对于搜索重定向的源，可以使用此方法获得重定向后的url
```
(()=>{
  if(page==1){
    let url='https://www.yooread.net/e/search/index.php,'+JSON.stringify({
    "method":"POST",
    "body":"show=title&tempid=1&keyboard="+key
    });
    return source.put('surl',String(java.connect(url).raw().request().url()));
  } else {
    return source.get('surl')+'&page='+(page-1)
  }
})()
或者
(()=>{
  let base='https://www.yooread.net/e/search/';
  if(page==1){
    let url=base+'index.php';
    let body='show=title&tempid=1&keyboard='+key;
    return base+source.put('surl',java.post(url,body,{}).header("Location"));
  } else {
    return base+source.get('surl')+'&page='+(page-1);
  }
})()
```

* 图片链接支持修改headers
```
let options = {
"headers": {"User-Agent": "xxxx","Referrer":baseUrl,"Cookie":"aaa=vbbb;"}
};
'<img src="'+src+","+JSON.stringify(options)+'">'
```

* 字体解析使用
> 使用方法,在正文替换规则中使用,原理根据f1字体的字形数据到f2中查找字形对应的编码
```
<js>
(function(){
  var b64=String(src).match(/ttf;base64,([^\)]+)/);
  if(b64){
    var f1 = java.queryTTF(b64[1]);
    var f2 = java.queryTTF("https://alanskycn.gitee.io/teachme/assets/font/Source Han Sans CN Regular.ttf");
    // return java.replaceFont(result, f1, f2);
    return java.replaceFont(result, f1, f2, true); // 过滤掉f1中不存在的字形
  }
  return result;
})()
</js>
```

* 购买操作
> 可直接填写链接或者JavaScript，如果执行结果是网络链接将会自动打开浏览器,js返回true自动刷新目录和当前章节

* 图片解密
> 适用于图片需要二次解密的情况，直接填写JavaScript，返回解密后的`ByteArray`  
>
部分变量说明：java（仅支持[js扩展类](https://github.com/huajideshutiao/legado/blob/master/shared/src/jvmAndAndroidMain/kotlin/io/legado/app/help/JsExtensionsJvm.kt)
），result为待解密图片的`ByteArray`，src为图片链接
> 漫画切块重排一类的解密可用注入的`image`对象（跨端一致），详见js帮助的「image 对象（图片解密）」章节

```js
java.createSymmetricCrypto("AES/CBC/PKCS5Padding", key, iv).decrypt(result)
```

```js
function decodeImage(data, key) {
  var input = new Packages.java.io.ByteArrayInputStream(data)
  var out = new Packages.java.io.ByteArrayOutputStream()
  var byte
  while ((byte = input.read()) != -1) {
    out.write(byte ^ key)
  }
  return out.toByteArray()
}

decodeImage(result, key)
```

* 封面解密
> 同图片解密 其中result为待解密封面的`inputStream`

```js
java.createSymmetricCrypto("AES/CBC/PKCS5Padding", key, iv).decrypt(result)
```

```js
function decodeImage(data, key) {
  var out = new Packages.java.io.ByteArrayOutputStream()
  var byte
  while ((byte = data.read()) != -1) {
    out.write(byte ^ key)
  }
  return out.toByteArray()
}

decodeImage(result, key)
```

* 段评规则（ReviewRule）
> 段评即"段落/章节/书籍评论"，配置在书源的 `ruleReview`（JSON）里，由 `enabledReview` 开关控制是否启用。  
> 进入位置：阅读界面点击段落尾部气泡（`paragraphIndex>0`）、章节菜单（`0`）、书籍详情页菜单（`-1`，此时无章节）。

> 局部变量（注入到 JS 规则，也可作 `{{...}}` 占位符使用；另见 js 帮助的变量表）

| 变量 | 含义 |
|---|---|
| paragraphIndex | 目标位置：`-1`=书籍级、`0`=章节级、`>0`=正文第 N 段 |
| page | 分页号，从 1 起（用于 `reviewUrl` / `replyListUrl`） |
| sort | 列表排序：`0`=最热、`1`=最新（仅 `reviewUrl`） |
| reviewId | 段评 ID（用于 `replyListUrl` 及各动作规则） |
| selected | 点赞/点踩的目标态（Boolean，本次操作后应处于的状态，即当前态取反） |
| result | 在 `replyRule` 中为用户输入的回复正文；在 `totalCountRule` / `hasMoreRule` 中为整页响应 body |

> 请求 / URL 规则（在列表项循环之前求值，上下文为整页 body 或纯 JS）

| 字段 | 说明 |
|---|---|
| reviewUrl | 段评列表 URL，走 AnalyzeUrl；可用 `{{paragraphIndex}}` `{{page}}` `{{sort}}` 占位符或同名 JS 变量。为空则报错 |
| reviewCountRule | 纯 JS 规则（`result`/`src` 为空串，需自行发起请求），返回 `{段落号: 评论数}` 的 JS 对象或 JSON 字符串；仅 `段落号>0` 且 `数>0` 的项生效，用于正文段落尾部气泡计数 |
| totalCountRule | 请求级，`result` 为整页 body；字符串透传给列表头部"全部评论"计数，未配置则不显示 |
| hasMoreRule | 请求级，`result` 为整页 body，按 Boolean 解析是否有下一页；未配置时：列表非空默认还有下一页，列表为空则判为无 |
| replyListUrl | 某条段评的回复列表 URL（通常写 `@js:`），变量 `paragraphIndex` `reviewId` `page`；仅用户展开回复时执行，复用下方 `reviewList` 及各列表项规则解析 |

> 列表 / 列表项规则（在 `reviewList` 选出的每个 item 内解析）

| 字段 | 说明 |
|---|---|
| reviewList | 评论列表节点选择器；未配置视为空列表 |
| reviewIdRule | 段评 ID，供点赞/点踩/回复/删除引用 |
| avatarRule | 发布者头像（为空则不显示） |
| nameRule | 发布者用户名 |
| contentRule | 段评正文；解析为空的条目直接跳过 |
| postTimeRule | 发布时间 |
| extraRule | 附加信息（楼层/等级/地区等，单行小字） |
| imagesRule | 图片列表，按 URL 列表解析 |
| voteUpCountRule | 点赞数，解析为整数（非数字按 0） |
| replyCountRule | 一级回复数，解析为整数（非数字按 0） |
| voteUpSelectedRule | 当前登录用户是否已点赞，Boolean 解析；未配置视为 false |
| voteDownSelectedRule | 当前登录用户是否已点踩，Boolean 解析；未配置视为 false |

> 动作规则（均为 JS，由用户操作触发）

| 字段 | 说明 |
|---|---|
| voteUpRule | 点赞；变量 `paragraphIndex` `reviewId` `selected` |
| voteDownRule | 点踩；变量 `paragraphIndex` `reviewId` `selected` |
| replyRule | 回复；变量 `paragraphIndex` `reviewId`（回复顶层段评时为空），回复正文经 `result` 传入 |
| deleteRule | 删除；变量 `paragraphIndex` `reviewId`；JS 返回 `true` 则本地移除该条，其他返回值触发整页重载 |

> 示例骨架（`ruleReview`，仅示意结构，规则值按目标站点改写）

```json
{
  "reviewUrl": "https://www.example.com/review?para={{paragraphIndex}}&sort={{sort}}&page={{page}}",
  "reviewList": "$.data.list[*]",
  "reviewIdRule": "$.id",
  "avatarRule": "$.user.avatar",
  "nameRule": "$.user.name",
  "contentRule": "$.content",
  "postTimeRule": "$.time",
  "extraRule": "$.floor",
  "imagesRule": "$.images",
  "voteUpCountRule": "$.likeCount",
  "replyCountRule": "$.replyCount",
  "voteUpSelectedRule": "$.liked",
  "totalCountRule": "$.data.total",
  "hasMoreRule": "$.data.hasNext",
  "replyListUrl": "<js>'https://www.example.com/reply?id='+reviewId+'&page='+page</js>",
  "reviewCountRule": "<js>/* 自行请求本章各段评论数, 返回 {\"1\":3,\"5\":8} */</js>",
  "voteUpRule": "<js>java.post('https://www.example.com/like', JSON.stringify({id: reviewId, cancel: !selected}), {})</js>",
  "replyRule": "<js>java.post('https://www.example.com/reply', JSON.stringify({id: reviewId, para: paragraphIndex, text: result}), {})</js>",
  "deleteRule": "<js>java.post('https://www.example.com/del', JSON.stringify({id: reviewId}), {})</js>"
}
```
