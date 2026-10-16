package io.legado.desktop.ui

import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.constant.SourceType
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.exception.InvalidBooksDirException
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.DirectLinkUploadRule
import io.legado.app.help.RssToolbarActions
import io.legado.app.help.book.bookLogDirOrNull
import io.legado.app.help.book.bookshelfJson
import io.legado.app.help.book.effectiveBookSaveDir
import io.legado.app.help.book.isImage
import io.legado.app.help.config.LocalConfigKeys
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.coroutine.mainDispatcher
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.file.desktopStoredLocalRef
import io.legado.app.help.service.ServiceLaunchers
import io.legado.app.help.source.SourceVerificationHelpShared
import io.legado.app.help.storage.DataStorageProviders
import io.legado.app.help.toast.Toasters
import io.legado.app.help.writeTextAtomically
import io.legado.app.model.Debug
import io.legado.app.model.fileBook.FileBook
import io.legado.app.ui.FileAssociationDispatch
import io.legado.app.ui.book.import.ImportFileItem
import io.legado.app.ui.book.info.WebFileImportShared
import io.legado.app.ui.book.read.config.FontItem
import io.legado.app.ui.book.source.BookSourceSort
import io.legado.app.ui.book.source.manage.BookSourceViewModelShared
import io.legado.app.ui.book.source.manage.selectedSourcesJson
import io.legado.app.ui.compose.platform.syncGetString
import io.legado.app.ui.config.MODE_EDIT_CONFIG
import io.legado.app.ui.config.MODE_EDIT_PREFS
import io.legado.app.ui.config.MODE_NEW_CONFIG
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.BookRef
import io.legado.app.ui.root.DefaultDialogTransitionSpec
import io.legado.app.ui.root.DialogTransitionSpec
import io.legado.app.ui.root.PickedFile
import io.legado.app.ui.root.SharedPlatformCapabilities
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.RouteResultPayload
import io.legado.app.ui.root.RouteTransitionSpec
import io.legado.app.ui.root.ensureBookSaveDirSelected
import io.legado.app.ui.root.reselectBookSaveDir
import io.legado.app.ui.root.pushExportDispatch
import io.legado.app.ui.root.TransitionEasing
import io.legado.app.ui.root.toRouteRef
import io.legado.app.ui.root.pushExportSectionConfig
import io.legado.app.utils.browseUrl
import io.legado.app.utils.FileUtilsBase
import io.legado.app.utils.atomicWriteTempPath
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.openFileWithSystem
import io.legado.app.utils.toExportJsonText
import io.legado.desktop.constant.DesktopAppInfo
import io.legado.desktop.help.DesktopCrashLogDirs
import io.legado.desktop.help.applyDesktopLanguagePref
import io.legado.desktop.help.restartDesktopAppForLanguage
import io.legado.desktop.help.book.DesktopBookExport
import io.legado.desktop.help.source.DesktopCheckSource
import io.legado.desktop.help.webview.DesktopWebViewEngines
import io.legado.desktop.help.webview.WebViewWindowHandle
import io.legado.desktop.help.webview.WebViewWindowRequest
import io.legado.desktop.model.fileBook.DesktopImportBook
import io.legado.desktop.model.fileBook.DesktopImportFile
import io.legado.desktop.ui.DesktopPlatformCapabilities.saveLog
import io.legado.desktop.ui.component.FileDialogs
import io.legado.desktop.ui.tray.DesktopMediaTray
import io.legado.desktop.ui.tray.ExportTrayBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import java.lang.management.ManagementFactory
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import javax.management.ObjectName

object DesktopPlatformCapabilities : SharedPlatformCapabilities {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 待导出队列, 键为书 url (对照原版 ExportBookService.waitExportBooks): 重复触发导出时新请求
     * 入队稍后照常导出; 同一本书已在队列里就换成最新落点。
     */
    private val waitExportBooks = LinkedHashMap<String, PendingExport>()

    /** 正在导出的书 url (对照原版 exportProgress: 同一本书正在导出时不再重复入队)。 */
    private var exportingBookUrl: String? = null

    /** 队列执行者是否在跑 (与队列读写同锁判断, 避免"收工瞬间入队"的书没人导)。 */
    private var exportWorkerRunning = false

    /** 队列执行者 (对照 app 端 ExportBookService 的服务存活, 可取消)。 */
    private var exportBooksJob: Job? = null

    /** 队列里的一本书: 落点 + 导出方式 (对照原版 ExportBookService 的 bookUrl → ExportConfig)。 */
    private class PendingExport(val path: String, val book: Book, val mode: ExportMode)

    /** 导出方式 (对照原版 ExportBookService.ExportConfig: type 或 epubScope+epubSize)。 */
    private sealed interface ExportMode {
        /** 按导出配置导: `txt` / `epub`, 图片书为 `cbz`。 */
        data class ByConfig(val type: String) : ExportMode

        /** 自定义导出: 章节范围 + 分卷大小 (epub)。 */
        data class CustomEpub(val range: String, val size: Int) : ExportMode
    }

    override val capabilityScope: CoroutineScope get() = scope

    private val appDb get() = AppDbProviders.get()
    private val prefs get() = PreferenceProviders.get()

    /** 书源分组增删改 (shared 下沉件), 供 [DesktopDialogHost] 的分组管理对话框调用。 */
    internal val bookSourceViewModel by lazy { BookSourceViewModelShared(scope) }

    override fun exitApplication() {
        // shared 的"再按一次退出程序"在桌面端 = 退到后台: 对齐原版 Android 双击返回 finish
        // 主界面、服务与通知继续 (真退出入口只有托盘菜单"退出")
        DesktopWindowChrome.interfaceExitRequest?.invoke()
    }

    // ===== 全局转场动画平台 spec (方案 A: 动画单一注入点参数化) =====
    // 桌面端无系统动画配置可动态读取 (JVM 无对应系统 API), 按桌面平台惯例提供默认值:
    // Windows Fluent motion 规范 (时长 150~300ms 取 200ms, 标准曲线 cubic-bezier(0.1,0.9,0.2,1));
    // 形态 = 淡入淡出 + 轻微位移 (8% 宽度), 旧页/出栈页不位移仅淡出 (窗口淡入淡出惯例)。
    override val routeTransitionSpec: RouteTransitionSpec
        get() = RouteTransitionSpec(
            pushDurationMillis = 200,
            pushEasing = TransitionEasing.CubicBezier(0.1f, 0.9f, 0.2f, 1f),
            newPageSlideFraction = 0.08f,
            oldPageShiftFraction = 0f,
            newPageFadeIn = true,
            oldPageFadeOut = true,
            newPageScaleFrom = 1f,
            popDurationMillis = 200,
            popEasing = TransitionEasing.CubicBezier(0.1f, 0.9f, 0.2f, 1f),
            targetPageSlideFraction = 0.08f,
            outgoingSlideFraction = 0f,
            // 目标页不淡入: 它在出栈页之下本就完整渲染, 淡入只会在中间帧露出半透明空白,
            // 由出栈页淡出直接露出即可 (原先动画层对 pop 目标页强制 alpha=1, 等价于此)
            targetPageFadeIn = false,
            outgoingFadeOut = true,
            targetPageScaleFrom = 1f,
        )

