package io.legado.app.help.config

import io.legado.app.data.registerIosAppDbAccessor
import io.legado.app.data.registerIosDatabaseDriver
import io.legado.app.help.book.registerNativeBookHelpAccessor
import io.legado.app.help.book.registerNativeBookImageStorage
import io.legado.app.help.book.registerNativeBookStorage
import io.legado.app.help.book.registerNativeLocalBookLocator
import io.legado.app.help.book.registerNativeContentProcessorAccessor
import io.legado.app.help.file.registerIosAppFilesDir
import io.legado.app.help.file.registerNativeFileDownloader
import io.legado.app.help.http.registerDefaultIosCookieStoreProvider
import io.legado.app.help.http.registerIosHttpProvider
import io.legado.app.help.notification.registerIosNotificationProgress
import io.legado.app.help.registerNativeFileCacheProvider
import io.legado.app.help.registerNativeSourceCacheProvider
import io.legado.app.help.service.registerIosServiceLauncher
import io.legado.app.help.service.registerNativeUpdateBookCallback
import io.legado.app.help.source.registerNativeSourceHelpAccessor
import io.legado.app.help.toast.registerIosToaster
import io.legado.app.help.tts.registerIosSystemTtsEngine
import io.legado.app.model.script.registerIosJsEngines
import io.legado.app.web.registerNativeWebServerPlatform
import io.legado.app.web.utils.registerNativeWebAssetSource
import io.legado.app.web.utils.registerNativeWebStrings

