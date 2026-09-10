package io.legado.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowDecoration
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.sun.jna.Platform
import io.legado.app.api.controller.ImageControllerProviders
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.LocalReadConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.config.ReadConfigProviders
import io.legado.app.help.config.ReadTipConfigShared
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.coroutine.registerJvmDebugState
import io.legado.app.help.image.decodeBytesSampled
import io.legado.app.help.image.registerJvmBookImageLoader
import io.legado.app.help.image.registerReaderImageResolver
import io.legado.app.help.toast.DesktopTrayNotifier
import io.legado.app.help.tts.TtsEngineProvider
import io.legado.app.model.fileBook.BitmapProviders
import io.legado.app.ui.FileAssociationDispatch
import io.legado.app.ui.association.DeepLinkImportHost
import io.legado.app.ui.association.LegadoDeepLink
import io.legado.app.ui.association.LegadoDeepLinkHandler
import io.legado.app.ui.book.audio.AudioPlayPlatformProviders
import io.legado.app.ui.book.audio.SharedAudioPlayPlatformProvider
import io.legado.app.ui.book.info.LocalBlurCoverBgSlot
import io.legado.app.ui.book.info.SharedBlurCoverBgCoil
import io.legado.app.ui.book.manga.MangaReaderScreenModel
import io.legado.app.ui.book.read.ReaderPlatformProviders
import io.legado.app.ui.book.read.page.provider.registerSkiaTextMeasurer
import io.legado.app.ui.book.source.SourceUiEventBridgeHost
import io.legado.app.ui.book.video.VideoPlayPlatformProviders
import io.legado.app.ui.browser.LocalWebViewSlot
import io.legado.app.ui.compose.component.LocalDialogAnchorSize
import io.legado.app.ui.compose.platform.AppKeyRouter
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalOverlayTopInset
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.SharedEventBusProvider
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.reader.ReaderDictWord
import io.legado.app.ui.reader.ReaderImageActionMenu
import io.legado.app.ui.root.AppFontScaleScope
import io.legado.app.ui.root.AppForegroundState
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.LegadoApp
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.ScreenModelStore
import io.legado.desktop.audio.DesktopAppUserModelId
import io.legado.desktop.audio.registerDesktopAudioPlayProviders
import io.legado.desktop.audio.registerDesktopSystemMediaControl
import io.legado.desktop.config.registerDesktopSystemNightModeDetector
import io.legado.desktop.help.DesktopCrashHandler
import io.legado.desktop.help.DesktopUrlProtocol
import io.legado.desktop.help.SingleInstanceGuard
import io.legado.desktop.help.archive.DesktopArchiveCodec
import io.legado.desktop.help.book.DesktopBitmapProvider
import io.legado.desktop.help.http.registerDesktopBackstageWebView
import io.legado.desktop.help.registerDesktopArchiveProvider
import io.legado.desktop.help.registerDesktopScreenInfoProvider
import io.legado.desktop.help.source.registerDesktopVerificationUiProvider
import io.legado.desktop.help.tts.DesktopReadAloudHost
import io.legado.desktop.help.ui.registerDesktopOpenUrlProvider
import io.legado.desktop.model.fileBook.DesktopPdfFile
import io.legado.desktop.model.fileBook.registerDesktopFileBookAccessor
import io.legado.desktop.model.webBook.DesktopImageControllerProvider
import io.legado.desktop.tts.DesktopSystemTtsEngine
import io.legado.desktop.ui.ChromeStripSpacer
import io.legado.desktop.ui.DesktopDialogHost
import io.legado.desktop.ui.DesktopNativeChromeHost
import io.legado.desktop.ui.DesktopPlatformCapabilities
import io.legado.desktop.ui.DesktopPlatformServices
import io.legado.desktop.ui.DesktopTitleBar
import io.legado.desktop.ui.DesktopToastHost
import io.legado.desktop.ui.DesktopToasts
import io.legado.desktop.ui.DesktopWindowChrome
import io.legado.desktop.ui.DesktopWindowChromeNative
import io.legado.desktop.ui.DesktopWindowHandle
import io.legado.desktop.ui.browser.DesktopWebViewSlot
import io.legado.desktop.ui.platform.DesktopMangaReaderPlatform
import io.legado.desktop.ui.platform.DesktopReaderPlatformProvider
import io.legado.desktop.ui.platform.MediampVideoPlayPlatformProvider
import io.legado.desktop.ui.tray.DesktopMediaTray
import io.legado.desktop.ui.tray.DesktopTaskbarMedia
import io.legado.desktop.ui.tray.ReadAloudTrayBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.openani.mediamp.mpv.MPVHandle
import org.openani.mediamp.mpv.MpvMediampPlayer
import java.awt.Desktop
import java.io.File
import javax.swing.SwingUtilities

private const val TAG = "legado-desktop"

/**
 * 桌面端入口 (desktop/jvm 走 CMP 桌面官方 JVM)。
 *
 * 目的: 证明 KMP shared 模块下沉的纯 Kotlin 业务逻辑可在任意 JVM (Win/Linux/macOS) 直接运行,
 * 无 Android 依赖。Compose Multiplatform 桌面窗口展示 shared jvm target 暴露的 API 调用结果,
 * 演示四大能力:
 *
 * 1. MD5 摘要 (commonMain 纯 Kotlin 实现, 无 java.security 依赖)
 * 2. AES/HMAC/摘要 (jvmAndAndroidMain hutool 实现, 桌面端白捡 JVM 兼容)
 * 3. 简繁转换 (jvmAndAndroidMain quick-transfer 实现, 桌面端白捡)
 * 4. Room KMP 数据库 (BundledSQLiteDriver 跨平台 SQLite, 桌面端可跑数据库)
 *
 * 跑法: .\gradlew :desktop:run
 *
 * # 启动性能优化 (三阶段注册)
 * 阶段1: 首屏必需 provider 同步注册 (窗口显示前) — 只注册 BookshelfScreen 渲染依赖的 provider
 * 阶段2: 显示窗口 — 让用户尽快看到界面
 * 阶段3: 后台异步注册非首屏 provider (LaunchedEffect + Dispatchers.Default) — 不阻塞首屏渲染
 */