    // 桌面无系统对话框动画规范, 沿用 shared 默认 (Android 系统 dialog 动画资源语义 200/150ms)
    override val dialogTransitionSpec: DialogTransitionSpec
        get() = DefaultDialogTransitionSpec

    override fun openExternalUrl(url: String) {
        browseUrl(url)
    }

    // 语言: CMP 资源按 Locale.current 选 values-xx 目录, 进程内改默认 locale 不会重取
    // 已组合的字符串, 所以与安卓一样靠重启生效 (对照原版 appCtx.restart())
    override val languageSwitchSupported: Boolean get() = true

    /** 关闭主窗口可缩小到系统托盘 (仅托盘图标能上屏的桌面环境)。 */
    override val closeToTraySupported: Boolean get() = DesktopMediaTray.traySupported()

    override fun applyAppLanguage() {
        applyDesktopLanguagePref()
        restartDesktopAppForLanguage()
    }

    override fun openWebView(url: String, sourceKey: String, sourceName: String) {
        // 2026-08-06 用户拍板: 桌面端所有中转 WebView 界面去掉, 直接开独立浏览器窗口
        // (cookie 经书源 key 回写, 登录态可复用; 无书源时裸开窗口)
        scope.launch {
            val source = if (sourceKey.isNotEmpty()) {
                runCatching {
                    AppDbProviders.get().bookSourceDao.getBookSource(sourceKey)
                }.getOrNull()
            } else null
            if (source != null) {
                SourceVerificationHelpShared.startBrowser(
                    source, url, sourceName.ifBlank { "网页" }, false, false
                )
            } else {
                DesktopWebViewEngines.get()?.openWindow(
                    WebViewWindowRequest(
                        url = url,
                        title = sourceName.ifBlank { "网页" },
                        cookieTag = sourceKey.ifBlank { null },
                        // source 查不到时默认 book (对照 AppRoute.WebView.sourceType 默认值);
                        // 删除源确认弹窗显示源名, 空时回退 sourceKey
                        sourceType = SourceType.book,
                        sourceName = sourceName,
                    )
                )
            }
        }
    }

    override val rssDirectWindow: Boolean get() = true

    /**
     * 书源 URL 登录直开窗 (2026-08-07 用户拍板: 去掉登录中转界面)。
     *
     * 带 isLogin 语义的独立浏览器窗口 (工具栏"确定" = 确认 cookie 后 reload 关窗,
     * 对照原版 WebViewActivity menu_ok isLogin 分支), cookie 按书源 key 回写;
     * 引擎不可用/开窗失败降级系统浏览器并提示。无论成败都返回 true —— 桌面端
     * 登录不弹对话框外壳 (表单登录仍走 shared Overlay, 不经过这里)。
     */
    override fun openLoginWebView(
        url: String,
        sourceKey: String,
        sourceName: String,
        sourceType: Int,
    ): Boolean {
        val engine = DesktopWebViewEngines.get(forceRefresh = true)
        if (engine == null) {
            browseUrl(url)
            runCatching {
                Toasters.get().toastLong("内置浏览器不可用, 已用系统浏览器打开登录页")
            }
            // 系统浏览器无关窗回调, 直接放行等在 showLoginDialog() 上的 JS 线程
            SourceVerificationHelpShared.notifyLoginFinished(sourceKey)
            return true
        }
        val handle = engine.openWindow(
            WebViewWindowRequest(
                url = url,
                // 对照原版登录页标题 getString(login_source, 源名)
                title = if (sourceName.isBlank()) {
                    "登录"
                } else {
                    runCatching { syncGetString("login_source", sourceName) }
                        .getOrElse { "登录 $sourceName" }
                },
                isLogin = true,
                cookieTag = sourceKey.ifBlank { null },
                sourceType = sourceType,
                sourceName = sourceName,
                // 关窗唤醒阻塞在 source.showLoginDialog() 上的 JS 线程
                onClosed = { SourceVerificationHelpShared.notifyLoginFinished(sourceKey) },
            )
        )
        if (handle == null) {
            browseUrl(url)
            runCatching {
                Toasters.get().toastLong("内置浏览器窗口打开失败, 已用系统浏览器打开登录页")
            }
            SourceVerificationHelpShared.notifyLoginFinished(sourceKey)
        }
        return true
    }

    /**
     * RSS 阅读直开窗 (2026-08-07 用户拍板: RSS 阅读页去外壳, 功能移入浏览器窗口工具栏)。
     *
     * 独立浏览器窗口带 RSS 按钮组 (收藏/朗读/分享/登录), 动作经 [RssToolbarActions]
     * 回调回 shared; 窗口关闭 → RSS 路由出栈, 路由出栈 → 窗口关闭 (经 onDetach, 幂等);
     * 引擎不可用/开窗失败降级系统浏览器并提示。返回 true (桌面端不再渲染页面外壳)。
     */
    override fun openRssReader(
        book: Book,
        chapter: BookChapter?,
        url: String,
        html: String?,
        headerMap: Map<String, String>,
        actions: RssToolbarActions,
        js: String?,
    ): Boolean {
        val target = url.ifBlank { book.tocUrl }
        val engine = DesktopWebViewEngines.get(forceRefresh = true)
        if (engine == null) {
            browseUrl(target)
            runCatching {
                Toasters.get().toastLong("内置浏览器不可用, 已用系统浏览器打开: ${book.name}")
            }
            return true
        }
        // 路由/窗口双向联动: 窗口关闭 → 路由出栈; 路由先出栈 (onDetach) → 关窗 (幂等)
        val detached = AtomicBoolean(false)
        var handle: WebViewWindowHandle? = null
        actions.onDetach = {
            detached.set(true)
            handle?.close()
        }
        handle = engine.openWindow(
            WebViewWindowRequest(
                url = url,
                html = html,
                title = book.name,
                cookieTag = book.origin,
                // RSS 窗口书源菜单: 默认 book 类型 (book/rss 同走 bookSourceDao,
                // 禁用/删除行为与源类型无关); 确认弹窗显示书源名
                sourceType = SourceType.book,
                sourceName = book.originName,
                rssActions = actions,
                // 源注入脚本: 对照原版每次网页加载完成后执行 (正文分支与直接加载网址分支
                // 都经这里执行), 窗口引擎在每次导航完成时回调本处, 经窗口句柄执行脚本
                onNavigated = {
                    val script = js
                    if (!script.isNullOrBlank()) {
                        scope.launch { handle?.evaluateJavascript(script) }
                    }
                },
                onClosed = {
                    // 窗口被关闭 → RSS 路由出栈; 但路由先出栈 (onDetach → close) 触发
                    // 的 close 回调不能再 pop, 否则会误弹 RSS 之下的路由 (reviewer 2026-08-07)
                    if (!detached.get()) {
                        scope.launch(Dispatchers.Main) {
                            AppNavigatorProviders.get().pop()
                        }
                    }
                },
            )
        )
        if (handle == null) {
            browseUrl(target)
            runCatching {
                Toasters.get().toastLong("内置浏览器窗口打开失败, 已用系统浏览器打开: ${book.name}")
            }
        } else if (detached.get()) {
            // 路由已先出栈: 立即关闭刚打开的窗口
            handle.close()
        }
        return true
    }

