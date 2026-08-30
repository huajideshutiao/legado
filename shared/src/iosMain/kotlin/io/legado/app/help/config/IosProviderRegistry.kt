package io.legado.app.help.config

import io.legado.app.api.controller.registerNativeBookControllerProviders
import io.legado.app.constant.registerNativeAndroidId
import io.legado.app.data.registerIosAppDbAccessor
import io.legado.app.data.registerIosDatabaseDriver
import io.legado.app.help.archive.registerNativeArchiveProvider
import io.legado.app.help.book.registerNativeBookHelpAccessor
import io.legado.app.help.book.registerNativeBookImageStorage
import io.legado.app.help.book.registerNativeBookStorage
import io.legado.app.help.storage.registerNativeDataStorage
import io.legado.app.help.book.registerNativeLocalBookLocator
import io.legado.app.help.book.registerNativeContentProcessorAccessor
import io.legado.app.help.file.registerIosAppFilesDir
import io.legado.app.help.file.registerNativeFileDownloader
import io.legado.app.help.http.registerIosBackstageWebView
import io.legado.app.help.registerNativeAppStringProvider
import io.legado.app.help.image.IosImageOps
import io.legado.app.help.image.NativeBitmapProvider
import io.legado.app.help.image.registerIosBookImageLoader
import io.legado.app.help.image.registerReaderImageResolver
import io.legado.app.help.log.registerNativeAppLogHost
import io.legado.app.help.http.registerDefaultIosCookieStoreProvider
import io.legado.app.help.http.registerNativeHttpProvider
import io.legado.app.help.http.registerSharedCookieJarBridge
import io.legado.app.help.media.registerIosMediaNotificationController
import io.legado.app.help.notification.registerIosNotificationProgress
import io.legado.app.help.registerNativeDefaultDataResourceProvider
import io.legado.app.help.registerNativeDirectLinkUploadProviders
import io.legado.app.help.registerNativeExploreKindsCacheProvider
import io.legado.app.help.registerNativeFileCacheProvider
import io.legado.app.help.registerNativeSourceCacheProvider
import io.legado.app.help.service.registerIosServiceLauncher
import io.legado.app.help.service.registerNativeUpdateBookCallback
import io.legado.app.help.source.registerNativeSourceHelpAccessor
import io.legado.app.help.source.registerNativeSourceProviders
import io.legado.app.help.source.registerNativeVerificationUiProvider
import io.legado.app.help.storage.registerNativeBackupRestoreHook
import io.legado.app.help.toast.registerIosToaster
import io.legado.app.help.tts.IosHttpTtsPlayer
import io.legado.app.help.tts.TtsEngineProvider
import io.legado.app.help.tts.registerIosSystemTtsEngine
import io.legado.app.help.ui.registerIosOpenUrlProvider
import io.legado.app.help.ui.registerNativeUserAgentProvider
import io.legado.app.model.fileBook.BitmapProviders
import io.legado.app.model.fileBook.registerNativeFileBookAccessor
import io.legado.app.model.registerIosAudioPlayCommanders
import io.legado.app.model.registerIosReadBookPlatform
import io.legado.app.model.registerNativeCacheBookCallback
import io.legado.app.model.script.registerNativeJsEngines
import io.legado.app.model.webBook.registerNativeWebBookProviders
import io.legado.app.ui.book.changesource.registerNativeChangeBookSourcePlatform
import io.legado.app.ui.book.manage.registerNativeBookshelfManagePlatform
import io.legado.app.ui.book.read.page.provider.registerNativeTextMeasurer
import io.legado.app.utils.registerIosScreenInfoProvider
import io.legado.app.web.registerNativeWebServerPlatform
import io.legado.app.web.utils.registerNativeWebAssetSource
import io.legado.app.web.utils.registerNativeWebStrings
import platform.UIKit.UIDevice
import platform.UIKit.UIScreen
import platform.UIKit.UITraitCollection
import platform.UIKit.UIUserInterfaceStyle