/**
 * 进程启动参数说明: [startupArgs] 顶层声明已下沉 :desktop-core (DesktopCore.kt, 供
 * DesktopAppRestart 复用), 本文件直接以同包名引用。headless 入口亦经 DesktopCore 共享该 API。
 */

/** 重启等待标记前缀: 新进程凭它等到旧进程退出后再抢单实例锁。 */
private const val RESTART_WAIT_PREFIX = "--legado-restart-wait="

fun main(args: Array<String>) {
    // 打栈开关: 对齐 Android BuildConfig.DEBUG 语义, 仅 debug 打栈。
    // build.gradle.kts 的 run 任务注入 -Dlegado.debug=true, 打包产物不注入 = 静默。
    registerJvmDebugState(System.getProperty("legado.debug")?.toBoolean() == true)
    // 2026-08-15 教训: bcprov 已移除 (见 desktop/build.gradle.kts 注释) —— BC 类在 classpath
    // 会让 hutool 的 RSA Cipher 走 BC (getBlockSize=127 触发分段加密), 网易云 weapi
    // encSecKey 错误全站 200 空体。PKCS7Padding 由 SymmetricCryptoAndroid 归一化解决,
    // 不再需要 JCE 补丁 provider (DesktopCryptoProvider.kt 已删除)。
    // 视频渲染: open-ani/mediamp (mediamp-mpv) 后端。Windows 走 Skiko Direct3D 默认渲染
    // (mpv D3D11 → 共享纹理 → Skia D3D12), macOS 默认 Metal, Linux 默认 OpenGL —— 均
    // 为各自平台默认值, 无需 (也不应) 强制 skiko.renderApi。
    // KP6: 便携模式检测 + native 库加载。必须最先执行 (早于 SingleInstanceGuard):
    // 它设置的 legado.portable.root 决定 desktopAppRootDir() 的解析结果, 而后者进程内 lazy
    // 只解析一次 —— 单实例守卫要在数据目录写 instance.lock, 提前读会把便携模式的根目录定位歪。
    initDesktopRuntimeEnvironment()
    // 全局崩溃日志 (对照 app 端 CrashHandler): 必须紧跟 initDesktopRuntimeEnvironment ——
    // 落盘目录依赖它设的 legado.portable.root, 提前装会把便携模式的日志写到系统缓存目录去。
    DesktopCrashHandler.install()
    // 视频: mediamp mpv natives 后台预解包 (独立协程, 早于窗口创建): 首次创建播放器时
    // 同步解包 ~20MB DLL + System.load 会硬卡顿, 启动期后台完成解包+加载,
    // 之后打开视频零等待 (prepareLibraries 幂等, 内部有锁, 与首次播放时的同步路径互斥安全)
    Coroutine.async {
        runCatching { MpvMediampPlayer.prepareLibraries() }
            .onFailure { AppLog.put("mediamp mpv natives 预解包失败: ${it.message}", it) }
    }
    // mpv 日志接入 AppLog: 播放失败时 mediamp 只给出 mpv_error 码 (如 -13
    // MPV_ERROR_LOADING_FAILED), 拿不到 mpv 自己那行原因 (HTTP 状态 / Failed to recognize
    // file format / 解码器缺失)。sink 汇总 mpv 事件、JNI 层与 mediamp Kotlin 三处日志,
    // 只收 error 级 (mpv 的级数越小越严重), 免得把 verbose 灌进日志界面。
    runCatching {
        MPVHandle.setLogHandler { msg ->
            if (msg.isError) AppLog.put("mpv: $msg")
        }
    }.onFailure { AppLog.put("mpv 日志接入失败: ${it.message}", it) }
    // 重启场景: 先等旧进程退出 (旧进程退出时 shutdown hook 才释放单实例锁),
    // 避免新进程把启动参数转发给正在退出的旧进程后自杀 (表现为“应用直接消失”)
    val effectiveArgs = waitForOldProcessIfRestart(args)
    startupArgs = effectiveArgs
    // 单实例守卫: 已有实例存活时把 args 转发过去 + 前置其窗口, 本进程 exitProcess(0) 不返回
    // (对照 app 端 AssociationActivity singleTask)。必须在 handleDeepLinkArgs 与任何
    // provider/数据库初始化之前, 否则二次启动进程会先碰同一个 SQLite 库再退出。
    SingleInstanceGuard.ensureSingleInstance(effectiveArgs)
    // legado:// deep link 启动参数处理 (对照 app 端 AssociationActivity intent-filter):
    // 系统级 URL protocol 注册: Windows/Linux 运行时幂等自注册 ([DesktopUrlProtocol]),
    // macOS 打包期 Info.plist CFBundleURLTypes (见 build.gradle.kts
    // nativeDistributions.macOS.infoPlist), 详见 handleDeepLinkArgs KDoc
    DesktopUrlProtocol.ensureRegisteredAsync()
    handleDeepLinkArgs(args)
    // 文件关联 (双击 .epub/.txt/.pdf/.cbz): 系统冷启动时把文件路径当 argv 送进来
    // (打包期注册见 build.gradle.kts nativeDistributions.fileAssociation)
    offerAssociationFiles(
        effectiveArgs.filter { !LegadoDeepLink.isDeepLink(it) && File(it).isFile }
    )
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
    // macOS: 关联文件同样经 Apple Event (OpenFilesHandler) 送达; Windows/Linux 走 argv,
    // APP_OPEN_FILE isSupported=false 静默跳过
    runCatching {
        if (Desktop.isDesktopSupported() &&
            Desktop.getDesktop().isSupported(Desktop.Action.APP_OPEN_FILE)
        ) {
            Desktop.getDesktop().setOpenFileHandler { event ->
                offerAssociationFiles(event.files.map { it.absolutePath })
            }
        }
    }
    runDesktopApp()
}