    override fun shareText(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    override fun copyToClipboard(text: String) {
        shareText(text)
        runCatching { Toasters.get().toast(syncGetString("copy_complete")) }
    }

    override fun getClipboardText(): String? = runCatching {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) return null
        clipboard.getData(DataFlavor.stringFlavor) as? String
    }.getOrNull()

    override fun testDirectLinkUpload(
        rule: DirectLinkUploadRule,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        scope.launch {
            runCatching {
                io.legado.desktop.help.DesktopDirectLinkUpload.upLoad(
                    "test.json", "{}", "application/json", rule,
                )
            }.onSuccess { onSuccess(it) }
                .onFailure { onError(it.localizedMessage ?: it.toString()) }
        }
    }

    override fun upLoadFile(
        fileName: String,
        file: Any,
        contentType: String,
        onResult: (String?) -> Unit
    ) {
        scope.launch {
            runCatching {
                io.legado.desktop.help.DesktopDirectLinkUpload.upLoad(
                    fileName, file, contentType
                )
            }.onSuccess { url ->
                withContext(Dispatchers.Main) { onResult(url) }
            }.onFailure { error ->
                AppLog.put("上传文件失败\n${error.message}", error)
                Toasters.get().toast("上传文件失败\n${error.message}")
                withContext(Dispatchers.Main) { onResult(null) }
            }
        }
    }

    // 按 bookUrl 查 DB 解析 BookRef, 供 deep link / 文件关联的路由导航
    override suspend fun resolveBookRef(bookUrl: String): BookRef? =
        appDb.bookDao.getBook(bookUrl)?.toRouteRef()

    // 书架布局 / 底栏配置: shared Compose 对话框 (BookshelfNavConfigDialogs.kt)

    override fun showThemeCustomizeDialog(configIndex: Int?, isNight: Boolean) {
        val mode = if (configIndex == null) MODE_NEW_CONFIG else MODE_EDIT_CONFIG
        val index = configIndex ?: -1
        AppNavigatorProviders.get()
            .showOverlay(AppOverlay.Dialog("theme_customize", payload = "$mode,$index,$isNight"))
    }

    override fun showCustomizeDayThemeDialog() {
        AppNavigatorProviders.get()
            .showOverlay(AppOverlay.Dialog("theme_customize", payload = "$MODE_EDIT_PREFS,-1,false"))
    }

    override fun showCustomizeNightThemeDialog() {
        AppNavigatorProviders.get()
            .showOverlay(AppOverlay.Dialog("theme_customize", payload = "$MODE_EDIT_PREFS,-1,true"))
    }

    override fun getAppVersionName(): String? = DesktopAppInfo.versionName

    // 桌面窗口无系统状态栏/导航栏, 也无屏幕方向: 对应设置项隐藏
    override fun hasSystemBars(): Boolean = false

    override fun hasScreenOrientation(): Boolean = false

    // 换封面源: 对照 app 端同名方法, 走 "change_cover" overlay (payload="name\nauthor"),
    // 结果经 overlayResults 的 RouteResultPayload.ChangeCover 回传
    override fun showChangeCoverDialog(book: Book, onCoverSelected: (String) -> Unit) {
        val navigator = AppNavigatorProviders.get()
        scope.launch {
            // 弹层是界面动作, 回主线程; 能力作用域是 IO, 弹层栈是"先读后写"。
            withContext(mainDispatcher) {
                navigator.showOverlay(
                    AppOverlay.Dialog("change_cover", payload = "${book.name}\n${book.author}")
                )
            }
            val result = navigator.overlayResults.first { it.key == "change_cover" }
            val payload = result.payload as? RouteResultPayload.ChangeCover ?: return@launch
            withContext(Dispatchers.Main) { onCoverSelected(payload.coverUrl) }
        }
    }

    // ===== 关于页 =====
    // 检查更新四端统一走 shared: AboutRoute → AboutScreenModel.checkUpdate →
    // AppUpdateManager (环境由 registerDesktopAppUpdate 注册, 见 DesktopAppUpdate.kt),
    // 无平台分支

    // 崩溃日志: 与 app 端同走 shared OverlayContentHost 的 "crash_logs" key
    // (数据源 = DesktopPlatformServices.crashLogs, 写入方见 DesktopCrashHandler)

    // 对照 app 端 saveLog: 打包运行日志 + 崩溃日志 (缓存里的 log 目录) 与堆转储成 logs.zip,
    // 落书籍保存位置下的 log 目录 (与崩溃日志同处, 书籍直接放根); 未设置保存位置则提示中止
    override fun saveLog() {
        scope.launch {
            runCatching { saveLogInternal() }
                .onSuccess { Toasters.get().toast("已保存至 $it") }
                .onFailure { error ->
                    if (error is InvalidBooksDirException) {
                        Toasters.get().toast(error.message ?: "未设置书籍保存位置")
                    } else {
                        AppLog.put("保存日志出错\n${error.localizedMessage}", error)
                        Toasters.get().toast("保存日志出错\n${error.message}")
                    }
                }
        }
    }

