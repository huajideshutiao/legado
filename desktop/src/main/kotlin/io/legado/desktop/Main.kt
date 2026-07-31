package io.legado.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.application
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabaseProviders
import io.legado.app.data.AppDbProviders
import io.legado.app.data.BundledDatabaseDriver
import io.legado.app.data.DatabaseDriverProviders
import io.legado.app.data.DesktopAppDatabaseProvider
import io.legado.app.help.DefaultDataResourceProviders
import io.legado.app.help.HomeTabHelpShared
import io.legado.app.help.PinnedExploreHelp
import io.legado.app.help.book.BookHelpProviders
import io.legado.app.help.book.BookImageStorageProviders
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.book.JvmBookImageStorage
import io.legado.app.help.book.JvmBookStorage
import io.legado.app.help.book.JvmLocalBookLocator
import io.legado.app.help.book.LocalBookLocators
import io.legado.app.help.config.LocalReadConfigProviders
import io.legado.app.help.config.ReadConfigProviders
import io.legado.app.help.config.ReadTipConfigShared
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.file.registerDesktopAppFilesDir
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.help.image.registerJvmBookImageLoader
import io.legado.app.help.image.registerReaderImageResolver
import io.legado.app.help.notification.registerDesktopNotificationProgress
import io.legado.app.help.service.registerDesktopServiceLauncher
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.source.SourceHelpAccessors
import io.legado.app.help.storage.registerJvmDataStorage
import io.legado.app.help.toast.registerDesktopToaster
import io.legado.app.help.tts.TtsEngineProvider
import io.legado.app.model.DesktopReadBookProvider
import io.legado.app.model.LocalReadBookProvider
import io.legado.app.model.fileBook.BitmapProviders
import io.legado.app.model.fileBook.ZipFileWrapperFactoryProviders
import io.legado.app.model.script.JsEngines
import io.legado.app.ui.association.DeepLinkImportHost
import io.legado.app.ui.association.LegadoDeepLink
import io.legado.app.ui.association.LegadoDeepLinkHandler
import io.legado.app.ui.book.audio.AudioPlayPlatformProviders
import io.legado.app.ui.book.info.LocalBlurCoverBgSlot
import io.legado.app.ui.book.info.SharedBlurCoverBgCoil
import io.legado.app.ui.book.manga.MangaReaderScreenModel
import io.legado.app.ui.book.read.ReaderPlatformProviders
import io.legado.app.ui.book.read.page.provider.SkiaTextMeasurer
import io.legado.app.ui.book.read.page.provider.TextMeasurerProviders
import io.legado.app.ui.book.source.SourceUiEventBridgeHost
import io.legado.app.ui.book.video.VideoPlayPlatformProviders
import io.legado.app.ui.browser.LocalWebViewSlot
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopEventBusProvider
import io.legado.app.ui.compose.platform.DesktopPreferenceStoreProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.LegadoApp
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.web.registerDesktopWebServerPlatform
import io.legado.app.web.utils.registerDesktopWebAssetSource
import io.legado.app.web.utils.registerDesktopWebStrings
import io.legado.desktop.audio.registerDesktopAudioPlayProviders
import io.legado.desktop.config.registerDesktopConfig
import io.legado.desktop.data.DesktopAppDbAccessor
import io.legado.desktop.help.DesktopDefaultDataResourceProvider
import io.legado.desktop.help.SingleInstanceGuard
import io.legado.desktop.help.book.DesktopBitmapProvider
import io.legado.desktop.help.book.DesktopBookHelpAccessor
import io.legado.desktop.help.book.DesktopZipFileWrapperFactory
import io.legado.desktop.help.book.registerDesktopBookshelfManagePlatform
import io.legado.desktop.help.changesource.registerDesktopChangeBookSourcePlatform
import io.legado.desktop.help.config.registerDesktopPasswordProvider
import io.legado.desktop.help.http.registerDesktopBackstageWebView
import io.legado.desktop.help.i18n.registerDesktopAppStringProvider
import io.legado.desktop.help.initDesktopDefaultData
import io.legado.desktop.help.log.registerDesktopAppLogHost
import io.legado.desktop.help.registerDesktopAndroidId
import io.legado.desktop.help.registerDesktopArchiveProvider
import io.legado.desktop.help.registerDesktopDirectLinkUploadProviders
import io.legado.desktop.help.registerDesktopFileCacheProvider
import io.legado.desktop.help.registerDesktopRegexErrorHandler
import io.legado.desktop.help.registerDesktopScreenInfoProvider
import io.legado.desktop.help.source.DesktopSourceHelpAccessor
import io.legado.desktop.help.source.registerDesktopSourceProviders
import io.legado.desktop.help.source.registerDesktopVerificationUiProvider
import io.legado.desktop.help.storage.registerDesktopBackupRestoreHook
import io.legado.desktop.help.tts.DesktopReadAloudHost
import io.legado.desktop.help.ui.registerDesktopOpenUrlProvider
import io.legado.desktop.help.ui.registerDesktopToastProvider
import io.legado.desktop.help.ui.registerDesktopUserAgentProvider
import io.legado.desktop.http.registerDesktopHttpProvider
import io.legado.desktop.js.registerDesktopJsEngines
import io.legado.desktop.model.DesktopCacheBook
import io.legado.desktop.model.fileBook.registerDesktopFileBookAccessor
import io.legado.desktop.model.registerDesktopReadBookPlatform
import io.legado.desktop.model.webBook.registerDesktopWebBookProviders
import io.legado.desktop.tts.DesktopHttpTtsPlayer
import io.legado.desktop.tts.DesktopSystemTtsEngine
import io.legado.desktop.ui.DesktopDialogHost
import io.legado.desktop.ui.DesktopPlatformCapabilities
import io.legado.desktop.ui.DesktopPlatformServices
import io.legado.desktop.ui.DesktopWindowHandle
import io.legado.desktop.ui.browser.DesktopWebViewSlot
import io.legado.desktop.ui.platform.DesktopAudioPlayPlatformProvider
import io.legado.desktop.ui.platform.DesktopMangaReaderPlatform
import io.legado.desktop.ui.platform.DesktopReaderPlatformProvider
import io.legado.desktop.ui.platform.DesktopVideoPlayPlatformProvider
import io.legado.desktop.ui.tray.DesktopMediaTray
import io.legado.desktop.ui.tray.ReadAloudTrayBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup
import java.awt.Desktop
import java.io.File
import javax.imageio.ImageIO