/**
 * 解析启动参数中的 legado://`/`yuedu:// deep link, 经 [LegadoDeepLinkHandler] 记录,
 * 待 [DeepLinkImportHost] 在窗口内消费弹导入对话框。
 *
 * # 各 OS 系统级 URL protocol 注册 (让浏览器点 `legado://` 链接能唤起本应用)
 *
 * - **Windows**: 运行时注册 `HKCU\Software\Classes\<scheme>` (空字符串值 `URL Protocol` +
 *   `shell\open\command` 默认值 `"<exe>" "%1"`), 见 [DesktopUrlProtocol];
 *   jpackage MSI / 便携 zip 产物启动时自动完成, per-user 无需管理员权限。
 * - **Linux**: 运行时写 `~/.local/share/applications/legado.desktop`
 *   (`MimeType=x-scheme-handler/legado;x-scheme-handler/yuedu;` + `Exec="<launcher>" %u`)
 *   并 `xdg-mime default` 设为默认 handler, 见 [DesktopUrlProtocol]。
 * - **macOS**: 打包期把 `CFBundleURLTypes` (CFBundleURLSchemes=[legado,yuedu]) 注入
 *   app bundle Info.plist (desktop/build.gradle.kts nativeDistributions.macOS.infoPlist);
 *   运行时回调走 Apple Event, 由 main() 里的 Desktop.setOpenURIHandler 承接 (非 argv)。
 */
/**
 * 重启场景: 新进程启动参数里带 `--legado-restart-wait=<pid>` 时, 等到旧进程退出再继续。
 *
 * 旧进程 [io.legado.desktop.help.DesktopRegexErrorHandler.restartApp] 会先拉起本进程再
 * `exitProcess(0)`; 单实例锁 (instance.lock) 由旧进程的 shutdown hook 在退出时释放, 故新进程
 * 必须先等旧进程死掉, 否则 [SingleInstanceGuard.ensureSingleInstance] 会把参数转发给正在退出的
 * 旧进程后自杀。最多等 [RESTART_WAIT_TIMEOUT_MS], 超时后继续 (残留进程按陈旧 lock 接管)。
 */