    // 对照 app 端 createHeapDump: Android 用 Debug.dumpHprofData, JVM 用 HotSpotDiagnosticMXBean
    override fun createHeapDump() {
        scope.launch {
            Toasters.get().toast("开始创建堆转储")
            runCatching { createHeapDumpInternal() }
                .onSuccess { Toasters.get().toast("堆转储已保存至 ${it.absolutePath}") }
                .onFailure {
                    AppLog.put("保存堆转储失败\n${it.localizedMessage}", it)
                    Toasters.get().toast("保存堆转储失败\n${it.message}")
                }
        }
    }

    /**
     * 打包日志到 logs.zip (对照 app 端 copyLogs + copyHeapDump, 桌面端无 logcat 环节)。
     *
     * 源: `{cacheDir}/log` (运行日志与崩溃日志同目录, AppLog 与崩溃处理器写入) + `{cacheDir}/heapDump`;
     * 目标: `书籍保存位置/log/logs.zip`。
     *
     * @return 目标 zip 绝对路径
     */
    private suspend fun saveLogInternal(): String = withContext(Dispatchers.IO) {
        val logDir = bookLogDirOrNull()
            ?: throw InvalidBooksDirException("未设置书籍保存位置")
        if (!AppLog.isRecordLogEnabled) {
            Toasters.get().toast("未开启日志记录, 请去其他设置里打开记录日志")
        }
        val cacheDir = File(AppFilesDirs.get().cacheDir)
        val sources = listOf(
            DesktopCrashLogDirs.cacheCrashDir,
            File(cacheDir, "heapDump"),
        ).filter { it.exists() }
        if (sources.isEmpty()) throw NoStackTraceException("没有可保存的日志文件")
        val outDir = File(logDir).apply { mkdirs() }
        val zipFile = File(outDir, "logs.zip")
        // 打包到同目录临时件再整份改名就位: 直接打包到目标会先把上一轮的 logs.zip 截断,
        // 打包被中断时用户手里既没有新件也没有旧件。临时件名走共享契约 ([atomicWriteTempPath]):
        // 导出目录是用户可见目录, 没有孤儿临时件的回收方, 残留只能靠下面的 finally 清掉
        val tempZip = File(atomicWriteTempPath(zipFile.path))
        try {
            if (!ZipUtils.zipFiles(sources, tempZip)) {
                throw NoStackTraceException("打包日志失败")
            }
            FileUtilsBase.replaceFileAtomically(tempZip, zipFile)
        } finally {
            tempZip.delete()
        }
        zipFile.absolutePath
    }

    /**
     * JVM 堆转储 (对照 app 端 CrashHandler.doHeapDump 的 Debug.dumpHprofData)。
     *
     * app 端每次 createFolderReplace 只留最新一份, 这里同样先清空目录。
     * 落 `{cacheDir}/heapDump`, 再经 [saveLog] 一起打包出去 (hprof 体积大, 不直接落用户目录)。
     */
    private suspend fun createHeapDumpInternal(): File = withContext(Dispatchers.IO) {
        val heapDir = File(AppFilesDirs.get().cacheDir, "heapDump")
        heapDir.deleteRecursively()
        heapDir.mkdirs()
        val heapFile = File(heapDir, "heap-dump-manually-${System.currentTimeMillis()}.hprof")
        System.gc()
        // HotSpotDiagnosticMXBean 是 JDK 内置诊断 MBean (com.sun.management), 经 MBeanServer
        // 反射调用避免对 jdk.management 模块的编译期依赖 (非 HotSpot JVM 上优雅失败)
        val server = ManagementFactory.getPlatformMBeanServer()
        server.invoke(
            ObjectName("com.sun.management:type=HotSpotDiagnostic"),
            "dumpHeap",
            arrayOf(heapFile.absolutePath, true),
            arrayOf("java.lang.String", "boolean"),
        )
        heapFile
    }

    // ===== 书籍详情页 =====

    // 本地书文件字节数 (对照 app 端 FileDoc.fromFile(bookUrl).size; bookUrl 形如 file:///path)
    override suspend fun localBookFileSize(bookUrl: String): Long = withContext(Dispatchers.IO) {
        runCatching { resolveLocalFile(bookUrl).length() }.getOrDefault(0L)
    }

    // 对照 BookInfoViewModel.downloadToLocal: 业务已共享 (FileBook.downloadRemoteBook)
    override fun downloadBookToLocal(book: Book, success: (() -> Unit)?) {
        scope.launch {
            runCatching { FileBook.downloadRemoteBook(book) }
                .onSuccess {
                    withContext(Dispatchers.Main) {
                        Toasters.get().toast("下载成功")
                        success?.invoke()
                    }
                }
                .onFailure { error ->
                    // 保存位置未设置时走既有的"去设置目录"出口 (与书籍详情的 web 文件导入同一
                    // 入口与偏好键); 用户取消 (uri == null) 时不动既有配置
                    if (error is InvalidBooksDirException) {
                        withContext(Dispatchers.Main) {
                            PlatformCapabilityProviders.get().pickBookTreeUri { uri ->
                                if (uri != null) {
                                    prefs.putString(PreferKey.defaultBookTreeUri, uri)
                                }
                            }
                        }
                    }
                    AppLog.put("下载远程书籍<${book.name}>失败", error, true)
                }
        }
    }

    // 对照 BookInfoActivity.onReadClick isWebFile 分支 + showWebFileDownloadAlert:
    // 直链书点"阅读"先选一个下载项, 再下载导入并跳阅读
    override fun handleWebFileRead(
        book: Book,
        onWaitDialog: (Boolean) -> Unit,
        onAction: (String) -> Unit,
        onSuccess: ((Book) -> Unit)?,
    ) {
        scope.launch {
            // 对照 BaseReadViewModel.loadWebFile: 本地书无书源, 其余按 origin 查源
            val source = if (book.origin != BookType.localTag) {
                appDb.bookSourceDao.getBookSource(book.origin)
            } else null
            // 构建 webFile 列表的平台无关段已下沉 WebFileImportShared (同 BaseReadViewModel.loadWebFile)
            val webFiles = WebFileImportShared.buildWebFiles(book, source)
            withContext(Dispatchers.Main) {
                if (webFiles.isEmpty()) {
                    Toasters.get().toast("Unexpected webFileData")
                    return@withContext
                }
                showWebFileDownloadAlert(book, webFiles, onWaitDialog, onAction, onSuccess)
            }
        }
    }