/**
 * iOS 宿主启动早期的统一 provider 注册入口。
 *
 * iOS app 在 didFinishLaunchingWithOptions 早期经 Kotlin/Native 桥接调用本函数,
 * 一次性完成所有 commonMain provider 注入。
 *
 * 注册顺序约束 (与 desktop Main.kt / registerOhosProviders 对齐):
 * 1. registerIosAppFilesDir 最先 (其他 provider 持久化目录依赖 AppFilesDirs)
 * 1.05 registerIosToaster 须在 registerNativeAppLogHost 之前 (AppLog 的 toast 出口走 Toasters,
 *    晚于 host 注册则初始化期 `AppLog.put(toast = true)` 的失败提示被 runCatching 吞掉;
 *    IosToaster 只依赖 UIKit (按钮文案经 syncGetString 查 composeResources), 不依赖被它跳过的任何 provider)
 * 2. registerIosPreferenceProvider 在 AppConfigAccessor 之前 (委托 PreferenceProvider)
 * 3. registerNativeHttpProvider 在数据库/书籍缓存之前
 * 4. registerIosDatabaseDriver / BookStorage / BookImageStorage / LocalBookLocator 在文件目录之后
 *    (路径从 AppFilesDirs 派生)
 * 5. AppDbAccessor / BookHelpAccessor / SourceHelpAccessor / SourceCacheProvider /
 *    FileCacheProvider 在数据库之后; SourceCache 未注册时 JS cache.get/put 失败被
 *    runCatching 吞掉 (书源变量缓存失效), FileCache 未注册时文件层抛 IllegalStateException
 * 6. registerNativeJsEngines 在任何 JS eval / JsBindings 构造之前 (未注册会 checkNotNull 失败)
 * 7. registerIosSystemTtsEngine 在 JsEngines 之后; UpdateBookCallback 须在 Toaster +
 *    NotificationProgress 之后、ServiceLauncher 之前
 *
 * 各 provider 均为真实实现: Preference (NSUserDefaults) / Database (Room KMP +
 * NativeSQLiteDriver) / HTTP (Ktor CIO 包装 KmpHttpClient) / 缓存 (NSFileManager) /
 * JS 引擎 (quickjs cinterop, 与 Android/Desktop 统一) + ImageOps (UIKit 真实像素) /
 * TTS (AVSpeechSynthesizer) / Toaster / NotificationProgress / ServiceLauncher
 * (NativeUpdateBookCallback 桥接 NotificationProgresses + Toasters, 与 desktop 同步)。
 */
