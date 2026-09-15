package io.legado.desktop

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabaseProviders
import io.legado.app.data.AppDbProviders
import io.legado.app.data.BundledDatabaseDriver
import io.legado.app.data.DesktopAppDatabaseProvider
import io.legado.app.help.AppWebDavShared
import io.legado.app.help.DefaultDataResourceProviders
import io.legado.app.help.RuleBigDataProviders
import io.legado.app.help.book.BookHelpProviders
import io.legado.app.help.book.BookHelpShared
import io.legado.app.help.book.BookImageStorageProviders
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.book.JvmBookImageStorage
import io.legado.app.help.book.JvmBookStorage
import io.legado.app.help.book.JvmLocalBookLocator
import io.legado.app.help.book.LocalBookLocators
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.COVER_CACHE_REF_SEGMENT
import io.legado.app.help.config.LocalConfigKeys
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.config.ReadBookConfigProviders
import io.legado.app.help.config.ReadBookConfigShared
import io.legado.app.help.config.ThemeConfigProviders
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.file.desktopAppRootDir
import io.legado.app.help.file.desktopResolveStoredRef
import io.legado.app.help.file.registerDesktopAppFilesDir
import io.legado.app.help.file.registerDesktopFileDownloader
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.help.i18n.registerAppStringProvider
import io.legado.app.help.notification.registerDesktopNotificationProgress
import io.legado.app.help.service.DesktopUpdateBookCallback
import io.legado.app.help.service.UpdateBookCallbacks
import io.legado.app.help.service.registerDesktopServiceLauncher
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.source.SourceHelpAccessors
import io.legado.app.help.storage.BackupShared
import io.legado.app.help.storage.registerJvmDataStorage
import io.legado.app.help.toast.registerDesktopToaster
import io.legado.app.help.tts.TtsEngineProvider
import io.legado.app.model.fileBook.ZipFileWrapperFactoryProviders
import io.legado.app.ui.book.changecover.CoverStorageServiceProviders
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.web.registerDesktopWebServerPlatform
import io.legado.app.web.utils.registerDesktopWebAssetSource
import io.legado.app.web.utils.registerDesktopWebStrings
import io.legado.desktop.DesktopCore.initDefaultData
import io.legado.desktop.DesktopCore.initRuntimeEnvironment
import io.legado.desktop.DesktopCore.registerCoreProviders
import io.legado.desktop.DesktopCore.registerSecondaryCoreProviders
import io.legado.desktop.DesktopCore.startupBackgroundTasks
import io.legado.desktop.config.registerDesktopConfig
import io.legado.desktop.data.DesktopAppDbAccessor
import io.legado.desktop.help.DesktopCrashHandler
import io.legado.desktop.help.DesktopDefaultDataResourceProvider
import io.legado.desktop.help.applyDesktopLanguagePref
import io.legado.desktop.help.book.DesktopBookHelpAccessor
import io.legado.desktop.help.book.DesktopZipFileWrapperFactory
import io.legado.desktop.help.book.registerDesktopBookshelfManagePlatform
import io.legado.desktop.help.changecover.DesktopCoverStorageService
import io.legado.desktop.help.changesource.registerDesktopChangeBookSourcePlatform
import io.legado.desktop.help.config.registerDesktopPasswordProvider
import io.legado.desktop.help.initDesktopDefaultData
import io.legado.desktop.help.log.registerDesktopAppLogHost
import io.legado.desktop.help.registerDesktopAndroidId
import io.legado.desktop.help.registerDesktopAppUpdate
import io.legado.desktop.help.registerDesktopDirectLinkUploadProviders
import io.legado.desktop.help.registerDesktopFileCacheProvider
import io.legado.desktop.help.registerDesktopRegexErrorHandler
import io.legado.desktop.help.source.DesktopSourceHelpAccessor
import io.legado.desktop.help.source.registerDesktopSourceProviders
import io.legado.desktop.help.storage.registerDesktopBackupRestoreHook
import io.legado.desktop.help.ui.registerDesktopUserAgentProvider
import io.legado.desktop.http.registerDesktopHttpProvider
import io.legado.desktop.js.registerDesktopJsEngines
import io.legado.desktop.model.DesktopCacheBook
import io.legado.desktop.model.registerDesktopReadBookPlatform
import io.legado.desktop.model.webBook.registerDesktopWebBookProviders
import io.legado.desktop.tts.DesktopHttpTtsPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "legado-desktop"