    // 对照 BookInfoActivity.showWebFileDownloadAlert: 下载项选择框 (桌面端走
    // DesktopDialogRequest.SelectList, 与 app 端 activity.selector 同语义)
    private fun showWebFileDownloadAlert(
        book: Book,
        webFiles: List<FileBook.WebFile>,
        onWaitDialog: (Boolean) -> Unit,
        onAction: (String) -> Unit,
        onSuccess: ((Book) -> Unit)?,
    ) {
        DesktopDialogs.show(
            DesktopDialogRequest.SelectList(
                title = syncGetString("download_and_import_file"),
                items = webFiles.map { it.name },
            ) { index ->
                val webFile = index?.let { webFiles.getOrNull(it) } ?: return@SelectList
                when {
                    webFile.isSupported ->
                        importWebFile(book, webFile, onWaitDialog, onAction) { onSuccess?.invoke(it) }

                    webFile.isSupportDecompress ->
                        downloadWebFile(book, webFile, onWaitDialog, onAction) { path ->
                            getArchiveFilesName(path) { fileNames ->
                                if (fileNames.size == 1) {
                                    importBookFromArchive(
                                        path,
                                        fileNames[0],
                                        book,
                                        onWaitDialog
                                    ) { onSuccess?.invoke(it) }
                                } else {
                                    showDecompressFileImportAlert(
                                        path,
                                        fileNames,
                                        book,
                                        onWaitDialog,
                                        onSuccess
                                    )
                                }
                            }
                        }

                    else -> showUnsupportedWebFileAlert(book, webFile, onWaitDialog, onAction)
                }
            }
        )
    }

    // 对照 BookInfoActivity.showDecompressFileImportAlert: 压缩包内选书
    private fun showDecompressFileImportAlert(
        archiveFilePath: String,
        fileNames: List<String>,
        book: Book,
        onWaitDialog: (Boolean) -> Unit,
        onSuccess: ((Book) -> Unit)?,
    ) {
        if (fileNames.isEmpty()) {
            Toasters.get().toast(syncGetString("unsupport_archivefile_entry"))
            return
        }
        DesktopDialogs.show(
            DesktopDialogRequest.SelectList(
                title = syncGetString("import_select_book"),
                items = fileNames,
            ) { index ->
                val name = index?.let { fileNames.getOrNull(it) } ?: return@SelectList
                importBookFromArchive(archiveFilePath, name, book, onWaitDialog) {
                    onSuccess?.invoke(it)
                }
            }
        )
    }

    // 对照 app 端 showWebFileDownloadAlert 的 open_fun 分支: 不支持的格式下载后交系统程序打开
    // (桌面等价的 openFileUri 通道 = openFileWithSystem, 与 ServiceLauncher 同一出口)
    private fun showUnsupportedWebFileAlert(
        book: Book,
        webFile: FileBook.WebFile,
        onWaitDialog: (Boolean) -> Unit,
        onAction: (String) -> Unit,
    ) {
        DesktopDialogs.show(
            DesktopDialogRequest.Confirm(
                title = syncGetString("draw"),
                message = syncGetString("file_not_supported", webFile.name),
                okText = syncGetString("open_fun"),
                noText = syncGetString("cancel"),
                onOk = {
                    downloadWebFile(book, webFile, onWaitDialog, onAction) { path ->
                        openFileWithSystem(path)
                    }
                },
                onNo = {},
            )
        )
    }

    // 对照 RemoteBookActivity.startRead 的 archive 分支: 在书籍保存目录里找已下载的压缩包,
    // 找到就交文件关联分发链 (与 NativeImportBook.openReader 同口径: 桌面无压缩包内选章 UI,
    // 走 FileAssociationDispatch 的直接导入链), 没找到就弹确认框由调用方重新下载后重试
    override fun startReadRemoteArchive(fileName: String, onNeedDownload: () -> Unit) {
        scope.launch {
            // 与 DesktopFileBookAccessor 的有效书籍保存目录同源 (用户在"书籍保存位置"选定)
            val dir = effectiveBookSaveDir()
            val path = dir?.let { File(it, fileName) }
            if (path != null && path.isFile) {
                FileAssociationDispatch.dispatch(path.absolutePath)
                return@launch
            }
            withContext(Dispatchers.Main) {
                DesktopDialogs.show(
                    DesktopDialogRequest.Confirm(
                        title = syncGetString("draw"),
                        message = syncGetString("archive_not_found"),
                        okText = syncGetString("ok"),
                        noText = syncGetString("cancel"),
                        onOk = { onNeedDownload() },
                        onNo = {},
                    )
                )
            }
        }
    }

    // ===== 书架管理: 导出 =====

    override fun enableCustomExport(): Boolean = prefs.getBoolean(PreferKey.enableCustomExport, false)
    override fun getDeleteBookOriginal(): Boolean = prefs.getBoolean(LocalConfigKeys.deleteBookOriginal, false)

    // 导出书籍所用书源 JSON (对照 exportAllUseBookSource)
    override fun exportAllUseBookSource() {
        scope.launch {
            val sources = runCatching { appDb.bookDao.getAllUseBookSource() }.getOrElse {
                Toasters.get().toast("导出所用书源失败\n${it.message}")
                return@launch
            }
            val json = sources.toExportJsonText()
            withContext(Dispatchers.Main) {
                pushExportDispatch("bookSource.json", json, "application/json")
            }
        }
    }

    // 导出书架 JSON (字段清单与 app 端 exportBookshelf 一致)
    override fun exportBookshelf(books: List<Book>) {
        if (books.isEmpty()) {
            Toasters.get().toast("导出书籍出错\n书籍不能为空")
            return
        }
        val json = bookshelfJson(books)
        pushExportDispatch("bookshelf.json", json, "application/json")
    }

    // 导出书籍正文, 格式取导出配置 (0=txt 1=epub, 对照 app 端 AppConfig.exportType;
    // 图片书自动走 cbz), 目录用"书籍保存位置" (与下载书籍/备份 zip 同一处, 书籍直接放根)。
    // 未设置先拉选择器; 已设置但目录不存在且建不出 (被删/盘符失效) 同样重新选一次, 取消则本次导出作罢;
    // 本次新选定目录且开了自定义导出时先弹章节配置 (对照原版 exportDir 回调), 否则直接导出
    override fun exportAllBooks(books: List<Book>) {
        if (books.isEmpty()) {
            Toasters.get().toast(syncGetString("no_book"))
            return
        }
        scope.launch {
            var freshlySelected = effectiveBookSaveDir() == null
            if (freshlySelected && !ensureBookSaveDirSelected()) return@launch
            var dir = effectiveBookSaveDir() ?: return@launch
            if (!File(dir).isDirectory && !File(dir).mkdirs()) {
                if (!reselectBookSaveDir()) return@launch
                dir = effectiveBookSaveDir() ?: return@launch
                freshlySelected = true
            }
            if (freshlySelected && io.legado.app.utils.enableCustomExport()) {
                showExportSectionConfig(books)
            } else {
                startExport(dir, books)
            }
        }
    }