// Debug 日志开关: -Dlegado.desktop.debug=true 开启 stdout 调试输出
// 生产环境静默, 减少 println 同步 flush 开销 (启动期 initDesktopRuntimeEnvironment 等)
private val desktopDebug = System.getProperty("legado.desktop.debug")?.toBoolean() == true

private fun debugLog(msg: String) {
    if (desktopDebug) println(msg)
}

/**
 * 桌面端入口 (plan 附录 J: desktop/jvm 走 CMP 桌面官方 JVM)。
 *
 * 目的: 证明 KMP shared 模块下沉的纯 Kotlin 业务逻辑可在任意 JVM (Win/Linux/macOS) 直接运行,
 * 无 Android 依赖。Compose Multiplatform 桌面窗口展示 shared jvm target 暴露的 API 调用结果,
 * 演示四大能力:
 *
 * 1. MD5 摘要 (commonMain 纯 Kotlin 实现, 无 java.security 依赖)
 * 2. AES/HMAC/摘要 (jvmAndAndroidMain hutool 实现, 桌面端白捡 JVM 兼容)
 * 3. 简繁转换 (jvmAndAndroidMain quick-transfer 实现, 桌面端白捡)
 * 4. Room KMP 数据库 (KP1.2: BundledSQLiteDriver 跨平台 SQLite, 桌面端可跑数据库)
 *
 * 跑法: .\gradlew :desktop:run
 *
 * # 启动性能优化 (三阶段注册)
 * 阶段1: 首屏必需 provider 同步注册 (窗口显示前) — 只注册 BookshelfScreen 渲染依赖的 provider
 * 阶段2: 显示窗口 — 让用户尽快看到界面
 * 阶段3: 后台异步注册非首屏 provider (LaunchedEffect + Dispatchers.Default) — 不阻塞首屏渲染
 * 冒烟测试 (testDesktopHttp/testDesktopDatabase/testDesktopJsEngine) 改为 debug 模式才执行
 * (通过 -Dlegado.desktop.smokeTest=true 开启), 生产环境不执行, 避免启动期阻塞
 */
fun main(args: Array<String>) {
    // KP6: 便携模式检测 + native 库加载。必须最先执行 (早于 SingleInstanceGuard):
    // 它设置的 legado.portable.root 决定 desktopAppRootDir() 的解析结果, 而后者进程内 lazy
    // 只解析一次 —— 单实例守卫要在数据目录写 instance.lock, 提前读会把便携模式的根目录定位歪。
    initDesktopRuntimeEnvironment()
    // 单实例守卫: 已有实例存活时把 args 转发过去 + 前置其窗口, 本进程 exitProcess(0) 不返回
    // (对照 app 端 AssociationActivity singleTask)。必须在 handleDeepLinkArgs 与任何
    // provider/数据库初始化之前, 否则二次启动进程会先碰同一个 SQLite 库再退出。
    SingleInstanceGuard.ensureSingleInstance(args)
    // legado:// deep link 启动参数处理 (对照 app 端 AssociationActivity intent-filter):
    // 系统级 URL protocol 注册 (注册表/.desktop/Info.plist) 属安装器配置, 见 handleDeepLinkArgs KDoc
    handleDeepLinkArgs(args)
    // macOS: legado:// 经 Apple Event (OpenURIHandler) 送达而非 argv, 注册 handler 承接;
    // Windows/Linux 的 Desktop.Action.APP_OPEN_URI isSupported=false, 静默跳过
    runCatching {
        if (Desktop.isDesktopSupported() &&
            Desktop.getDesktop().isSupported(Desktop.Action.APP_OPEN_URI)
        ) {
            Desktop.getDesktop().setOpenURIHandler { event ->
                LegadoDeepLinkHandler.handle(event.uri.toString())
            }
        }
    }
    runDesktopApp()
}

/**
 * 解析启动参数中的 legado://`/`yuedu:// deep link, 经 [LegadoDeepLinkHandler] 记录,
 * 待 [DeepLinkImportHost] 在窗口内消费弹导入对话框。
 *
 * # 各 OS 系统级 URL protocol 注册方法 (安装器/打包配置, 本函数只管进程启动参数)
 *
 * - **Windows**: 注册表 `HKEY_CLASSES_ROOT\legado` 键下建空字符串值 `URL Protocol` +
 *   子键 `shell\open\command` 默认值 `"C:\path\legado.exe" "%1"`; jpackage 安装器可在
 *   post-install 脚本写入, MSIX 打包则用 manifest `uap:Protocol Name="legado"`。yuedu 同理。
 * - **Linux**: .desktop 文件加 `MimeType=x-scheme-handler/legado;x-scheme-handler/yuedu;`
 *   且 `Exec=legado %u`, 安装后执行
 *   `xdg-mime default legado.desktop x-scheme-handler/legado x-scheme-handler/yuedu`。
 * - **macOS**: app bundle Info.plist 加 `CFBundleURLTypes` (CFBundleURLSchemes=[legado,yuedu]),
 *   jpackage 17+ 可用 `--mac-url-scheme legado --mac-url-scheme yuedu` 生成;
 *   运行时回调走 Apple Event, 由 main() 里的 Desktop.setOpenURIHandler 承接 (非 argv)。
 */