fun registerIosProviders() {
    // 1. 文件系统目录 (其他 provider 持久化依赖)
    registerIosAppFilesDir()

    // 1.05 Toaster (须在 AppLog 宿主之前: AppLog.put(toast = true) 的 toast 出口走 Toasters,
    // 晚注册则初始化期的失败提示丢失; IosToaster 只依赖 UIKit, 拿不到 vc 时 NSLog 兜底)
    registerIosToaster()

    // 1.05.5 AppString provider (help/i18n appString 通道: model/help 层异常与翻页边界提示等
    // 同步文案; 未注册时 fallback 返回 key 名, 运行期可见为 "no_prev_page" 之类原始 key。
    // 零平台依赖顺序无关, 只须在任何 appString 调用之前)
    registerNativeAppStringProvider()

    // 1.1 AppLog 宿主 (日志落盘到 {filesDir}/logs, 供 CrashLogProvider 收集;
    // 须在 AppFilesDirs 之后 (日志目录从 filesDir 派生)、任何 AppLog.put 之前)
    registerNativeAppLogHost()

    // 2. 配置 provider (PreferenceProvider -> AppConfigAccessor)
    registerIosPreferenceProvider()
    registerNativeAppConfigAccessor()

    // 2.3 设备标识注入 (identifierForVendor, 取不到回退落盘 UUID; BaseSource 登录信息加密依赖 ≥16 字符)
    registerNativeAndroidId(UIDevice.currentDevice.identifierForVendor?.UUIDString)

    // 2.4 备份密码 provider (读 PreferenceProviders "password", 与 app 端 LocalConfig.password 同 key)
    registerNativePasswordProvider()

    // 2.5 主题配置 provider (文件持久化 themeConfig.json, 与 app 端 ThemeConfig 同格式; 替换原内存版)
    ThemeConfigProviders.register(FileThemeConfigProvider())

    // 2.5.1 阅读配置 provider (readConfig.json / shareReadConfig.json, 供 BackupShared 备份/恢复)
    ReadBookConfigProviders.register(ReadBookConfigShared(PreferenceProviders.get()))

    // 2.6 默认数据 provider (composeResources files/defaultData, 供 DefaultDataShared 装载默认规则)
    registerNativeDefaultDataResourceProvider()

    // 2.7 直链上传配置 provider (Store 落 {filesDir}/directLinkUploadRule.json + Defaults 读默认数据,
    // 须在 AppFilesDirs + DefaultDataResourceProvider 之后; 供备份/恢复与直链上传配置用)
    registerNativeDirectLinkUploadProviders()

    // 3. HTTP provider (Ktor CIO 包装, 注册到 OkHttpClientProviders + OkHttpProxyClientProviders)
    // 必须在数据库/书籍缓存之前: BookImageStorage/FileDownloader/IosBookCover 取 OkHttpClient,
    // AnalyzeUrlCore 取 OkHttpProxyClient; 未注册时这些调用抛 IllegalStateException
    registerNativeHttpProvider()
    // 注册业务层 CookieStoreProvider (commonMain SharedCookieStore, Room cookieDao 持久化)
    // 与 desktop registerDefaultJvmCookieStoreProvider / app registerAndroidCookieStoreProvider 对齐
    registerDefaultIosCookieStoreProvider()
    // 注册 CookieJarBridge (commonMain SharedCookieJarBridge, 1:1 复刻 app CookieManager)
    // 须在 CookieStoreProvider 之后 (bridge 通过 CookieStoreProviders.get() 间接访问存储)
    registerSharedCookieJarBridge()

    // 3.5 Coil3 图片加载 (BookImageLoaders + SingletonImageLoader, 对齐 app 端 App.onCreate 的
    // registerAndroidBookImageLoader + setSafe): 网络后端复用 NativeHttpProvider 的 Ktor client,
    // 磁盘缓存 {cacheDir}/image_cache; ImageLoader lazy 构建, 注册本身不触发网络栈/文件系统读取
    registerIosBookImageLoader()

    // 4. 数据库 provider (AppDatabaseProviders, 依赖 AppFilesDirs)
    registerIosDatabaseDriver()

    // 5. 书籍缓存 provider (BookStorage / BookImageStorage / LocalBookLocator, 依赖 AppFilesDirs)
    // DataStorage 须最先: BookStorage 的 rootPath 取自 chapterCacheDir
    registerNativeDataStorage()
    registerNativeBookStorage()
    registerNativeBookImageStorage()
    registerNativeLocalBookLocator()

    // 6. 数据访问 provider (依赖 AppDatabaseProviders / BookStorageProviders)
    // AppDbAccessor: 委托 AppDatabaseProviders 取全部 17 个 DAO (供 WebBook/SourceHelp 等编排层用)
    // BookHelpAccessor: 委托 BookStorageProviders.saveText 落盘章节正文 (供 BookContent 用)
    // ContentProcessorAccessor: 复用 commonMain 的 ContentProcessorShared 提供完整正文处理
    // (替换规则 / 简繁 / 段落重排 / 去重标题, 依赖 AppDbProviders 已就绪)
    // SourceHelpAccessor: nativeMain NativeSourceHelpAccessor (删源后 SourceConfig.removeSource +
    // AppCacheManager.clearSourceVariables; ReadBook/AudioPlay 内存缓存快捷路径无对应单例故返回 null,
    // 由调用方回退 bookSourceDao 查询)
    registerIosAppDbAccessor()
    registerNativeBookHelpAccessor()
    registerNativeContentProcessorAccessor()
    registerNativeSourceHelpAccessor()

    // 6.5 缓存 provider (SourceCacheProvider + FileCacheProvider, 依赖 AppFilesDirs, 必须在 JS 引擎之前)
    // - SourceCacheProvider: 替代 app 端 CacheManager 的 cacheDao + LruCache 双层缓存 (iOS 端用
    //   kotlin.io.File 持久化 + in-memory HashMap 内存层); 让 BaseSource.getLoginHeader/setVariable
    //   等持久层调用可用, 以及 JS bindings["cache"] 绑定不为 null
    // - FileCacheProvider: CacheManager 文件/二进制层 (getFile/putFile/getByteArray/put(ByteArray)/
    //   delete 文件部分) 委托 FileCacheProviders; 未注册时抛 IllegalStateException;
    //   亦经 @JsApi 暴露给 JS, 须在 JS 引擎之前注册
    registerNativeSourceCacheProvider()
    registerNativeFileCacheProvider()
    // 发现规则缓存 (对应 app 端 ACache.get("explore"), 让 @js: 发现规则解析结果落盘复用)
    registerNativeExploreKindsCacheProvider()

    // 6.6 source 扩展 provider (SourceDebugLoggers/RuleBigData/help.UserAgent/SourceNetwork cookie 桥)
    // 依赖 AppFilesDirs + PreferenceProviders + CookieStoreProviders 已就绪, 须在 JS eval 之前
    registerNativeSourceProviders()

    // 6.7 压缩包 provider (zip/cbz, NativeZipCodec + RemoteZipCore; rar/7z 明确抛异常)
    registerNativeArchiveProvider()

    // 7. JS 引擎 provider (IosJsEngine + IosImageOps + SharedJsScope + JsExtFactory), 必须在任何 JS eval 之前
    // (解除 KP3 P0 阻塞: iOS 端 JS 引擎缺失导致书源规则解析全失效)
    registerNativeJsEngines(IosImageOps)

    // 7.2 webBook 编排 provider (BookInfoRefresher/IntentData/RegexReplacer), 须在 JsEngines 之后
    // (NativeRegexReplacer 的 @js: 分支依赖已注册的 JsEngines)
    registerNativeWebBookProviders()

    // 7.3 后台 WebView (隐藏 WKWebView 异步桥; sourceRegex 资源嗅探无等价能力仍抛
    // UnsupportedOperationException 让调用方 runCatching 回退 HTTP; 须在任何 webView 规则解析之前)
    registerIosBackstageWebView()

    // 7.5 BitmapProvider (CbzFile/EpubFile 封面提取用, 委托 IosImageOps 的 UIImage 解码/编码)
    // 必须在任何封面提取调用之前 (BitmapProviders 未注册时 get() 抛 IllegalStateException)
    BitmapProviders.register(NativeBitmapProvider(IosImageOps))

    // 7.6 本地书 accessor (FileBookProviders: epub 走 nativeMain EpubFile, txt/pdf/cbz 明确抛异常)
    // 须在 BookStorage/LocalBookLocator/BitmapProviders 之后, 任何 FileBook 调用之前
    registerNativeFileBookAccessor()

    // 8. TTS 引擎 provider (AVSpeechSynthesizer), 供 ReadAloudControllerShared 用
    // (对齐 desktop Main.kt 中 TtsEngineProvider.register 在 registerDesktopJsEngines 之后)
    registerIosSystemTtsEngine()
    // 8b. HttpTTS 播放器工厂 (三端朗读 HttpTTS 路径, AVPlayer actual)
    TtsEngineProvider.registerHttpTtsPlayerFactory { IosHttpTtsPlayer() }

    // 9. 其余业务 provider (顺序无关)
    registerNativeFileDownloader()
    // 屏幕尺寸 provider (UIScreen.nativeBounds): sharedUiMain AppDialogSizes 在容器尺寸未知时
    // 取 ScreenInfoProviders.get() 兜底, 未注册会 error 导致所有对话框崩溃
    registerIosScreenInfoProvider()
    // 阅读编排平台钩子 (朗读桥接 IosReadAloudHost, 缓存服务运行态取 IosBackgroundTasks)
    registerIosReadBookPlatform()
    // 备份/恢复钩子 (lastBackup 时间戳 + 恢复完成提示; zip 复制/解压走 BackupFileOps 默认实现)
    registerNativeBackupRestoreHook()
    registerIosNotificationProgress()
    // UI provider (Toast/OpenUrl/UserAgent), 供 JsExtensionsCommon 调用, 顺序无关, 须在任何 JS eval 之前
    registerIosOpenUrlProvider()
    registerNativeUserAgentProvider()
    // 源验证 UI provider (最小实现: 不支持路径明确报错+Toast, 纯打开链接走 OpenUrlProviders;
    // 未注册时 JS 验证入口裸抛 IllegalStateException)
    registerNativeVerificationUiProvider()
    // UpdateBook callback 须在 Toaster + NotificationProgress 之后 (本 callback 委托这两个 provider)、
    // ServiceLauncher 之前 (NativeServiceLauncher.updateBookShared lazy 构造时取 UpdateBookCallbacks.getDefault)
    registerNativeUpdateBookCallback()
    // CacheBook callback 须在 ServiceLauncher 之前 (缓存流程未注册时 CacheBookCallbacks.get() 直接 error)
    registerNativeCacheBookCallback()
    registerIosServiceLauncher()
    // 音频播控 Commander (IosAudioPlayCommander, 与 ServiceLauncher 同级的播放编排入口)
    registerIosAudioPlayCommanders()
    // 系统媒体控制 (NowPlaying / RemoteCommandCenter / 锁屏控制卡片)
    registerIosMediaNotificationController()
    // 换源平台 provider (commonMain ChangeBookSourceViewModelShared 调用, 须在 WebBookProviders 之后)
    registerNativeChangeBookSourcePlatform()
    // 书架管理平台 provider (commonMain BookshelfManageViewModelShared 调用, 须在 WebBookProviders 之后)
    registerNativeBookshelfManagePlatform()

    // 9.5 阅读排版真实字形度量器 (Skia Font 度量, 取代 SimpleTextMeasurer 等宽近似;
    // 须在任何章节排版之前, 依赖 skiko 随 compose ui 已就绪)
    registerNativeTextMeasurer()

    // 9.6 阅读页内嵌图片解析器 (EPUB 插图/PDF 单图页: 排版取尺寸 + 绘制取位图,
    // 对照 Android MainActivity / desktop Main.kt; 未注册时 ImageResolverProviders.createOrNull
    // 返回 null, 排版静默跳过图片 —— 此前两端漏注册, 图片全部不显示)
    registerReaderImageResolver()

    // 10. Web 服务 provider (WebAssetSource + WebStrings + WebServerPlatform, iOS/鸿蒙共用 Ktor server 壳)
    // 仅注册平台实现, 不启动服务 (WebServerManager.start 由用户操作触发)
    registerNativeWebAssetSource()
    registerNativeWebStrings()
    // BookController 图片/阅读状态 provider (/cover /image 直出缓存字节, /deleteBook /saveBookProgress
    // 经 NativeReadBookStateProvider 桥接阅读页挂接的 ReadBookShared)
    registerNativeBookControllerProviders()
    registerNativeWebServerPlatform()
}

/**
 * iOS actual: 读 UIKit 的当前 trait。
 *
 * UITraitCollection 只保证主线程可读, 故只在启动早期与 Compose 侧 trait 变化回写时调用,
 * 业务侧一律读 [NativeSystemTheme.isNight] 内存缓存 (见 MainViewController 的 LocalSystemTheme 回写)。
 */
internal actual fun probeSystemNightMode(): Boolean? =
    runCatching {
        UIScreen.mainScreen.traitCollection.userInterfaceStyle ==
            UIUserInterfaceStyle.UIUserInterfaceStyleDark
    }.getOrNull()