    // 导出配置弹窗 (对照 app 端 showExportConfig / dialog_export_config.xml 全量字段):
    // 导出文件名 JS 规则 / 导出类型 txt|epub / 导出编码 / TXT 不导出章节名
    // (cbz 按 app 端语义由图片书自动选择, 无需用户配置); 弹共享实现 (与鸿蒙同一形制)
    override fun showExportConfig() {
        AppNavigatorProviders.get().showOverlay(AppOverlay.Dialog("book_export_format"))
    }

    // 自定义章节导出配置弹窗: 弹共享实现 (四端同一份)
    // (导出全部 / 自定义导出: 章节范围 + epub 分卷大小 + epub 文件名 JS 规则)
    override fun showExportSectionConfig(books: List<Book>) {
        pushExportSectionConfig(books)
    }

    // 对照原版 configExportSection 的自定义分支: 逐本按 epubScope/epubSize 导出
    override fun exportCustomBooks(books: List<Book>, epubSize: Int, epubScope: String) {
        val dir = effectiveBookSaveDir() ?: return
        startCustomExport(dir, books, epubScope, epubSize)
    }

    private fun startCustomExport(path: String, books: List<Book>, range: String, size: Int) {
        enqueueExport(books, path, label = " (自定义章节)") { ExportMode.CustomEpub(range, size) }
    }

    /**
     * 把当前导出进度交给托盘呈现 (对照 app 端 ExportBookService.upExportNotification):
     * 托盘图标常驻显示书名 + 剩余待导本数, 右键菜单可取消 (取消即取消本次导出任务)。
     */
    private fun upExportTrayBinding(book: Book, remaining: Int) {
        DesktopMediaTray.export = ExportTrayBinding(
            bookName = book.name,
            remaining = remaining,
            cancel = { exportBooksJob?.cancel() },
        )
    }

    private fun startExport(dir: String, books: List<Book>) {
        val type = prefs.getInt(PreferKey.exportType, 0)
        // 导出配置在此快照 (对照原版 BookshelfManageActivity 把 exportType 随 intent 传进服务),
        // 图片书一律导 cbz (对照 app 端 MainActivity.startExportBooks)
        enqueueExport(books, dir) {
            ExportMode.ByConfig(if (it.isImage) "cbz" else if (type == 1) "epub" else "txt")
        }
    }

    /**
     * 导出请求入队 (对照原版 BookshelfManageActivity.startExportBooks → ExportBookService:
     * 每本书一条队列项, 重复触发入队稍后照常导出), 队列空闲时顺手拉起执行者。
     *
     * @param label 导出方式后缀 (自定义章节导出提示里带"自定义章节", 普通导出为空)
     * @param modeOf 按书算导出方式 (图片书一律 cbz 等)
     */
    private fun enqueueExport(
        books: List<Book>,
        path: String,
        label: String = "",
        modeOf: (Book) -> ExportMode,
    ) {
        if (books.isEmpty()) return
        val (added, started) = synchronized(this) {
            // 同一本书只入队一次 (正在导出的那本不入队), 已在队列里则换成最新落点
            var count = 0
            books.forEach { book ->
                if (book.bookUrl == exportingBookUrl) return@forEach
                waitExportBooks[book.bookUrl] = PendingExport(path, book, modeOf(book))
                count++
            }
            val start = count > 0 && !exportWorkerRunning
            if (start) exportWorkerRunning = true
            count to start
        }
        if (added == 0) return
        Toasters.get().toast(
            if (started) "开始导出 $added 本$label" else "已加入导出队列 $added 本$label"
        )
        if (started) {
            exportBooksJob = scope.launch { runExportQueue() }
        }
    }

    /** 取队首的一本书并标记"正在导出" (对照原版 export(): 取队首 → exportProgress 置位), 空队列返回 null。 */
    private fun takeNextExport(): PendingExport? = synchronized(this) {
        val entry = waitExportBooks.entries.firstOrNull() ?: return null
        waitExportBooks.remove(entry.key)
        exportingBookUrl = entry.key
        entry.value
    }

    /** 队列里还剩几本待导 (托盘进度显示用)。 */
    private fun pendingExportCount(): Int = synchronized(this) { waitExportBooks.size }

    /** 当前这本导完, 清掉"正在导出"标记。 */
    private fun finishExport() = synchronized(this) { exportingBookUrl = null }

    /**
     * 队列空时收工 (与 [enqueueExport] 同锁): 确认没新入队才真收工, 否则继续导 —— 否则在收工
     * 的一瞬间入队的书就没人导了。托盘进度与"执行者在跑"同一原子动作清掉, 不会清掉下一批的绑定。
     */
    private fun finishWorkerIfQueueEmpty(): Boolean = synchronized(this) {
        if (waitExportBooks.isNotEmpty()) {
            false
        } else {
            exportWorkerRunning = false
            DesktopMediaTray.export = null
            true
        }
    }

    /** 取消时丢掉剩余队列并归还执行者名额 (对照原版 IntentAction.stop → stopSelf: 未导出的不再导出)。 */
    private fun discardExportQueue() = synchronized(this) {
        waitExportBooks.clear()
        exportingBookUrl = null
        exportWorkerRunning = false
        DesktopMediaTray.export = null
    }

    /**
     * 串行导出队列 (对照原版 ExportBookService.export(): 逐本从队首取出, 导完一本再取下一本)。
     * 单本出错只记日志并继续下一本; 托盘取消则丢掉剩余队列, 与 app 端 stopSelf 同语义。
     */
    private suspend fun runExportQueue() {
        val failures = mutableListOf<String>()
        var lastPath: String? = null
        var exported = 0
        try {
            while (true) {
                val pending = takeNextExport()
                if (pending == null) {
                    if (finishWorkerIfQueueEmpty()) break
                    continue
                }
                lastPath = pending.path
                try {
                    upExportTrayBinding(pending.book, pendingExportCount())
                    when (val mode = pending.mode) {
                        is ExportMode.ByConfig -> when (mode.type) {
                            "cbz" -> DesktopBookExport.exportCbz(pending.path, listOf(pending.book))
                            "epub" -> DesktopBookExport.exportEpub(pending.path, listOf(pending.book))
                            else -> DesktopBookExport.exportTxt(pending.path, listOf(pending.book))
                        }

                        is ExportMode.CustomEpub -> DesktopBookExport.exportCustomEpub(
                            pending.path, listOf(pending.book), mode.range, mode.size
                        )
                    }
                    exported++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures.add("${pending.book.name}：${e.message ?: "未知错误"}")
                    AppLog.put("导出书籍出错\n${pending.book.name}\n${e.message}", e)
                } finally {
                    finishExport()
                }
            }
        } catch (e: CancellationException) {
            // 托盘取消导出: 不报错, 已写出的半成品与 app 端取消语义一致; 剩余队列不再导出
            discardExportQueue()
            throw e
        } catch (e: Throwable) {
            // 意外异常不能把执行者名额带走, 否则后续导出会一直排队没人导
            discardExportQueue()
            throw e
        }
        if (exported > 0) {
            lastPath?.let { Toasters.get().toast("导出完成\n$it") }
        }
        if (failures.isNotEmpty()) {
            Toasters.get().toast("${failures.size} 本书导出失败：${failures.take(2).joinToString()}")
        }
        if (exported > 0 && failures.isNotEmpty()) {
            AppLog.put("部分书籍导出失败：${failures.joinToString()}")
        }
    }