private fun waitForOldProcessIfRestart(args: Array<String>): Array<String> {
    val waitArg = args.firstOrNull { it.startsWith(RESTART_WAIT_PREFIX) } ?: return args
    val rest = args.filterNot { it.startsWith(RESTART_WAIT_PREFIX) }.toTypedArray()
    val pid = waitArg.substringAfter('=').toLongOrNull() ?: return rest
    val old = runCatching { ProcessHandle.of(pid) }.getOrNull()?.orElse(null) ?: return rest
    if (old.isAlive) {
        val deadline = System.currentTimeMillis() + RESTART_WAIT_TIMEOUT_MS
        while (old.isAlive && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
    }
    return rest
}

private const val RESTART_WAIT_TIMEOUT_MS = 30_000L

private fun handleDeepLinkArgs(args: Array<String>) {
    val url = args.firstOrNull { LegadoDeepLink.isDeepLink(it) } ?: return
    if (!LegadoDeepLinkHandler.handle(url)) {
        AppLog.put("deep link 解析失败 (缺 src 参数): $url", tag = TAG)
    }
}

/**
 * 文件关联待分发队列 (对照 [LegadoDeepLinkHandler.pending] 的投递语义)。
 *
 * 三个投递方都可能早于首帧: argv 冷启动、macOS Apple Event、单实例转发;
 * [FileAssociationDispatch] 打开书籍要 navigator, 而它到首帧组合才注册, 故一律先入队,
 * 由窗口内 LaunchedEffect 订阅后分发。
 */
internal val pendingAssociationFiles = MutableStateFlow<List<String>>(emptyList())

/** 投递关联文件路径 (空列表直接忽略); 线程安全, 各投递方可在任意线程调用。 */
internal fun offerAssociationFiles(paths: List<String>) {
    if (paths.isEmpty()) return
    pendingAssociationFiles.update { it + paths }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
private fun runDesktopApp() = application {
    // ==================== 阶段1: 首屏必需 provider 同步注册 (窗口显示前) ====================
    // 目标: 只注册首屏 (BookshelfScreen) 渲染依赖的 provider, 让窗口尽快显示。
    // 其余 provider 延迟到窗口显示后异步注册 (见阶段3 registerSecondaryProviders)。

    // KP6: 便携模式检测 + native 库加载已提前到 main() 首行执行 (单实例守卫要先读数据目录),
    // 此处不再重复调用; 其结果 (legado.portable.root / legado.quickjs.lib 系统属性) 对
    // 下方所有 provider 注册依然有效。
    //
    // 阶段1 核心子集 (无 UI 依赖的 provider 注册) 已下沉 :desktop-core 的
    // DesktopCore.registerCoreProviders() —— 与 headless 入口共用同一注册序列, 保证两种入口
    // 的数据/配置环境等价。包含: AppLog/AppString/AndroidId/Toaster/NotificationProgress/
    // UpdateBookCallback/config+语言/AppUpdate/AppFilesDir/HTTP+jsoup/
    // DataStorage+BookImageStorage/HttpTTS 播放器工厂/JS 引擎/DefaultDataResource/数据库/
    // BookStorage/AppDb/BookHelp/ReadBookPlatform/CoverStorage。
    // remember: 只在首组合执行一次 (原实现非 remember 的注册函数本就幂等, 收敛后行为等价);
    // 返回值 desktopReadBookConfig 供阶段2 LocalReadConfigProviders 注入 (与全局同实例)。
    val desktopReadBookConfig = remember {
        registerDesktopSystemNightModeDetector()
        val config = DesktopCore.registerCoreProviders()
        // Compose UI 类型的 JVM 图片加载器 (SingletonImageLoader + BookImageLoaders, 依赖 ImageBitmap, 仅桌面 GUI 需要)
        registerJvmBookImageLoader()
        config
    }
    // ===== 以下为阶段1 的 UI 绑定注册 (依赖 AWT/Compose/JNA, 留在 :desktop) =====
    // Windows: 设置进程级 AppUserModelID + 保证开始菜单快捷方式身份注册 (SMTC 媒体卡
    // 应用名来源; :desktop:run/java -jar 无安装注册时按官方文档自建快捷方式)。
    // 须在 AppString provider 注册之后 (快捷方式文件名取 app_name 显示名),
    // 且必须在首窗口创建前 (MSDN: SetCurrentProcessExplicitAppUserModelID 须先于 UI)。
    DesktopAppUserModelId.ensureProcessAppId()
    // 注册桌面端 ScreenInfoProvider (Toolkit.getDefaultToolkit().screenSize),
    // 供 shared commonMain 经 ScreenInfoProviders.get() 读屏幕尺寸; 无依赖, 同步注册
    registerDesktopScreenInfoProvider()
    // Toaster 链路 (登录对话框"没有请求头！"等) 统一收口到主窗口 UI toast
    // (DesktopToasts → DesktopToastHost), 不再只依赖托盘气泡 (Windows 通知设置
    // 会静默拦截 TrayIcon.displayMessage, 曾表现为 toast 无反应)
    // (Toaster/NotificationProgress provider 本体注册在上方 DesktopCore.registerCoreProviders)
    DesktopTrayNotifier.uiSender = { msg ->
        DesktopToasts.show(msg, false)
        true
    }
    // 注册桌面端 PlatformCapabilities (供 shared LegadoApp 经 PlatformCapabilityProviders.get() 取能力)
    PlatformCapabilityProviders.register(DesktopPlatformCapabilities)
    // 注册桌面端 PlatformServices (必须早于 LegadoApp: shared 侧一律 PlatformServiceProviders.get(),
    // 未注册即 error, 不再有静默回退分支)
    // windowHandle 在 Window 组装后由 DisposableEffect 注入 AWT 窗口, 供全屏切换
    val windowHandle = remember { DesktopWindowHandle() }
    PlatformServiceProviders.register(
        DesktopPlatformServices(
            DesktopPlatformCapabilities,
            windowHandle
        )
    )
    // 朗读引擎 (Windows SAPI / Linux espeak / macOS say): DesktopSystemTtsEngine 依赖 JNA
    // (WindowsSapiTtsBackend) 留在 :desktop 注册; HttpTTS 播放器工厂 (DesktopHttpTtsPlayer,
    // 纯 JVM) 已随上方 DesktopCore.registerCoreProviders 注册, headless 亦具备该能力
    val desktopTtsEngine = remember { DesktopSystemTtsEngine() }
    TtsEngineProvider.register(desktopTtsEngine)
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
            timeMinute = { DesktopReadAloudHost.timeMinute },
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
    val desktopReaderProvider = remember { DesktopReaderPlatformProvider() }
    ReaderPlatformProviders.register(desktopReaderProvider)
    // 注: ReadBookShared 平台钩子 (registerDesktopReadBookPlatform) 无 UI 依赖,
    // 已随上方 DesktopCore.registerCoreProviders 注册 (headless 共用)
    // 阅读排版度量: 注册 Skia 真实字形度量, 取代 SimpleTextMeasurer 等宽近似
    // (字形来源与 PageContentCanvas 的 loadReaderFontFamily / FontFamily.Default 同源)
    registerSkiaTextMeasurer()
    // 阅读页内嵌图片 (PDF 单图页 / EPUB 插图): 排版取尺寸 + 绘制取位图
    registerReaderImageResolver()
    AudioPlayPlatformProviders.register(SharedAudioPlayPlatformProvider)
    MangaReaderScreenModel.Providers.register(DesktopMangaReaderPlatform)
    VideoPlayPlatformProviders.register(MediampVideoPlayPlatformProvider(windowHandle))

    // ==================== 阶段2: 显示窗口 ====================
    // 启动闪屏 (AWT JWindow, 无边框, 在主窗口创建前显示)
    val themeStoreProviderSplash = remember { DesktopThemeStoreProvider() }
    val splashScreen =
        remember { DesktopSplashScreen(themeStoreProviderSplash) }
    val splashDuration = remember { splashScreen.show() }
    val appName = rememberString("app_name")
    // 窗口状态记忆: 读"上次是否最大化" + 普通状态下的位置尺寸 (恢复规则用户拍板 2026-08-18):
    // - 上次最大化 → 完全不读 W/H/XY, 以 CMP 默认 800x600 创建再最大化, 于是"向下还原"
    //   回到默认小窗口, 而不是回到一个看起来满屏的坐标;
    // - 否则有记录按记录恢复, 无记录用 CMP 默认。
    val prefProvider = PreferenceProviders.get()
    val savedMaximized = prefProvider.getBoolean(PreferKey.windowMaximized, false)
    val restoreBounds = !savedMaximized && prefProvider.contains(PreferKey.windowWidth)
    // 尺寸/位置恢复无跳变: CMP 的 update 块在窗口显示前就 setSizeSafely/setPositionSafely
    // (pack + setSize/setLocation), isVisible=true 由 AwtWindow 另一个效果排到后续 EDT 任务,
    // 所以窗口第一次出现即目标尺寸位置。AWT 坐标在 Windows 缩放下是逻辑单位, 与 dp 同尺
    // (CMP 自己也是 width.dp 直转), 两侧都不乘 density。
    val windowState = if (restoreBounds) {
        val savedW = prefProvider.getInt(PreferKey.windowWidth, 800)
        val savedH = prefProvider.getInt(PreferKey.windowHeight, 600)
        // 屏幕边界修正: 显示器分辨率/布局变化后旧坐标可能越界, 保证窗口至少有部分留在屏幕内
        // 便于拖回 (不强制完整可见, 多屏场景留有余地)
        val screenSize = java.awt.Toolkit.getDefaultToolkit().screenSize
        val minVisible = 80
        val savedX = prefProvider.getInt(PreferKey.windowX, 0)
            .coerceIn(-savedW + minVisible, screenSize.width - minVisible)
        val savedY = prefProvider.getInt(PreferKey.windowY, 0)
            .coerceIn(-savedH + minVisible, screenSize.height - minVisible)
        rememberWindowState(
            position = WindowPosition.Absolute(savedX.dp, savedY.dp),
            size = DpSize(savedW.dp, savedH.dp),
        )
    } else {
        rememberWindowState()
    }
    // 窗口可见性: 先以 visible=false 创建, 尺寸/最大化都在显示前应用完再置 true,
    // 窗口第一次出现即最终状态 (见下方 DisposableEffect)
    var windowVisible by remember { mutableStateOf(false) }
    // classpath 资源加载: 手动 Skia 解码 + BitmapPainter
    val iconPainter = remember {
        runCatching {
            Thread.currentThread().contextClassLoader
                ?.getResourceAsStream("icon.png")?.use { decodeBytesSampled(it.readBytes(), 0) }
                ?.let { BitmapPainter(it) }
        }.getOrNull()
    }
    // AppNavigator: 零薄壳导航唯一状态源 (替代旧 DesktopApp 的 20+ 并行状态字段)
    val navigator = remember { AppNavigator(AppRoute.Main()) }
    val screenModelStore = remember { ScreenModelStore() }
    // Compose 未捕获异常兜底: CMP 默认工厂 (DefaultWindowExceptionHandlerFactory) 弹的是
    // 模态 JOptionPane —— 模态窗口会禁用主窗口输入却不影响重绘, 又常被置顶的 Dialog 图层
    // 或全屏窗口遮住, 表现就是"窗口还能 resize 重排, 键鼠全部失灵"。改为只记日志不弹窗。
    // EDT 上的异常不经 Thread.setDefaultUncaughtExceptionHandler, 必须在这里单独落盘,
    // 否则"关于 → 崩溃日志"看不到任何界面侧崩溃。
    CompositionLocalProvider(
        LocalWindowExceptionHandlerFactory provides WindowExceptionHandlerFactory {
            WindowExceptionHandler { DesktopCrashHandler.handleCrash(it, "Compose 未捕获异常") }
        }
    ) {
    Window(
        onCloseRequest = ::exitApplication,
        visible = windowVisible,
        // 按键由 shared AppKeyRouter 统一分发 (全屏 Esc 退全屏 → 统一返回链 → F5 刷新 →
        // 快捷键栈捕获/冒泡两阶段), desktop Window 不再做任何业务判断。
        //
        // 两个钩子必须分开接, 语义见 AppKeyRouter 的 KDoc (与 BackKeyHandler 的 Modifier 一致):
        //  - onPreviewKeyEvent = 捕获阶段: 抢占式快捷键 (媒体键/带修饰组合) **先于 Compose 焦点链**,
        //    否则聚焦的按钮会先吃掉空格 —— 表现为"空格触发按钮而不是播放/暂停" (用户实测)。
        //  - onKeyEvent = 冒泡阶段: 非抢占快捷键 (无修饰方向键/翻页键) 让聚焦输入框先消费,
        //    这样搜索框里打空格不会被当成播放/暂停。
        // 曾经两阶段都塞在 onKeyEvent 里 (dispatchPlatform), 于是捕获阶段实际发生在焦点之后, 抢占失效。
        onPreviewKeyEvent = { event -> AppKeyRouter.dispatchCapture(event) },
        onKeyEvent = { event -> AppKeyRouter.dispatchBubble(event) },
        title = appName,
        icon = iconPainter,
        state = windowState,
        // 控制栏方案 (用户拍板 2026-08 终版):
        // - Windows: 保持系统装饰 (decorated) + JBR CustomTitleBar —— 客户区顶到窗口顶端,
        //   标题栏整条 Compose 绘制 (深浅色/⋯菜单保留), 系统三键由 JBR 原生画在右上角
        //   (controls.dark 跟随), 拖拽/双击最大化/贴靠/Snap Layouts 全部原生。
        //   (探针实证: 纯 Win32 拦截无法实现同效果, JBR 原生机制是唯一可行路径,
        //   对照 JetBrains IDE/jewel/ab-download-manager 同款方案)
        // - Linux: undecorated 自绘控制栏 (保持原有实现不动)
        // - macOS: 原生红绿灯标题栏 (SystemDefault)
        // 真全屏 (无边框) 由 DesktopFullscreenController 在 Win32 层临时去 WS_CAPTION,
        // 与窗口是否装饰无关, 退出全屏自动还原; Windows 下同步解除/恢复 JBR 自定义标题栏
        // (见下方 LaunchedEffect)。
        // 置顶开关 (控制栏菜单切换, DesktopWindowChrome 单一状态源); SwingWindow
        // updater 同步 AWT setAlwaysOnTop
        decoration = if (Platform.isLinux()) {
            WindowDecoration.Undecorated()
        } else {
            WindowDecoration.SystemDefault
        },
        alwaysOnTop = DesktopWindowChrome.alwaysOnTop,
    ) {
        // 单实例守卫绑定主窗口: 二次启动转发到达时前置本窗口 (取消最小化 + toFront + 请求焦点);
        // DisposableEffect 保证窗口销毁后解绑, 不让守卫持有已 dispose 的 AWT Window
        // 同步注入 AWT 窗口句柄到 DesktopWindowHandle, 供 DesktopWindowController 切换全屏;
        // 同时注入任务栏媒体 (缩略图按钮/进度条) 的 HWND (窗口重建时自动重挂)
        DisposableEffect(window) {
            // 主窗口最小尺寸 (用户拍板 2026-08-13): 极窄窗口曾致 JBR 客户区布局锁死
            // (拉窄再拉宽后内容区不复原), 直接限制最小宽 300dp/高 600dp 从根上规避。
            // AWT 尺寸在 Windows 缩放下是逻辑单位, 与 dp 同尺 (CMP 自己也是 width.dp 直转),
            // 不乘 density —— 乘了会把最小尺寸抬成 375x750dp
            window.minimumSize = java.awt.Dimension(300, 600)
            // 最大化恢复: 显示前直连 setExtendedState (native WS_MAXIMIZE 样式在窗口首次显示
            // 时才应用, 所以窗口一出现就是最大化, 无"先小窗口再最大化"的过程)。
            // 必须排到下一个 EDT 任务: 本效果跑在 CMP update 块的 setSizeSafely → pack() 里
            // (窗口内容组合推迟到组件 attach), 而同一次 update 随后的 placement 分支会执行
            // window.placement = Floating → extendedState and MAXIMIZED_BOTH.inv(), 当场设会
            // 被它抹掉; 而 isVisible=true 要经"快照通知→重组→apply→launch"至少 3 个任务才排队,
            // 所以本任务夹在两者之间。
            if (savedMaximized) {
                SwingUtilities.invokeLater {
                    window.extendedState =
                        window.extendedState or javax.swing.JFrame.MAXIMIZED_BOTH
                }
            }
            windowVisible = true
            // 闪屏关闭时机: 主窗口可见即关, 最迟 splashDuration 关闭 (取先到者)
            if (splashDuration > 0) {
                var splashClosed = false
                fun closeSplash() {
                    if (!splashClosed) {
                        splashClosed = true
                        SwingUtilities.invokeLater { splashScreen.close() }
                    }
                }
                // 兜底定时器: 主窗口首帧渲染过慢/异常时不至于无限挂着
                Coroutine.async {
                    val remain = splashDuration - 200  // 留 200ms 让主窗口首帧渲染
                    if (remain > 0) kotlinx.coroutines.delay(remain)
                    closeSplash()
                }
                // 主窗口可见即关 (取先到者): AWT componentShown 在窗口显示时触发
                if (window.isVisible) {
                    closeSplash()
                } else {
                    window.addComponentListener(object : java.awt.event.ComponentAdapter() {
                        override fun componentShown(e: java.awt.event.ComponentEvent) {
                            closeSplash()
                            window.removeComponentListener(this)
                        }
                    })
                }
            }
            windowHandle.window = window
            SingleInstanceGuard.bindWindow(window)
            DesktopTaskbarMedia.attach(window)
            // app 前后台唯一置位点: 主窗口失活=其他应用在前台 (对应原版 Activity.onPause 语义;
            // 进程内对话框/菜单同属本 ComposeWindow, 不触发失活)。阅读/漫画/视频页经
            // RouteActiveEffect 消费, 各页不再自挂 AWTEventListener
            val foregroundListener = object : java.awt.event.WindowAdapter() {
                override fun windowActivated(e: java.awt.event.WindowEvent) =
                    AppForegroundState.set(true)

                override fun windowDeactivated(e: java.awt.event.WindowEvent) =
                    AppForegroundState.set(false)
            }
            window.addWindowListener(foregroundListener)
            // Windows 原生控制条 (legado_wndchrome) 的挂载在 DesktopNativeChromeHost 里做 ——
            // 组合期窗口还没 realize (isDisplayable=false), 必须等 realize 后再挂;
            // 这里只保留 onDispose 的显式 detach 兜底 (native 侧收 WM_NCDESTROY 也会自解挂, 幂等)。
            // 全屏 Esc 退全屏策略注册进 shared AppKeyRouter (消费返回 true, 优先于统一返回链);
            // 路由的 dispatchCapture 在全屏 Esc 分支最先询问本策略, 与旧 Window 层判断等价
            AppKeyRouter.registerFullscreenEsc {
                if (DesktopWindowChrome.fullscreen) {
                    PlatformServiceProviders.get().window.setFullscreen(false)
                    true
                } else {
                    false
                }
            }
            onDispose {
                // 窗口状态记忆落盘: 最大化标志直读 AWT 真值 (Frame.state 是普通字段, dispose
                // 不会改; 不依赖 CMP placement 回写, 后者经 wndchrome 最大化时可能漏报);
                // 尺寸/位置只在普通状态下写, 最大化/真全屏关闭时保留上次记录不覆盖
                val p = PreferenceProviders.get()
                val maximized = (window.extendedState and javax.swing.JFrame.MAXIMIZED_BOTH) != 0
                p.putBoolean(PreferKey.windowMaximized, maximized)
                if (!maximized && !DesktopWindowChrome.fullscreen) {
                    p.putInt(PreferKey.windowWidth, window.width)
                    p.putInt(PreferKey.windowHeight, window.height)
                    p.putInt(PreferKey.windowX, window.x)
                    p.putInt(PreferKey.windowY, window.y)
                }
                DesktopWindowChromeNative.detach()
                window.removeWindowListener(foregroundListener)
                windowHandle.window = null
                SingleInstanceGuard.bindWindow(null)
                AppKeyRouter.registerFullscreenEsc(null)
            }
        }
        // ==================== 阶段3: 后台异步注册非首屏 provider ====================
        // 用 LaunchedEffect 在窗口显示后立即启动协程注册, 不阻塞首屏渲染
        // 用 withContext(Dispatchers.Default) 在后台线程执行, 避免阻塞 UI 线程
        LaunchedEffect(Unit) {
            registerSecondaryProviders()
        }
        // 文件关联分发: 队列在 main() 就可能有值 (argv 冷启动), 这里等首帧组合完成
        // (navigator 已注册) 再消费; 解压/读文件是阻塞 IO, 切 IO 线程
        LaunchedEffect(Unit) {
            pendingAssociationFiles.collect { queued ->
                if (queued.isEmpty()) return@collect
                val files = pendingAssociationFiles.getAndUpdate { emptyList() }
                withContext(Dispatchers.IO) {
                    files.forEach { FileAssociationDispatch.dispatch(it) }
                }
            }
        }
        // 注入 4 个 DesktopXxxProvider, 供 commonMain AppTheme 通过 LocalXxx 取依赖
        val themeStoreProvider = remember { DesktopThemeStoreProvider() }
        val appConfigProvider = remember { DesktopAppConfigProvider() }
        val eventBusProvider = remember { SharedEventBusProvider() }
        // 阅读器注入: ReaderRoute/ReaderDrawStyle/PageViewComposable 消费, 缺省值是 error()
        // —— 未注入时打开阅读器即抛异常, 被 DesktopCoroutineExceptionHandler 吞掉后表现为输入冻结
        val readConfigProviders = remember {
            object : ReadConfigProviders {
                override val readBookConfig = desktopReadBookConfig
                override val readTipConfig = ReadTipConfigShared(desktopReadBookConfig)
            }
        }
        // 对话框尺寸锚点: 主窗口尺寸 (场景根处读取, 非对话框层; 随 resize 自动重组刷新)
        val dialogAnchor = LocalWindowInfo.current.containerSize
        // 覆盖物 (菜单/划词条/补全条) 的顶部安全区 = 窗口控制条高度: Windows 的控制条在
        // z-order 恒高于 Compose 画布的 native 子窗口里, Linux 自绘条也不该被菜单压住;
        // macOS 原生标题栏不占客户区, 真全屏时控制条隐藏 ⇒ 均为 0
        val overlayTopInset = if (Platform.isMac() || DesktopWindowChrome.fullscreen) {
            0.dp
        } else {
            AppTheme.DesignTokens.viewHeightLarge
        }
        CompositionLocalProvider(
            LocalDialogAnchorSize provides
                (if (dialogAnchor.width > 0) IntSize(
                    dialogAnchor.width,
                    dialogAnchor.height
                ) else null),
            LocalOverlayTopInset provides overlayTopInset,
            LocalThemeStoreProvider provides themeStoreProvider,
            LocalAppConfigProvider provides appConfigProvider,
            LocalEventBusProvider provides eventBusProvider,
            LocalReadConfigProviders provides readConfigProviders,
            LocalWebViewSlot provides { config, modifier, callbacks ->
                DesktopWebViewSlot(config, modifier, callbacks)
            },
            // 注入 Coil3 模糊封面背景到 shared 详情页路由, 覆盖 LocalBlurCoverBgSlot 兜底
            LocalBlurCoverBgSlot provides { book, coverTick, inBookshelf, isEInkMode, modifier, land ->
                SharedBlurCoverBgCoil(book, coverTick, inBookshelf, isEInkMode, modifier, land)
            },
        ) {
            // 字体缩放: 把"界面设置→字体大小"档位接到 LocalDensity (安卓由 AppContextWrapper.wrap
            // 写进 Configuration.fontScale 生效, 不需要这一层)。未设档位时不覆写, 保留平台自身
            // fontScale (iOS 跟系统 Dynamic Type, 桌面/鸿蒙恒 1) —— 语义对照原版 getFontScale
            AppFontScaleScope {
                AppTheme {

                    // 书源 JS 弹窗事件桥宿主: 订阅 FlowBus(SOURCE_UI_REQUEST) 弹 Compose Dialog
                    // (四端同一份, 实现见 shared/sharedUiMain 的 SourceUiEventBridgeHost)
                    SourceUiEventBridgeHost()
                    // 桌面端命令式对话框宿主: PlatformCapabilities 的同步方法经 DesktopDialogs 推请求
                    DesktopDialogHost()
                    // 桌面端 Toast 宿主: JS java.toast / Toaster 链路统一收口到窗口内 UI toast
                    // (Popup 呈现: 独立合成层不被 LegadoApp 覆盖, 相对主窗口底部定位)
                    DesktopToastHost()
                    // 阅读页长按文字选择对话框宿主 (对照 app 端 MainActivity.readerSelection 分支)
                    desktopReaderProvider.TextSelectionHost()
                    // 阅读页图片长按菜单宿主 (四端同一份自绘浮动菜单, 见 shared ReaderImageActionMenu)
                    ReaderImageActionMenu.Host()
                    // 阅读页查词对话框宿主 (四端同一份, 见 shared ReaderDictWord)
                    ReaderDictWord.Host()
                    // legado:// deep link 导入对话框宿主: 消费 main(args)/OpenURIHandler 经
                    // LegadoDeepLinkHandler 记录的待导入请求 (对照 app 端 AssociationActivity 分发)
                    DeepLinkImportHost()
                    // 窗口控制栏三分支: macOS 原生红绿灯 / Windows 自研 native 控制条
                    // (legado_wndchrome, 客户区顶到窗口顶 + 鼠标穿透 layered 子窗口自绘整条,
                    //  拖拽/双击最大化/贴靠/Snap Layouts 由 WM_NCHITTEST 返回值换取) /
                    // Linux 自绘全功能控制栏 (DesktopTitleBar)。
                    // 全屏时 Esc 优先退出全屏 (用户拍板 2026-08): 由 shared AppKeyRouter 的
                    // fullscreenEsc 策略 (上方 DisposableEffect(window) 注册) 在统一返回链前处理
                    if (Platform.isMac()) {
                        // macOS: 纯系统标题栏 (原生红绿灯), 深浅色/设置走设置页 (原版观感)
                        LegadoApp(
                            navigator = navigator,
                            screenModelStore = screenModelStore,
                        )
                    } else {
                        // Windows: 控制条整条由 native (legado_wndchrome) 画在鼠标穿透的 layered
                        //   子窗口里, 命中测试全在 native WndProc —— Compose 侧只留等高空位 +
                        //   DesktopNativeChromeHost 推主题色/标题/图标并承接按键回调。
                        // Linux: 保持自绘全功能控制栏 (DesktopTitleBar) 不动。
                        if (Platform.isWindows()) {
                            DesktopNativeChromeHost(
                                appName = appName,
                                window = window,
                                themeStore = themeStoreProvider,
                                navigator = navigator,
                            )
                        }
                        Column(Modifier.fillMaxSize()) {
                            if (!DesktopWindowChrome.fullscreen) {
                                if (Platform.isWindows()) {
                                    // native 控制条的占位: 必须显式涂同色底, 见 ChromeStripSpacer
                                    ChromeStripSpacer()
                                } else {
                                    DesktopTitleBar(
                                        appName = appName,
                                        icon = iconPainter,
                                        window = window,
                                        windowState = windowState,
                                        themeStore = themeStoreProvider,
                                        navigator = navigator,
                                        onCloseRequest = ::exitApplication,
                                    )
                                }
                            }
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                LegadoApp(
                                    navigator = navigator,
                                    screenModelStore = screenModelStore,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

/**
 * 后台异步注册非首屏必需的 provider (启动性能优化)。
 *
 * 首屏 (BookshelfScreen) 只依赖 AppDbProviders + BookHelpProviders + BookStorageProviders
 * (已在阶段1同步注册), 其余 provider 在窗口显示后用 [LaunchedEffect] + [withContext]
 * 在后台线程顺序注册, 保持原依赖关系:
 * - registerDesktopAudioPlayProviders 依赖 SourceHelpAccessors + WebBookProviders + JsEngines + OkHttpClientProviders
 *   (必须在它们之后注册)
 *
 * 无 UI 核心子集已下沉 :desktop-core 的 DesktopCore.registerSecondaryCoreProviders()
 * (原 0/0.5/1/4/5(zip)/7/8/8b/9/10/10b(正则)/11/11b(UserAgent)/13b 步, 顺序保持),
 * 本函数只保留 UI 绑定子集 + 尾部启动任务调度, headless 入口复用核心子集。
 */
private suspend fun registerSecondaryProviders() {
    withContext(Dispatchers.Default) {
        // ===== 核心子集 (无 UI 依赖): 见 DesktopCore.registerSecondaryCoreProviders 注释 =====
        DesktopCore.registerSecondaryCoreProviders()
        // ===== UI 绑定子集 (依赖 Compose/AWT/JNA/skia/mediamp, 留在 :desktop) =====
        // 原 3. BackstageWebView (内嵌浏览器引擎: Windows 走系统自带 WebView2 Runtime;
        //    引擎缺失时 create 仍抛 UnsupportedOperationException 由调用方 runCatching 回退 HTTP)
        registerDesktopBackstageWebView()
        // 原 5. CbzFile 位图 provider (skia; ZipFileWrapperFactory 已在核心子集注册)
        BitmapProviders.register(DesktopBitmapProvider)
        // 原 6. EpubFile 相关 (压缩/PDF 能力注入: DesktopArchiveCodec + DesktopPdfFile 均留 :desktop)
        registerDesktopFileBookAccessor(DesktopArchiveCodec, DesktopPdfFile)
        // 原 11. Web 服务封面/插图 provider: 字节流实现在 desktop-core;
        //    未注册时 BookController.getCover/getImg 抛 IllegalStateException
        ImageControllerProviders.register(DesktopImageControllerProvider())
        // 原 11b. OpenUrl provider (打开确认框走 DesktopDialogs, 无 UI 无法确认)
        registerDesktopOpenUrlProvider()
        // 原 11c. 书源验证 UI provider (图片验证码走 Swing 输入框, 网页验证给明确报错)
        //      依赖 OkHttpClientProviders (阶段1, 拉验证码图片) + Toasters (已注册);
        //      未注册时 JS 触发验证会 IllegalStateException 裸抛
        registerDesktopVerificationUiProvider()
        // 原 13. AudioPlay (依赖 AppDbProviders + BookHelpProviders + SourceHelpAccessors + WebBookProviders
        //     + JsEngines + OkHttpClientProviders, 必须最后注册)
        registerDesktopAudioPlayProviders()
        // 原 13a. 系统媒体控制 (SMTC 卡片写入端 + 朗读宿主, 判定见 shared SystemMediaControl)
        registerDesktopSystemMediaControl()
        // 注: TTS 引擎 + HttpTTS 播放器工厂已提前到阶段1同步注册 (无依赖, 消除开窗即朗读的竞态)
        // 压缩文件解压 provider (原 10b 后半, DesktopArchiveCodec 依赖 junrar/commons-compress)
        registerDesktopArchiveProvider()

        // ===== 尾部启动任务 (原 15/16 步, 逐行等价逻辑在 DesktopCore.startupBackgroundTasks) =====
        DesktopCore.startupBackgroundTasks()
    }
}

/**
 * KP6 桌面端运行时环境初始化 (便携模式 + native 库加载)。
 *
 * 核心逻辑 (系统属性设置 + native 库定位) 已下沉 :desktop-core 的
 * [DesktopCore.initRuntimeEnvironment] (与 headless 共用); 本函数只负责 :desktop 特有的
 * 定位输入:
 * - 便携模式: 经 `compose.application.resources.dir` 定位 exe 所在目录, 数据存其同级 data/
 *   (编译期 InstallType 控制行为, InstallType 是 desktop/build.gradle.kts 生成类, headless 不用)
 * - quickjs native: 从 resourcesDir (打包后 = app/{packageName}/, 含 copyQuickjsNativeToResources
 *   task 纳入的 legado_quickjs.dll) 直接定位; 开发期目录无 dll → 不设属性,
 *   Platform.kt 候选3 从当前目录向上递归找 modules/quickjs 构建产物
 *
 * 开发期默认 dev (build.gradle.kts 默认值), 保护项目源码树不被污染。
 */
private fun initDesktopRuntimeEnvironment() {
    val resourcesDir = System.getProperty("compose.application.resources.dir") ?: return
    if (resourcesDir.isEmpty()) return
    val resDirFile = File(resourcesDir)
    if (!resDirFile.isDirectory) return

    // 便携模式: resDirFile 指向 app/<packageName>/ (jar + 资源所在目录),
    // 其 parentFile = jpackage package root (exe 所在目录的同级)。
    // portable 模式数据存 exe 同级 dataDir; installed/dev 模式传 null (走 portable.txt 标记
    // 检测 / 系统数据目录), 仅日志记录安装模式。
    val dataDir = if (InstallType.IS_PORTABLE) {
        val exeDir = resDirFile.parentFile?.parentFile ?: resDirFile.parentFile
        File(exeDir, "data")
    } else {
        AppLog.put(jvmGetString("desktop_install_mode_not_portable", InstallType.TYPE), tag = TAG)
        null
    }

    val osName = System.getProperty("os.name").lowercase()
    val libName = when {
        osName.contains("windows") -> "legado_quickjs.dll"
        osName.contains("mac") || osName.contains("darwin") -> "liblegado_quickjs.dylib"
        else -> "liblegado_quickjs.so"
    }
    val libFile = File(resDirFile, libName).takeIf { it.isFile }
    DesktopCore.initRuntimeEnvironment(dataDir, libFile)
}
