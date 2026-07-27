package io.legado.app.help.config

import io.legado.app.data.DatabaseDriverProviders
import io.legado.app.data.OhosDatabaseDriver
import io.legado.app.data.registerNativeAppDb
import io.legado.app.help.book.registerNativeBookHelpAccessor
import io.legado.app.help.book.registerNativeBookStorage
import io.legado.app.help.book.registerNativeBookImageStorage
import io.legado.app.help.book.registerNativeContentProcessorAccessor
import io.legado.app.help.book.registerNativeLocalBookLocator
import io.legado.app.help.file.registerOhosAppFilesDir
import io.legado.app.help.file.registerNativeFileDownloader
import io.legado.app.help.http.registerDefaultOhosCookieStoreProvider
import io.legado.app.help.notification.registerOhosNotificationProgress
import io.legado.app.help.registerNativeFileCacheProvider
import io.legado.app.help.registerNativeSourceCacheProvider
import io.legado.app.help.service.registerNativeUpdateBookCallback
import io.legado.app.help.service.registerOhosServiceLauncher
import io.legado.app.help.source.registerNativeSourceHelpAccessor
import io.legado.app.help.toast.registerOhosToaster
import io.legado.app.help.tts.registerOhosSystemTtsEngine
import io.legado.app.model.script.registerOhosJsEngines
import io.legado.app.napi.registerOhosNativeBridge
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
 * 6. [registerOhosJsEngines] (JS 引擎 + ImageOps 降级占位) 在任何 JS eval / JsBindings 构造之前
 *    (JsBindings 构造时访问 JsBindingInjector.image, 未注册会 checkNotNull 失败;
 *     JsEngines.get() 未注册 provider 会抛 IllegalStateException)
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
 * + OhosImageOps (KP5 降级占位, 不抛异常让 JS 调用链不崩, 真实像素操作待 KP6+ 用
 *   @ohos.multimedia.image napi 桥接实现)
 *
 * HTTP 层: 鸿蒙端基于 Ktor HttpClient (CIO engine, 纯 Kotlin 跨平台) 实现 KmpHttpClient/KmpHttpClientBuilder,
 * 无需独立 provider 注册 (actual class 直接构造, 不依赖 OkHttpClientProviders provider 模式);
 * 与 desktop 端 OkHttp + OkHttpClientProviders 模式不同但功能等价。
 *
 * 模式参考 Android 端 `App.onCreate` / iOS 端 `registerIosProviders` /
 * desktop 端 `Main.kt` 中的 provider 注册序列。
 */
fun registerOhosProviders() {
    // 1. 文件系统目录 (其他 provider 持久化依赖)
    registerOhosAppFilesDir()

    // 2. 配置 provider (PreferenceProvider -> AppConfigAccessor)
    registerOhosPreferenceProvider()
    registerNativeAppConfigAccessor()

    // 3. 数据库 provider (DatabaseDriver + AppDatabase + AppDb, 依赖 AppFilesDirs)
    // 与 desktop Main.kt 中 `DatabaseDriverProviders.register + AppDatabaseProviders.register + AppDbProviders.register` 三步对齐
    // registerNativeAppDb 一次性注册 NativeAppDatabaseProvider + NativeAppDbAccessor (两端共用, 替代原 ohosMain 专属实现)
    val dbDriver = OhosDatabaseDriver()
    DatabaseDriverProviders.register(dbDriver)
    registerNativeAppDb(dbDriver)

    // 4. 书籍缓存 provider (BookStorage + BookHelp, 依赖 AppFilesDirs)
    // 与 desktop Main.kt 中 `BookStorageProviders.register + BookHelpProviders.register` 顺序对齐
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

    // 6. JS 引擎 provider (OhosJsEngine + OhosImageOps), 必须在任何 JS eval 之前
    // (解除 KP4 P0 阻塞: 鸿蒙端 JS 引擎缺失导致书源规则解析全失效)
    registerOhosJsEngines()

    // 7. TTS 引擎 provider (OhosSystemTtsEngine 占位), 在 JsEngines 之后
    // (与 desktop Main.kt 中 `TtsEngineProvider.register(DesktopSystemTtsEngine())` 位置一致)
    registerOhosSystemTtsEngine()

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
    registerOhosServiceLauncher()
    // 注册业务层 CookieStoreProvider (stub, 后续接入 @ohos.net.http CookieManager 后替换)
    // 与 desktop registerDefaultJvmCookieStoreProvider / app registerAndroidCookieStoreProvider
    // / iOS registerDefaultIosCookieStoreProvider 对齐
    registerDefaultOhosCookieStoreProvider()

    // 9. Web 服务 provider (WebAssetSource + WebStrings + WebServerPlatform, iOS/鸿蒙共用 Ktor server 壳)
    // 仅注册平台实现, 不启动服务 (WebServerManager.start 由用户操作触发)
    registerNativeWebAssetSource()
    registerNativeWebStrings()
    registerNativeWebServerPlatform()
}
