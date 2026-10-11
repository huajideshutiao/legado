package io.legado.app.ui

import io.legado.app.constant.AppLog
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import io.legado.app.constant.BookType
import io.legado.app.constant.DirUsage
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.HttpTTS
import io.legado.app.docsUriToMountPath
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.config.effectiveBookExportDir
import io.legado.app.help.config.rememberBookExportDir
import io.legado.app.help.AppWebDavShared
import io.legado.app.help.book.effectiveBookSaveDir
import io.legado.app.help.book.getExportFileName
import io.legado.app.help.book.isImage
import io.legado.app.help.book.tryParesExportFileName
import io.legado.app.help.file.OhosDirAuthorizations
import io.legado.app.help.copyToClipboard as copyTextToClipboard
import io.legado.app.help.readFromClipboard
import io.legado.app.help.openURL
import io.legado.app.help.notification.cancelOhosUpdateBookRefresh
import io.legado.app.help.service.OhosCacheBookNotifications
import io.legado.app.help.service.OhosWebServiceNotifications
import io.legado.app.help.source.OhosCheckSource
import io.legado.app.help.toast.Toasters
import io.legado.app.help.tts.OhosReadAloudHost
import io.legado.app.help.upLoadToDirectLink
import io.legado.app.help.storage.NativeSaveLog
import io.legado.app.model.Debug
import io.legado.app.model.ExportBookUtils
import io.legado.app.model.fileBook.FileBook
import io.legado.app.napi.NotificationButton
import io.legado.app.napi.OhosNativeBridge
import io.legado.app.service.defaultTtsEngineConfig
import io.legado.app.ui.book.import.ImportFileItem
import io.legado.app.ui.book.info.WebFileImportShared
import io.legado.app.ui.book.source.BookSourceSort
import io.legado.app.ui.compose.platform.syncGetString
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.BookRef
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.ensureBookSaveDirSelected
import io.legado.app.ui.root.pushExportDispatch
import io.legado.app.ui.root.toRouteRef
import io.legado.app.utils.File
import io.legado.app.utils.GSON
import io.legado.app.utils.KS_JSON
import io.legado.app.utils.ScreenInfoProviders
import io.legado.app.utils.toJson
import io.legado.app.utils.verificationField
import io.legado.app.web.WebServerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * 鸿蒙端 [io.legado.app.ui.root.PlatformCapabilities]: 内核已下沉的能力复用 [SharedPlatformCapabilities] (对照 desktop),
 * 依赖弹窗宿主 (分组管理/文本输入/主题列表) 的能力保持 unsupported —
 * 需输入的弹窗走 ArkTS 侧自绘对话框桥 (select/confirm/form 三种, 见 SysDialogBridgeHandler.ets;
 * 本地书导入浏览已由 [NativeImportBook] 支持, "按文件名导入 js" 走 shared 的 TextInput Overlay)。
 * 转场动画 spec 不 override, 随 shared 默认 (iOS 式 300ms): 鸿蒙系统默认页面转场
 * 使用弹簧曲线 (spring curve), 时长与物理参数相关且不同设备默认动画不同
 * (查证 OpenHarmony 官方文档 arkts-navigation-animation.md: "默认转场动画使用弹簧曲线,
 * 时长不可控"), 无公开参数可读也无可复刻的稳定值, 鸿蒙端待平台能力接入后再定。
 */
object OhosPlatformCapabilities : NativePlatformCapabilities {
    private val scope = CoroutineScope(SupervisorJob() + IoDispatcher)

    override val capabilityScope: CoroutineScope get() = scope

    /**
     * 保存日志到备份目录 (对照原版 `AboutFragment.saveLog` 的三段式: 未设备份目录则提示中止、
     * 未开记录日志只提醒、写成功后提示"已保存至备份目录")。
     *
     * 打包与落盘见 nativeMain 的 [NativeSaveLog] —— 它要用备份目录的授权通道
     * (`copyBackupIntoUserDir`), 那个声明在 nativeMain, 本源集可见。
     */
    override fun saveLog() {
        NativeSaveLog.run(capabilityScope, AppLog.isRecordLogEnabled)
    }

    private val appDb get() = AppDbProviders.get()
    private val prefs get() = PreferenceProviders.get()
    private val services get() = PlatformServiceProviders.get()

    /** 导出队列与"正在导出"标记的读写锁 (与队列读写同锁判断, 避免"收工瞬间入队"的书没人导)。 */
    private val exportLock = SynchronizedObject()

    /**
     * 待导出队列, 键为书 url (对照原版 ExportBookService.waitExportBooks): 重复触发导出时新请求
     * 入队稍后照常导出; 同一本书已在队列里就换成最新导出方式。
     */
    private val waitExportBooks = LinkedHashMap<String, PendingExport>()

    /** 正在导出的书 url (对照原版 exportProgress: 同一本书正在导出时不再重复入队)。 */
    private var exportingBookUrl: String? = null

    /** 队列执行者是否在跑。 */
    private var exportWorkerRunning = false

    /** 队列执行者 (对照 app 端 ExportBookService 的服务存活, 可取消)。 */
    private var exportBooksJob: Job? = null

    /** 队列里的一本书: 导出方式 (对照原版 ExportBookService 的 bookUrl → ExportConfig)。 */
    private class PendingExport(val book: Book, val mode: ExportMode)

    /** 导出方式 (对照原版 ExportBookService.ExportConfig: type 或 epubScope+epubSize)。 */
    private sealed interface ExportMode {
        /** 按导出配置导: `txt` / `epub`, 图片书为 `cbz`。 */
        data class ByConfig(val type: Int) : ExportMode

        /** 自定义导出: 章节下标集合 + 分卷大小 (epub)。 */
        data class CustomEpub(val scope: Set<Int>, val size: Int) : ExportMode
    }

    /**
     * 当前显示密度 (px → vp), 由 [io.legado.app.MainOhos] 在根组合里经 [updateDisplayDensity] 注入。
     *
     * 唯一用途是 [isTablet] 的判据: 鸿蒙只有"屏幕物理像素"桥
     * ([OhosNativeBridge.getScreenSizePx], 值 = vp × densityPixels), 而"是否平板"要和安卓端
     * 同口径 (屏幕短边 dp/vp 档位), 换算所需的密度只有 Compose 侧拿得到
     * (与 shared `AppDialogSizes` 用 LocalDensity 把屏幕物理像素换成 dp 同款口径)。
     * 0 = 尚未注入 (根组合之前), [isTablet] 按非平板处理。
     */
    @Volatile
    private var displayDensity: Float = 0f

    /**
     * 平板判据: 屏幕短边 vp 下限 (**近似**安卓 `SCREENLAYOUT_SIZE_LARGE` 档的 sw480dp 级, 不等价)。
     */
    private const val TABLET_MIN_SMALLEST_WIDTH_VP = 480f

    /** 注入当前显示密度 (根组合里读 LocalDensity; 密度变化时重注入, 只接受正值)。 */
    fun updateDisplayDensity(density: Float) {
        if (density > 0f) displayDensity = density
    }

    // 鸿蒙由系统统一管理应用生命周期, 无 Activity.finish 等价物;
    // 退出经 OhosNativeBridge.exitApplication → window tsfn → ArkTS UIAbilityContext.terminateSelf()
    // (复用 window 桥, action="exitApplication", 见 EntryAbility onWindowStageCreate 的 window callback)
    override fun exitApplication() {
        OhosNativeBridge.exitApplication()
    }

    override fun openExternalUrl(url: String) {
        openURL(url)
    }

    override fun shareText(text: String) {
        services.sharing.shareText(text)
    }

    override fun copyToClipboard(text: String) {
        copyTextToClipboard(text)
    }

    // 读系统剪贴板 (对照原版 ContextExtensions getClipText: 主题导入/规则粘贴等 7 场景)
    override fun getClipboardText(): String? = readFromClipboard()

    /**
     * 应用版本名 (对照 app 端 AppConst.appInfo.versionName)。
     *
     * 鸿蒙版本号在 app.json5 且由 hvigorfile.ts 在构建阶段覆写, Kotlin/Native 无读取应用包信息的
     * API, 故由 ArkTS 启动时经 [OhosNativeBridge.registerAppVersionFn] 注入; 未注入返回 null,
     * 关于页版本号条目退化为空 summary。
     */
    override fun getAppVersionName(): String? = OhosNativeBridge.getAppVersionName()

    /** 引擎保存后重载正在播放的朗读 (与 iOS 同语义, 对照 ReadAloudConfigDialog 保存后的重建)。 */
    override fun onHttpTtsEdited(engine: HttpTTS) {
        if (OhosReadAloudHost.isRun && defaultTtsEngineConfig() == engine.id.toString()) {
            // 正在播放则重载；暂停时记录重建位置，继续朗读才启用新配置。
            OhosReadAloudHost.play(play = !OhosReadAloudHost.isPause)
        }
    }

    /**
     * 导出分发「上传 URL」: 走 nativeMain 下沉的 [upLoadToDirectLink] (对照 app 端
     * `DirectLinkUpload.upLoad`)。失败路径也必须回调 (传 null) —— 导出对话框的防重复
     * 点击标志靠这一次回调解开, 不回调就是整个对话框永久点不动。
     */
    override fun upLoadFile(
        fileName: String,
        file: Any,
        contentType: String,
        onResult: (String?) -> Unit,
    ) {
        Coroutine.async { upLoadToDirectLink(fileName, file, contentType) }
            .onSuccess { onResult(it) }
            .onError { error ->
                AppLog.put("上传文件失败\n${error.message}", error)
                Toasters.get().toast("上传文件失败\n${error.message}")
                onResult(null)
            }
    }

    // 按 bookUrl 查 DB 解析 BookRef, 供 deep link / 文件关联的路由导航
    override suspend fun resolveBookRef(bookUrl: String): BookRef? =
        appDb.bookDao.getBook(bookUrl)?.toRouteRef()

    /**
     * 是否平板 (对照 ChapterProvider.upLayout 的 appCtx.isPad, "平板/横屏双页" auto 分支)。
     *
     * 原版判据 (app/.../utils/ContextExtensions.kt 的 `Context.isPad`) 逐字是:
     * `(resources.configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK) >=
     * Configuration.SCREENLAYOUT_SIZE_LARGE` —— 读的是系统给的 screenLayout 尺寸桶。
     *
     * ⚠️ **本端是自造近似, 与原判据不等价**: 鸿蒙没有 screenLayout / SCREENLAYOUT_SIZE_LARGE
     * 对应 API (本机 SDK 无等价枚举, 也没有"系统判定过的大屏档"可取), 只能退而用
     * "屏幕短边物理像素 ÷ 密度 >= 480vp" 去猜那个桶 (即常见手机 360~450vp / 平板 800vp 起
     * 的经验分界)。系统桶会照顾厂商的 sw 声明与非矩形屏, 本近似不会; 折叠屏展开、车机这类
     * 设备上是否与系统桶一致**未在真机验证过**, 需真机核对; 不一致时以本近似为准也只能算
     * 已知偏差 (iOS/鸿蒙之外的两端不受影响: 安卓走原判据, 桌面继承 shared 默认 false)。
     *
     * 屏幕尺寸来自既有桥 ([ScreenInfoProviders] / [OhosNativeBridge.getScreenSizePx], 单位物理像素),
     * 密度由根组合注入 —— 鸿蒙侧没有独立的设备形态桥, 物理像素不除密度会把高密度手机误判成平板。
     * 密度/尺寸任一未就绪时返回 false (按非平板处理, 即不启用双页)。
     */
    override fun isTablet(): Boolean {
        val density = displayDensity
        if (density <= 0f) return false
        val size = runCatching { ScreenInfoProviders.get() }.getOrNull() ?: return false
        val smallestVp = minOf(size.screenWidthPx, size.screenHeightPx) / density
        return smallestVp >= TABLET_MIN_SMALLEST_WIDTH_VP
    }

    // ===== Web 服务 (WebServerManager + NativeWebServerPlatform 已下沉并在 OhosProviderRegistry 注册) =====

    // ===== 关于页 =====

    // ===== 书籍详情页 =====

    // 本地书文件字节数 (bookUrl 形如 file:///path, 鸿蒙沙盒为 POSIX 路径, 去 scheme 即可);
    // bookUrl 可能指向用户授权目录内的原文件 (阅读重定位后不复制), 先激活授权再取属性
    override suspend fun localBookFileSize(bookUrl: String): Long = withContext(IoDispatcher) {
        val path = bookUrl.removePrefix("file://")
        OhosDirAuthorizations.ensureActivatedFor(path, DirUsage.BookTree)
        File(path).length()
    }

    // 阅读重定位 (ReaderScreenModel.relocateLocalBook) 传入的 dirUri 是选目录返回的 POSIX 路径:
    // 先激活授权再走 NativePlatformCapabilities 的 depth 0/1 扫描, 返回保持 file:// 前缀 bookUrl;
    // 激活失败抛 SecurityException (带 errCode/policyCode), 不吞成文件不存在
    override fun findBookFileInDir(dirUri: String, fileName: String): String? {
        OhosDirAuthorizations.ensureActivatedFor(dirUri, DirUsage.BookTree)
        return super<NativePlatformCapabilities>.findBookFileInDir(dirUri, fileName)
    }

    // ===== webFile 下载导入 (对照 BookInfoActivity 同名方法 / iOS IosPlatformCapabilities) =====
    //
    // 平台无关编排 (构建 webFile 列表 / 下载 / 导入本地 / 压缩包条目) 已下沉:
    // 取件与文件名推导在 WebFileImportShared, 下载导入/压缩包导入在 SharedPlatformCapabilities
    // (目录落库统一走 BookChapterLoader.fetchFromSource)。本端只保留两处平台形态:
    // 条目选择框 (系统对话框桥) 与"不支持格式时交系统程序打开" (无系统应用打开通道, 同 iOS
    // 用系统分享面板把文件交给其它 app)。

    /** 对照 BookInfoActivity.onReadClick isWebFile 分支: 构建 webFile 列表后弹条目选择框。 */
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
            val webFiles = WebFileImportShared.buildWebFiles(book, source)
            if (webFiles.isEmpty()) {
                Toasters.get().toast("Unexpected webFileData")
                return@launch
            }
            // 条目选择框 (对照 app 端 activity.selector(download_and_import_file, webFiles));
            // 取消/关闭 = 未选择, 直接结束 (不弹第二个框)
            val index = selectFromList(
                title = syncGetString("download_and_import_file"),
                items = webFiles.map { it.name },
            ) ?: return@launch
            val webFile = webFiles.getOrNull(index) ?: return@launch
            when {
                webFile.isSupported ->
                    importWebFile(book, webFile, onWaitDialog, onAction) { onSuccess?.invoke(it) }

                webFile.isSupportDecompress ->
                    downloadWebFile(book, webFile, onWaitDialog, onAction) { path ->
                        getArchiveFilesName(path) { fileNames ->
                            if (fileNames.size == 1) {
                                importBookFromArchive(path, fileNames[0], book, onWaitDialog) {
                                    onSuccess?.invoke(it)
                                }
                            } else {
                                showArchiveEntrySelect(path, fileNames, book, onWaitDialog, onSuccess)
                            }
                        }
                    }

                else -> showUnsupportedWebFileAlert(book, webFile, onWaitDialog, onAction)
            }
        }
    }

    /** 压缩包内选章 (对照 app 端 `showDecompressFileImportAlert` / iOS presentArchiveEntrySelect)。 */
    private fun showArchiveEntrySelect(
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
        val index = selectFromList(
            title = syncGetString("import_select_book"),
            items = fileNames,
        ) ?: return
        val name = fileNames.getOrNull(index) ?: return
        importBookFromArchive(archiveFilePath, name, book, onWaitDialog) { onSuccess?.invoke(it) }
    }

    /** 不支持的格式: 确认后下载并交其它应用打开 (对照 app 端 alert 的 open_fun 分支)。 */
    private fun showUnsupportedWebFileAlert(
        book: Book,
        webFile: FileBook.WebFile,
        onWaitDialog: (Boolean) -> Unit,
        onAction: (String) -> Unit,
    ) {
        val open = confirm(
            title = syncGetString("draw"),
            message = syncGetString("file_not_supported", webFile.name),
            confirmText = syncGetString("open_fun"),
            cancelText = syncGetString("cancel"),
        )
        if (!open) return
        downloadWebFile(book, webFile, onWaitDialog, onAction) { path ->
            // 鸿蒙无"按扩展名把本地文件交系统应用打开"的通道 (安卓 openFileUri 那条),
            // 用系统分享面板把文件交给其它 app (与 iOS presentShareSheet 同款出口);
            // MIME 用 application/octet-stream: 鸿蒙侧按 UTD 反查, 认不出具体类型也照常
            // 出现在候选应用里 (与 iOS 不带类型同口径), 不自行猜 MIME。
            services.sharing.shareFile(path, "application/octet-stream")
        }
    }

    // 对照 RemoteBookActivity.startRead 的 archive 分支 + Android startReadRemoteArchive:
    // 在有效书籍保存目录里找已下载的压缩包 (目录与 NativeFileBookAccessor 同源),
    // 找到就交文件关联分发链 (与 NativeImportBook.openReader 同口径走直接导入),
    // 没找到就弹确认框由调用方重新下载后重试。
    override fun startReadRemoteArchive(fileName: String, onNeedDownload: () -> Unit) {
        scope.launch {
            // 与 NativeFileBookAccessor 的有效书籍保存目录同源 (用户在"书籍保存位置"选定);
            // 选定目录在沙盒外, 存在性检查同样要先激活既有目录授权
            val dir = effectiveBookSaveDir()
            if (dir != null) {
                val path = "$dir/$fileName"
                val exists = runCatching {
                    withLocalDirAccess(path) { File(path).exists() }
                }.getOrDefault(false)
                if (exists) {
                    FileAssociationDispatch.dispatch(path)
                    return@launch
                }
            }
            val confirmed = confirm(
                title = syncGetString("draw"),
                message = syncGetString("archive_not_found"),
                confirmText = syncGetString("ok"),
                cancelText = syncGetString("cancel"),
            )
            if (confirmed) onNeedDownload()
        }
    }

    // ===== 书架管理: 导出开关 =====

    /** 导出进度通知正文 (对照 app 端 ExportBookService.notificationContentText)。 */
    private var notificationContentText = syncGetString("service_starting")

    /**
     * 更新导出进度通知 (对照 app 端 ExportBookService.upExportNotification)。
     *
     * 通知 id 用 [NotificationId.ExportBookService]: 与 ArkTS 侧 NOTIFICATION_ROUTES 的 id→route
     * 表同源, 点击通知回书籍管理页; 同 id 覆盖式更新即“同一条通知”从进行中变结束态。
     *
     * 进行中带“取消”按钮 (对照原版 `addAction`, 动作字面量 [IntentAction.stop]), 点是后由宿主
     * 经统一平台事件通道回传到 [onNotificationAction]; 结束态 (finish=true) 不带按钮。
     *
     * 不申请长时任务保活 (鸿蒙长时任务"使用场景"表里无可用类型): 导出是本地生成文件, 既非
     * `dataTransfer` 的"非托管形式的上传、下载", 也没有确定进度 (原版这条通知只有文本摘要:
     * "导出逐本串行、进度为文本摘要而非单一确定总量"), 满足不了该类型"必须持续更新进度且
     * 更新通知必须为实况窗"的硬要求; `taskKeeping` (计算任务) 有设备/ACL 门槛。
     *
     * @param finish true = 结束态 (对照原版该分支的 `setOngoing(false)` + `setAutoCancel(true)`)
     */
    private fun upExportNotification(finish: Boolean = false) {
        OhosNativeBridge.showNotification(
            id = NotificationId.ExportBookService,
            title = syncGetString("export_book"),
            content = notificationContentText,
            progress = 0,
            max = 0,
            ongoing = !finish,
            // 对照原版 setOnlyAlertOnce(!finish)
            alertOnce = !finish,
            buttons = if (finish) {
                emptyList()
            } else {
                listOf(NotificationButton(text = syncGetString("cancel"), action = IntentAction.stop))
            },
        )
    }

    /**
     * 通知按钮/点击动作入口 (通知 actionButtons 或点击动作 → 统一平台事件通道 type="notificationAction")。
     *
     * (id, action) 逐字对照原版各通知的 pendingIntent: `servicePendingIntent<XxxService>(IntentAction.stop)`
     * 与 WebService 的 `"copyHostAddress"` —— id 指明哪个通知 (哪个“服务”), action 是原版的
     * Intent action 字面量。
     */
    fun onNotificationAction(notificationId: Int, action: String) {
        when (notificationId) {
            NotificationId.ExportBookService ->
                if (action == IntentAction.stop) cancelExport()

            NotificationId.UpdateBookService ->
                if (action == IntentAction.stop) cancelOhosUpdateBookRefresh()

            NotificationId.CacheBookService ->
                if (action == IntentAction.stop) OhosCacheBookNotifications.cancelDownloads()

            NotificationId.CheckSourceService ->
                if (action == IntentAction.stop) cancelCheckSource()

            NotificationId.WebService -> when (action) {
                // 对照原版 WebService.onStartCommand 的 IntentAction.stop → stopSelf()
                IntentAction.stop -> setWebService(false)
                OhosWebServiceNotifications.ACTION_COPY_HOST_ADDRESS -> copyWebServiceAddress()
            }
        }
    }

    /**
     * 通知点击 → 复制服务地址 (对照 app 端 `WebService.onStartCommand` 的 "copyHostAddress"
     * 分支: `sendToClip(hostAddress)`)。
     */
    private fun copyWebServiceAddress() {
        val address = WebServerManager.hostAddress
        if (address.isEmpty()) {
            AppLog.put("Web 服务地址为空, 未复制")
            return
        }
        copyToClipboard(address)
    }

    /**
     * 取消正在跑的导出并撤掉导出通知 (对照原版 ExportBookService 的 IntentAction.stop 分支:
     * `notificationManager.cancel(NotificationId.ExportBook)` + `stopSelf()` —— 后者使服务的
     * lifecycleScope 取消, 导出协程随之在章节目录/分卷边界停下)。
     */
    private fun cancelExport() {
        exportBooksJob?.cancel()
        exportBooksJob = null
        services.notifications.cancelNotification(NotificationId.ExportBookService)
    }

    // 导出书籍正文, 直接写进有效导出目录 (用户经目录选择器选定; 鸿蒙应用沙盒对文件管理器
    // 不可见, 写进去用户找不着), 不经系统分享面板 (对照 app 端 ExportBookService 的落盘语义)
    override fun exportAllBooks(books: List<Book>) {
        if (books.isEmpty()) {
            Toasters.get().toast("书籍不能为空")
            return
        }
        // 未选过目录时先选 (对照 BookshelfManageActivity.exportAll)
        if (effectiveBookExportDir() == null) {
            selectExportFolder(books)
            return
        }
        // 导出配置在此快照 (对照原版把 exportType 随 intent 传进服务)
        val exportType = prefs.getInt(PreferKey.exportType, 0)
        enqueueExport(books) { ExportMode.ByConfig(exportType) }
    }

    /**
     * 导出请求入队 (对照原版 BookshelfManageActivity.startExportBooks → ExportBookService:
     * 每本书一条队列项, 重复触发入队稍后照常导出), 队列空闲时顺手拉起执行者。
     *
     * @param label 导出方式后缀 (自定义章节导出提示里带"自定义章节", 普通导出为空)
     * @param modeOf 按书算导出方式 (图片书一律 cbz 等)
     */
    private fun enqueueExport(books: List<Book>, label: String = "", modeOf: (Book) -> ExportMode) {
        if (books.isEmpty()) return
        val (added, started) = synchronized(exportLock) {
            // 同一本书只入队一次 (正在导出的那本不入队), 已在队列里则换成最新导出方式
            var count = 0
            books.forEach { book ->
                if (book.bookUrl == exportingBookUrl) return@forEach
                waitExportBooks[book.bookUrl] = PendingExport(book, modeOf(book))
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
        if (started) exportBooksJob = scope.launch { runExportQueue() }
    }

    /** 取队首的一本书并标记"正在导出" (对照原版 export(): 取队首 → exportProgress 置位), 空队列返回 null。 */
    private fun takeNextExport(): PendingExport? = synchronized(exportLock) {
        val entry = waitExportBooks.entries.firstOrNull() ?: return null
        waitExportBooks.remove(entry.key)
        exportingBookUrl = entry.key
        entry.value
    }

    /** 队列里还剩几本待导。 */
    private fun pendingExportCount(): Int = synchronized(exportLock) { waitExportBooks.size }

    /** 当前这本导完, 清掉"正在导出"标记。 */
    private fun finishExport() = synchronized(exportLock) { exportingBookUrl = null }

    /** 队列空时收工 (与 [enqueueExport] 同锁): 确认没新入队才真收工, 否则继续导。 */
    private fun finishWorkerIfQueueEmpty(): Boolean = synchronized(exportLock) {
        if (waitExportBooks.isNotEmpty()) {
            false
        } else {
            exportWorkerRunning = false
            true
        }
    }

    /** 取消时丢掉剩余队列并归还执行者名额 (对照原版 IntentAction.stop → stopSelf: 未导出的不再导出)。 */
    private fun discardExportQueue() = synchronized(exportLock) {
        waitExportBooks.clear()
        exportingBookUrl = null
        exportWorkerRunning = false
    }

    /** 导出 [pending] 这一本, 返回写出的文件 (对照原版 ExportBookService 按 ExportConfig 分流)。 */
    private suspend fun exportPending(
        pending: PendingExport,
        exportDir: File,
        usedPaths: MutableSet<String>,
    ): List<File> = when (val mode = pending.mode) {
        is ExportMode.ByConfig -> listOf(
            NativeBookExporter.exportBookFile(pending.book, exportDir, usedPaths, mode.type)
        )

        is ExportMode.CustomEpub -> if (pending.book.isImage) {
            listOf(NativeBookExporter.exportBookFile(pending.book, exportDir, usedPaths, 1))
        } else {
            val chapters = appDb.bookChapterDao.getChapterList(pending.book.bookUrl)
            val selected = chapters.filterIndexed { index, _ -> index in mode.scope }
            if (selected.isEmpty()) error("章节范围内没有章节")
            selected.chunked(mode.size).mapIndexed { index, part ->
                NativeBookExporter.exportBookFile(
                    pending.book, exportDir, usedPaths, 1, part,
                    pending.book.getExportFileName("epub", index + 1),
                )
            }
        }
    }

    /**
     * 串行导出队列 (对照原版 ExportBookService.export(): 逐本从队首取出, 导完一本再取下一本)。
     * 单本出错只记日志并继续下一本; 通知取消则丢掉剩余队列, 与 app 端 stopSelf 同语义。
     */
    private suspend fun runExportQueue() {
        val failures = mutableListOf<String>()
        val usedPaths = mutableSetOf<String>()
        var exported = 0
        var lastDirPath: String? = null
        try {
            while (true) {
                val pending = takeNextExport()
                if (pending == null) {
                    if (finishWorkerIfQueueEmpty()) break
                    continue
                }
                try {
                    currentCoroutineContext().ensureActive()
                    val exportDir = openExportDir()
                    if (exportDir == null) {
                        // 目录不可用 (已提示并忘掉记忆目录): 本次不能导出, 剩余队列不再导
                        discardExportQueue()
                        break
                    }
                    lastDirPath = exportDir.absolutePath
                    notificationContentText = syncGetString(
                        "export_book_notification_content",
                        pending.book.name,
                        pendingExportCount(),
                    )
                    upExportNotification()
                    exportPending(pending, exportDir, usedPaths).forEach { file ->
                        exported++
                        if (prefs.getBoolean(PreferKey.exportToWebDav, false)) {
                            AppWebDavShared.exportWebDav(file.absolutePath, file.name)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures.add("${pending.book.name}：${e.message ?: "未知错误"}")
                    AppLog.put("导出书籍失败：${pending.book.name}\n${e.message}", e)
                } finally {
                    finishExport()
                }
            }
        } catch (e: CancellationException) {
            // 通知取消导出: 不报错, 已写出的半成品与 app 端取消语义一致; 剩余队列不再导出
            discardExportQueue()
            throw e
        } catch (e: Throwable) {
            // 意外异常不能把执行者名额带走, 否则后续导出会一直排队没人导
            discardExportQueue()
            throw e
        }
        notificationContentText = "导出完成"
        upExportNotification(finish = true)
        if (exported > 0) {
            Toasters.get().toast("导出完成\n已导出 $exported 个文件到 ${lastDirPath.orEmpty()}")
        }
        if (failures.isNotEmpty()) {
            Toasters.get().toast("${failures.size} 本书导出失败：${failures.take(2).joinToString()}")
        }
    }

    /**
     * 打开发送导出目录: 取有效导出目录 ([effectiveBookExportDir]) → 激活其持久化目录授权
     * → 确保目录存在。
     *
     * 选定目录在应用沙盒之外, 按 POSIX 路径写入前必须先激活授权; 授权失效 (用户在系统里
     * 撤销/换过目录) 时清掉记忆目录, 下次导出重新让用户选。
     *
     * @return 可写目录; 已提示并返回 null 表示本次不能导出
     */
    private fun openExportDir(): File? {
        val path = effectiveBookExportDir() ?: return null
        val dir = File(path)
        val usable = try {
            OhosDirAuthorizations.ensureActivatedFor(path, DirUsage.Export)
            dir.isDirectory || dir.mkdirs()
        } catch (e: Exception) {
            AppLog.put("导出目录不可用\n${e.message}", e)
            rememberBookExportDir(null)
            Toasters.get().toast("导出目录不可用，请重新选择")
            return null
        }
        if (!usable) {
            Toasters.get().toast("无法创建导出目录")
            return null
        }
        return dir
    }

    /**
     * 选择导出文件夹 (对照 BookshelfManageActivity.selectExportFolder 与 exportDir 回调):
     * 复用 [PlatformServiceProviders] 的目录选择器选一个用户可见的公共目录
     * (DocumentViewPicker + 授权持久化写模式 + 官方转换路径 + 记入授权表供重启后激活),
     * 选中后记入设备本地配置空间 ([rememberBookExportDir]); 开了自定义导出章节先弹章节配置, 否则直接导出。
     * 目录按 [DirUsage.Export] 登记: 只放行导出写入, 不放行书籍文件访问。
     */
    override fun selectExportFolder(books: List<Book>) {
        scope.launch {
            val path = services.files.pickDirectory(DirUsage.Export) ?: return@launch
            rememberBookExportDir(path)
            if (books.isEmpty()) return@launch
            // 对照 BookshelfManageActivity.exportDir 回调的 enableCustomExport():
            // 开关与"导出类型为 epub"两条相与 (同 [io.legado.app.utils.enableCustomExport], 不分第二份判据);
            // 本类同名成员只是开关当前值 (菜单勾选态), 故走全限定名
            if (io.legado.app.utils.enableCustomExport()) {
                showExportSectionConfig(path, books)
            } else {
                exportAllBooks(books)
            }
        }
    }

    /**
     * 自定义导出章节配置 (对照 BookshelfManageActivity.configExportSection 与
     * dialog_select_section_export.xml: 导出全部 / 自定义导出 + 分卷文件名 JS 规则 + 分卷大小
     * + 章节范围)。表单形制与 iOS 同项一致 (三个输入框 + "导出所有"/"自定义导出"两个提交键),
     * 自绘对话框由 ArkTS 侧承载 (鸿蒙系统提示对话框只有标题/正文/按钮, 承载不了输入框, 见
     * SysDialogBridgeHandler.ets 的 form 能力)。
     *
     * @param path 刚选定的导出目录; 两个导出分支都经 [effectiveBookExportDir] 重取
     *   (与本参数同值, 与 iOS 同项实现同口径)
     * @param books 待导出书籍
     */
    override fun showExportSectionConfig(path: String, books: List<Book>) {
        if (books.isEmpty()) {
            Toasters.get().toast("书籍不能为空")
            return
        }
        scope.launch {
            val outcome = formDialog(
                OhosDialogFormPayload(
                    title = syncGetString("select_section_export"),
                    fields = listOf(
                        OhosDialogFormField(
                            key = FORM_FIELD_FILE_NAME,
                            label = syncGetString("export_file_name"),
                            initial = prefs.getString(PreferKey.episodeExportFileName, "").orEmpty(),
                        ),
                        OhosDialogFormField(
                            key = FORM_FIELD_SIZE,
                            label = syncGetString("file_contains_number"),
                            initial = "1",
                            numeric = true,
                        ),
                        OhosDialogFormField(
                            key = FORM_FIELD_SCOPE,
                            label = syncGetString("export_chapter_index"),
                            hint = "1-5,8,10-18",
                        ),
                    ),
                    actions = listOf(
                        OhosDialogFormAction(
                            key = FORM_ACTION_ALL,
                            text = syncGetString("export_all"),
                        ),
                        OhosDialogFormAction(
                            key = FORM_ACTION_CUSTOM,
                            text = syncGetString("custom_export"),
                        ),
                    ),
                    cancelText = syncGetString("cancel"),
                )
            ) ?: return@launch
            // 分卷文件名 JS 规则仅在合法时持久化 (对照 etEpubFilename 的失焦校验)
            val fileName = outcome.values?.get(FORM_FIELD_FILE_NAME).orEmpty()
            if (tryParesExportFileName(fileName)) {
                prefs.putString(PreferKey.episodeExportFileName, fileName)
            }
            if (outcome.action == FORM_ACTION_ALL) {
                exportAllBooks(books)
                return@launch
            }
            val scopeText = outcome.values?.get(FORM_FIELD_SCOPE).orEmpty().trim()
            if (!verificationField(scopeText)) {
                Toasters.get().toast(syncGetString("error_scope_input"))
                return@launch
            }
            // 空值按 1 卷处理 (对照 etEpubSize 的 `toIntOrNull() ?: 1`), 但 0/负数不放行:
            // 分卷按 chunked(size) 切, 非正数会在导出中途报错
            val size = outcome.values?.get(FORM_FIELD_SIZE).orEmpty().toIntOrNull() ?: 1
            if (size < 1) {
                Toasters.get().toast("每份章节数必须大于 0")
                return@launch
            }
            exportCustomBooks(books, ExportBookUtils.parseScope(scopeText), size)
        }
    }

    /**
     * 按章节范围分卷导出 epub (对照 BookshelfManageActivity.configExportSection 向
     * ExportBookService 传的 epubScope + epubSize: 选定章节按 [size] 卷切分, 每卷一个 epub,
     * 卷名走分卷文件名规则)。图片书仍按既有实现导出 cbz (对照 startExport 的
     * `if (book.isImage) "cbz"`)。
     *
     * @param scope 选中章节的下标集合 (0-based, [ExportBookUtils.parseScope] 的结果)
     */
    private fun exportCustomBooks(books: List<Book>, scopeChapters: Set<Int>, size: Int) {
        if (books.isEmpty()) return
        if (effectiveBookExportDir() == null) {
            selectExportFolder(books)
            return
        }
        enqueueExport(books, label = " (自定义章节)") { ExportMode.CustomEpub(scopeChapters, size) }
    }

    override fun showExportConfig() {
        AppNavigatorProviders.get().showOverlay(AppOverlay.Dialog("book_export_format"))
    }

    // ===== 书架管理: 导出 JSON =====

    override fun exportAllUseBookSource() {
        scope.launch {
            val sources = runCatching { appDb.bookDao.getAllUseBookSource() }.getOrElse {
                Toasters.get().toast("导出所用书源失败\n${it.message}")
                return@launch
            }
            saveJson("bookSource.json", GSON.toJson(sources))
        }
    }

    // 导出书架 JSON (字段清单与 app 端 exportBookshelf 一致)
    override fun exportBookshelf(books: List<Book>) {
        if (books.isEmpty()) {
            Toasters.get().toast("书籍不能为空")
            return
        }
        scope.launch { saveJson("bookshelf.json", GSON.toJson(books.map { it.toShelfJsonMap() })) }
    }

    // ===== 书源管理 =====

    override fun exportBookSourceSelection(
        selection: List<BookSourcePart>,
        allCount: Int,
        sortAscending: Boolean,
        sort: BookSourceSort
    ) {
        scope.launch {
            val json = selectedSourcesJson(selection) ?: return@launch
            saveJson("bookSource.json", json)
        }
    }

    override fun checkBookSource(selection: List<BookSourcePart>) {
        if (selection.isEmpty()) return
        OhosCheckSource.start(selection)
    }

    // 校验中重进书源管理页 (对照 app 端 CheckSourceService 的 IntentAction.resume: 重发一次进度,
    // 进度事件与通知一并恢复; 共享路由靠该事件点亮进度条)
    override fun resumeCheckSource() {
        OhosCheckSource.resume()
    }

    override fun cancelCheckSource() {
        OhosCheckSource.stop()
        Debug.finishChecking()
    }

    // ===== 其它设置 =====

    // ===== 文件关联 =====

    // 完整分发链 (压缩包/JSON 一键导入/书籍文件) 见 FileAssociationDispatch, 四端共用
    override fun openImportFile(filePath: String) {
        scope.launch {
            // 系统投递进来的是文档授权体 URI (`file://docs/...`): 共享转换按 RFC 8089 把 `docs`
            // 当网络主机段, 得出 `//docs/storage/...` 这种设备上不存在的形态 —— 先折回存储挂载点
            // 真实路径再交分发链 (与 ArkTS 媒体桥折回视频地址同一规则)
            val mountPath = filePath.docsUriToMountPath()
            if (mountPath == filePath) {
                FileAssociationDispatch.dispatch(filePath)
                return@launch
            }
            // 该文件在应用沙盒之外: 先按书籍文件用途登记它的授权 URI (与选目录同一套登记机制,
            // 不弹界面), 否则文件访问会被授权表拦成"没有访问权限"
            OhosDirAuthorizations.remember(filePath, mountPath, DirUsage.BookTree)
            FileAssociationDispatch.dispatch(mountPath)
        }
    }

    // ===== 导入本地书 (状态与扫描见 NativeImportBook, 与 iOS 共用) =====

    override fun initImportBookData() = NativeImportBook.init(restoreLast = true)

    override fun importBookItems(): StateFlow<List<ImportFileItem>> = NativeImportBook.items
    override fun importBookPath(): StateFlow<String?> = NativeImportBook.path
    override fun importBookLoading(): StateFlow<Boolean> = NativeImportBook.loading
    override fun importBookEmptyMsgVisible(): StateFlow<Boolean> =
        NativeImportBook.emptyMsgVisible

    // 对照 Android onPickFolder / selectFolder.launch;
    // 复用 OhosPlatformServices.pickDirectory (DocumentViewPicker 选目录 + 授权持久化 +
    // fileUri 官方转换, 记入授权表供重启后激活), 桥接未就绪/用户取消/目录不可访问时
    // 返回 null 保持原目录不动; 目录按 [DirUsage.BookTree] 登记 (导入页浏览与导入的都是书籍文件)
    override fun pickImportFolder() {
        scope.launch {
            val path = services.files.pickDirectory(DirUsage.BookTree) ?: return@launch
            NativeImportBook.setRoot(path)
        }
    }

    override fun scanImportFolder() = NativeImportBook.scan()

    // 按文件名导入 js 编辑框: 鸿蒙无命令式文本输入宿主, 经共享 Overlay 弹 TextInputDialog
    // (key="import_file_name", LegadoApp 分支内写 PreferKey.bookImportFileName; 对照 app 端
    // alertImportFileName 的 editTextView + okButton 语义)
    override fun alertImportFileName() {
        AppNavigatorProviders.get().showOverlay(AppOverlay.Dialog("import_file_name"))
    }


    // 压缩包导入需要书籍保存位置: 未设置时经既有"书籍保存位置"选择器选定后继续导入
    override fun addImportSelectionToBookshelf(
        items: List<ImportFileItem>,
        onComplete: () -> Unit,
    ) = NativeImportBook.addToBookshelf(
        items,
        onComplete,
        onNeedBooksDir = { ensureBookSaveDirSelected() },
    )

    override fun updateImportBookFilter(key: String) = NativeImportBook.updateFilter(key)

    override fun updateImportBookSort(sort: Int) = NativeImportBook.updateSort(sort)

    override fun openImportedBookReader(item: ImportFileItem) = NativeImportBook.openReader(item)

    override fun navigateImportDir(item: ImportFileItem) = NativeImportBook.enterDir(item)

    override fun goBackImportDir() {
        NativeImportBook.goBack()
    }

    // ===== 私有辅助 =====

    /**
     * 弹带标题的候选列表并返回选中下标 (对照 app 端 `activity.selector` / iOS presentSelectList)。
     *
     * 平台形态: ArkTS 侧 `UIContext.getPromptAction().showDialog` (系统提示对话框),
     * 经 [OhosNativeBridge.invokeDialogSync] (请求走 window tsfn, 结果走 platformEvent) 同步取回。
     * 用户取消/关闭对话框 = 未选择, 桥未就绪或超时同样返回 null (调用方直接结束, 不假选第一项)。
     */
    private fun selectFromList(title: String, items: List<String>): Int? {
        val payload = KS_JSON.encodeToString(
            OhosDialogSelectPayload(title = title, items = items)
        )
        val resultJson = OhosNativeBridge.invokeDialogSync("select", payload) ?: return null
        val resp = runCatching {
            KS_JSON.decodeFromString(OhosDialogResult.serializer(), resultJson)
        }.getOrNull()
        if (resp == null || !resp.ok) {
            AppLog.putDebug("弹候选列表失败: ${resp?.error ?: resultJson}", tag = "ohos-dialog")
            return null
        }
        return resp.index
    }

    /** form 对话框的字段键/动作键 (与 ArkTS 侧原样回传的键对应)。 */
    private const val FORM_FIELD_FILE_NAME = "fileName"
    private const val FORM_FIELD_SIZE = "size"
    private const val FORM_FIELD_SCOPE = "scope"
    private const val FORM_ACTION_ALL = "all"
    private const val FORM_ACTION_CUSTOM = "custom"

    /**
     * form 对话框的等待上限 (10 分钟): 用户要逐个字段输入, 不能用 select/confirm 的 60s。
     */
    private const val FORM_DIALOG_TIMEOUT_MS = 600_000L

    /**
     * 弹多字段输入对话框并返回结果 (action 为点中的动作键, values 为各字段当前文本)。
     *
     * 平台形态: ArkTS 侧自绘自定义对话框 ([OhosDialogFormPayload] 声明字段与动作键, 见
     * SysDialogBridgeHandler.ets 的 `form` 能力) —— 系统提示对话框 (showDialog) 只有标题/正文/
     * 按钮, 承载不了输入框。用户取消/关闭/桥未就绪/超时返回 null。
     *
     * 超时比其他两种形态长: 用户要在对话框里逐字输入, 默认 60s 会在输入途中放弃这次请求,
     * 此后用户再点提交就没人接收了。
     */
    private fun formDialog(payload: OhosDialogFormPayload): OhosDialogResult? {
        val payloadJson = KS_JSON.encodeToString(payload)
        val resultJson = OhosNativeBridge.invokeDialogSync(
            "form",
            payloadJson,
            timeoutMs = FORM_DIALOG_TIMEOUT_MS,
        ) ?: return null
        val resp = runCatching {
            KS_JSON.decodeFromString(OhosDialogResult.serializer(), resultJson)
        }.getOrNull()
        if (resp == null || !resp.ok) {
            AppLog.putDebug("弹多字段对话框失败: ${resp?.error ?: resultJson}", tag = "ohos-dialog")
            return null
        }
        if (resp.cancelled) return null
        return resp
    }

    /**
     * 弹是-否确认框并返回是否确认 (对照 app 端 `activity.alert { okButton/noButton }`)。
     *
     * 点"否"与取消/关闭 (未选择) 在调用点语义相同 (都不继续), 故统一返回 false。
     */
    private fun confirm(
        title: String,
        message: String?,
        confirmText: String,
        cancelText: String,
    ): Boolean {
        val payload = KS_JSON.encodeToString(
            OhosDialogConfirmPayload(
                title = title,
                message = message,
                confirmText = confirmText,
                cancelText = cancelText,
            )
        )
        val resultJson = OhosNativeBridge.invokeDialogSync("confirm", payload) ?: return false
        val resp = runCatching {
            KS_JSON.decodeFromString(OhosDialogResult.serializer(), resultJson)
        }.getOrNull()
        if (resp == null || !resp.ok) {
            AppLog.putDebug("弹确认框失败: ${resp?.error ?: resultJson}", tag = "ohos-dialog")
            return false
        }
        return resp.confirmed == true
    }

    /**
     * 云端有新备份时的确认框 (标题/正文与 app 端 `MainActivity.backupSync` 的
     * `alert(R.string.restore, R.string.webdav_after_local_restore_confirm)` 逐字同源)。
     *
     * 判定不在本方法内: 调用方拿到
     * [io.legado.app.help.StartupMaintenanceShared.NewBackupCheckResult.NewBackup] 后才调本方法。
     * 整个动作 (弹框 + 恢复) 放在本对象 IO 作用域里跑 —— 鸿蒙对话框是同步阻塞桥
     * ([confirm] → [OhosNativeBridge.invokeDialogSync]), 直接在主线程调会把界面线程堵在
     * runBlocking 上; 恢复走共享恢复入口 [AppWebDavShared.restoreWebDav] (与 app 端
     * `MainViewModel.restoreWebDav` 同一个入口), 失败与 app 端同一口径落日志 + toast。
     *
     * @param name 云端备份文件名 (判定结果携带的 `displayName`)
     */
    fun presentWebDavNewBackupConfirm(name: String) {
        scope.launch {
            val confirmed = confirm(
                title = syncGetString("restore"),
                message = syncGetString("webdav_after_local_restore_confirm"),
                confirmText = syncGetString("ok"),
                cancelText = syncGetString("cancel"),
            )
            if (!confirmed) return@launch
            try {
                AppWebDavShared.restoreWebDav(name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 对照 app 端 restoreWebDav 的 onError (AppLog + toastOnUi),
                // 与共享 BackupConfigRoute 恢复分支同一文案
                AppLog.put("WebDav恢复出错\n${e.message}", e)
                Toasters.get().toast("WebDav恢复出错\n${e.message}")
            }
        }
    }

    /**
     * 单文件导出 (书源/书架/所选书源 JSON): 走共享导出分发对话框 —— 按钮表与原版
     * HandleFileContract.EXPORT 一致 (上传 URL + 目录选择), 与桌面端同一形制; 选"保存到文件"
     * 后由平台文件选择器取目标路径并写盘。
     *
     * scope 是 IoDispatcher (IO 执行), pushExportDispatch 是 UI 操作, 故切主线程。
     */
    private suspend fun saveJson(defaultName: String, json: String) {
        withContext(Dispatchers.Main) {
            pushExportDispatch(defaultName, json, "application/json")
        }
    }

    /** 书架导出字段 (与 app 端 exportBookshelf 的 13 个字段一致)。 */
    private fun Book.toShelfJsonMap(): Map<String, Any?> = buildMap {
        put("bookUrl", bookUrl)
        put("tocUrl", tocUrl)
        put("origin", origin)
        put("originName", originName)
        put("name", name)
        put("author", author)
        kind?.let { put("kind", it) }
        coverUrl?.let { put("coverUrl", it) }
        customCoverUrl?.let { put("customCoverUrl", it) }
        intro?.let { put("intro", it) }
        customIntro?.let { put("customIntro", it) }
        put("type", type)
        wordCount?.let { put("wordCount", it) }
    }
}

// ===== 系统对话框桥跨语言 payload / response (与 ArkTS SysDialogBridgeHandler.ets JSON 协议对齐) =====

/** select 请求 payload (Kotlin → ArkTS)。 */
@Serializable
private data class OhosDialogSelectPayload(
    val title: String,
    val items: List<String>,
)

/** confirm 请求 payload (Kotlin → ArkTS); 按钮文案由本侧按语言给 (syncGetString), ArkTS 不拼文案。 */
@Serializable
private data class OhosDialogConfirmPayload(
    val title: String,
    val message: String? = null,
    val confirmText: String,
    val cancelText: String,
)

/** form 请求里的一个输入字段 (Kotlin → ArkTS)。 */
@Serializable
private data class OhosDialogFormField(
    /** 字段键 (回传 [OhosDialogResult.values] 的键)。 */
    val key: String,
    /** 字段名 (输入框上方的标签)。 */
    val label: String,
    /** 初始文本。 */
    val initial: String = "",
    /** 输入框占位提示。 */
    val hint: String? = null,
    /** 是否用数字键盘。 */
    val numeric: Boolean = false,
)

/** form 请求里的一个提交按钮 (Kotlin → ArkTS): 点它即带上 [key] 提交当前各字段值。 */
@Serializable
private data class OhosDialogFormAction(
    val key: String,
    val text: String,
)

/** form 请求 payload (Kotlin → ArkTS); 标题/标签/按钮文案由本侧给, ArkTS 不拼文案。 */
@Serializable
private data class OhosDialogFormPayload(
    val title: String,
    val message: String? = null,
    val fields: List<OhosDialogFormField>,
    val actions: List<OhosDialogFormAction>,
    /** 取消按钮文案; 缺省时只能拍遮罩/返回键关闭。 */
    val cancelText: String? = null,
)

/**
 * 对话框响应 (ArkTS → Kotlin)。
 *
 * - select 成功: [ok]=true, [index]=选中下标
 * - confirm 成功: [ok]=true, [confirmed]=是否点了"是"
 * - form 成功: [ok]=true, [action]=点中的动作键, [values]=各字段当前文本
 * - 取消/关闭: [ok]=true, [cancelled]=true (未选择)
 * - 失败: [ok]=false, [error]=错误信息
 */
@Serializable
private data class OhosDialogResult(
    val ok: Boolean,
    val index: Int? = null,
    val confirmed: Boolean? = null,
    val action: String? = null,
    val values: Map<String, String>? = null,
    val cancelled: Boolean = false,
    val error: String? = null,
)