private fun handleDeepLinkArgs(args: Array<String>) {
    val url = args.firstOrNull { LegadoDeepLink.isDeepLink(it) } ?: return
    if (!LegadoDeepLinkHandler.handle(url)) {
        debugLog("[legado-desktop] deep link 解析失败 (缺 src 参数): $url")
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
private fun runDesktopApp() = application {
    // ==================== 阶段1: 首屏必需 provider 同步注册 (窗口显示前) ====================
    // 目标: 只注册首屏 (BookshelfScreen) 渲染依赖的 provider, 让窗口尽快显示。
    // 其余 provider 延迟到窗口显示后异步注册 (见阶段3 registerSecondaryProviders)。

    // KP6: 便携模式检测 + native 库加载已提前到 main() 首行执行 (单实例守卫要先读数据目录),
    // 此处不再重复调用; 其结果 (legado.portable.root / legado.quickjs.lib 系统属性) 对
    // 下方所有 provider 注册依然有效。
    // 注册桌面端 Host 类 provider (启动期最早, 让 shared commonMain 调用 AppLog/appString 时有输出)
    // - AppLogHost: 桥接到 println, 未注册时 AppLog 副作用 (write/toast/debugPrint) 静默 no-op
    // - AppStringProvider: 返回 key.name 兜底, 未注册时 appString 同样 fallback 到 key.name
    registerDesktopAppLogHost()
    registerDesktopAppStringProvider()
    // 注册桌面端 ScreenInfoProvider (Toolkit.getDefaultToolkit().screenSize),
    // 供 shared commonMain 经 ScreenInfoProviders.get() 读屏幕尺寸; 无依赖, 同步注册
    registerDesktopScreenInfoProvider()
    // 注入机器标识到 AndroidIdHolder (对照 app 端 JsEnginesAndroid 注入 ANDROID_ID)。
    // 默认值 "null" 只有 4 字符, BaseSource 登录信息 AES 密钥取前 16 字节会越界被吞,
    // 表现为桌面端书源登录信息无法持久化; 必须在任何 getLoginInfo/putLoginInfo 之前注入
    registerDesktopAndroidId()
    // 注册桌面端 Toaster + NotificationProgress provider (shared jvmMain 已实现)
    // - Toasters: SystemTray + TrayIcon 显示通知 (forceRefresh 提示 / 错误反馈用)
    // - NotificationProgresses: SystemTray 进度通知 (已弃用: 桌面端进度改走 UI 内 StateFlow)
    // 未注册时 Toasters.get() 抛 IllegalStateException (forceRefresh 未 runCatching 防御);
    // SystemTray 需在主线程初始化, 故同步注册
    registerDesktopToaster()
    registerDesktopNotificationProgress()
    // KP1.4 + 备份格式兼容性: 注册桌面端 config provider
    // - PreferenceProvider + AppConfigAccessor (KP1.4)
    // - ReadBookConfigProviders + ThemeConfigProviders (备份格式兼容性补齐, 供 BackupShared 用)
    // 用 remember 构造 DesktopPreferenceStoreProvider 实例, 同一实例在 Window 内复用,
    // 保证 UI 样式配置与备份逻辑状态同步
    val preferenceStoreProvider = remember { DesktopPreferenceStoreProvider() }
    // 接住返回值: LocalReadConfigProviders 必须与全局 ReadBookConfigProviders 同实例, 否则配置写读分家
    val desktopReadBookConfig = remember { registerDesktopConfig(preferenceStoreProvider) }
    // 注入 HomeTabHelpShared 的 prefs provider (commonMain 下沉的主页分组持久化)
    // 复用已创建的 DesktopPreferenceStoreProvider, 对齐 app 端 App.kt 的 HomeTabHelpShared.prefs 注入
    HomeTabHelpShared.prefs = preferenceStoreProvider
    // 注入 PinnedExploreHelp 的 prefs provider (commonMain 下沉的发现页收藏分类持久化)
    // 与 HomeTabHelpShared 同源, 对齐 app 端 App.kt 的 PinnedExploreHelp.prefs 注入
    PinnedExploreHelp.prefs = preferenceStoreProvider
    // KP1.4 补: 注册桌面端 AppFilesDir (~/.legado/files), 供 BackupShared/RestoreShared 用
    registerDesktopAppFilesDir()
    // Coil3 图片栈: 注册 BookImageLoader + SingletonImageLoader.setSafe (共享 ImageLoader,
    // 拦截器/diskCache 装配见 shared BookImageLoader.jvm.kt)。必须在阶段1:
    // setSafe 晚于首个 rememberAsyncImagePainter/AsyncImage 的 get 会抛 IllegalStateException。
    // 注册零开销 (ImageLoader lazy, OkHttpClient 惰性到首次网络 fetch, 无启动期网络栈初始化)
    registerJvmBookImageLoader()
    // 注册桌面端 DefaultDataResourceProvider (actual 实现由另一子代理处理, 这里只负责 register)
    // 必须在 AppDatabaseProviders.register 之前注册 (首次建库 dbCallback.onCreate →
    // DefaultData.keyboardAssists → DefaultDataResourceProviders.get().readResource("keyboardAssists.json")),
    // 否则首次建库时 dbCallback.onCreate 抛 IllegalStateException 被 runCatching 吞掉,
    // 表现为 keyboardAssists 表无默认数据。
    DefaultDataResourceProviders.register(DesktopDefaultDataResourceProvider())
    // KP1.2: 注册桌面端 AppDatabase provider (首屏 BookshelfScreen 通过 AppDbProviders 访问 bookDao)
    // BundledDatabaseDriver 用 Room.databaseBuilder + BundledSQLiteDriver 构造 AppDatabase
    // (~/.legado/legado.db), 同一实例供 AppDatabaseProviders + DatabaseDriverProviders 共享
    val dbDriver = BundledDatabaseDriver()
    DatabaseDriverProviders.register(dbDriver)
    AppDatabaseProviders.register(DesktopAppDatabaseProvider(dbDriver))
    // KP2-D: 注册桌面端 BookStorage provider (首屏书架/阅读用, ~/.legado/book_cache)
    // ReadBookViewModelShared.loadChapter 通过 BookStorageProviders.get().getContent 读章节缓存正文
    // 存储路径集中 provider 须先注册 (JvmBookStorage 构造时取 chapterCacheDir)
    registerJvmDataStorage()
    BookStorageProviders.register(JvmBookStorage())
    // 注: BookImageStorageProviders (漫画图片缓存) 非首屏必需, 延迟到阶段3 registerSecondaryProviders 注册
    // KP2-D: 注册桌面端 AppDbAccessor / BookHelpAccessor provider (首屏书架间接访问)
    // 供 shared commonMain 中下沉的 webBook 编排层通过 AppDbProviders.get() / BookHelpProviders.get()
    // 间接访问 appDb 的 9 个 DAO / saveContent; 未注册时阅读流/搜索/书源管理全失效
    AppDbProviders.register(DesktopAppDbAccessor())
    BookHelpProviders.register(DesktopBookHelpAccessor())
    // 注册桌面端 PlatformCapabilities (供 shared LegadoApp 经 PlatformCapabilityProviders.get() 取能力)
    PlatformCapabilityProviders.register(DesktopPlatformCapabilities)
    // 注册桌面端 PlatformServices (对照 app 端 MainActivity.onActivityCreated 同步注册):
    // shared 路由中 PlatformServiceProviders.getOrNull() ?: return 回退分支不再触发,
    // 文件选择/分享/窗口控制等不再静默失败
    // windowHandle 在 Window 组装后由 DisposableEffect 注入 AWT 窗口, 供全屏切换
    val windowHandle = remember { DesktopWindowHandle() }
    PlatformServiceProviders.register(
        DesktopPlatformServices(
            DesktopPlatformCapabilities,
            windowHandle
        )
    )
    // 系统托盘: 音频/朗读活跃时给播放控制菜单 (最小化后仍可控), 同时承载 toast/进度气泡
    DisposableEffect(Unit) {
        DesktopMediaTray.install(
            windowProvider = { windowHandle.window },
            exitAction = ::exitApplication,
        )
        // 朗读控制器绑定: 托盘按 controller.state 增删朗读菜单项, 空闲时自动隐藏
        DesktopMediaTray.readAloud = ReadAloudTrayBinding(
            controller = DesktopReadAloudHost.controller,
            bookName = { DesktopReadAloudHost.bookName },
            chapterTitle = { DesktopReadAloudHost.chapterTitle },
        )
        onDispose {
            DesktopMediaTray.readAloud = null
            DesktopMediaTray.uninstall()
        }
    }
    // 注册桌面端 4 个媒体 PlatformProvider (对照 app 端 MainActivity.onActivityCreated 同步注册),
    // 避免 Reader/Audio/Manga/Video Route 显示 "platform unavailable":
    // - Reader: DesktopReadMenuController 功能性菜单 (导航/章节/夜间模式)
    // - Audio: 复用 shared AudioPlayScreenContent + 桌面端 slots (封面/模糊背景/歌词/对话框)
    // - Manga: Coil3 AsyncImage 渲染 + ColorMatrix 灰度/颜色滤镜
    // - Video: MPV 播放器 (SwingPanel + nativeHwnd 桥接, Windows 用 WComponentPeer getHwnd)
    ReaderPlatformProviders.register(DesktopReaderPlatformProvider())
    // ReadBookShared 的平台钩子 (朗读宿主 / 缓存运行态 / 本地 txt 分章缓存; 图片缓存为空实现,
    // 见 DesktopReadBookPlatform 注释)。须早于任何阅读页打开
    registerDesktopReadBookPlatform()
    // 阅读排版度量: 注册 Skia 真实字形度量, 取代 SimpleTextMeasurer 等宽近似
    // (字形来源与 PageContentCanvas 的 loadReaderFontFamily / FontFamily.Default 同源)
    TextMeasurerProviders.register { textSizePx, letterSpacingPx, fontPath ->
        SkiaTextMeasurer(
            textSizePx = textSizePx,
            letterSpacingPx = letterSpacingPx,
            typeface = SkiaTextMeasurer.readerTypeface(fontPath),
        )
    }
    // 阅读页内嵌图片 (PDF 单图页 / EPUB 插图): 排版取尺寸 + 绘制取位图
    registerReaderImageResolver()
    AudioPlayPlatformProviders.register(DesktopAudioPlayPlatformProvider())
    MangaReaderScreenModel.Providers.register(DesktopMangaReaderPlatform)
    VideoPlayPlatformProviders.register(DesktopVideoPlayPlatformProvider(windowHandle))

    // ==================== 阶段2: 显示窗口 ====================
    // KP2: 桌面端窗口框架——注入 4 个 DesktopXxxProvider + AppTheme 包装 LegadoApp
    // 窗口标题改为 "阅读" (用户反馈: 应用名应该就叫阅读)
    // icon 用 classpath 资源 icon.png (复制自 app/src/main/res/mipmap-xxxhdpi/ic_launcher.png),
    // 不再使用 Java 默认咖啡杯图标; 资源位于 desktop/src/main/resources/icon.png
    // 窗口标题走 rememberString("app_name"); application{} 顶层是 @Composable 上下文,
    // 在 Window 调用前求值后传给 title (Window.title 接收 String 而非 @Composable)
    val appName = rememberString("app_name")
    // classpath 资源加载: 弃用的 painterResource(String) 改为手动 ImageIO 解码 + BitmapPainter
    val iconPainter = remember {
        runCatching {
            Thread.currentThread().contextClassLoader
                ?.getResourceAsStream("icon.png")?.use { ImageIO.read(it) }
                ?.toComposeImageBitmap()?.let { BitmapPainter(it) }
        }.getOrNull()
    }
    // AppNavigator: 零薄壳导航唯一状态源 (替代旧 DesktopApp 的 20+ 并行状态字段)
    val navigator = remember { AppNavigator(AppRoute.Main()) }
    val screenModelStore = remember { ScreenModelStore() }
    // Compose 未捕获异常兜底: CMP 默认工厂 (DefaultWindowExceptionHandlerFactory) 弹的是
    // 模态 JOptionPane —— 模态窗口会禁用主窗口输入却不影响重绘, 又常被置顶的 Dialog 图层
    // 或全屏窗口遮住, 表现就是"窗口还能 resize 重排, 键鼠全部失灵"。改为只记日志不弹窗。
    CompositionLocalProvider(
        LocalWindowExceptionHandlerFactory provides WindowExceptionHandlerFactory {
            WindowExceptionHandler { AppLog.put("Compose 未捕获异常", it) }
        }
    ) {
    Window(
        onCloseRequest = ::exitApplication,
        // 返回键由 shared LegadoApp 内部 handleBackKey 统一处理, desktop Window 不消费
        onKeyEvent = { false },
        title = appName,
        icon = iconPainter,
    ) {
        // 单实例守卫绑定主窗口: 二次启动转发到达时前置本窗口 (取消最小化 + toFront + 请求焦点);
        // DisposableEffect 保证窗口销毁后解绑, 不让守卫持有已 dispose 的 AWT Window
        // 同步注入 AWT 窗口句柄到 DesktopWindowHandle, 供 DesktopWindowController 切换全屏
        DisposableEffect(window) {
            windowHandle.window = window
            SingleInstanceGuard.bindWindow(window)
            onDispose {
                windowHandle.window = null
                SingleInstanceGuard.bindWindow(null)
            }
        }
        // ==================== 阶段3: 后台异步注册非首屏 provider ====================
        // 用 LaunchedEffect 在窗口显示后立即启动协程注册, 不阻塞首屏渲染
        // 用 withContext(Dispatchers.Default) 在后台线程执行, 避免阻塞 UI 线程
        LaunchedEffect(Unit) {
            registerSecondaryProviders()
        }
        // 注入 4 个 DesktopXxxProvider, 供 commonMain AppTheme 通过 LocalXxx 取依赖
        val themeStoreProvider = remember { DesktopThemeStoreProvider() }
        val appConfigProvider = remember { DesktopAppConfigProvider() }
        val eventBusProvider = remember { DesktopEventBusProvider() }
        // preferenceStoreProvider 复用顶层 remember 实例 (与 ReadBookConfigProviders 共享同一实例)
        // 阅读器注入: ReaderRoute/ReaderDrawStyle/PageViewComposable 消费, 缺省值是 error()
        // —— 未注入时打开阅读器即抛异常, 被 DesktopCoroutineExceptionHandler 吞掉后表现为输入冻结
        val readConfigProviders = remember {
            object : ReadConfigProviders {
                override val readBookConfig = desktopReadBookConfig
                override val readTipConfig = ReadTipConfigShared(desktopReadBookConfig)
            }
        }
        val readBookProvider = remember { DesktopReadBookProvider() }
        CompositionLocalProvider(
            LocalThemeStoreProvider provides themeStoreProvider,
            LocalAppConfigProvider provides appConfigProvider,
            LocalEventBusProvider provides eventBusProvider,
            LocalPreferenceStoreProvider provides preferenceStoreProvider,
            LocalReadConfigProviders provides readConfigProviders,
            LocalReadBookProvider provides readBookProvider,
            LocalWebViewSlot provides { url, modifier -> DesktopWebViewSlot(url, modifier) },
            // 注入 Coil3 模糊封面背景到 shared 详情页路由, 覆盖 LocalBlurCoverBgSlot 兜底
            LocalBlurCoverBgSlot provides { book, coverTick, inBookshelf, isEInkMode, modifier ->
                SharedBlurCoverBgCoil(book, coverTick, inBookshelf, isEInkMode, modifier)
            },
        ) {
            AppTheme {
                // 对照 app 端 App.kt:132 SourceUiEventBridge.init(): desktop 无 Activity,
                // 改用 Composable 宿主订阅 FlowBus(SOURCE_UI_REQUEST) 弹 Compose Dialog
                // (实现见 shared/sharedUiMain 的 SourceUiEventBridgeHost)
                SourceUiEventBridgeHost()
                // 桌面端命令式对话框宿主: PlatformCapabilities 的同步方法经 DesktopDialogs 推请求
                DesktopDialogHost()
                // legado:// deep link 导入对话框宿主: 消费 main(args)/OpenURIHandler 经
                // LegadoDeepLinkHandler 记录的待导入请求 (对照 app 端 AssociationActivity 分发)
                DeepLinkImportHost()
                // 零薄壳: shared LegadoApp 统一管理导航栈 + ScreenModel 生命周期,
                // 所有路由由 shared RouteContent 直接渲染
                LegadoApp(
                    navigator = navigator,
                    screenModelStore = screenModelStore,
                )
            }
        }
    }
    }
}

/**
 * 后台异步注册非首屏必需的 provider (启动性能优化)。
 *
 * 首屏 (BookshelfScreen) 只依赖 AppDbProviders + BookHelpProviders + BookStorageProviders
 * (已在 main() 同步注册), 其余 provider 在窗口显示后用 [LaunchedEffect] + [withContext]
 * 在后台线程顺序注册, 保持原依赖关系:
 * - registerDesktopAudioPlayProviders 依赖 SourceHelpAccessors + WebBookProviders + JsEngines + OkHttpClientProviders
 *   (必须在它们之后注册)
 *
 * # 冒烟测试
 * testDesktopHttp / testDesktopDatabase / testDesktopJsEngine 改为仅 debug 模式执行
 * (通过 -Dlegado.desktop.smokeTest=true 系统属性开启), 生产环境不执行,
 * 避免启动期同步网络请求 (baidu.com) / native 库加载 (legado_quickjs.dll) 阻塞 UI。
 * 原同步执行时这三项是双击打开卡顿的主要原因。
 */
private suspend fun registerSecondaryProviders() {
    withContext(Dispatchers.Default) {
        // 0. FileCacheProvider (CacheManager 文件/二进制层, 依赖 AppFilesDirs 已同步注册)
        // 未注册时 CacheManager.getFile/putFile/getByteArray/put(ByteArray)/delete 文件层静默 no-op,
        // source 变量 / loginHeader / userInfo 等文件缓存全失效 (P0)
        registerDesktopFileCacheProvider()
        // 1. 备份/直链相关 (依赖 PreferenceProviders, 已同步注册)
        // - PasswordProvider: 供 BackupAES 无参构造经 PasswordProviders 反向获取 password
        // - DirectLinkUploadProviders: 供 BackupShared/RestoreShared 备份恢复 directLinkUploadRule.json
        registerDesktopPasswordProvider()
        registerDesktopDirectLinkUploadProviders()
        // - BackupRestoreHooks: 备份/恢复的平台收尾 (lastBackup 时间戳 + 恢复完成提示);
        //   zip 复制/解压走 shared 默认文件分支, 桌面端无 SAF
        registerDesktopBackupRestoreHook()
        // 2. HTTP 层 (OkHttp + CookieJarBridge, 独立)
        registerDesktopHttpProvider()
        // jsoup 走宿主共享 OkHttpClient (对照 app 端 App.kt:138 Jsoup.clientFactory = { okHttpClient })
        // 让 jsoup 解析继承 CookieJar/限流/Cronet 拦截器, 不绕过 shared HTTP 层
        Jsoup.clientFactory = { OkHttpClientProviders.get().okHttpClient }
        // 3. BackstageWebView (内嵌浏览器引擎: Windows 走系统自带 WebView2 Runtime;
        //    引擎缺失时 create 仍抛 UnsupportedOperationException 由调用方 runCatching 回退 HTTP)
        registerDesktopBackstageWebView()
        // 4. 本地书定位器 (独立, 供 DesktopBookshelfManagePlatform.deleteLocalBook 用)
        LocalBookLocators.register(JvmLocalBookLocator())
        // 5. CbzFile 相关 (独立, 漫画解析用)
        BitmapProviders.register(DesktopBitmapProvider)
        ZipFileWrapperFactoryProviders.register(DesktopZipFileWrapperFactory)
        // 5b. BookImageStorage provider (webBook 漫画图片缓存, 非首屏必需, 从阶段1延迟到此)
        //     供 shared commonMain webBook 编排层通过 BookImageStorageProviders.get() 间接调用;
        //     saveImages 运行时依赖 OkHttpClientProviders (第2步已注册), 注册顺序无强约束
        BookImageStorageProviders.register(JvmBookImageStorage())
        // 注: Coil3 BookImageLoader/SingletonImageLoader 已提前到阶段1 注册 (setSafe 时机约束)
        // 6. EpubFile 相关 (依赖 AppDbProviders, 已同步注册)
        registerDesktopFileBookAccessor()
        // 7. SourceHelp (独立, 供 shared SourceHelp.saveSource/deleteBookSource 调用)
        SourceHelpAccessors.register(DesktopSourceHelpAccessor())
        // 8. 服务启动器 (依赖 AppDbProviders + BookHelpProviders + BookStorageProviders, 已同步注册)
        registerDesktopServiceLauncher()
        // 8b. Web 服务 provider (NanoHTTPD 独立, 不依赖其他 provider)
        // - WebServerPlatform: HttpServer+WebSocketServer 起停 (JvmWebServerPlatform 共用逻辑)
        // - WebAssetSource: composeResources 读 commonMain/composeResources/files/web/ 静态资源 (单一数据源)
        // - WebStrings: 硬编码中文文案 (后续接入 i18n 资源后替换)
        // 须在任何 WebServerManager.start()/stop() 之前注册 (MyScreen Web 服务开关触发时)
        registerDesktopWebServerPlatform()
        registerDesktopWebAssetSource()
        registerDesktopWebStrings()
        // 9. CacheBook 回调 (依赖 ServiceLauncher 已注册)
        DesktopCacheBook.registerCallback()
        // 10. Source 扩展 provider (依赖 PreferenceProviders, in-memory 实现)
        registerDesktopSourceProviders()
        // 10b. 正则替换错误处理 + 压缩文件解压 provider (供 shared RegexReplacerImpl / JsExtensionsCommon 调用,
        //      必须在 WebBook 编排层 + JS 引擎首次 eval 之前注册, Toasters / AppFilesDirs 已就绪)
        registerDesktopRegexErrorHandler()
        registerDesktopArchiveProvider()
        // 11. WebBook 编排层 (依赖 AppDbProviders.replaceRuleDao 已就绪)
        registerDesktopWebBookProviders()
        // 11b. JS 扩展回调 provider (Toast/OpenUrl/UserAgent, 供 JsExtensionsCommon 回调,
        //      必须在 JS 引擎首次 eval 之前注册)
        registerDesktopToastProvider()
        registerDesktopOpenUrlProvider()
        registerDesktopUserAgentProvider()
        // 11c. 书源验证 UI provider (图片验证码走 Swing 输入框, 网页验证给明确报错)
        //      依赖 OkHttpClientProviders (第2步, 拉验证码图片) + ToastProviders (上一行);
        //      未注册时 JS 触发验证会 IllegalStateException 裸抛
        registerDesktopVerificationUiProvider()
        // 12. JS 引擎 (native 库在首次 eval 时加载, 注册本身不耗时)
        registerDesktopJsEngines()
        // 13. AudioPlay (依赖 AppDbProviders + BookHelpProviders + SourceHelpAccessors + WebBookProviders
        //     + JsEngines + OkHttpClientProviders, 必须最后注册)
        registerDesktopAudioPlayProviders()
        // 13b. ChangeBookSource / BookshelfManage 平台 provider (对照 app 端 App.kt:183/187
        //      registerAndroidChangeBookSourcePlatform / registerAndroidBookshelfManagePlatform,
        //      须在 registerDesktopWebBookProviders 之后, 因换源/书架管理依赖 AppDbProviders /
        //      WebBookProviders / ContentProcessorProviders 已注册)
        registerDesktopChangeBookSourcePlatform()
        registerDesktopBookshelfManagePlatform()
        // 14. TTS 引擎 (独立, Windows SAPI / Linux espeak / macOS say)
        TtsEngineProvider.register(DesktopSystemTtsEngine())
        // 14b. HttpTTS 播放器工厂 (KP2-D P0-9: 三端朗读 HttpTTS 路径)
        TtsEngineProvider.registerHttpTtsPlayerFactory { DesktopHttpTtsPlayer() }

        // 15. 启动期异步任务 (对照 app 端 App.kt onCreate 的 Coroutine.async 块)
        // adjustSortNumber: 调整书源排序序号 (依赖 AppDbProviders, 已注册)
        // 异常由 Coroutine 内部 printOnDebug 吞没, 与 app 端语义一致
        Coroutine.async { SourceHelp.adjustSortNumber() }
        // LogUtils.init 为 Android 专属, desktop 用 registerDesktopAppLogHost 替代
        // 对照 app 端 App.kt:144 DefaultData.upVersion() + dbCallback.onCreate 预置数据:
        // 桌面端 Room KMP 无 Callback, 首启/升级的默认数据统一在这里幂等补齐
        initDesktopDefaultData()
        // 注: app 端 App.kt:171 BookCover.toString() 未补齐 — BookCover object 依赖 Android
        // Glide/Bitmap/Drawable/appCtx, desktop 用独立的 DesktopBookCover (JDK ImageIO + OkHttp),
        // 无对应下沉的封面缓存初始化逻辑。

        // 16. 启动期缓存清理 + WebDav 进度同步
        // (对照 app 端 MainActivity.onPostCreate viewModel.postLoad)
        // TODO: 待下沉到 shared LegadoApp 内部统一编排 (原 DesktopStartupTasks 已删除, 避免临时 scope 泄漏)

        // 冒烟测试: 仅在 debug 模式执行 (生产环境不执行, 避免启动期阻塞)
        // 开启方式: java -Dlegado.desktop.smokeTest=true -jar ... 或在 build.gradle.kts jvmArgs 添加
        if (System.getProperty("legado.desktop.smokeTest")?.toBoolean() == true) {
            testDesktopHttp()
            testDesktopDatabase()
            testDesktopJsEngine()
        }
    }
}

/**
 * KP1.1 桌面端 JS 引擎冒烟测试。
 *
 * 通过 [JsEngines.get] 取当前 JS 引擎 (应为 QuickJsJsEngine), 执行三组测试:
 * 1. 纯算术: `1 + 2 * 3` 期望 7
 * 2. 字符串拼接: `"Hello, " + platform` 期望 "Hello, android" (AppConst.JS_PLATFORM 当前固定 "android")
 * 3. JSON 解析: `JSON.stringify({a:1,b:2})` 期望 `{"a":1,"b":2}`
 *
 * 任何一步抛异常 (含 native 库缺失 UnsatisfiedLinkError) 都打印 stack trace 但不中断主流程,
 * 让后续 UI 仍能起来, 便于观察错误。
 *
 * native 库依赖: 首次 eval 触发 `loadLegadoQuickJsNative()` 加载 .dll/.so/.dylib,
 * 详见 `modules/quickjs/src/jvmMain/kotlin/com/script/quickjs/Platform.kt` 注释。
 * 若未构建 native 库, 此处会打印 UnsatisfiedLinkError, 需先执行
 * `./gradlew :modules:quickjs:buildJvmNativeLib`。
 *
 * 注意: 本函数仅在 debug 模式 (-Dlegado.desktop.smokeTest=true) 由 [registerSecondaryProviders] 调用,
 * 生产环境不执行, 避免启动期 native 库加载阻塞 UI。
 */
private fun testDesktopJsEngine() {
    try {
        val engine = JsEngines.get()
        debugLog("=== KP1.1 Desktop JS Engine Test ===")
        debugLog("engine=${engine::class.simpleName} type=${engine.type}")

        // 1. 纯算术 (JS 整数运算返回 Integer/Double, 走 toString 即可)
        val arithmetic = engine.eval("1 + 2 * 3")
        debugLog("1+2*3 = $arithmetic (expect 7)")

        // 2. 字符串拼接 + 读取 bindings 中的 platform 变量
        //    JsBindings init 注入 platform="android" / image=DesktopImageOps,
        //    JS 顶层 eval 可直接访问 platform
        val greeting = engine.eval("'Hello, ' + platform")
        debugLog("Hello, platform = $greeting")

        // 3. JSON.stringify 返回 JS string, Kotlin 侧拿到的就是 String
        val json = engine.eval("JSON.stringify({a:1,b:2})")
        debugLog("JSON.stringify({a:1,b:2}) = $json")

        debugLog("=====================================")
    } catch (e: Throwable) {
        debugLog("=== KP1.1 Desktop JS Engine Test FAILED ===")
        e.printStackTrace()
        debugLog("================================================")
        debugLog(jvmGetString("desktop_smoke_test_js_hint"))
        debugLog("  ./gradlew :modules:quickjs:buildJvmNativeLib")
        debugLog("================================================")
    }
}

/**
 * KP1.3 桌面端 HTTP 层冒烟测试。
 *
 * 通过 [OkHttpClientProviders] 取桌面端 OkHttpClient, 同步 GET https://www.baidu.com,
 * 打印响应 code 与 body 前 200 字符到 stdout, 验证 SSL/拦截器链/解压全链路工作。
 *
 * 注意: 本函数仅在 debug 模式 (-Dlegado.desktop.smokeTest=true) 由 [registerSecondaryProviders] 调用,
 * 生产环境不执行, 避免启动期同步网络请求阻塞 UI。
 */
private fun testDesktopHttp() {
    try {
        val client = OkHttpClientProviders.get().okHttpClient
        val request = Request.Builder().url("https://www.baidu.com").build()
        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            val preview = if (body.length > 200) body.take(200) else body
            debugLog("=== KP1.3 Desktop HTTP Test ===")
            debugLog("code=${response.code}")
            // 换行替换为空格, 避免控制台预览串行难读
            debugLog("body(200)=${preview.replace('\n', ' ')}")
            debugLog("===============================")
        }
    } catch (e: Exception) {
        debugLog("=== KP1.3 Desktop HTTP Test FAILED ===")
        e.printStackTrace()
        debugLog("=======================================")
    }
}

/**
 * KP1.2 桌面端 Room KMP 数据库冒烟测试 (真实 BundledSQLiteDriver)。
 *
 * # 实现状态
 * 17 个 DAO 完成 suspend 迁移 + 启用 kspJvm 后, [BundledDatabaseDriver.appDatabase] 是真 Room,
 * 通过 [BundledSQLiteDriver] + `~/.legado/legado.db` 构造。本测试:
 * 1. 触发 lazy 构造 (访问 appDb.isOpen)
 * 2. 跑 suspend DAO 冒烟: bookSourceDao.allCount() (空表返回 0)
 *
 * 验证 Room KMP + BundledSQLiteDriver 全链路在桌面 JVM 工作。
 *
 * 注意: 本函数仅在 debug 模式 (-Dlegado.desktop.smokeTest=true) 由 [registerSecondaryProviders] 调用,
 * 生产环境不执行 (数据库 lazy 由首屏 BookshelfScreen 在协程中触发, 不阻塞主线程)。
 */
private fun testDesktopDatabase() {
    try {
        val appDb = AppDatabaseProviders.get().appDb
        debugLog("=== KP1.2 Desktop Database Test (Room KMP + BundledSQLiteDriver) ===")
        debugLog("databaseName=${DatabaseDriverProviders.get().databaseName}")
        debugLog("databaseVersion=${DatabaseDriverProviders.get().databaseVersion}")
        // Room KMP RoomDatabase 不暴露 isOpen (Android 端 RoomDatabase.isOpen 是 JVM 实现),
        // 桌面端通过 BundledSQLiteDriver 内部连接管理, 直接跳过 isOpen 检测
        // 跑 suspend DAO 冒烟 (runBlocking 触发 lazy + 执行查询)
        kotlinx.coroutines.runBlocking {
            val count = appDb.bookSourceDao.allCount()
            debugLog(jvmGetString("desktop_smoke_test_db_count", count))
        }
        debugLog("=====================================================================")
    } catch (e: Exception) {
        debugLog("=== KP1.2 Desktop Database Test FAILED ===")
        e.printStackTrace()
        debugLog("=============================================")
    }
}

/**
 * KP6 桌面端运行时环境初始化 (便携模式 + native 库加载)。
 *
 * 必须在所有 provider 注册前调用 (Main.kt application{} 第一行), 因下游:
 * - [io.legado.app.help.file.DesktopAppFilesDir] / [io.legado.app.data.BundledDatabaseDriver] /
 *   [io.legado.app.help.book.JvmBookStorage] 构造时经 [io.legado.app.help.file.desktopAppRootDir]
 *   读 `legado.portable.root` 系统属性定位配置根目录
 * - [io.legado.app.model.script.quickjs.QuickJsJsEngine] 首次 eval 经
 *   `com.script.quickjs.loadLegadoQuickJsNative()` 读 `legado.quickjs.lib` 系统属性定位 native 库
 *
 * # 便携模式定位 (KP6+: 编译期 InstallType 控制)
 *
 * 安装类型由 gradle property `legado.installType` (portable|installed|dev) 在编译期
 * 决定 (desktop/build.gradle.kts 生成 InstallType.kt), 不再靠运行时嗅探 runtime/ 目录
 * (用户裁决: 嗅探目录在用户安装到非指定目录时会误判)。
 *
 * portable 模式: 经 `compose.application.resources.dir` 定位 exe 所在目录, 在其同级
 * 创建 `data/` 作为便携配置根 (数据库/配置跟随 exe, 拷贝即迁移, 卸载即清空),
 * 设置 `legado.portable.root` 系统属性供下游读取。
 *
 * installed/dev 模式: 不设置系统属性, 下游 DesktopAppPaths 走 portable.txt 标记检测
 * (便携 zip 内置) / 系统数据目录 (%APPDATA%、XDG_DATA_HOME、Application Support)。
 *
 * 开发期默认 dev (build.gradle.kts 默认值), 保护项目源码树不被污染。
 *
 * # native 库加载
 *
 * 从 `compose.application.resources.dir` (打包后 = `app/{packageName}/`, 含经
 * `copyQuickjsNativeToResources` task 纳入的 `legado_quickjs.dll`) 定位 native 库,
 * 设置 `legado.quickjs.lib` 系统属性, 让 quickjs 模块 Platform.kt 属性1逻辑能 `System.load` 加载。
 *
 * 开发期该目录无 dll (copy task 未触发), 属性不设置, Platform.kt 走候选3
 * (向上递归找 `modules/quickjs/build/libs/jvm/native/`) 加载开发机构建产物。
 */
private fun initDesktopRuntimeEnvironment() {
    val resourcesDir = System.getProperty("compose.application.resources.dir") ?: return
    if (resourcesDir.isEmpty()) return
    val resDirFile = File(resourcesDir)
    if (!resDirFile.isDirectory) return

    // 调试输出: 打印 resourcesDir 实际值, 便于验证 Compose Desktop 行为
    debugLog("[legado-desktop] compose.application.resources.dir = $resourcesDir")
    debugLog("[legado-desktop] resDirFile.parentFile = ${resDirFile.parentFile?.absolutePath}")

    // 1. 便携模式定位 (KP6+: 由编译期 InstallType 控制, 不再嗅探 runtime/ 目录):
    //    resDirFile 指向 app/<packageName>/ (jar + 资源所在目录),
    //    其 parentFile = jpackage package root (exe 所在目录的同级)。
    //    portable 模式: 数据存 exe 同级 dataDir (设置 legado.portable.root 系统属性,
    //      下游 DesktopAppFilesDir/JvmBookStorage 读此属性定位配置根)。
    //    installed/dev 模式: 不设置系统属性, 下游 DesktopAppPaths 走 portable.txt
    //      标记检测 / 系统数据目录。
    if (InstallType.IS_PORTABLE) {
        val exeDir = resDirFile.parentFile?.parentFile ?: resDirFile.parentFile
        val dataDir = File(exeDir, "data")
        dataDir.mkdirs()
        System.setProperty("legado.portable.root", dataDir.absolutePath)
        debugLog("[legado-desktop] portable mode ON (InstallType=portable), dataDir = ${dataDir.absolutePath}")
    } else {
        debugLog(jvmGetString("desktop_install_mode_not_portable", InstallType.TYPE))
    }

    // 2. native 库加载: 从 resourcesDir 找平台对应 native 库
    //    (经 copyQuickjsNativeToResources task 纳入, 与 jar 同级)
    val osName = System.getProperty("os.name").lowercase()
    val libName = when {
        osName.contains("windows") -> "legado_quickjs.dll"
        osName.contains("mac") || osName.contains("darwin") -> "liblegado_quickjs.dylib"
        else -> "liblegado_quickjs.so"
    }
    val libFile = File(resDirFile, libName)
    if (libFile.exists()) {
        System.setProperty("legado.quickjs.lib", libFile.absolutePath)
        debugLog("[legado-desktop] quickjs native lib loaded: ${libFile.absolutePath}")
    } else {
        debugLog("[legado-desktop] quickjs native lib NOT found at ${libFile.absolutePath}")
    }
}
