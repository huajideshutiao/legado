# 漫画/视频插件子系统（Mihon/Tachiyomi 扩展生态兼容）

本包宿主化 Mihon 扩展生态：识别/加载/信任扩展 APK（私有扩展形态，`filesDir/exts/<pkg>.ext`，免系统安装）、仓库增删与索引解析、安装/更新/卸载、备份恢复集成、源注册表。

## 兼容层配套

- 扩展按 `eu.kanade.tachiyomi.*` 原包名编译（compileOnly），运行时类由宿主提供：见 `app/src/main/java/eu/kanade/tachiyomi/README.md`。
- 数据映射与现有书籍管线对接：见 `app/src/main/java/io/legado/app/model/manga/`（漫画）、`model/anime/`（视频）。

## 加载契约（逐字对齐 Mihon main ExtensionLoader）

- 识别：包 feature `tachiyomi.extension`；元数据 `tachiyomi.extension.class`（分号分隔、`.`开头相对包名）、`.factory`、`.nsfw`、`tachiyomix.name`、`tachiyomix.extensionLib`、`tachiyomix.contentWarning`。
- lib 版本白名单：漫画 [1.4, 1.6]；Aniyomi 视频 [14, 17]。
- 类加载：API 28+ `DelegateLastClassLoader`，低版本 child-first 回退；单扩展失败隔离（NotLoaded.Failed）。
- 签名：纯 v2/v3 APK Signing Block（无 META-INF jar 签名块），必须走 API 28+ signingInfo 分支，SHA-256 hex。

## 已知缺口与关键决策

| 项 | 状态/说明 |
|---|---|
| KeiSource（lib 1.6）/ 扩展自带 core 不由宿主提供 | 已验证：打进扩展 APK 自带，宿主重复提供会遮蔽扩展副本 |
| SortState 上游不存在 | 实际类型 `Filter.Sort.Selection(index, ascending)` |
| injekt 用 null2264 fork | kohesive 原版 1.16.1 缺扩展所需包面 |
| keiyoushi 主仓库索引格式 | `index.min.json` 已是 "Outdated App" 占位；实际为 index.pb / 新版 index.json（`extensionList.extensions[]`：`packageName`/`resources.apkUrl`(GitHub Releases 直链)/`language`/contentWarning 字符串枚举）；yuzono 等社区仓库仍发老格式（`pkg`/`apk`/`lang`/int nsfw/float lib，`$baseUrl/apk/$apk` 拼接）。三种格式需自动识别 |
| org.jsoup 门面不全 | 见 `data/src/commonMain/kotlin/org/jsoup/README.md`：597 个扩展文件引用，门面补齐前仅 JSON-API 型扩展可用 |
| rxjava 1.3.8 / kotlinx-serialization-protobuf / zstd-kmp-okio 未引入 | 22 / 23 / 1 个扩展不可用，暂不处理 |
| shim 兜底待替换 | okhttp3/brotli、okhttp3/zstd、androidx.preference ×9、kotlinx/serialization/json/okio（文件头已注明），引真依赖后须删 |
| Cloudflare/WebView 挑战 | Android 已实现（插件栈+书源栈注入，同 host 公平锁去重，cf_clearance 经 CookieStore 回写）；desktop 书源栈由同套语义的 DesktopCloudflareInterceptor 接入（DesktopWebViewEngine）；iOS 第二阶段（WKWebView+cookie 桥已具备）；ohos 阻塞在 WebView napi 桥（ui/src/ohosMain/.../OhosWebViewStub.kt 既有方案） |
| desktop 插件支持 | 第二阶段（dex2jar 转 JAR + URLClassLoader + android.* 模拟层，Suwayomi-Server 先例；兼容层需从 app 模块迁 jvm 共享源集） |
| iOS/ohos 插件支持 | 机制性不可能（无 dex/ART/JVM 运行时且系统禁动态加载，dex2jar 转出也无处理处）；native 端扩展生态唯一替代路线是 JS 规则（legado 书源式） |

## 备份/恢复

扩展数据以 JSON 字符串偏好持久化（`ExtensionPrefs` 三个自持键：仓库列表、已信任签名、启用源状态），随 config.json 自动进出备份；恢复收尾钩子在 `help/storage/Backup.kt`（onRestoreFinished → MangaExtensionManager.onRestoreFinished）。私有扩展 APK 本体不进备份，恢复后按仓库列表重装。

## 已接入的生态与测试状态

- 漫画：keiyoushi（index.pb 主格式）；视频：yuzono/anime-repo（min.json）——同契约的其他仓库（Komikku/TachiyomiSY/Kohi-den 等）加 URL 即可。
- TVBox/FongMi 影视源（spider jar + 配置 json）：见 `help/tvbox/` 与 `model/tvbox/`，壳类面 FQCN 在 `com/github/catvod/`；实测 gaotianliuyun/gao 配置全链路可播。
- 测试记分板：JVM 单测 10/10；desktop 加载测试 2/2（dex2jar，`desktop/src/test/.../JvmExtensionLoaderTest`）；真机远程全链路（索引→下载→安装→加载→搜索/详情/目录/正文图片/TVBox 全链路）——TvBox 2/2、卸载 1/1 通过；漫画全链路与 iyf 视频链路受真机网络与第三方源自身行为影响（TLS 握手中断 / 扩展内 lazy NPE），测试样本均为远程运行时获取、不进仓库。

## 已知遗留（生态扩展）

- TVBox：Python(type 2) spider 未接；parse=1 网页嗅探已实现（Android WebView，桌面嗅探引擎属后续任务，直链与 json 解析不受影响）；本地代理 9978 已接（TvBoxLocalProxy，两端同链路，见 help/tvbox/README.md），部分站点 socks5 外发代理宿主不代跑；fastjson 未引入（旧 jar 会 NCDFE，CVE 风险）；旧壳 SpiderReq/SpiderUrl（依赖 rxhttp）；生产入口已接（Android App.onCreate / 桌面 DesktopCore 均注册平台钩子并调 TvBoxManager.init）。
- Kotatsu parsers（1000+ 源，maven 直依赖即可接入，社区 fork 活跃）：未启动。
- 真机链路：Comic Fury 全链路在部分网络下 TLS 被中断（环境因素）；iyf 搜索期扩展内 lazy NPE（需对照其源码定位）。