/**
 * 进程启动参数 (main() 入口保存, 剔除重启等待标记后), 供桌面端重启
 * ([io.legado.desktop.help.launchRestartProcess] 的 ProcessBuilder 复用)。
 *
 * 原 :desktop Main.kt 顶层声明, 随"无 UI 核心"抽取下沉本模块 (DesktopAppRestart 引用);
 * 包名保持 io.legado.desktop 不变, :desktop Main.kt 无需改动引用。
 */
@Volatile
var startupArgs: Array<String> = emptyArray()

/**
 * 进程重启时拉起的主类名 ([io.legado.desktop.help.launchRestartProcess] 用)。
 *
 * 默认 io.legado.desktop.MainKt (Compose Desktop application 配置, 开发/打包一致);
 * headless 入口覆写为 io.legado.headless.MainKt, 避免无头进程重启时拉起桌面 UI。
 */
@Volatile
var restartMainClass: String = "io.legado.desktop.MainKt"

/**
 * 桌面端"无 UI 核心"启动编排 (从 :desktop Main.kt 机械抽取)。
 *
 * :desktop (Compose UI) 与 :headless (无头后台进程) 共用同一套数据/配置环境初始化与
 * provider 注册序列, 保证两种入口的数据落位/行为等价。UI 绑定 provider (依赖
 * compose/awt/jna/skia/mediamp 的注册项) 不在本对象内, 由 :desktop Main.kt 原位注册
 * (各函数体内的注释标明对应原 Main.kt 编号, 顺序语义与抽取前一致)。
 *
 * # 调用顺序 (与原 Main.kt 三阶段结构一一对应)
 * 1. [initRuntimeEnvironment] — 便携模式 + quickjs native 定位 (main 最前, 早于一切 provider)
 * 2. [DesktopCrashHandler.install] — 崩溃日志 (紧跟 1, 落盘目录依赖 legado.portable.root)
 * 3. [registerCoreProviders] — 阶段1 同步注册核心子集 (原 application{} 内注册块)
 * 4. [registerSecondaryCoreProviders] — 阶段3 异步注册核心子集 (原 registerSecondaryProviders)
 * 5. [startupBackgroundTasks] — 阶段3 尾部启动期异步任务 (adjustSortNumber/默认数据/清理/WebDav)
 */
object DesktopCore {

    /**
     * KP6 桌面端运行时环境初始化 (便携模式数据根 + quickjs native 库定位)。
     *
     * 必须在所有 provider 注册前调用 (下游 lazy 缓存一次):
     * - shared 的 DesktopAppFilesDir/BundledDatabaseDriver/JvmBookStorage 构造时经
     *   desktopAppRootDir 读 `legado.portable.root` 系统属性定位配置根目录
     * - QuickJsJsEngine 首次 eval 经 loadLegadoQuickJsNative() 读 `legado.quickjs.lib`
     *   系统属性定位 native 库
     *
     * @param portableDataRoot 便携模式数据根目录 (null = installed/dev 模式, 不设系统属性,
     *   下游 DesktopAppPaths 走 portable.txt 标记检测 / 系统数据目录)。非 null 时创建目录并
     *   设置 `legado.portable.root`, 数据库/配置/缓存全部落该目录 (拷贝即迁移, 卸载即清空)
     * @param quickjsLibFile quickjs native 库文件绝对路径 (null/不存在时不设属性,
     *   quickjs 模块 Platform.kt 回落候选 2/3: 环境变量或向上递归找构建产物)
     */
    fun initRuntimeEnvironment(portableDataRoot: File?, quickjsLibFile: File?) {
        // 1. 便携模式定位: 数据存 exe/启动目录同级 data/ (设置 legado.portable.root 系统属性)
        if (portableDataRoot != null) {
            // 创建失败不静默: 失败后仍设属性, 后续 prefs/DB 会在首次访问时才炸, 现场离根因很远
            if (!portableDataRoot.mkdirs() && !portableDataRoot.isDirectory) {
                AppLog.put("portable 数据目录创建失败: ${portableDataRoot.absolutePath}", tag = TAG)
            }
            System.setProperty("legado.portable.root", portableDataRoot.absolutePath)
            AppLog.put("portable 模式, dataDir = ${portableDataRoot.absolutePath}", tag = TAG)
        }
        // 2. native 库定位: 有文件即设属性, 让 quickjs 模块 Platform.kt 候选1 System.load 加载
        if (quickjsLibFile != null && quickjsLibFile.isFile) {
            System.setProperty("legado.quickjs.lib", quickjsLibFile.absolutePath)
            AppLog.put("quickjs native 库已定位: ${quickjsLibFile.absolutePath}", tag = TAG)
        } else {
            AppLog.put(
                "quickjs native 库未定位 (${quickjsLibFile ?: "未提供"}), 回落 Platform.kt 候选路径",
                tag = TAG,
            )
        }
    }

