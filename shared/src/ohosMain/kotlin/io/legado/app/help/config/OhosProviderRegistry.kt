package io.legado.app.help.config

import io.legado.app.api.controller.registerNativeBookControllerProviders
import io.legado.app.constant.registerNativeAndroidId
import io.legado.app.data.DatabaseDriverProviders
import io.legado.app.data.OhosDatabaseDriver
import io.legado.app.data.registerNativeAppDb
import io.legado.app.help.archive.registerNativeArchiveProvider
import io.legado.app.help.book.registerNativeBookHelpAccessor
import io.legado.app.help.book.registerNativeBookStorage
import io.legado.app.help.storage.registerNativeDataStorage
import io.legado.app.help.storage.registerOhosBackupRestoreHook
import io.legado.app.help.book.registerNativeBookImageStorage
import io.legado.app.help.book.registerNativeContentProcessorAccessor
import io.legado.app.help.book.registerNativeLocalBookLocator
import io.legado.app.help.file.registerOhosAppFilesDir
import io.legado.app.help.file.registerNativeFileDownloader
import io.legado.app.help.image.OhosBitmapProvider
import io.legado.app.help.http.registerDefaultOhosCookieStoreProvider
import io.legado.app.help.http.registerOhosBackstageWebView
import io.legado.app.help.http.registerOhosHttpProvider
import io.legado.app.help.http.registerSharedCookieJarBridge
import io.legado.app.help.notification.registerOhosNotificationProgress
import io.legado.app.help.registerNativeDefaultDataResourceProvider
import io.legado.app.help.registerNativeDirectLinkUploadProviders
import io.legado.app.help.registerNativeExploreKindsCacheProvider
import io.legado.app.help.registerNativeFileCacheProvider
import io.legado.app.help.registerNativeSourceCacheProvider
import io.legado.app.help.registerOhosMainThread
import io.legado.app.help.service.registerNativeUpdateBookCallback
import io.legado.app.help.service.registerOhosServiceLauncher
import io.legado.app.help.source.registerNativeSourceHelpAccessor
import io.legado.app.help.source.registerNativeSourceProviders
import io.legado.app.help.source.registerNativeVerificationUiProvider
import io.legado.app.help.toast.registerOhosToaster
import io.legado.app.help.tts.OhosHttpTtsPlayer
import io.legado.app.help.tts.TtsEngineProvider
import io.legado.app.help.tts.registerOhosSystemTtsEngine
import io.legado.app.help.ui.registerOhosOpenUrlProvider
import io.legado.app.help.ui.registerOhosUserAgentProvider
import io.legado.app.model.fileBook.BitmapProviders
import io.legado.app.model.fileBook.registerNativeFileBookAccessor
import io.legado.app.model.registerNativeCacheBookCallback
import io.legado.app.model.registerOhosAudioPlayCommanders
import io.legado.app.model.registerOhosReadBookPlatform
import io.legado.app.model.script.registerOhosJsEngines
import io.legado.app.model.webBook.registerNativeWebBookProviders
import io.legado.app.napi.registerOhosNativeBridge
import io.legado.app.ui.book.changesource.registerOhosChangeBookSourcePlatform
import io.legado.app.ui.book.manage.registerOhosBookshelfManagePlatform
import io.legado.app.utils.registerOhosScreenInfoProvider
import io.legado.app.ui.book.read.page.provider.registerOhosTextMeasurer
import io.legado.app.ui.compose.platform.OhosPreferenceStoreProvider
import io.legado.app.web.registerNativeWebServerPlatform
import io.legado.app.web.utils.registerNativeWebAssetSource
import io.legado.app.web.utils.registerNativeWebStrings