/**
 * iOS 宿主启动早期的统一 provider 注册入口。
 *
 * iOS app (SwiftUI / UIKit) 在 application(_:didFinishLaunchingWithOptions:) 早期
 * 通过 Kotlin/Native 桥接调用本函数, 一次性完成所有 commonMain provider 的注入,
 * 之后即可调用 shared 业务代码。
 *
 * 注册顺序约束 (与 desktop `Main.kt` / [registerOhosProviders] 对齐):
 * 1. [registerIosAppFilesDir] 必须最先 (其他 provider 持久化目录依赖 [io.legado.app.help.file.AppFilesDirs])
 * 2. [registerIosPreferenceProvider] 在 [registerNativeAppConfigAccessor] 之前
 *    (IosAppConfigAccessor 委托 PreferenceProvider)
 * 3. [registerIosHttpProvider] 在数据库/书籍缓存之前 (BookImageStorage/FileDownloader/IosBookCover
 *    通过 OkHttpClientProviders 取 KmpHttpClient; AnalyzeUrlCore 通过 OkHttpProxyClientProviders 取代理客户端)
 * 4. [registerIosDatabaseDriver] 在文件目录之后
 *    (IosDatabaseDriver 默认 dbPath 从 AppFilesDirs.filesDir 派生)
 * 5. [registerNativeBookStorage] / [registerNativeBookImageStorage] / [registerNativeLocalBookLocator] 在文件目录之后
 *    (rootPath / cacheRootPath 从 AppFilesDirs.filesDir 派生)
 * 6. [registerIosAppDbAccessor] 在数据库之后 (本文件通过 `AppDatabaseProviders.get().appDb` 取 DAO)
 *    [registerNativeBookHelpAccessor] 在 BookStorage 之后 (本文件通过 `BookStorageProviders.get().saveText` 落盘)
 *    [registerNativeSourceHelpAccessor] 顺序紧跟 AppDbAccessor (与 desktop Main.kt 顺序一致)
 *    [registerNativeSourceCacheProvider] 在 AppFilesDirs 之后、JS 引擎之前 (持久化目录从
 *    AppFilesDirs.filesDir 派生; JS eval 时 bindings["cache"] = SourceCacheProviders.impl?.asBinding(),
 *    未注册时 bindings["cache"] 为 null, JS 调用 cache.get/put 会失败被 runCatching 吞掉,
 *    表现为书源变量缓存失效)
 *    [registerNativeFileCacheProvider] 紧随 SourceCacheProvider (CacheManager 文件/二进制层
 *    getFile/putFile/getByteArray/put(ByteArray)/delete 委托 FileCacheProviders; 持久化目录从
 *    AppFilesDirs.cacheDir 派生; 亦经 @JsApi 暴露给 JS, 未注册时文件层静默 no-op)
 * 7. [registerIosJsEngines] (JS 引擎 + ImageOps 降级占位) 在任何 JS eval / JsBindings 构造之前
 *    (JsBindings 构造时访问 JsBindingInjector.image, 未注册会 checkNotNull 失败;
 *     JsEngines.get() 未注册 provider 会抛 IllegalStateException)
 * 8. [registerIosSystemTtsEngine] (AVSpeechSynthesizer) 在 JsEngines 之后
 *    (与 desktop Main.kt 中 TtsEngineProvider.register 在 registerDesktopJsEngines 之后对齐)
 * 9. 其余 provider ([registerNativeFileDownloader] / [registerIosToaster] /
 *    [registerIosNotificationProgress]) 顺序无关; 但 [registerNativeUpdateBookCallback] 须在
 *    Toaster + NotificationProgress 之后 (委托这两个 provider)、[registerIosServiceLauncher]
 *    之前 (NativeServiceLauncher.updateBookShared lazy 构造时取 UpdateBookCallbacks.getDefault)
 *
 * # 当前实现状态 (KP4 真实化后)
 * - 真实持久化的 provider: IosPreferenceProvider (NSUserDefaults) /
 *   IosDatabaseDriver (Room KMP + BundledSQLiteDriver) / IosBookStorage (NSFileManager) /
 *   IosBookImageStorage (NSFileManager + Ktor) / IosLocalBookLocator (NSFileManager) /
 *   IosFileDownloader (NSFileManager + Ktor) / BackupFileOps (actual object, NSFileManager)
 * - HTTP provider: IosHttpProvider (Ktor CIO engine 包装 KmpHttpClient, 注册到
 *   OkHttpClientProviders + OkHttpProxyClientProviders), 解除 iOS 端 HTTP 层缺失阻塞
 *   (IosBookCover / AnalyzeUrlCore 等通过 provider 取客户端)
 * - 数据访问 provider: IosAppDbAccessor (委托 AppDatabaseProviders 取 9 个 DAO) /
 *   IosBookHelpAccessor (委托 BookStorageProviders.saveText 落盘) /
 *   IosSourceHelpAccessor (委托已下沉 commonMain 的 SourceConfig + AppCacheManager, 依赖 PreferenceProviders)
 * - 缓存 provider: IosSourceCacheProvider (基于 NSFileManager + AppFilesDirs.filesDir 的文件持久化,
 *   替代 app 端 CacheManager 的 cacheDao + LruCache 双层缓存; 内存层用 in-memory HashMap,
 *   对应 CacheManager.putMemory 语义; 让 BaseSource.getLoginHeader/setVariable 等持久层调用可用);
 *   IosFileCacheProvider (委托 nativeMain 共用的 NativeFileCacheProvider, 基于 kotlin.io.File +
 *   AppFilesDirs.cacheDir/file_cache/ 的文件持久化, 替代 app 端 ACache; TTL 头格式与 app/desktop 一致,
 *   让 CacheManager.getFile/putFile/getByteArray/put(ByteArray) 文件层可用)
 * - JS 引擎 provider: IosJsEngine (基于 quickjs-ng C 源码 cinterop 编译, 与 Android/Desktop 端 quickjs 统一,
 *   解除 iOS 端 JS 引擎缺失阻塞) + IosImageOps (KP4 已真实化: 基于 UIKit UIImage + UIGraphics 真实像素操作,
 *   decode/encode/split/stitch/crop/size 全部可用; 替代原 P0 降级占位)
 * - TTS provider: IosSystemTtsEngine (AVSpeechSynthesizer, 替代 desktop 命令行 TTS)
 * - 业务 provider (KP4 已真实化):
 *   IosToaster (dispatch_async 主线程 + UIAlertController present, NSLog 兜底; 替代原 P0 NSLog 降级);
 *   IosNotificationProgress (UNUserNotificationCenter 本地通知 + 权限请求, NSLog 兜底; 替代原 P0 NSLog 降级);
 *   IosServiceLauncher (kotlinx.coroutines 协程 + CacheBookShared 真实调度; UpdateBook 经
 *   NativeUpdateBookCallback (已下沉 nativeMain 共用) 桥接 NotificationProgresses + Toasters 真实化, 与 desktop DesktopServiceLauncher 同步)
 *
 * 模式参考 desktop `Main.kt` / [registerOhosProviders] / Android 端 `App.onCreate` 中的 provider 注册序列。
 */