    // ===== 书源管理 =====

    override fun selectionAddToGroups(selection: List<BookSourcePart>) {
        if (selection.isEmpty()) return
        DesktopDialogs.show(
            DesktopDialogRequest.TextInput(title = "添加分组", hint = "分组名") { group ->
                val name = group.trim()
                if (name.isEmpty()) return@TextInput
                bookSourceViewModel.selectionAddToGroups(selection, name)
            }
        )
    }

    override fun selectionRemoveFromGroups(selection: List<BookSourcePart>) {
        if (selection.isEmpty()) return
        DesktopDialogs.show(
            DesktopDialogRequest.TextInput(title = "移除分组", hint = "分组名") { group ->
                val name = group.trim()
                if (name.isEmpty()) return@TextInput
                bookSourceViewModel.selectionRemoveFromGroups(selection, name)
            }
        )
    }

    override fun exportBookSourceSelection(
        selection: List<BookSourcePart>,
        allCount: Int,
        sortAscending: Boolean,
        sort: BookSourceSort
    ) {
        if (selection.isEmpty()) return
        scope.launch {
            val json = selectedSourcesJson(selection, allCount, sortAscending, sort) ?: return@launch
            withContext(Dispatchers.Main) {
                pushExportDispatch("bookSource.json", json, "application/json")
            }
        }
    }

    // 对照 app 端 menu_share_source: saveToFile + share(file)。桌面端无系统分享面板,
    // shareFile 用系统文件管理器定位文件 (Windows: explorer /select; macOS: open -R; Linux: xdg-open)
    override fun shareBookSourceSelection(
        selection: List<BookSourcePart>,
        allCount: Int,
        sortAscending: Boolean,
        sort: BookSourceSort
    ) {
        scope.launch {
            val json = selectedSourcesJson(selection, allCount, sortAscending, sort) ?: return@launch
            val dir = File(DataStorageProviders.get().userExportDir).apply { mkdirs() }
            val file = File(dir, "shareBookSource.json")
            runCatching {
                // 整份落盘: 覆盖写被中断会在用户目录留下半截 JSON, 分享出去的是一份打不开的文件
                check(writeTextAtomically(file.path, json)) { "写入失败: ${file.path}" }
            }
                .onSuccess {
                    // 分享与提示是界面动作, 回主线程; 能力作用域是 IO。
                    withContext(mainDispatcher) {
                        PlatformServiceProviders.get().sharing
                            .shareFile(file.absolutePath, "application/json")
                        Toasters.get().toast("已导出到 ${file.absolutePath}")
                    }
                }
                .onFailure { error ->
                    withContext(mainDispatcher) {
                        Toasters.get().toast("导出失败\n${error.message}")
                    }
                }
        }
    }

    // 关键词输入与校验设置中性钮走共享对话框 (与 Android 逐字一致; 平台侧只负责启动)
    override fun checkBookSource(selection: List<BookSourcePart>) {
        showCheckBookSourceDialog(selection)
    }

    override fun startCheckBookSource(urls: List<String>) {
        DesktopCheckSource.startUrls(urls)
    }

    override fun cancelCheckSource() {
        DesktopCheckSource.stop()
        Debug.finishChecking()
    }

    /**
     * 退出前收尾: 停掉正在跑的后台任务 (缓存下载 / 书源校验 / 导出), 并等导出协程收手。
     *
     * 缓存下载与书源校验的停止口是同步的 (调完即已停); 导出跑在协程上, 取消后不等它结束,
     * 进程会在写文件的中途被结束 —— 文件流没走完收尾, 留下的半成品比"取消当时已写完的部分"
     * 更不完整。故等待其 job 真正完成; 半成品本身保留不删 (与托盘取消导出同一路径)。
     *
     * 等导出有上限: 卡在不可中断的网络/磁盘调用时无限等下去, 会让退出永远完不成 ——
     * 退不掉比丢失半本导出更严重 (进程已不再响应任何退出操作)。
     */
    internal suspend fun stopBackgroundTasksAwait() {
        runCatching { ServiceLaunchers.get().stopCacheBookService() }
        runCatching { cancelCheckSource() }
        val exportJob = exportBooksJob ?: return
        exportJob.cancel()
        withTimeoutOrNull(EXIT_EXPORT_GRACE_MS) { exportJob.join() }
    }

    private const val EXIT_EXPORT_GRACE_MS = 5_000L

    // ===== 其它设置 =====

    override fun pickBookTreeUri(onSelected: (String?) -> Unit) {
        scope.launch { onSelected(FileDialogs.pickDirectory("选择书籍目录")?.absolutePath) }
    }

    // 桌面端目录是普通路径: 先查选定目录本身, 再查一层子目录 (与 Android 端 SAF 查找同语义)。
    // bookUrl 经 desktopStoredLocalRef 生成 (数据根下存相对引用, 外部文件存 file: URI),
    // 与 importBook 写入的形态一致, 否则 JvmLocalBookLocator 解析不到。
    override fun findBookFileInDir(dirUri: String, fileName: String): String? {
        val dir = File(dirUri).takeIf { it.isDirectory } ?: return null
        File(dir, fileName).takeIf { it.isFile }?.let { return desktopStoredLocalRef(it) }
        return dir.listFiles { f -> f.isDirectory }
            ?.asSequence()
            ?.mapNotNull { sub -> File(sub, fileName).takeIf { it.isFile } }
            ?.firstOrNull()
            ?.let { desktopStoredLocalRef(it) }
    }