/**
 * 鸿蒙宿主启动早期的统一 provider 注册入口。
 *
 * 鸿蒙 app (ArkTS) 在 EntryAbility.onCreate 早期通过 napi 调用本函数,
 * 一次性完成所有 commonMain provider 的 stub 注入, 之后即可调用 shared 业务代码。
 *
 * 注册顺序约束 (与 desktop `Main.kt` 一致, 详见 [registerIosProviders] 对齐说明):
 * 1. [registerOhosAppFilesDir] 必须最先 (其他 provider 持久化目录依赖 [AppFilesDirs])
 * 2. [registerOhosPreferenceProvider] 在 [registerNativeAppConfigAccessor] 之前
 *    (OhosAppConfigAccessor 委托 PreferenceProvider)
 * 3. [registerNativeAppDb] (Database + AppDatabase + AppDb) 在文件目录之后
 *    (OhosDatabaseDriver 默认 dbPath 从 AppFilesDirs.filesDir 派生)
 * 4. [registerNativeBookStorage] + [registerNativeBookHelpAccessor] 在文件目录之后
 *    (OhosBookStorage rootPath 从 AppFilesDirs.filesDir 派生)
 * 5. [registerNativeSourceHelpAccessor] 在 AppDb 之后 (SourceHelp.deleteBookSource* 依赖 AppDb)
 * 5.5 [registerNativeSourceCacheProvider] 在 AppFilesDirs 之后、JS 引擎之前 (持久化目录从
 *    AppFilesDirs.filesDir 派生; JS eval 时 bindings["cache"] = SourceCacheProviders.impl?.asBinding(),
 *    未注册时 bindings["cache"] 为 null, JS 调用 cache.get/put 会失败被 runCatching 吞掉,
 *    表现为书源变量缓存失效)
 * 6. [registerOhosJsEngines] (JS 引擎 + OhosImageOps 真实像素操作) 在任何 JS eval / JsBindings 构造之前
 *    (JsBindings 构造时访问 JsBindingInjector.image, 未注册会 checkNotNull 失败;
 *     JsEngines.get() 未注册 provider 会抛 IllegalStateException)
 * 6.5 [BitmapProviders.register]([OhosBitmapProvider]) 在任何 CbzFile/EpubFile 封面提取调用之前
 *    (委托 OhosImageOps; 未注册时 BitmapProviders.get() 抛 IllegalStateException)
 * 7. [registerOhosSystemTtsEngine] (SystemTts 占位) 在 JsEngines 之后
 *    (与 desktop Main.kt 中 TtsEngineProvider.register 位置一致)
 * 8. 其余 provider ([registerNativeFileDownloader] / [registerOhosToaster] /
 *    [registerOhosNotificationProgress] / [registerOhosServiceLauncher]) 顺序无关
 * 8.5 [registerOhosNativeBridge] (napi 桥接基础设施) 必须在 [registerOhosToaster] /
 *    [registerOhosNotificationProgress] 之前 (当前为空操作占位, 真实 tsfn 由 EntryAbility 注入)
 *
 * 当前为 KP5 阶段, 各 provider 已落地真实实现 (Database / BookStorage / Preference / HTTP /
 * ImageOps / JsEngine / FileDownloader); SystemTtsEngine 为占位实现 (需 napi 桥接 @ohos.textToSpeech);
 * Toaster / NotificationProgress 为 KP7+ 已真实化 (走 [io.legado.app.napi.OhosNativeBridge] napi 桥接,
 *   未注入 tsfn 时降级 println; 真实 tsfn 由 EntryAbility.onCreate 调 legado.registerToastCallback /
 *   registerNotificationCallback 注入, 详见 docs/ohos-napi-bridge.md);
 * ServiceLauncher 仍为 stub (后续接入鸿蒙原生 API 后替换)。
 *
 * JS 引擎 provider: OhosJsEngine (基于 quickjs cinterop 编译 C 源码, 与 Android/Desktop/iOS 端
 * quickjs 引擎统一, KP6 替代原 JSVM-API dlopen/dlsym stub, 解除鸿蒙端 JS 引擎缺失阻塞)
 * + OhosImageOps (KP8+ 真实化: 基于 @ohos.multimedia.image PixelMap napi 桥接, decode/encode/
 *   split/stitch/crop/size 全部可用; 桥接未就绪时降级为字节持有, 不抛异常让 JS 调用链不崩)
 *
 * HTTP 层: 鸿蒙端基于 napi 桥接 @ohos.net.http 实现 KmpHttpClient/KmpHttpClientBuilder,
 * 通过 [registerOhosHttpProvider] 注册到 OkHttpClientProviders + OkHttpProxyClientProviders,
 * 与 desktop 端 OkHttp + OkHttpClientProviders 模式一致 (与 iOS 端 IosHttpProvider 同构)。
 *
 * 模式参考 Android 端 `App.onCreate` / iOS 端 `registerIosProviders` /
 * desktop 端 `Main.kt` 中的 provider 注册序列。
 */
