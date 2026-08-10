# js变量和函数
> 阅读使用[QuickJS-ng v0.15.1](https://github.com/quickjs-ng/quickjs) 作为JavaScript引擎，支持ES2023绝大部分特性；
> 并内置Java桥接层（兼容Rhino LiveConnect写法）用于调用Java类和方法

|构造函数|函数|对象|简要说明|
|------|-----|------|------|
|JavaImporter|importClass| |导入Java类到JavaScript。顶层`importPackage`为空实现（QuickJS无法枚举Java包），请改用`new JavaImporter(包)`或全限定类名；ios/ohos 无 Java 反射，不可用|
|||Packages java javax android org com io cn|按需懒加载的Java包代理，如`Packages.java.lang.String`|
|JavaAdapter|||用JS对象实现Java接口|

> 注意`java`变量指向已经被阅读修改，如果想要调用`java.*`下的包，请使用`Packages.java.*`

> 在书源规则中使用`@js` `<js>` `{{}}`可使用JavaScript调用阅读部分内置的类和方法

>
注意为了安全，阅读会屏蔽部分java类调用，见[JsSecurityPolicy](https://github.com/huajideshutiao/legado/blob/master/modules/quickjs/src/main/java/com/script/quickjs/JsSecurityPolicy.kt)；源开启`enableDangerousApi`后放行（慎用）

> 不同的书源规则中支持的调用的Java类和方法可能有所不同

> 规则JS每次执行都在独立子作用域中进行，支持顶层`return`；`let` `const`具备标准块级作用域，不会污染共享作用域

| 变量名            | 调用类                                                                                                                          |
|----------------|------------------------------------------------------------------------------------------------------------------------------|
| java           | 当前类                                                                                                                          |
| baseUrl        | 当前url,String                                                                                                                 |
| result         | 上一步的结果                                                                                                                       |
| book           | [书籍类](https://github.com/huajideshutiao/legado/blob/master/app/src/main/java/io/legado/app/data/entities/Book.kt)            |
| chapter        | [章节类](https://github.com/huajideshutiao/legado/blob/master/app/src/main/java/io/legado/app/data/entities/BookChapter.kt)     |
| source         | [基础书源类](https://github.com/huajideshutiao/legado/blob/master/app/src/main/java/io/legado/app/data/entities/BaseSource.kt)    |
| cookie         | [cookie操作类](https://github.com/huajideshutiao/legado/blob/master/app/src/main/java/io/legado/app/help/http/CookieStore.kt)   | 
| cache          | [缓存操作类](https://github.com/huajideshutiao/legado/blob/master/app/src/main/java/io/legado/app/help/CacheManager.kt)           |
| title          | 章节当前标题 String                                                                                                                |
| src            | 当前解析的源码（图片解密规则中为图片地址）                                                                                                        |
| nextChapterUrl | 下一章节url                                                                                                                      |
| platform       | 运行平台名 String，取值 `android`/`ios`/`ohos`/`jvm`，见 platform 变量章节                                                                  |
| image          | [图片解密操作类](https://github.com/huajideshutiao/legado/blob/master/app/src/main/java/io/legado/app/help/image/ImageOps.kt)，见 image 对象章节 |

> 部分场景会额外注入局部变量：搜索/发现/字典规则中的 `key`（关键字）与 `page`（页数）、
> httpTTS 规则中的 `speakText` `speakSpeed`（见网络朗读帮助）、段评规则中的 `paragraphIndex` `sort` `reviewId` `selected`

## 当前类对象的可使用的部分方法

### [RssJsExtensions](https://github.com/huajideshutiao/legado/blob/master/app/src/main/java/io/legado/app/ui/book/rss/RssJsExtensions.kt)
> 只能在书源正文规则的`shouldOverrideUrlLoading`规则中使用（订阅源已并入书源，本规则用于内置浏览器网页跳转拦截）  
> js返回true拦截本次跳转, js变量`url`为将要跳转的地址  
> url跳转拦截规则不能执行耗时操作
> 例子https://github.com/huajideshutiao/legado/discussions/3259

* 调用阅读搜索

```js
java.searchBook(key: String)
```

* 添加书架

```js
java.addBook(bookUrl: String)
```

### [AnalyzeUrl](https://github.com/huajideshutiao/legado/blob/master/app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeUrl.kt) 部分函数
> js中通过java.调用,只在`登录检查JS`规则中有效
```js
initUrl() //重新解析url,可以用于登录检测js登录后重新解析url重新访问
getHeaderMap().putAll(source.getHeaderMap(true)) //重新设置登录头
getStrResponse(jsStr: String? = null, sourceRegex: String? = null, allowWebView: Boolean = true) //返回访问结果,文本类型,书源内部重新登录后可调用此方法重新返回结果
getResponse(): Response //返回访问结果,网络朗读引擎采用的是这个,调用登录后在调用这方法可以重新访问,参考阿里云登录检测
```

### [AnalyzeRule](https://github.com/huajideshutiao/legado/blob/master/app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeRule.kt) 部分函数
* 获取文本/文本列表
> `mContent` 待解析源代码，默认为当前页面  
> `isUrl` 链接标识，默认为`false`
```js
java.getString(ruleStr: String?, mContent: Any? = null, isUrl: Boolean = false)
java.getStringList(ruleStr: String?, mContent: Any? = null, isUrl: Boolean = false)
```
* 设置解析内容

```js
java.setContent(content: Any?, baseUrl: String? = null):
```

* 获取Element/Element列表

> 如果要改变解析源代码，请先使用`java.setContent`

```js
java.getElement(ruleStr: String)
java.getElements(ruleStr: String)
```

* 重新获取目录url

> 本质为重新获取书籍详情

```js
java.refreshTocUrl()
```
* 变量存取

```js
java.get(key)
java.put(key, value)
```

### [js扩展类](https://github.com/huajideshutiao/legado/blob/master/shared/src/jvmAndAndroidMain/kotlin/io/legado/app/help/JsExtensionsJvm.kt) 部分函数

*
链接解析[JsURL](https://github.com/huajideshutiao/legado/blob/master/app/src/main/java/io/legado/app/utils/JsURL.kt)
```js
java.toURL(url): JsURL
java.toURL(url, baseUrl): JsURL
```
* 获取SystemWebView User-Agent
```js
java.getWebViewUA(): String
```
* 网络请求
```js
java.ajax(urlStr): String
java.ajaxAll(urlList: Array<String>): Array<StrResponse>
//返回StrResponse 方法body() code() message() headers() raw() toString() 
java.connect(urlStr): StrResponse
//header为json字符串
java.connect(urlStr, header: String?): StrResponse

java.post(url: String, body: String, headerMap: Map<String, String>): Connection.Response

java.get(url: String, headerMap: Map<String, String>): Connection.Response

java.head(url: String, headerMap: Map<String, String>): Connection.Response

* 使用webView访问网络
* @param html 直接用webView载入的html, 如果html为空直接访问url
* @param url html内如果有相对路径的资源不传入url访问不了
* @param js 用来取返回值的js语句, 没有就返回整个源代码
* @param delayTime 可选,页面加载完成后延时取结果,默认1000ms
* @return 返回js获取的内容
java.webView(html: String?, url: String?, js: String?): String?
java.webView(html: String?, url: String?, js: String?, delayTime: Long): String?

* 使用webView获取跳转url,可选参数delayTime同上
java.webViewGetOverrideUrl(html: String?, url: String?, js: String?, overrideUrlRegex: String): String?

* 使用webView获取资源url,可选参数delayTime同上
java.webViewGetSource(html: String?, url: String?, js: String?, sourceRegex: String): String?

* 使用内置浏览器打开链接，可用于获取验证码 手动验证网站防爬
* @param url 要打开的链接
* @param title 浏览器的标题
java.startBrowser(url: String, title: String)

* 使用内置浏览器打开链接，并等待网页结果 .body()获取网页内容
* @param refetchAfterSuccess 可省略,默认false;为true时验证成功后自动重新请求url并返回其结果
java.startBrowserAwait(url: String, title: String, refetchAfterSuccess: Boolean = false): StrResponse

```
* 调试
```js
java.log(msg)
java.logType(var)
```
* 通知 UI 刷新；target 大小写不敏感，可选: `login` / `explore` / `book`
  分别对应：登录界面 / 发现分类 / 书籍简介界面。
  调用时对应界面未活跃则无效果
```js
java.refreshUi("book")
```
* 读取cookie,tag一般传源URL;key省略时返回全部cookie
```js
java.getCookie(tag: String): String
java.getCookie(tag: String, key: String?): String
```
* 获取用户输入的验证码
```js
java.getVerificationCode(imageUrl)
```
* 弹窗提示
```js
java.longToast(msg: Any?)
java.toast(msg: Any?)
```
* 从网络(由java.cacheFile实现)、本地读取JavaScript文件，导入上下文请手动`eval(String(...))`
```js
java.importScript(url)
//相对路径支持android/data/{package}/cache
java.importScript(relativePath)
java.importScript(absolutePath)
```
* 缓存网络文件
```js
获取
java.cacheFile(url)
java.cacheFile(url,saveTime)
执行内容
eval(String(java.cacheFile(url)))
使缓存失效
cache.delete(java.md5Encode16(url))
```
* 获取网络压缩文件里面指定路径的数据 *可替换Zip Rar 7Z
```js
java.get*StringContent(url: String, path: String): String

java.get*StringContent(url: String, path: String, charsetName: String): String

java.get*ByteArrayContent(url: String, path: String): ByteArray?

```
* URI编码
```js
java.encodeURI(str: String) //默认enc="UTF-8"
java.encodeURI(str: String, enc: String)
```
* base64
> flags参数可省略，默认Base64.NO_WRAP，查看[flags参数说明](https://blog.csdn.net/zcmain/article/details/97051870)
```js
java.base64Decode(str: String)
java.base64Decode(str: String, charset: String)
java.base64Decode(str: String, flags: Int)
java.base64DecodeToByteArray(str: String)
java.base64DecodeToByteArray(str: String, flags: Int)
java.base64Encode(str: String)
java.base64Encode(str: String, flags: Int)
```
* ByteArray
```js
Str转Bytes
java.strToBytes(str: String)
java.strToBytes(str: String, charset: String)
Bytes转Str
java.bytesToStr(bytes: ByteArray)
java.bytesToStr(bytes: ByteArray, charset: String)
```
* Hex
```js
HexString 解码为字节数组
java.hexDecodeToByteArray(hex: String)
hexString 解码为utf8String
java.hexDecodeToString(hex: String)
utf8 编码为hexString
java.hexEncodeToString(utf8: String)
```
* 标识id
```js
java.randomUUID()
java.androidId()
```
* 繁简转换
```js
将文本转换为简体
java.t2s(text: String): String
将文本转换为繁体
java.s2t(text: String): String
```
* 时间格式化
```js
java.timeFormatUTC(time: Long, format: String, sh: Int): String?
java.timeFormat(time: Long): String
```
* html格式化
```js
java.htmlFormat(str: String): String
```
* 文件
>  所有对于文件的读写删操作都是相对路径,只能操作阅读缓存/android/data/{package}/cache/内的文件
```js
//文件下载 url用于生成文件名，返回文件路径
downloadFile(url: String): String
//文件解压,zipPath为压缩文件路径，返回解压路径
unArchiveFile(zipPath: String): String
unzipFile(zipPath: String): String
unrarFile(zipPath: String): String
un7zFile(zipPath: String): String
//文件夹内所有文件读取(读取后删除文件夹)
getTxtInFolder(unzipPath: String): String
//获取文件File对象
getFile(path: String): File
//读取文件,返回ByteArray
readFile(path: String): ByteArray?
//读取文本文件,不传charsetName时自动识别编码
readTxtFile(path: String): String
readTxtFile(path: String, charsetName: String): String
//删除文件
deleteFile(path: String): Boolean
```
* 字体解析,返回[字体解析类](https://github.com/huajideshutiao/legado/blob/master/app/src/main/java/io/legado/app/model/analyzeRule/QueryTTF.kt)
> `data`支持url、本地文件相对路径、base64、ByteArray，自动判断并自动缓存；`useCache`可省略默认true  
> `java.queryBase64TTF(data)`已过时，请改用`queryTTF`
```js
java.queryTTF(data: Any?): QueryTTF?
java.queryTTF(data: Any?, useCache: Boolean): QueryTTF?
//用正确字体的轮廓数据反查unicode替换错误字体的文字
//filter可省略默认false,为true时删除errorQueryTTF中不存在的字符
java.replaceFont(text: String, errorQueryTTF: QueryTTF?, correctQueryTTF: QueryTTF?): String
java.replaceFont(text: String, errorQueryTTF: QueryTTF?, correctQueryTTF: QueryTTF?, filter: Boolean): String
```
* 章节标题中文数字转阿拉伯数字
```js
java.toNumChapter(s: String?): String? //如 第一千零三章 -> 第1003章
```

### [js加解密类](https://github.com/huajideshutiao/legado/blob/master/app/src/main/java/io/legado/app/help/JsEncodeUtils.kt) 部分函数

> 提供在JavaScript环境中快捷调用crypto算法的函数，android/jvm 由[hutool-crypto](https://www.hutool.cn/docs/#/crypto/概述)实现  
> 由于兼容性问题，hutool-crypto当前版本为5.8.22  
> ios/ohos 为各平台原生等价实现，支持算法为 hutool 的子集，明细见下方"platform 变量"一节的能力差异表  

> 注意：如果输入的参数不是Utf8String 可先调用`java.hexDecodeToByteArray java.base64DecodeToByteArray`转成ByteArray
* 对称加密
> 输入参数key iv 支持ByteArray|**Utf8String**；iv可省略，key为null时使用随机密钥
```js
// 创建Cipher
java.createSymmetricCrypto(transformation, key, iv)
java.createSymmetricCrypto(transformation, key)
```
>解密加密参数 data支持ByteArray|Base64String|HexString|InputStream
```js
//解密为ByteArray String
cipher.decrypt(data)
cipher.decryptStr(data)
//加密为ByteArray Base64字符 HEX字符
cipher.encrypt(data)
cipher.encryptBase64(data)
cipher.encryptHex(data)
```
* 非对称加密
> 输入参数 key支持ByteArray|**Utf8String**
```js
//创建cipher
java.createAsymmetricCrypto(transformation)
//设置密钥
.setPublicKey(key)
.setPrivateKey(key)

```
> 解密加密参数 data支持ByteArray|Base64String|HexString|InputStream  
```js
//解密为ByteArray String
cipher.decrypt(data,  usePublicKey: Boolean? = true
)
cipher.decryptStr(data, usePublicKey: Boolean? = true
)
//加密为ByteArray Base64字符 HEX字符
cipher.encrypt(data,  usePublicKey: Boolean? = true
)
cipher.encryptBase64(data,  usePublicKey: Boolean? = true
)
cipher.encryptHex(data,  usePublicKey: Boolean? = true
)
```
* 签名
> 输入参数 key 支持ByteArray|**Utf8String**
```js
//创建Sign
java.createSign(algorithm)
//设置密钥
.setPublicKey(key)
.setPrivateKey(key)
```
> 签名参数 data支持ByteArray|inputStream|String
```js
//签名输出 ByteArray HexString
sign.sign(data)
sign.signHex(data)
```
* 摘要
```js
java.digestHex(data: String, algorithm: String): String

java.digestBase64Str(data: String, algorithm: String): String
```
* md5
```js
java.md5Encode(str)
java.md5Encode16(str)
```
* HMac
```js
java.HMacHex(data: String, algorithm: String, key: String): String

java.HMacBase64(data: String, algorithm: String, key: String): String
```

## book对象的可用属性
### 属性
> 使用方法: 在js中或{{}}中使用book.属性的方式即可获取.如在正文内容后加上 ##{{book.name+"正文卷"+title}} 可以净化 书名+正文卷+章节名称（如 我是大明星正文卷第二章我爸是豪门总裁） 这一类的字符.
```js
bookUrl // 详情页Url(本地书源存储完整文件路径)
tocUrl // 目录页Url (toc=table of Contents)
origin // 书源URL(默认BookType.local)
originName //书源名称 or 本地书籍文件名
name // 书籍名称(书源获取)
author // 作者名称(书源获取)
kind // 分类信息(书源获取)
customTag // 分类信息(用户修改)
coverUrl // 封面Url(书源获取)
customCoverUrl // 封面Url(用户修改)
intro // 简介内容(书源获取)
customIntro // 简介内容(用户修改)
charset // 自定义字符集名称(仅适用于本地书籍)
type // 类型,BookType二进制位标志(可组合): 8文本 16更新失败 32音频 64图片 128文件下载 256本地 512压缩包 1024未上架 2048视频 4096订阅
group // 自定义分组索引号
latestChapterTitle // 最新章节标题
latestChapterTime // 最新章节标题更新时间
lastCheckTime // 最近一次更新书籍信息的时间
lastCheckCount // 最近一次发现新章节的数量
totalChapterNum // 书籍目录总数
durChapterTitle // 当前章节名称
durChapterIndex // 当前章节索引
durChapterPos // 当前阅读的进度(首行字符的索引位置)
durChapterTime // 最近一次阅读书籍的时间(打开正文的时间)
wordCount // 字数
canUpdate // 刷新书架时更新书籍信息
order // 手动排序
originOrder //书源排序
variable // 自定义书籍变量信息(用于书源规则检索书籍信息)
syncTime // 进度同步时间
 ```

## chapter对象的部分可用属性
> 使用方法: 在js中或{{}}中使用chapter.属性的方式即可获取.如在正文内容后加上 ##{{chapter.title+chapter.index}} 可以净化 章节标题+序号(如 第二章 天仙下凡2) 这一类的字符.
 ```js
 url // 章节地址
 title // 章节标题
 isVolume // 是否是卷名
 bookUrl // 书籍地址
 index // 章节序号
 isVip // 是否VIP
 isPay // 是否已购买
 resourceUrl // 音频真实URL
 tag // 更新时间或其他章节附加信息
 wordCount // 本章节字数
 start // 章节起始位置
 end // 章节终止位置
 startFragmentId // EPUB书籍当前章节的fragmentId
 endFragmentId // EPUB书籍下一章节的fragmentId
 variable //变量
 ```
 
## source对象的部分可用函数
* 获取书源url/名称
```js
source.getKey() //源URL
source.getTag() //源名称
```
* 书源变量存取
```js
source.setVariable(variable: String?)
source.getVariable()
```

* 弹出对话框(需前台有界面, 无则忽略)
```js
source.showLoginDialog() //弹出登录对话框
source.showSourceVariableDialog() //弹出源变量对话框
```

* 登录头操作
```js
获取登录头
source.getLoginHeader()
获取登录头某一键值
source.getLoginHeaderMap().get(key: String)
保存登录头
source.putLoginHeader(header: String)
清除登录头
source.removeLoginHeader()
```
* 用户登录信息操作
> 使用`登录UI`规则，并成功登录，阅读自动加密保存登录UI规则中除type为button的信息
```js
login函数获取登录信息
source.getLoginInfo()
login函数获取登录信息键值
source.getLoginInfoMap().get(key: String)
保存登录信息(aes加密),成功返回true
source.putLoginInfo(info: String)
清除登录信息
source.removeLoginInfo()
```
## cookie对象的部分可用函数
```js
获取全部cookie
cookie.getCookie(url)
获取cookie某一键值
cookie.getKey(url,key)
设置cookie
cookie.setCookie(url,cookie)
替换cookie
cookie.replaceCookie(url,cookie)
删除cookie
cookie.removeCookie(url)
cookie字符串与Map互转
cookie.cookieToMap(cookie: String)
cookie.mapToCookie(cookieMap: Map<String, String>)
```

## cache对象的部分可用函数
> saveTime单位:秒，可省略  
> 保存至数据库和缓存文件(50M)，保存的内容较大时请使用`getFile putFile`
```js
保存
cache.put(key: String, value: Any, saveTime: Int)
读取数据库
cache.get(key: String): String?
按类型读取
cache.getInt(key) cache.getLong(key) cache.getDouble(key) cache.getFloat(key) cache.getByteArray(key)
删除
cache.delete(key: String)
缓存文件内容
cache.putFile(key: String, value: String, saveTime: Int)
读取文件内容
cache.getFile(key: String): String?
保存到内存
cache.putMemory(key: String, value: Any)
读取内存
cache.getFromMemory(key: String): Any?
删除内存
cache.deleteMemory(key: String)
```

## jsLib 共享函数库

> 源的`jsLib`字段用于存放多个规则共用的JS函数，支持两种格式：  
> `JavaScript Code` 直接填写JS片段  
> `{"example":"https://www.example.com/js/example.js", ...}` 按url下载js文件（自动缓存）后加载

> jsLib只编译并执行一次（QuickJS bytecode缓存），加载进该源的共享作用域；此后该源所有
> `@js:` `<js>` `{{}}`都在共享作用域之上的子作用域执行，可直接调用jsLib里定义的函数，
> 函数体内也能访问`java` `source` `cache`等变量（调用时注入）。
> 共用函数放进jsLib可避免每条规则重复解析编译。

> 注意：jsLib顶层代码只在作用域创建时执行一次，不会随每条规则重跑；不同线程各有独立的
> 共享作用域，顶层全局变量不跨线程共享，不要用它传递状态。修改源后作用域自动失效重建。

## platform 变量

> 所有 JS 作用域（书源规则、jsLib、登录 JS、替换规则等）都注入了 `platform` 变量，
> 值为运行平台名 String：`android`（当前）/`ios`/`ohos`/`jvm`。
> 各平台可用的 Java 类和方法不同，跨端书源请用它做差异判断，而不要探测类是否存在。

各平台能力差异（书源相关）：

| 能力                                        | android | ios/ohos      |
|-------------------------------------------|---------|---------------|
| crypto 系列（createSymmetricCrypto/Sign/digestHex 等） | 可用（hutool） | 大部分可用（mbedTLS 统一后端 + 平台回落，见下表） |
| java.* 反射调用 Android/JVM 类                  | 可用      | 不可用           |
| image 对象（图片解密）                             | 可用      | 可用（各平台原生实现）   |
| webView 系列（java.webView 等）                 | 可用      | 以各平台实现为准      |

> `java.*`/`Packages.*`/`importClass`/`JavaImporter`/`JavaAdapter` 反射在 ios/ohos 均不可用
> （native 端无 Java 反射，`Packages.java.xxx`/`importClass` 等 LiveConnect 写法恒失败），
> 跨端书源请改用 `java.*` 绑定（`java.encodeURI`/`java.randomUUID` 等，全平台可用）。
> 内置 TTS 条目已不依赖 Java 反射，ios/ohos 可用。
> 需要摘要/HMac/加解密的跨端书源请用内置封装方法
> `java.HMacBase64`/`java.HMacHex`/`java.digestHex`/`java.base64Encode`（见 crypto 小节，全平台可用）；
> `ruleHelp.md` 里 `ByteArrayInputStream`/`ByteArrayOutputStream` 图片解密示例同理仅 android/jvm 可用。

crypto 系列各算法明细（android 为 hutool/JCA 全量，ios/ohos 统一走 mbedTLS 后端，异常回落平台实现）：

| 算法                                          | ios | ohos |
|---------------------------------------------|-----|------|
| 摘要/HMac：MD5/SHA-1/SHA-224/SHA-256/SHA-384/SHA-512/RIPEMD160 | 可用  | 可用   |
| AES/ECB（NoPadding/PKCS5\|PKCS7Padding）       | 可用  | 可用   |
| AES/CBC、CFB、OFB、CTR（支持 iv；padding 另支持 ANSIX923/ISO10126/Zero） | 可用  | 可用   |
| AES/PCBC（mbedTLS 无此模式，iOS 走 krypto 回落）        | 可用  | 不可用  |
| AES/GCM/NoPadding（密文=cipher+tag16，须 setIv）    | 可用  | 可用   |
| DES/DESede（ECB/CBC）                          | 可用  | 可用   |
| RC4/SM2/SM3/SM4                             | 不可用 | 不可用  |
| RSA 非对称（encrypt/decrypt/decryptStr/encryptHex/encryptBase64；PKCS1 v1.5/OAEP；含私钥加密/公钥解密反向，反向仅 v1.5） | 可用  | 可用   |
| Sign 签名/验签（sign/signHex/verify；MD5/SHA-1/224/256/384/512withRSA、SHA*withRSA/PSS、NONEwithRSA、ECDSA） | 可用  | 可用   |

> 对称加密 key 为 null 时各端均使用随机密钥（AES 128 位/DES 8 字节/DESede 24 字节，对齐 hutool KeyUtil）；
> 不支持的算法/模式会抛出点名异常，便于书源降级判断。

示例——算法超出 ios/ohos 支持面时降级：

```js
if (platform == 'android' || platform == 'jvm') {
    // SM2/SM3/SM4/RC4 等仅 android/jvm (hutool) 提供
    result = java.createSymmetricCrypto('SM4/ECB/PKCS5Padding', key).decryptStr(result);
} else {
    // 其他平台走纯 JS 实现或提示不支持
    throw '本源的解密仅支持 android';
}
```

## image 对象（图片解密）

> 所有 JS 作用域都注入了 `image` 变量，提供跨端一致的图片解密 API，
> 用于漫画/封面的切块重排、裁剪等反爬解密场景（不是通用图片库）。
> `ImageRef` 是不透明句柄（android 下内持 Bitmap），split/stitch/crop 直接操作原生图，
> 不经过中间编解码，最后 `encode` 才输出字节；句柄随 JS 引用由 GC 回收。

```js
// 解码：图片字节、base64 字符串（可带 data:image/...;base64, 前缀）或输入流 -> 句柄
image.decode(bytes: ByteArray): ImageRef
image.decode(base64: String): ImageRef
image.decode(input: InputStream): ImageRef
// 编码：句柄 -> 图片字节。format 支持 png/jpg/webp，quality 0-100（png 无损，忽略）
image.encode(img: ImageRef, format: String, quality: Int): ByteArray
// 均分切块：行优先（先左到右再上到下），除不尽的余数并入最后一行/列
image.split(img: ImageRef, rows: Int, cols: Int): List<ImageRef>
// 按序拼接：direction 为 'h' 水平（左->右）或 'v' 垂直（上->下）
image.stitch(imgs: List<ImageRef>, direction: String): ImageRef
// 裁剪：以 (x,y) 为起点裁出 w×h，越界抛异常
image.crop(img: ImageRef, x: Int, y: Int, w: Int, h: Int): ImageRef
// 尺寸：返回 {w,h}，用 .w .h 取值
image.size(img: ImageRef)
```

示例——正文图片解密规则（图片切块重排）。服务端把图片纵向切成 4 块并打乱顺序，
规则里按真实顺序重拼后重新编码。`result` 为上一步结果，图片字节或输入流（如封面解密路径下
拿到的 InputStream）都可直接传给 `image.decode`；返回值需为 ByteArray：

```js
var img = image.decode(result);
var s = image.size(img);           // s.w 宽 s.h 高
var parts = image.split(img, 4, 1); // 切成 4 行 1 列
var order = [3, 2, 1, 0];          // 服务端打乱前的真实顺序
var restored = [];
for (var i = 0; i < order.length; i++) {
    restored.push(parts.get(order[i]));
}
image.encode(image.stitch(restored, 'v'), 'jpg', 90);
```

## 跳转外部链接/应用函数
> 调用时会先弹出确认对话框，用户同意后才跳转
```js
// 跳转外部链接，传入http链接或者scheme跳转到浏览器或其他应用
java.openUrl(url:String)
// 指定mimeType，可以跳转指定类型应用，例如（video/*）
java.openUrl(url:String,mimeType:String)
```