fun registerIosProviders() {
    // 1. 文件系统目录 (其他 provider 持久化依赖)
    registerIosAppFilesDir()

    // 2. 配置 provider (PreferenceProvider -> AppConfigAccessor)
    registerIosPreferenceProvider()
    registerNativeAppConfigAccessor()

    // 3. HTTP provider (Ktor CIO 包装, 注册到 OkHttpClientProviders + OkHttpProxyClientProviders)
    // 必须在数据库/书籍缓存之前: BookImageStorage/FileDownloader/IosBookCover 取 OkHttpClient,
    // AnalyzeUrlCore 取 OkHttpProxyClient; 未注册时这些调用抛 IllegalStateException
    registerIosHttpProvider()
    // 注册业务层 CookieStoreProvider (真实实现: NSHTTPCookieStorage.sharedHTTPCookieStorage)
    // 与 desktop registerDefaultJvmCookieStoreProvider / app registerAndroidCookieStoreProvider 对齐
    registerDefaultIosCookieStoreProvider()

    // 4. 数据库 provider (DatabaseDriverProviders + AppDatabaseProviders, 依赖 AppFilesDirs)
    registerIosDatabaseDriver()

    // 5. 书籍缓存 provider (BookStorage / BookImageStorage / LocalBookLocator, 依赖 AppFilesDirs)
    registerNativeBookStorage()
    registerNativeBookImageStorage()
    registerNativeLocalBookLocator()

    // 6. 数据访问 provider (依赖 AppDatabaseProviders / BookStorageProviders)
    // AppDbAccessor: 委托 AppDatabaseProviders 取 9 个 DAO (供 WebBook/SourceHelp 等编排层用)
    // BookHelpAccessor: 委托 BookStorageProviders.saveText 落盘章节正文 (供 BookContent 用)
    // ContentProcessorAccessor: 复用 commonMain 的 ContentProcessorShared 提供完整正文处理
    // (替换规则 / 简繁 / 段落重排 / 去重标题, 依赖 AppDbProviders 已就绪)
    // SourceHelpAccessor: 空占位 (供 SourceHelp.saveSource/deleteBookSource 用, 避免 IllegalStateException)
    registerIosAppDbAccessor()
    registerNativeBookHelpAccessor()
    registerNativeContentProcessorAccessor()
    registerNativeSourceHelpAccessor()

    // 6.5 缓存 provider (SourceCacheProvider + FileCacheProvider, 依赖 AppFilesDirs, 必须在 JS 引擎之前)
    // - SourceCacheProvider: 替代 app 端 CacheManager 的 cacheDao + LruCache 双层缓存 (iOS 端用
    //   kotlin.io.File 持久化 + in-memory HashMap 内存层); 让 BaseSource.getLoginHeader/setVariable
    //   等持久层调用可用, 以及 JS bindings["cache"] 绑定不为 null
    // - FileCacheProvider: CacheManager 文件/二进制层 (getFile/putFile/getByteArray/put(ByteArray)/
    //   delete 文件部分) 委托 FileCacheProviders; 未注册时静默 no-op (getFile 永远 null,
    //   put(ByteArray) 丢弃); 亦经 @JsApi 暴露给 JS, 须在 JS 引擎之前注册
    registerNativeSourceCacheProvider()
    registerNativeFileCacheProvider()

    // 7. JS 引擎 provider (IosJsEngine + IosImageOps), 必须在任何 JS eval 之前
    // (解除 KP3 P0 阻塞: iOS 端 JS 引擎缺失导致书源规则解析全失效)
    registerIosJsEngines()

    // 8. TTS 引擎 provider (AVSpeechSynthesizer), 供 ReadAloudControllerShared 用
    // (对齐 desktop Main.kt 中 TtsEngineProvider.register 在 registerDesktopJsEngines 之后)
    registerIosSystemTtsEngine()

    // 9. 其余业务 provider (顺序无关)
    registerNativeFileDownloader()
    registerIosToaster()
    registerIosNotificationProgress()
    // UpdateBook callback 须在 Toaster + NotificationProgress 之后 (本 callback 委托这两个 provider)、
    // ServiceLauncher 之前 (NativeServiceLauncher.updateBookShared lazy 构造时取 UpdateBookCallbacks.getDefault)
    registerNativeUpdateBookCallback()
    registerIosServiceLauncher()

    // 10. Web 服务 provider (WebAssetSource + WebStrings + WebServerPlatform, iOS/鸿蒙共用 Ktor server 壳)
    // 仅注册平台实现, 不启动服务 (WebServerManager.start 由用户操作触发)
    registerNativeWebAssetSource()
    registerNativeWebStrings()
    registerNativeWebServerPlatform()
}
