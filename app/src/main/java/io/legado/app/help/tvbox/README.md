# TVBox / FongMi 影视源接入（宿主侧）

壳类面 FQCN 在 `data/src/jvmAndAndroidMain/kotlin/com/github/catvod/`（`Spider` / `OkHttp` / `Proxy` / `Init` / `bean` / `utils`），
便于社区 spider jar 以原 FQCN 直接调宿主。

- 配置/装载/站点面：`data/src/jvmAndAndroidMain/kotlin/io/legado/app/help/tvbox/`
  - `TvBoxConfig.kt` — 配置 json（`sites[]` / `spider` / `parses[]`）解析，相对路径按 `baseUrl` 折算
  - `TvBoxJarLoader.kt` — dex/jar spider 的类装载与缓存
  - `TvBoxCmsSpider.kt` — 苹果 CMS 直连站（api 为 http 根 URL，无 jar）的宿主实现
  - `TvBoxLocalProxy.kt` — 本地代理 9978（见下节）
  - `TvBoxPlatform.kt` — 平台差异（上下文/assets/类加载器）注入契约
  - `TvBoxSniffer.kt` — **parse=1 网页嗅探**（见下）
  - `TvBoxJsBridge.kt` / `TvBoxJsSpiderLoader.kt` — JS spider（同包并行开发的兄弟模块）
- 取数委派：`data/src/jvmAndAndroidMain/kotlin/io/legado/app/model/tvbox/`
  - `TvBoxSourceDelegateImpl` — 搜索/详情/目录/取播；取播失败到兜底时调 `TvBoxSniffer`
  - `TvBoxManager` — 配置拉取、虚拟书源行同步、委派挂载、本地代理起停
- 真机测试：`app/src/androidTest/java/io/legado/app/help/tvbox/`

## parse=1 网页嗅探（已实现）

TVBox 站点的 `playerContent` 可能回的不是视频直链，而是**播放页**：`parse`(1) 或 `jx`(1) 标记，
或干脆给一个 `.html` / 解析站形态（`?url=http`）的地址。这类内容要先加载页面、拦下它的网络请求、
从中找出真实 m3u8/mp4，才能播 —— 生态里叫「普通嗅探」（原版 `bean/ParseBean.type` 注释：0 普通嗅探）。

查证来源：

| 事实 | 源码路径 |
|---|---|
| `parse=1` / `jx=1` 触发解析 | FongMi `app/.../bean/Result.needParse()` / `isUseParse()` |
| type=0 分支走 WebView 嗅探 | FongMi `app/.../player/parse/ParseJob.doInBackground()` → `startWeb` |
| 嗅探实现：遍历子资源请求 | FongMi `app/.../ui/custom/CustomWebView.webViewClient()` → `shouldInterceptRequest` |
| 视频地址判据 | FongMi `app/.../utils/Sniffer.isVideoFormat()` + `SNIFFER` 正则 |
| iframe 内嵌播放器要再钻一层 | FongMi `CustomWebView.PLAYER`（`player.*https?://`）→ `onParseAdd` |
| 解析站清单 `parses[]` 的形态 | 原版 `q215613905/TVBoxOS` `app/.../bean/ParseBean`（`type`：0 普通嗅探 / 1 json / 2 Json 扩展 / 3 聚合） |

本仓库的实现（`TvBoxSniffer.kt`）路径一致：headless WebView + `shouldInterceptRequest`，
命中 `TvBoxVideoPredicate.Sniffer`（与 `Sniffer.SNIFFER` 同正则）； spider 声明 `manualVideoCheck()`
时改用 spider 自己的 `isVideoFormat()`（对应 FongMi `CustomWebView.isVideoFormat()` 的 spider 委托分支）。

结果以现有 `url,{"headers":{...}}` 内容串语法回灌播放管线（播放器侧 `AnalyzeUrlCore` 拆出并入 headerMap）。
取播委派 `TvBoxSourceDelegateImpl.getContentAwait` 的顺序是：**先收直连线路**（无 WebView 开销，
多线路仍按 `线路名::内容` 拼行），全线路都拿不到直链时才逐条走嗅探（最多 2 条），首个成功即用。

## 本地 HTTP 代理 9978 与 proxy 转发（已实现）

`TvBoxLocalProxy`（同包）以 NanoHTTPD 壳监听 `/proxy`，端口自 9978 逐个尝试至 9998
（FongMi `Server.start` 同语义），成功后回填 `com.github.catvod.Proxy` 与 `TvBoxJsProxy`
两侧端口接线 —— 此后 jar/JS spider 构造的 `http://127.0.0.1:{port}/proxy?do=…` 才可达。