fun registerOhosProviders() {
    // 0. 主线程 id 捕获 (任何 JS eval / webView 调用之前, EntryAbility.onCreate 在主线程执行本函数)
    registerOhosMainThread()

    // 0.5 屏幕尺寸 provider (sharedUiMain AppDialogSizes 兜底取 ScreenInfoProviders.get(),
    // 未注册时 error 导致所有对话框崩溃; 数据由 EntryAbility.onWindowStageCreate 经
    // legado.registerScreenSize 注入, 未注入时回退默认尺寸)
    registerOhosScreenInfoProvider()

    // 1. 文件系统目录 (其他 provider 持久化依赖)
    registerOhosAppFilesDir()

    // 2. 配置 provider (PreferenceProvider -> AppConfigAccessor)
    registerOhosPreferenceProvider()
    registerNativeAppConfigAccessor()

    // 2.1 设备标识注入 (鸿蒙无现成设备 id 桥, 用首启生成后经 PreferenceProviders 落盘的 UUID;
    // BaseSource 登录信息加密依赖 ≥16 字符, 默认 "null" 必越界)
    registerNativeAndroidId()

    // 2.2 备份密码 provider (读 PreferenceProviders "password", 与 app 端 LocalConfig.password 同 key)
    registerNativePasswordProvider()

    // 2.3 主题配置 provider (文件持久化 themeConfig.json, 与 app 端 ThemeConfig 同格式; 替换原内存版)
    ThemeConfigProviders.register(FileThemeConfigProvider())

    // 2.3.1 阅读配置 provider (readConfig.json / shareReadConfig.json, 供 BackupShared 备份/恢复)
    ReadBookConfigProviders.register(ReadBookConfigShared(OhosPreferenceStoreProvider()))

    // 2.4 默认数据 provider (composeResources files/defaultData, 供 DefaultDataShared 装载默认规则)
    registerNativeDefaultDataResourceProvider()

    // 2.4.5 直链上传配置 provider (Store 落 {filesDir}/directLinkUploadRule.json + Defaults 读默认数据,
    // 须在 AppFilesDirs + DefaultDataResourceProvider 之后; 供备份/恢复与直链上传配置用)
    registerNativeDirectLinkUploadProviders()

    // 2.5 HTTP provider (napi 桥接 @ohos.net.http, 注册到 OkHttpClientProviders + OkHttpProxyClientProviders)
    // 必须在数据库/书籍缓存之前: BookImageStorage/FileDownloader 取 OkHttpClient,
    // AnalyzeUrlCore 取 OkHttpProxyClient; 未注册时这些调用抛 IllegalStateException
    // (与 iOS 端 registerIosHttpProvider 位置对齐: 文件目录 → 配置 → HTTP provider → 数据库)
    registerOhosHttpProvider()

    // 3. 数据库 provider (DatabaseDriver + AppDatabase + AppDb, 依赖 AppFilesDirs)
    // 与 desktop Main.kt 中 `DatabaseDriverProviders.register + AppDatabaseProviders.register + AppDbProviders.register` 三步对齐
    // registerNativeAppDb 一次性注册 NativeAppDatabaseProvider + NativeAppDbAccessor (两端共用, 替代原 ohosMain 专属实现)
    val dbDriver = OhosDatabaseDriver()
    DatabaseDriverProviders.register(dbDriver)
    registerNativeAppDb(dbDriver)

    // 4. 书籍缓存 provider (BookStorage + BookHelp, 依赖 AppFilesDirs)
    // 与 desktop Main.kt 中 `BookStorageProviders.register + BookHelpProviders.register` 顺序对齐
    // DataStorage 须最先: BookStorage 的 rootPath 取自 chapterCacheDir
    registerNativeDataStorage()
    registerNativeBookStorage()
    registerNativeBookImageStorage()
    registerNativeLocalBookLocator()
    registerNativeBookHelpAccessor()

    // 4.5 ContentProcessor provider (复用 commonMain 的 ContentProcessorShared, 依赖 AppDb 已就绪)
    // 与 desktop registerDesktopWebBookProviders / iOS registerNativeContentProcessorAccessor 顺序对齐
    registerNativeContentProcessorAccessor()

    // 5. SourceHelp provider (依赖 AppDb, 顺序紧跟 AppDbAccessor 便于维护)
    // 与 desktop Main.kt 中 `SourceHelpAccessors.register(DesktopSourceHelpAccessor())` 顺序对齐
    registerNativeSourceHelpAccessor()

    // 5.5 缓存 provider (SourceCacheProvider, 依赖 AppFilesDirs, 必须在 JS 引擎之前)
    // 替代 app 端 CacheManager 的 cacheDao + LruCache 双层缓存 (鸿蒙端用 kotlin.io.File 文件持久化 +
    // in-memory HashMap 内存层); 让 BaseSource.getLoginHeader/setVariable 等持久层调用可用,
    // 以及 JS bindings["cache"] 绑定不为 null
    registerNativeSourceCacheProvider()
    registerNativeFileCacheProvider()
    // 发现规则缓存 (对应 app 端 ACache.get("explore"), 让 @js: 发现规则解析结果落盘复用)
    registerNativeExploreKindsCacheProvider()

    // 5.6 source 扩展 provider (SourceDebugLoggers/RuleBigData/help.UserAgent/SourceNetwork cookie 桥)
    // 依赖 AppFilesDirs + PreferenceProviders 已就绪 (cookie 桥运行期才取 CookieStoreProviders), 须在 JS eval 之前
    registerNativeSourceProviders()

    // 5.7 压缩包 provider (zip/cbz, NativeZipCodec + RemoteZipCore; rar/7z 明确抛异常)
    registerNativeArchiveProvider()

    // 6. JS 引擎 provider (OhosJsEngine + OhosImageOps + SharedJsScope + JsExtFactory), 必须在任何 JS eval 之前
    // (解除 KP4 P0 阻塞: 鸿蒙端 JS 引擎缺失导致书源规则解析全失效)
    registerOhosJsEngines()

    // 6.2 webBook 编排 provider (BookInfoRefresher/IntentData/RegexReplacer), 须在 JsEngines 之后
    // (NativeRegexReplacer 的 @js: 分支依赖已注册的 JsEngines)
    registerNativeWebBookProviders()

    // 6.3 后台 WebView (隐藏 Web 组件 napi 桥; 桥未就绪时抛明确失败信息让规则层 runCatching;
    // 须在任何 webView 规则解析之前)
    registerOhosBackstageWebView()

    // 6.5 BitmapProvider (CbzFile/EpubFile 封面提取用, 委托 OhosImageOps 的 PixelMap 解码/编码)
    // 必须在任何封面提取调用之前 (BitmapProviders 未注册时 get() 抛 IllegalStateException)
    BitmapProviders.register(OhosBitmapProvider)

    // 6.6 本地书 accessor (FileBookProviders: epub 走 nativeMain EpubFile, txt/pdf/cbz 明确抛异常)
    // 须在 BookStorage/LocalBookLocator/BitmapProviders 之后, 任何 FileBook 调用之前
    registerNativeFileBookAccessor()

    // 7. TTS 引擎 provider (OhosSystemTtsEngine 占位), 在 JsEngines 之后
    // (与 desktop Main.kt 中 `TtsEngineProvider.register(DesktopSystemTtsEngine())` 位置一致)
    registerOhosSystemTtsEngine()
    // 7b. HttpTTS 播放器工厂 (三端朗读 HttpTTS 路径, 鸿蒙 AVPlayer napi 桥接 actual)
    TtsEngineProvider.registerHttpTtsPlayerFactory { OhosHttpTtsPlayer() }

    // 8. 其余业务 provider (顺序无关)
    registerNativeFileDownloader()
    // 8.5 napi 桥接基础设施 (Toast/NotificationProgress 通过它调用 ArkTS 系统能力)
    // 必须在 registerOhosToaster / registerOhosNotificationProgress 之前 (当前为空操作占位,
    // 真实 tsfn 由 EntryAbility.onCreate 调 legado.registerToastCallback/registerNotificationCallback 注入)
    registerOhosNativeBridge()
    registerOhosToaster()
    registerOhosNotificationProgress()
    // 8.6 UpdateBook callback 须在 Toaster + NotificationProgress 之后 (本 callback 委托这两个 provider)、
    // ServiceLauncher 之前 (NativeServiceLauncher.updateBookShared lazy 构造时取 UpdateBookCallbacks.getDefault)
    // KP8+: 已下沉到 nativeMain (NativeUpdateBookCallback 真实化, 桥接 NotificationProgresses + Toasters)
    registerNativeUpdateBookCallback()
    // CacheBook callback 须在 ServiceLauncher 之前 (缓存流程未注册时 CacheBookCallbacks.get() 直接 error)
    registerNativeCacheBookCallback()
    registerOhosServiceLauncher()
    // 注册业务层 CookieStoreProvider (commonMain SharedCookieStore, Room cookieDao 持久化)
    // 与 desktop registerDefaultJvmCookieStoreProvider / app registerAndroidCookieStoreProvider
    // / iOS registerDefaultIosCookieStoreProvider 对齐
    registerDefaultOhosCookieStoreProvider()
    // 注册 CookieJarBridge (commonMain SharedCookieJarBridge, 1:1 复刻 app CookieManager)
    // 须在 CookieStoreProvider 之后 (bridge 通过 CookieStoreProviders.get() 间接访问存储)
    registerSharedCookieJarBridge()

    // 8.7 UI provider (Toast / OpenUrl / UserAgent, JsExtensionsCommon 在 JS eval 时回调)
    // 必须在任何 JS 执行之前 (JS eval 在本函数返回后由业务代码触发); stub 实现, 真实实现需 tsfn 桥接 ArkTS
    registerOhosOpenUrlProvider()
    registerOhosUserAgentProvider()
    // 源验证 UI provider (最小实现: 不支持路径明确报错+Toast, 纯打开链接走 OpenUrlProviders;
    // 未注册时 JS 验证入口裸抛 IllegalStateException)
    registerNativeVerificationUiProvider()
    // 音频播控 Commander (OhosAudioPlayCommander, 与 ServiceLauncher 同级的播放编排入口)
    registerOhosAudioPlayCommanders()
    // 换源平台 provider (commonMain ChangeBookSourceViewModelShared 调用, 须在 WebBookProviders 之后)
    registerOhosChangeBookSourcePlatform()
    // 书架管理平台 provider (commonMain BookshelfManageViewModelShared 调用, 须在 WebBookProviders 之后)
    registerOhosBookshelfManagePlatform()
    // 阅读编排平台钩子 (朗读/缓存服务运行态暂缺, 本地 txt 分章缓存清理真实;
    // 对照 iOS registerIosReadBookPlatform, 未注册时默认空实现行为一致)
    registerOhosReadBookPlatform()
    // 备份/恢复钩子 (lastBackup 时间戳 + 恢复完成提示; zip 复制/解压走 BackupFileOps 默认实现,
    // 对照 iOS registerIosBackupRestoreHook; 未注册时默认空实现静默丢这些副作用)
    registerOhosBackupRestoreHook()

    // 8.8 阅读排版真实字形度量器 (Skia Font 度量, 取代 SimpleTextMeasurer 等宽近似;
    // 须在任何章节排版之前, 依赖 skiko 随 compose ui 已就绪)
    registerOhosTextMeasurer()

    // 9. Web 服务 provider (WebAssetSource + WebStrings + WebServerPlatform, iOS/鸿蒙共用 Ktor server 壳)
    // 仅注册平台实现, 不启动服务 (WebServerManager.start 由用户操作触发)
    registerNativeWebAssetSource()
    registerNativeWebStrings()
    // BookController 图片/阅读状态 provider (/cover /image 直出缓存字节, /deleteBook /saveBookProgress
    // 经 NativeReadBookStateProvider 桥接阅读页挂接的 ReadBookShared)
    registerNativeBookControllerProviders()
    registerNativeWebServerPlatform()
}
