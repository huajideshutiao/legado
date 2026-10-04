# TVBox / FongMi 影视源接入（宿主侧）

壳类面 FQCN 在 `app/src/main/java/com/github/catvod/`（`Spider` / `OkHttp` / `Proxy` / `Init` / `bean` / `utils`），
便于社区 spider jar 以原 FQCN 直接调宿主。

- 配置/装载/站点面：`app/src/main/java/io/legado/app/help/tvbox/`
  - `TvBoxConfig.kt` — 配置 json（`sites[]` / `spider` / `parses[]`）解析，相对路径按 `baseUrl` 折算
  - `TvBoxJarLoader.kt` — dex/jar spider 的类装载与缓存
  - `TvBoxCmsSpider.kt` — 苹果 CMS 直连站（api 为 http 根 URL，无 jar）的宿主实现
  - `TvBoxSniffer.kt` — **parse=1 网页嗅探**（见下）
  - `TvBoxJsBridge.kt` / `TvBoxJsSpiderLoader.kt` — JS spider（同包并行开发的兄弟模块）
- 取数委派：`app/src/main/java/io/legado/app/model/tvbox/`
  - `TvBoxSourceDelegateImpl` — 搜索/详情/目录/取播；取播失败到兜底时调 `TvBoxSniffer`
  - `TvBoxManager` — 配置拉取、虚拟书源行同步、委派挂载
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

## 未实现：本地 HTTP 代理 9978 与 proxy 转发

**理由一句话**：TVBox/FongMi 那层本地代理是为「把已嗅到的地址暴露成一个 URL 交给**外部**播放器自己去拉流」
存在的；本仓库嗅探在进程内完成，真实 m3u8/mp4 直接交给宿主自己的 ExoPlayer 管线，中间没有跨进程播放器，
因此没有中转需求 —— 起一个 9978 只会凭空多一段环路和多一套攻击面。

具体不搬运的组件（均属上述中转形态）：

- 本地 HTTP 服务（FongMi `app/.../server/Server` 起 NanoHTTPD 占 9978；`com.github.catvod.Proxy`
  作地址提供）
- 静态 iframe 解析页服务（FongMi `server/process/Parse` 渲染 `app/src/main/assets/parse.html`；
  原版 `q215613905/TVBoxOS` `util/parser/SuperParse.loadHtml` 同款 HTML + `proxy://go=SuperParse&...`）
- `Spider.proxy(Map)` 的宿主转发服务（FongMi `server/process/Proxy` → `BaseLoader.get().proxy(...)`）

连带的两点取舍：

- `com.github.catvod.Proxy` 壳类保留，但 `getPort()` 恒为 -1：那是给 jar 内代码看的既有签名，
  保持存在比让 jar 因 `NoClassDefFoundError` 崩掉好，语义上它就是「宿主不提供代理」。
- 站点 `ext` 里的代理串（如 `socks5 127.0.0.1:10172`）按生态语义**原样透传给 spider**：那是
  spider 自己的私有配置，由它自己决定怎么用；宿主不代跑代理、不解释它。（因此个别站点
  —— 实测 Gaoqing / ddys / wo4k —— 取不到数据时，缺口在它们的 spider 依赖宿主代理服务，
  补齐就得上面的中转层，代价/收益不成立。）

## 未实现：JS / Python spider

配置里 `type` 字段在生态中只标识数据格式（0=xml CMS、1=json CMS、3=spider），**不决定引擎**；
引擎按 `api` 形态选：`csp_*`=jar spider、含 `.js`=JS spider、`http`=CMS 直连（FongMi `BaseLoader.getSpider` 同语义）。

JS spider 由同包兄弟模块 `TvBoxJsBridge` / `TvBoxJsSpiderLoader` 负责；Python spider 未启：

- **理由一句话**：JS spider 需要的是 TVBox 那套自己封装的 `js` API 面（`spider.js` 里的大量同步
  HTTP / 加解密 / DOM 行为），与宿主已有的 quickjs 桥不是同一件事，等于另起一套 API 面；
  Python 则还需要再加一个解释器运行时，收益远不如直接复用宿主既有能力。
- **替代路径**：这类站点用 legado 自己的**视频书源 JS 规则**表达即可 —— 同样是 JS，且直接跑在
  宿主的规则引擎上，不需要移植 TVBox 的 JS API 面，也不用为 Python 另引一套运行时。

## 已知遗留

- `parses[]` 里 type≠0（json / Json 扩展 / 聚合）的解析形态未实现：它们走的是「HTTP 请求一个 json 接口」
  而非「给 WebView 一个 URL」，属另一个面；type=0 之外的项在 `TvBoxParse.fromJson` 里照常解析出来，
  由 `pickWebSniff` 过滤掉。
- 嗅探依赖系统 WebView 可用性（对齐 `io.legado.app.help.http.WebViewUtil.supportsWebView` 的同类前提）；
  CF 挑战需要人工交互的页面无法静默嗅出，会超时失败。
- 宿主侧改动集中在 `help/tvbox/` 与 `model/tvbox/`，生产入口由 `TvBoxManager.init()` 幂等挂载。