- 起停由 `TvBoxManager` 随配置装载自动驱动：init 重载已有配置、setConfig 导入新配置时
  `start`（幂等），clear 时 `stop`；分发语义对齐 FongMi `BaseLoader.proxy`：带 siteKey
  按站点 key 找 Spider 实例，否则交 jar 自带 `com.github.catvod.spider.Proxy.proxy(Map)`
  静态方法（do 值由 jar 自己定义，如 B 站的 "bili"）。
- Android 与桌面同一链路：桌面端 `DesktopTvBoxHostPlatform` 以 URLClassLoader（父加载器为
  应用类加载器）直载 jar，壳类由宿主 classpath 提供（parent-first），`Proxy.set` 回填的
  端口对 jar 侧直接可见。

不需要的组件（对应能力已改为进程内实现，不经 9978）：

- 静态 iframe 解析页服务（FongMi `server/process/Parse` 渲染 `app/src/main/assets/parse.html`；
  原版 `q215613905/TVBoxOS` `util/parser/SuperParse.loadHtml` 同款 HTML + `proxy://go=SuperParse&...`）
  —— type=1 json API 由 `TvBoxParse` 进程内直取，type=0 嗅探由 `TvBoxSniffer` 进程内完成。

连带取舍：

- 站点 `ext` 里的代理串（如 `socks5 127.0.0.1:10172`）按生态语义**原样透传给 spider**：那是
  spider 自己的私有配置，由它自己决定怎么用；宿主不代跑代理、不解释它。个别站点因此取不到
  数据时，缺口在 spider 侧的外发代理，与 9978 链路无关。

## 未实现：JS / Python spider

配置里 `type` 字段在生态中只标识数据格式（0=xml CMS、1=json CMS、3=spider），**不决定引擎**；
引擎按 `api` 形态选：`csp_*`=jar spider、含 `.js`=JS spider、`http`=CMS 直连（FongMi `BaseLoader.getSpider` 同语义）。

JS spider 由同包兄弟模块 `TvBoxJsBridge` / `TvBoxJsSpiderLoader` 负责；Python spider 未启：

- **理由一句话**：JS spider 需要的是 TVBox 那套自己封装的 `js` API 面（`spider.js` 里的大量同步
  HTTP / 加解密 / DOM 行为），与宿主已有的 quickjs 桥不是同一件事，等于另起一套 API 面；
  Python 则还需要再加一个解释器运行时，收益远不如直接复用宿主既有能力。
- **替代路径**：这类站点用 legado 自己的**视频书源 JS 规则**表达即可 —— 同样是 JS，且直接跑在
  宿主的规则引擎上，不需要移植 TVBox 的 JS API 面，也不用为 Python 另引一套运行时。

## 解析站 type≠0 形态（已实现）

`parses[]` 的 `type` 语义（查证自 FongMi `ParseJob.doInBackground` + 原版 `ParseBean` 注释）：

- **type=0 普通嗅探**：WebView 加载 `url + 播放页` 嗅出真实地址（见上节）。
- **type=1 json API**：HTTP 请求 `url + 播放页`，按返回 JSON 结构提取直链（根 `url` 或 `data.url`），
  响应里的 UA/Referer/Cookie 作为防盗链头一并带回；有效性判据对齐 FongMi `checkResult`（url 过短视为无直链）。
- **type=2 Json 扩展 / type=3 聚合**：收集全体解析项（`name→extUrl()` / `name→{type,ext,url}`），
  反射调用站点 jar 内 `com.github.catvod.parser.Json{url}` / `Mix{url}` 静态 `parse(...)`（
  FongMi `JarLoader.jsonExt/jsonExtMix` 同语义）；结果若仍标 `parse/jx=1` 则下钻 WebView 嗅探。
  **JS spider 站点无 jar 侧解析类，该形态如实失败**（FongMi 同样走 jarLoader）。

取播委派 `TvBoxSourceDelegateImpl.sniffContent` 按 type 依次尝试（json API 快 → 网页嗅探 → 聚合），首个成功即用。

## 已知遗留

- 嗅探依赖系统 WebView 可用性（对齐 `io.legado.app.help.http.WebViewUtil.supportsWebView` 的同类前提）；
  CF 挑战需要人工交互的页面无法静默嗅出，会超时失败。
- type=2/3 聚合依赖站点 jar 内 `com.github.catvod.parser.*` 解析类：只有 JAR spider 站点且其 jar 携带
  该类时可用；JS/Python/CMS 站点不适用（如实报错）。
- 宿主侧改动集中在 `help/tvbox/` 与 `model/tvbox/`，生产入口由 `TvBoxManager.init()` 幂等挂载。