    /**
     * 清理内嵌浏览器数据 (对照 app 端 clearWebViewData 删 webview 私有目录)。
     *
     * 桌面端 WebView2 的 cookie/localStorage 落 `{cacheDir}/webview2` (见 WebView2Instance);
     * 该目录被存活的 WebView2 环境独占, 运行中删不干净, 故与 app 端一样删完提示重启。
     * app 端 delay 后 appCtx.restart(), 桌面端不自动重启进程 (用户可能正在阅读), 只提示。
     */
    override fun clearWebViewData() {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    File(AppFilesDirs.get().cacheDir, "webview2").deleteRecursively()
                }.getOrDefault(false)
            }
            if (ok) {
                Toasters.get().toast("已清理 WebView 数据, 重启应用后生效")
            } else {
                // 浏览器窗口开着时目录被占用, 删除会部分失败
                Toasters.get().toast("WebView 数据未能完全清理, 请关闭所有浏览器窗口后重试")
            }
        }
    }

    /**
     * 扫描字体 (对照 app 端 scanFontItems: pref 字体目录 + 内置字体目录, 同名去重按名排序)。
     *
     * 桌面端无 SAF, fontFolder 恒为真实路径; 内置目录取 DataStorage.fontsDir
     * (对应 app 端 externalFiles/font)。
     * 不走 shared 版: 本端返回 [java.io.File.absolutePath] (相对 pref 也会解析成绝对路径),
     * shared 版返回 okio 列目录得到的路径串, 两者未逐字等价。
     */
    override suspend fun scanFontItems(): List<FontItem> = withContext(Dispatchers.IO) {
        val fontRegex = AppPattern.fontFileRegex
        val items = arrayListOf<FontItem>()
        // 先扫 pref 目录: distinctBy 保留先出现者, 与 app 端 mergeFontItems 优先级一致
        prefs.getString(PreferKey.fontFolder).takeIf { it.isNotBlank() }?.let {
            scanFontDir(items, File(it), fontRegex)
        }
        scanFontDir(items, File(DataStorageProviders.get().fontsDir), fontRegex)
        items.distinctBy { it.name }.sortedBy { it.name }
    }

    private fun scanFontDir(items: MutableList<FontItem>, dir: File, fontRegex: Regex) {
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach {
            if (it.isFile && it.name.matches(fontRegex)) {
                items.add(FontItem(it.absolutePath, it.name))
            }
        }
    }

    // ===== 导入本地书 (状态与扫描见 DesktopImportBook) =====

    override suspend fun initImportBookData(): Boolean {
        DesktopImportBook.init()
        return DesktopImportBook.needsRootDir
    }

    override fun importBookItems(): StateFlow<List<ImportFileItem>> = DesktopImportBook.items
    override fun importBookPath(): StateFlow<String?> = DesktopImportBook.path
    override fun importBookLoading(): StateFlow<Boolean> = DesktopImportBook.loading
    override fun importBookEmptyMsgVisible(): StateFlow<Boolean> = DesktopImportBook.emptyMsgVisible

    override fun importBookHasParentDir(): StateFlow<Boolean> = DesktopImportBook.hasParentDir

    override fun pickImportFolder() {
        scope.launch { FileDialogs.pickDirectory("选择导入目录")?.let { DesktopImportBook.setRoot(it) } }
    }

    override fun scanImportFolder() = DesktopImportBook.scan()

    override fun alertImportFileName() {
        DesktopDialogs.show(
            DesktopDialogRequest.TextInput(
                title = "按文件名导入",
                message = "使用js处理文件名变量src，将书名作者分别赋值到变量name author",
                initialValue = prefs.getString(PreferKey.bookImportFileName, ""),
                hint = "js",
            ) { prefs.putString(PreferKey.bookImportFileName, it) }
        )
    }

    override fun addImportSelectionToBookshelf(items: List<ImportFileItem>, onComplete: () -> Unit) {
        // 压缩包导入需要书籍保存位置: 未设置时经既有"书籍保存位置"选择器选定后继续导入
        DesktopImportBook.addToBookshelf(
            items,
            onComplete,
            onNeedBooksDir = { ensureBookSaveDirSelected() },
        )
    }

    /** 选文件导入: 选到的文件直接当条目走上架链 (不经列表勾选)。 */
    override fun importPickedBookFiles(files: List<PickedFile>, onComplete: () -> Unit) {
        addImportSelectionToBookshelf(
            files.map { DesktopImportFile(File(it.ref)) },
            onComplete,
        )
    }

    override fun updateImportBookFilter(key: String) = DesktopImportBook.updateFilter(key)

    override fun updateImportBookSort(sort: Int) = DesktopImportBook.updateSort(sort)

    override fun navigateImportDir(item: ImportFileItem) = DesktopImportBook.enterDir(item)

    override fun goBackImportDir() {
        DesktopImportBook.goBack()
    }

    override fun openImportedBookReader(item: ImportFileItem) {
        val file = (item as? DesktopImportFile)?.file ?: return
        openImportFile(file.absolutePath)
    }

    // 完整分发链 (压缩包/JSON 一键导入/书籍文件) 见 FileAssociationDispatch, 四端共用
    override fun openImportFile(filePath: String) {
        scope.launch { FileAssociationDispatch.dispatch(filePath) }
    }

    /**
     * 系统 TTS 设置入口 (朗读设置弹窗"系统TTS设置"项): 各平台打开自己的语音设置页。
     * - Windows: `ms-settings:speech` (设置 → 隐私 → 语音)
     * - macOS: 系统设置 → 辅助功能 → 朗读内容 (Spoken Content)
     * - Linux: 尽力尝试 gnome-control-center, 失败提示不支持
     */
    override fun openTtsSettings() {
        val os = System.getProperty("os.name").lowercase()
        val command = when {
            os.contains("win") -> listOf("cmd", "/c", "start", "", "ms-settings:speech")
            os.contains("mac") -> listOf(
                "open",
                "x-apple.systempreferences:com.apple.preference.universalaccess?SpokenContent"
            )

            os.contains("linux") -> listOf("gnome-control-center", "universal-access")
            else -> null
        }
        if (command == null) {
            unsupported("系统 TTS 设置")
            return
        }
        runCatching { ProcessBuilder(command).start() }.onFailure {
            AppLog.put("打开系统 TTS 设置失败: ${it.message}", it)
            unsupported("系统 TTS 设置")
        }
    }

    // ===== 私有辅助 =====

    private fun resolveLocalFile(bookUrl: String): File = if (bookUrl.startsWith("file:")) {
        // Windows 上 URI.path 是 "/C:/..." 不是合法路径, 必须交给 File(URI) 解析盘符
        runCatching { File(URI(bookUrl)) }.getOrElse { File(bookUrl.removePrefix("file:")) }
    } else {
        File(bookUrl)
    }
}