    /**
     * 启动期原生依赖预热 (与 AWT/Swing 初始化赛跑, 在 main() 里调):
     *
     * 1. **JNA 首用** —— 阶段0 构 `DesktopAppConfigAccessor` 时, `themeMode="0"`（默认，跟随系统）
     *    的 `isNightTheme` 会调 :desktop 注入的 `probeSystemNightMode()` 读注册表。
     *    实测证据 (零改动对照): 把 `theme/Mode` 改成"1"(走不到探测器) 后, `config: AppConfig` 段从
     *    **86ms 掉到 11ms**; 而只 `Class.forName(Advapi32Util)` 预热**无效** (第八轮实测仍 81ms),
     *    因为贵的是**首次真正调用 JNA** (要在此刻完成原生调用准备), 不是类加载。
     *    所以本模块只预热与 AWT 有关的部分, JNA 那一跳由 :desktop 的 Main.kt 在后台线程真调一次
     *    (`:desktop-core` 依赖闭包不得携带 jna, 供 :headless 复用, 故不能写在这里)。
     * 2. **AWT 字体环境** —— 首次枚举系统字体只在主线程发生时, 会压在闪屏构造与首帧排版前面
     *    (实测闪屏段内 JWindow 构造+尺寸/底色 从 145ms 降到 54ms, 整段 197ms→95ms)。
     *
     * 与单实例不变量的关系: 两项**不读不写应用配置值、不碰 SQLite/数据文件** (GraphicsEnvironment
     * 只读系统字体目录), 所以可以在 [SingleInstanceGuard.awaitPrimaryDecision] 之前起。
     * 本函数**只由 :desktop 调**: :headless 无 UI, 不该在这里替它初始化 AWT。
     */
    fun warmUpNativeDependencies() {
        Thread({
            runCatching {
                java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().allFonts
            }.onFailure { AppLog.put("AWT 字体环境预热失败 (不影响功能)", it) }
        }, "native-dep-warm").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 阶段1: 首屏必需 provider 同步注册 — 核心子集 (无 UI 依赖)。
     *
     * 从 :desktop Main.kt application{} 内的同步注册块机械抽取, 保持原相对顺序。
     * UI 绑定项 (AppUserModelId/ScreenInfo/TrayNotifier.uiSender/PlatformCapabilities/
     * PlatformServices/系统 TTS 引擎/BookImageLoader) 留在 :desktop Main.kt 原位置注册。
     *
     * @return 构造好的 [ReadBookConfigShared] (供 :desktop 阶段2 构造 LocalReadConfigProviders
     *   注入 Compose; 必须与全局 ReadBookConfigProviders 同实例, 否则配置写读分家)。
     *   headless 不消费该返回值, 但注册本身必须执行 (备份格式兼容性补齐)。
     */
    fun registerEarlyProviders(): ReadBookConfigShared {
        // 注册桌面端 Host 类 provider (启动期最早, 让 shared commonMain 调用 AppLog/appString 时有输出)
        // - AppLogHost: 桥接到 println, 未注册时 AppLog 副作用 (write/toast/debugPrint) 静默 no-op
        // - AppStringProvider: key → strings.xml 同步查表 (jvmGetString, runBlocking 桥接),
        //   未注册时 appString fallback 返回 key 名; 须先于任何 appString 调用
        registerDesktopAppLogHost()
        registerAppStringProvider { key, args -> jvmGetString(key.name, *args) }
        // 注入机器标识到 AndroidIdHolder (对照 app 端 JsEnginesAndroid 注入 ANDROID_ID)。
        // 默认值 "null" 只有 4 字符, BaseSource 登录信息 AES 密钥取前 16 字节会越界被吞,
        // 表现为桌面端书源登录信息无法持久化; 必须在任何 getLoginInfo/putLoginInfo 之前注入
        registerDesktopAndroidId()
        // 注册桌面端 Toaster + NotificationProgress provider (shared jvmMain 已实现)
        // - Toasters: 托盘气泡, 托盘未装/无头环境自动回落 AppLog 日志兜底 (注册本身惰性,
        //   无 AWT 初始化, 无头进程可安全调用)
        // 未注册时 Toasters.get() 抛 IllegalStateException (forceRefresh 未 runCatching 防御)
        registerDesktopToaster()
        registerDesktopNotificationProgress()
        // 注册 UpdateBookCallback 默认实现: shared BookshelfViewModel 据此构造 UpdateBookShared
        // 刷新引擎 (书架菜单/下拉刷新、自动更新、条目转圈状态均依赖它)。须在阶段1同步注册:
        // 书架激活后首个书籍流发射即可能触发 autoUpdateGroup → 引擎 lazy 构造, 晚注册存在竞态。
        // iOS/鸿蒙端在 registerNativeUpdateBookCallback 注册, app 端在 App.kt 注册。
        UpdateBookCallbacks.registerDefault(DesktopUpdateBookCallback)
        // 备份格式兼容性: 注册桌面端 config provider
        // - PreferenceProvider + AppConfigAccessor
        // - ReadBookConfigProviders + ThemeConfigProviders (备份格式兼容性补齐, 供 BackupShared 用)
        // 接住返回值: 必须与全局 ReadBookConfigProviders 同实例, 否则配置写读分家
        val desktopReadBookConfig = registerDesktopConfig()
        // 应用内语言 (PreferKey.language → JVM 默认 Locale):
        // 必须在 registerDesktopConfig 之后 (要读 pref)
        applyDesktopLanguagePref()
        return desktopReadBookConfig
    }

    /**
     * 阶段1 的余下部分 (HTTP/JS/Room/存储/封面等重注册)。
     *
     * 与 [registerEarlyProviders] 拆开的原因 (2026-09 启动实测): 闪屏只能读偏好+主题, 却被排在
     * 整段注册之后才 show, 导致冷启动 0.96s 屏幕上仍无任何反馈 (用户报的“启动卡顿”很大程度是这段
     * 无反馈)。拆开后 :desktop 可以在 early 完成即弹闪屏, 再做重注册; 注册相对顺序与拆分前一致。
     */
    fun registerRestProviders() {
        // 以下分段 timed 打点 (2026-09 启动归因): 阶段1 总耗时实测 277~299ms, 但不清楚钱具体
        // 在哪一步 (Room 开库/加载 sqliteJni? OkHttp+TLS? JS 引擎?), 不拆就没法判断该拿哪段开刀。
        // 注册桌面端更新能力 (AppUpdateEnvironment, 薄壳转发 shared AppUpdateManager):
        // 依赖 PreferenceProviders (上方 registerDesktopConfig) + DesktopAppInfo, 无其他依赖
        registerDesktopAppUpdate()
        // 注册桌面端 AppFilesDir (~/.legado/files), 供 BackupShared/RestoreShared 用
        registerDesktopAppFilesDir()
        // HTTP 层 (OkHttp + CookieJarBridge, 独立): 提前到阶段1, 与 JS 引擎同批就绪,
        // 消除"首次 JS eval 触发网络请求 → OkHttpClientProviders 未注册"的启动竞态
        // (registerDesktopJsEngines 在前, 书源 JS 里 java.ajax 依赖此层)
        registerDesktopHttpProvider()
        // jsoup 走宿主共享 OkHttpClient (对照 app 端 App.kt:138 Jsoup.clientFactory = { okHttpClient })
        Jsoup.clientFactory = { OkHttpClientProviders.get().okHttpClient }
        // 漫画图片缓存 provider (webBook 漫画取图链路 BookImageStorageProviders.get() 的依赖):
        // 提前到阶段1同步注册, 消除"异步注册完成前打开漫画页 → get() 抛 not registered"的启动竞态
        // (OkHttpClient 惰性到首次下载)。
        // 注意: JvmBookImageStorage 构造会取 defaultRootPath → DataStorageProviders.get(),
        // 必须先 registerJvmDataStorage() (注册本身零开销, 只 new JvmDataStorage)。
        registerJvmDataStorage()
        BookImageStorageProviders.register(JvmBookImageStorage())
        // HttpTTS 播放器工厂 (DesktopHttpTtsPlayer, javax.sound 纯 JVM 无 UI 依赖)。
        // 系统 TTS 引擎 (DesktopSystemTtsEngine) 依赖 JNA (WindowsSapiTtsBackend) 留 :desktop;
        // headless 仅缺系统 TTS 朗读引擎, Web 服务 HttpTTS 朗读可用
        TtsEngineProvider.registerHttpTtsPlayerFactory { DesktopHttpTtsPlayer() }
        // JS 引擎 provider (JsEngines/SharedJsScope/简繁词典/DesktopImageOps): 任何页面/协程首次 eval 前必然就绪;
        // 注册本身零开销 (native 库在首次 eval 时才加载)。
        registerDesktopJsEngines()
        // 注册桌面端 DefaultDataResourceProvider: 必须在 AppDatabaseProviders.register 之前
        // (首次建库 dbCallback.onCreate → DefaultData.keyboardAssists → DefaultDataResourceProviders
        // .get().readResource("keyboardAssists.json")), 否则首次建库时 keyboardAssists 表无默认数据
        DefaultDataResourceProviders.register(DesktopDefaultDataResourceProvider())
        // 注册桌面端 AppDatabase provider (BundledDatabaseDriver 用 Room.databaseBuilder +
        // BundledSQLiteDriver 构造 AppDatabase, 同一实例经 AppDatabaseProviders 共享)
        val dbDriver = BundledDatabaseDriver()
        AppDatabaseProviders.register(DesktopAppDatabaseProvider(dbDriver))
        // 注册桌面端 BookStorage provider (~/.legado/book_cache)
        // ReadBookViewModelShared.loadChapter 通过 BookStorageProviders.get().getContent 读章节缓存正文
        BookStorageProviders.register(JvmBookStorage())
        // 注册桌面端 AppDbAccessor / BookHelpAccessor provider
        // 供 shared commonMain 中下沉的 webBook 编排层通过 AppDbProviders.get() / BookHelpProviders.get()
        // 间接访问 appDb 的 9 个 DAO / saveContent; 未注册时阅读流/搜索/书源管理全失效
        AppDbProviders.register(DesktopAppDbAccessor())
        BookHelpProviders.register(DesktopBookHelpAccessor())
        // 注册桌面端阅读平台钩子 (朗读宿主 / 缓存运行态 / 本地 txt 分章缓存; 图片缓存为空实现,
        // 见 DesktopReadBookPlatform 注释)。原 Main.kt 阶段1 在 UI Platform 注册之后调用,
        // 注册间无先后依赖, 核心子集内提前对行为无影响; 须早于任何阅读页/朗读打开
        registerDesktopReadBookPlatform()
        // 封面选图持久化 (对齐 Android 原版 externalFiles/covers, 落桌面应用数据根目录 covers/)
        CoverStorageServiceProviders.register(DesktopCoverStorageService())
        // Room 开库预热 (与首帧组合并行; 上一轮我误删了它, 实测把 273ms 开库成本又退回主链路上)。
        // 实测: 注册 provider 只要 1~2ms, 真正开库 (加载 sqliteJni 原生库 + 建/打开 db 与 -wal/-shm
        // + Room schema 校验 + InvalidationTracker 启动) 要 224~282ms, 而它拖到首次 DAO 访问 ——
        // 首次访问就是首帧的分组/书籍流。预热的是**首帧真正要用的那两条查询** (任意一条便宜查询
        // 只能做到"连接已开", 语句准备与表页加载仍会算到首帧头上)。
        // 不用 Coroutine.async: 它的 executeContext 是 mainDispatcher, 启动期 EDT 正忙首帧。
        // 失败不阻断启动 (首帧会再触发同一条路径), 但必须落日志。
        Thread({
            runCatching {
                kotlinx.coroutines.runBlocking {
                    val db = AppDbProviders.get()
                    db.bookGroupDao.flowShow().first()
                    db.bookDao.flowByGroup(io.legado.app.data.entities.BookGroup.IdAll).first()
                }
            }.onFailure { AppLog.put("Room 开库预热失败 (不影响功能, 首帧会重新触发开库)", it) }
        }, "room-open-warm").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 全量注册 = [registerEarlyProviders] + [registerRestProviders]。
     *
     * headless 入口无 UI/无闪屏, 保持单调用原语义; 与 :desktop 的分两段调用等价
     * (早段返回的 ReadBookConfig 实例经全局 ReadBookConfigProviders 共享, 不存在两份实例)。
     */
    fun registerCoreProviders(): ReadBookConfigShared =
        registerEarlyProviders().also { registerRestProviders() }

    /**
     * 阶段3: 后台异步注册非首屏必需 provider — 核心子集 (无 UI 依赖)。
     *
     * 从 :desktop Main.kt registerSecondaryProviders 机械抽取, 保持原相对顺序与编号。
     * UI 绑定项 (BackstageWebView/BitmapProviders/本地书 FileBookAccessor/验证码 UI/
     * AudioPlay/SMTC/OpenUrl 确认框/漫画 ImageController) 留在 :desktop Main.kt 注册;
     * headless 缺失对应能力 (详见 headless Main.kt 顶部取舍注释)。
     * 尾部启动期异步任务拆到 [startupBackgroundTasks] (:desktop 在 UI 注册完成后调用)。
     */
    suspend fun registerSecondaryCoreProviders() {
        withContext(Dispatchers.Default) {
            // 0. FileCacheProvider (CacheManager 文件/二进制层, 依赖 AppFilesDirs 已同步注册)
            // 未注册时 CacheManager 文件层抛 IllegalStateException (不再静默 no-op),
            // 且须在 JS 引擎之前注册, 否则首次 JS 文件缓存调用即崩
            registerDesktopFileCacheProvider()
            // 0.5 文件下载器 (shared Download 编排: 更新弹窗"下载"等入口; 此前从未注册,
            // FileDownloaders.get() 抛 IllegalStateException 且在协程内被吞, 表现为点了下载无反应)
            registerDesktopFileDownloader()
            // 1. 备份/直链相关 (依赖 PreferenceProviders, 已同步注册)
            // - PasswordProvider: 供 BackupAES 无参构造经 PasswordProviders 反向获取 password
            // - DirectLinkUploadProviders: 供 BackupShared/RestoreShared 备份恢复 directLinkUploadRule.json
            registerDesktopPasswordProvider()
            registerDesktopDirectLinkUploadProviders()
            // - BackupRestoreHooks: 备份/恢复的平台收尾 (lastBackup 时间戳 + 恢复完成提示);
            //   zip 复制/解压走 shared 默认文件分支, 桌面端无 SAF
            registerDesktopBackupRestoreHook()
            // 2. HTTP 层已提前到阶段1同步注册 (OkHttp + CookieJarBridge, 独立)
            // 注: jsoup clientFactory 亦随 HTTP 层提前, 书源 JS 首次 eval 前网络栈必就绪
            // 3. BackstageWebView (内嵌浏览器引擎: Windows 走系统自带 WebView2 Runtime) —
            //    UI 绑定, 留 :desktop; headless 缺失时书源 create 仍由调用方 runCatching 回退 HTTP
            // 4. 本地书定位器 (独立, 供 BookshelfManagePlatform.deleteLocalBook 用)
            LocalBookLocators.register(JvmLocalBookLocator())
            // 5. CbzFile 相关: ZipFileWrapperFactory (纯 zip, 无重依赖) 在此注册;
            //    BitmapProviders (skia 漫画位图) UI 绑定, 留 :desktop
            ZipFileWrapperFactoryProviders.register(DesktopZipFileWrapperFactory)
            // 6. EpubFile 相关 (DesktopFileBookAccessor 依赖 junrar/commons-compress/pdfbox) —
            //    UI 绑定, 留 :desktop; headless 缺 EPUB/PDF 本地书解析
            // 7. SourceHelp (独立, 供 shared SourceHelp.saveSource/deleteBookSource 调用)
            SourceHelpAccessors.register(DesktopSourceHelpAccessor())
            // 8. 服务启动器 (依赖 AppDbProviders + BookHelpProviders + BookStorageProviders, 已同步注册)
            registerDesktopServiceLauncher()
            // 8b. Web 服务 provider (NanoHTTPD 独立, 不依赖其他 provider)
            // - WebServerPlatform: HttpServer+WebSocketServer 起停 (JvmWebServerPlatform 共用逻辑)
            // - WebAssetSource: composeResources 读 commonMain/composeResources/files/web/ 静态资源
            // - WebStrings: 硬编码中文文案 (后续接入 i18n 资源后替换)
            // 须在任何 WebServerManager.start()/stop() 之前注册 (MyScreen Web 服务开关触发时)
            registerDesktopWebServerPlatform()
            registerDesktopWebAssetSource()
            registerDesktopWebStrings()
            // 9. CacheBook 回调 (依赖 ServiceLauncher 已注册)
            DesktopCacheBook.registerCallback()
            // 10. Source 扩展 provider (依赖 PreferenceProviders, in-memory 实现)
            registerDesktopSourceProviders()
            // 10b. 正则替换错误处理 provider (供 shared RegexReplacerImpl / JsExtensionsCommon 调用,
            //      必须在 WebBook 编排层 + JS 引擎首次 eval 之前注册);
            //      压缩文件解压 provider (DesktopArchiveProvider → junrar/commons-compress) 留 :desktop
            registerDesktopRegexErrorHandler()
            // 11. WebBook 编排层 (依赖 AppDbProviders.replaceRuleDao 已就绪);
            //     Web 服务封面/插图 provider (DesktopImageControllerProvider)
            //     由 desktop Main.kt / headless Main.kt 注册
            registerDesktopWebBookProviders()
            // 11b. JS 扩展回调 provider (UserAgent, 供 JsExtensionsCommon 回调,
            //      必须在 JS 引擎首次 eval 之前注册); OpenUrl 确认框 provider (DesktopDialogs) 留 :desktop
            registerDesktopUserAgentProvider()
            // 11c. 书源验证 UI provider (Swing 输入框/内嵌浏览器窗口) — UI 绑定, 留 :desktop
            // 13. AudioPlay (mediamp) + 系统媒体控制 (SMTC) — UI 绑定, 留 :desktop
            // 13b. ChangeBookSource / BookshelfManage 平台 provider (对照 app 端 App.kt:183/187
            //      registerAndroidChangeBookSourcePlatform / registerAndroidBookshelfManagePlatform,
            //      须在 registerDesktopWebBookProviders 之后, 因换源/书架管理依赖 AppDbProviders /
            //      WebBookProviders / ContentProcessorProviders 已注册)
            registerDesktopChangeBookSourcePlatform()
            registerDesktopBookshelfManagePlatform()
            // 注: TTS 引擎 + HttpTTS 播放器工厂已提前到阶段1同步注册 (消除开窗即朗读的竞态)
        }
    }

    /**
     * 阶段3 尾部: 启动期异步任务 (对照 app 端 App.kt onCreate 的 Coroutine.async 块)。
     *
     * 与 :desktop registerSecondaryProviders 尾部逐行等价; [initDefaultData] 单独暴露,
     * 供只想要默认数据补齐的入口复用 (headless 与桌面共用同一时序: 注册完成后调用本函数)。
     */
    suspend fun startupBackgroundTasks() {
        withContext(Dispatchers.Default) {
            // 15. adjustSortNumber: 调整书源排序序号 (依赖 AppDbProviders, 已注册)
            // 异常由 Coroutine 内部 printOnDebug 吞没, 与 app 端语义一致
            // 书源表为空时不调: 它无条件发 max/min/重复序号 3 条聚合 SQL, 而 0 行时结果恒为
            // "不越界 + 无重复" 直接 return; 用 1 条 count 换掉 3 条聚合, 判定本身比被省掉的活便宜
            Coroutine.async {
                if (AppDbProviders.get().bookSourceDao.allCount() > 0) {
                    SourceHelp.adjustSortNumber()
                }
            }
            // LogUtils.init 为 Android 专属, desktop 用 registerDesktopAppLogHost 替代
            // 对照 app 端 App.kt:144 DefaultData.upVersion() + dbCallback.onCreate 预置数据:
            // 桌面端 Room KMP 无 Callback, 首启/升级的默认数据统一在这里幂等补齐
            initDesktopDefaultData()
            // 15b. 旧数据封面引用修复: 旧版 coverUrl 存绝对路径, 便携移动程序目录后失效,
            // 同名文件在当前 covers 目录存在时改存 coverCache/ 相对引用 (幂等)
            Coroutine.async { repairLegacyStoredCoverRefs() }
            // 16. 启动期缓存清理 + WebDav 进度同步
            // (对照 app 端 App.kt onCreate 的两个 Coroutine.async 块:
            //  - 缓存清理: 距上次清理超过 1 天才执行, 清 cacheDao 过期条目 + 无效书籍缓存 +
            //    备份/阅读背景/主题背景缓存;
            //  - 进度同步: syncBookProgress 开启时从 WebDav 拉取所有书籍进度写回本地)
            Coroutine.async {
                val prefs = PreferenceProviders.get()
                // 节流读专用的 lastCacheCleanup: 原写法读 lastBackup, 但那个 key 只在用户真备份/恢复
                // 时写入, 从不备份的用户恒为 0 → 条件恒成立 → 每次启动都全跑一遍清理族
                val lastCleanup = prefs.getLong(LocalConfigKeys.lastCacheCleanup, 0L)
                if (lastCleanup + TimeUnit.DAYS.toMillis(1) < System.currentTimeMillis()) {
                    runCatching {
                        AppDbProviders.get().cacheDao.clearDeadline(System.currentTimeMillis())
                        // 书架无书且缓存目录无残留时 clearInvalidCache 必然空转: 它删的就是
                        // "书架之外的 book_cache / ruleData 子目录", 三者全空则无物可删
                        if (hasInvalidCacheWork()) {
                            BookHelpShared.clearInvalidCache()
                        }
                        BackupShared.clearCache()
                        ReadBookConfigProviders.get().clearBgAndCache()
                        ThemeConfigProviders.get().clearBg()
                    }.onSuccess {
                        // 只在整段跑完才写时间戳: 中途抛错就不写, 下次启动重试,
                        // 否则一次失败会被当成"今天已经清过"静默节流掉
                        prefs.putLong(LocalConfigKeys.lastCacheCleanup, System.currentTimeMillis())
                    }.onFailure {
                        AppLog.put("启动期缓存清理失败\n${it.message}", it)
                    }
                }
            }
            Coroutine.async {
                if (AppConfigProviders.get().syncBookProgress) {
                    AppWebDavShared.downloadAllBookProgress()
                }
            }
        }
    }

    /**
     * 旧数据封面引用修复 (一次性兜底, 幂等可重复执行)。
     *
     * 背景: 桌面端落库引用曾存绝对路径/file: URI (Book.coverUrl), 便携版移动程序目录后
     * 指向移动前位置失效; 新数据已存 coverCache/ 相对引用 (getCoverPath)。本修复把
     * "旧格式引用 + 同名封面文件 (md5_16(bookUrl).jpg) 已随数据目录迁移到当前 covers 目录"
     * 的书改存相对引用。bookUrl (主键) 不重写: 主键变更牵动 chapters/toc/缓存目录联动,
     * 且 books/ 相对引用的读取端本就有 originName 兜底 (openLocalFile)。
     */
    private suspend fun repairLegacyStoredCoverRefs() {
        runCatching {
            val coversDir = File(desktopAppRootDir(), "covers")
            // 早退两条判据都比下面的 all() 全表读便宜: covers 目录不存在时同名封面文件必不存在
            // (candidate.isFile 全假), 书架 0 行时无行可修; 空库/全新用户启动不再物化全部 Book 实体
            if (!coversDir.isDirectory) return@runCatching
            val bookDao = AppDbProviders.get().bookDao
            if (bookDao.allBookCount() == 0) return@runCatching
            bookDao.all().forEach { book ->
                val coverUrl = book.coverUrl ?: return@forEach
                // 仅旧格式 (file: URI / 绝对路径); 相对引用与网络 URL 跳过
                val isLegacyRef = coverUrl.startsWith("file:") ||
                    coverUrl.startsWith('/') || coverUrl.startsWith('\\') ||
                    (coverUrl.length > 1 && coverUrl[1] == ':')
                if (!isLegacyRef) return@forEach
                val candidate = File(coversDir, desktopResolveStoredRef(coverUrl).name)
                if (!candidate.isFile) return@forEach
                book.coverUrl = "$COVER_CACHE_REF_SEGMENT/${candidate.name}"
                bookDao.update(book)
            }
        }.onFailure { AppLog.put("封面引用修复失败\n${it.message}", it) }
    }

    /**
     * 首启 / 升级时的默认数据补齐 (对照 app 端 DefaultData.upVersion + dbCallback.onCreate):
     * 预置书架分组 / 键盘助手 / httpTTS / txtTocRule / dictRule, 幂等可重复调用。
     */
    suspend fun initDefaultData() = initDesktopDefaultData()

    /**
     * [BookHelpShared.clearInvalidCache] 是否还有活可干 (调用点早退判据)。
     *
     * 判据必须比被它省掉的活便宜: 1 条 COUNT(*) + 2 次单层目录 list, 换掉
     * "全表读 name/bookUrl + book_cache 子目录逐个扫描 + ruleData 子目录逐个扫描"。
     *
     * 不漏清的依据: clearInvalidCache 共四步 —— ①② 按书架删 book_cache 失效目录与漫画超量淘汰
     * (根目录空 → 无可删对象), ③ 按书架删 RuleBigData 大变量目录 (impl 未注册时本来就是 no-op),
     * ④ clearCacheExtra (桌面/headless 共用的 DesktopBookHelpAccessor 为空实现)。
     * 书架 0 行 + ①③ 两类目录都空时四步全为空转, 故本判定与原行为等价。
     */
    private suspend fun hasInvalidCacheWork(): Boolean {
        if (AppDbProviders.get().bookDao.allBookCount() > 0) return true
        if (!dirAbsentOrEmpty(File(BookStorageProviders.get().rootPath))) return true
        val ruleDirs = RuleBigDataProviders.impl?.listBookDataDirs().orEmpty()
        return ruleDirs.any { !dirAbsentOrEmpty(File(it)) }
    }

    /**
     * 单层目录是否"不存在或为空" (只 list 一层, 不递归)。
     *
     * File.list() 在"是目录但读目录出错"时也返回 null, 这种情况按非空处理让清理照原样跑,
     * 不用空判掩盖 IO 错误造成漏清。
     */
    private fun dirAbsentOrEmpty(dir: File): Boolean {
        val entries = dir.list()
        return if (entries == null) !dir.isDirectory else entries.isEmpty()
    }
}
