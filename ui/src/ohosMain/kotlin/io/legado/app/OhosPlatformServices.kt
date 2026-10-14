package io.legado.app

import io.legado.app.constant.AppLog
import io.legado.app.constant.DirUsage
import io.legado.app.help.copyToClipboard
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.file.OhosDirAuthorizations
import io.legado.app.help.file.pickDocumentContent
import io.legado.app.help.file.pickDocuments
import io.legado.app.help.file.pickDirectory as pickDirectoryPolicy
import io.legado.app.help.file.saveImageToAlbum
import io.legado.app.help.log.NativeCrashLogs
import io.legado.app.help.openURL
import io.legado.app.help.toast.Toasters
import io.legado.app.help.writeBytesAtomically
import io.legado.app.napi.OhosNativeBridge
import io.legado.app.ui.OhosPlatformCapabilities
import io.legado.app.ui.compose.platform.SharedThemeStoreProvider
import io.legado.app.ui.percentDecode
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.BrowserService
import io.legado.app.ui.root.CrashLogProvider
import io.legado.app.ui.root.ExternalRequestService
import io.legado.app.ui.root.FileFilter
import io.legado.app.ui.root.FilePickerService
import io.legado.app.ui.root.KeyboardController
import io.legado.app.ui.root.LaunchRequest
import io.legado.app.ui.root.LaunchRequestBus
import io.legado.app.ui.root.navigateToRoute
import io.legado.app.ui.root.MediaService
import io.legado.app.ui.root.NotificationService
import io.legado.app.ui.root.OrientationPolicy
import io.legado.app.ui.root.PermissionService
import io.legado.app.ui.root.PlatformCapabilities
import io.legado.app.ui.root.PlatformServices
import io.legado.app.ui.root.ReaderSystemBarAppearance
import io.legado.app.ui.root.ShareService
import io.legado.app.ui.root.SoftInputPolicy
import io.legado.app.ui.root.SystemBarsPolicy
import io.legado.app.ui.root.imageExtension
import io.legado.app.ui.root.WindowController
import io.legado.app.ui.video.VideoDirect
import io.legado.app.utils.File
import io.legado.app.utils.KS_JSON
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.systemCurrentTimeMillis
import androidx.compose.ui.graphics.toArgb
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * 鸿蒙端 [PlatformServices] 实现: 各能力经 [OhosNativeBridge] napi 桥接到 ArkTS。
 * 经 PlatformServiceProviders.register 注册后供 shared LegadoApp 使用。
 *
 * 桥未注入 tsfn 时统一降级 (记日志 / 剪贴板 / 返回 false), 与 Toast/Window 同策略,
 * 保证 napi 未接入阶段调用链不崩。
 */
object OhosPlatformServices : PlatformServices {

    override val capabilities: PlatformCapabilities
        get() = OhosPlatformCapabilities

    override val files: FilePickerService = OhosFilePickerService

    // 系统分享走 @ohos.share.systemShare SharePanel; 桥未就绪时降级到剪贴板 (对照 desktop shareText)
    override val sharing: ShareService = object : ShareService {
        override fun shareText(text: String) {
            if (OhosNativeBridge.isShareBridgeReady()) {
                OhosNativeBridge.shareText(text)
            } else {
                copyToClipboard(text)
                Toasters.get().toast("已复制到剪贴板")
            }
        }

        override fun shareFile(filePath: String, mimeType: String) {
            if (OhosNativeBridge.isShareBridgeReady()) {
                OhosNativeBridge.shareFile(filePath, mimeType)
            } else {
                Toasters.get().toast("已保存到 $filePath")
            }
        }
    }

    // 鸿蒙无可复用的内置 WebView 宿主, 两者均走系统浏览器 (对照 iOS IosBrowserService)
    override val browser: BrowserService = object : BrowserService {
        override fun openUrl(url: String) = openURL(url)
        override fun openUrlInApp(url: String) = openURL(url)
    }

    // 权限走 @ohos.abilityAccessCtrl, 经同步桥查询/申请; 桥未就绪按"无权限"降级
    override val permissions: PermissionService = object : PermissionService {
        override fun hasPermission(permission: String): Boolean = query("check", permission)

        // 鸿蒙 requestPermissionsFromUser 会回授权结果, 故返回值即最终是否授予
        override fun requestPermission(permission: String): Boolean = query("request", permission)

        private fun query(action: String, permission: String): Boolean {
            if (!OhosNativeBridge.isPermissionBridgeReady()) return false
            val payload = KS_JSON.encodeToString(PermissionPayload(permission = permission))
            val json = OhosNativeBridge.invokePermissionSync(action, payload) ?: return false
            val resp = runCatching {
                KS_JSON.decodeFromString(PermissionResponse.serializer(), json)
            }.getOrNull()
            return resp?.ok == true && resp.granted == true
        }
    }

    // 窗口策略经 OhosNativeBridge tsfn 桥 dispatch 到 ArkTS @ohos.window (无 NDK C 接口);
    // 桥未就绪时记一条降级日志 (兼容 napi 未接入阶段)
    override val window: WindowController = object : WindowController {
        override fun setFullscreen(enabled: Boolean) {
            // window.setWindowLayoutFullScreen: 布局延伸到状态栏区域 (对照 Android immersive)
            OhosNativeBridge.setWindowFullScreenLayout(enabled)
        }

        override fun setKeepScreenOn(enabled: Boolean) {
            // window.setWindowKeepScreenOn (对照 Android FLAG_KEEP_SCREEN_ON)
            OhosNativeBridge.setWindowKeepScreenOn(enabled)
        }

        override fun setOrientation(policy: OrientationPolicy) {
            // window.setPreferredOrientation, 映射到 OHOS Orientation 枚举值
            // (0=UNSPECIFIED 1=PORTRAIT 2=LANDSCAPE 3=PORTRAIT_INVERTED 4=LANDSCAPE_INVERTED 5=AUTO_ROTATION)
            val orientation = when (policy) {
                OrientationPolicy.Unspecified -> 0 // UNSPECIFIED
                OrientationPolicy.Portrait -> 1    // PORTRAIT
                OrientationPolicy.Landscape -> 2   // LANDSCAPE
                OrientationPolicy.Sensor -> 5      // AUTO_ROTATION
                OrientationPolicy.ReversePortrait -> 3 // PORTRAIT_INVERTED
            }
            OhosNativeBridge.setWindowPreferredOrientation(orientation)
        }

        override fun setSystemBars(policy: SystemBarsPolicy) {
            // window.setWindowSystemBarEnable 仅支持整体显隐 (鸿蒙 API 无分栏参数),
            // 故单栏策略 (HiddenStatusBar / HiddenNavigationBar) 降级为整体隐藏 ——
            // 与原版"隐藏指定栏"意图一致但会连带另一栏, 属平台 API 限制;
            // 桥未注册 (EntryAbility 未 registerWindowCallback) 时丢弃命令。
            val visible = policy == SystemBarsPolicy.Default || policy == SystemBarsPolicy.Visible
            OhosNativeBridge.setWindowSystemBarEnable(visible)
        }

        override fun setReaderSystemBarAppearance(appearance: ReaderSystemBarAppearance?) {
            if (appearance == null) {
                // 离开阅读页: 图标内容色交还系统默认 (系统栏底色由系统与窗口决定, 本端不接管)
                OhosNativeBridge.setWindowSystemBarProperties(DEFAULT_CONTENT_COLORS)
                return
            }
            // 阅读页: 状态栏图标明暗按外观值; 导航栏区域由页面内容铺满 (沉浸式全屏布局),
            // 按导航栏底色亮度定 (外观未指定底色时回到主题导航栏色)
            val navigationBarColor = appearance.navigationBarColor
                ?: SharedThemeStoreProvider().navigationBarColor.toArgb()
            OhosNativeBridge.setWindowSystemBarProperties(
                mapOf(
                    "statusBarContentColor" to
                        if (appearance.lightStatusBarIcons) ICON_DARK else ICON_LIGHT,
                    "navigationBarContentColor" to
                        if (ColorUtils.isColorLight(navigationBarColor)) ICON_DARK else ICON_LIGHT,
                )
            )
        }
    }

    // 软键盘走 @ohos.inputMethod + UIContext.setKeyboardAvoidMode (对照 Android IMM + softInputMode)
    override val keyboard: KeyboardController = object : KeyboardController {
        override fun hideSoftInput() = OhosNativeBridge.hideSoftInput()

        override fun setSoftInputPolicy(policy: SoftInputPolicy) {
            when (policy) {
                // KeyboardAvoidMode: 0=OFFSET(上推, 同 ADJUST_PAN), 1=RESIZE(收缩), 2=NONE
                SoftInputPolicy.Default -> OhosNativeBridge.setKeyboardAvoidMode(0)
                SoftInputPolicy.Resize -> OhosNativeBridge.setKeyboardAvoidMode(1)
                SoftInputPolicy.Pan -> OhosNativeBridge.setKeyboardAvoidMode(0)
                SoftInputPolicy.Hidden -> OhosNativeBridge.hideSoftInput()
            }
        }
    }

    /**
     * 通用媒体播放: 复用已通的 media 桥 (ArkTS AVPlayer), 独占 playerId 避免抢占
     * 音频书/HttpTTS/视频书实例。headers 随 setSourceUrl 下发 —— ArkTS 侧
     * `media.createMediaSourceWithUrl(url, headers)` (API 12+) 就是为请求头准备的
     * (见 MediaBridgeHandler.ets handleSetSourceUrl), 早先"鸿蒙无对应入参"的注释不成立。
     */
    override val media: MediaService = object : MediaService {
        override fun playMedia(url: String, headers: Map<String, String>) {
            send(MediaCommand(action = "setSourceUrl", url = url, headers = headers.takeIf { it.isNotEmpty() }))
            send(MediaCommand(action = "play"))
        }

        override fun pauseMedia() = send(MediaCommand(action = "pause"))

        override fun stopMedia() = send(MediaCommand(action = "stop"))

        private fun send(cmd: MediaCommand) {
            OhosNativeBridge.sendMediaCommand(KS_JSON.encodeToString(cmd))
        }
    }

    // 通知走已接入的 notification 桥 (@ohos.notificationManager), max<=0 表示无进度条
    override val notifications: NotificationService = object : NotificationService {
        override fun notify(id: Int, title: String, content: String) {
            OhosNativeBridge.showNotification(id, title, content, progress = 0, max = 0)
        }

        override fun cancelNotification(id: Int) = OhosNativeBridge.cancelNotification(id)
    }

    override val externalRequests: ExternalRequestService = object : ExternalRequestService {
        // ArkTS 侧只能经 napi 传字符串, 故 request 为 Want.uri (或 "route:xxx")
        override fun parseLaunchRequest(request: Any): LaunchRequest? =
            (request as? String)?.let(OhosLaunchRequests::parse)

        // 实际处理由 LegadoApp 经 LaunchRequestBus 消费 (对照 Android 端同为 false)
        override fun handleLaunchRequest(request: LaunchRequest): Boolean = false
    }

    // 崩溃日志: 只列本应用自己落盘的异常现场 (logs/crash/ 下的 crash-*.log, 未捕获异常钩子与
    // 系统崩溃事件, 两条都带调用栈)。系统事件的故障日志文件也写进同目录；
    // 运行日志 (logs/appLog-*.txt, recordLog 开启时落盘) 不再混入
    override val crashLogs: CrashLogProvider = object : CrashLogProvider {
        override suspend fun loadCrashLogs(): List<CrashLogProvider.CrashLogEntry> =
            NativeCrashLogs.listLogs().map { CrashLogProvider.CrashLogEntry(it) }

        override suspend fun readCrashLog(name: String): String? = NativeCrashLogs.readLog(name)

        override suspend fun clearCrashLogs() = NativeCrashLogs.clearLogs()

        override fun shareCrashLog(name: String) {
            val path = NativeCrashLogs.logPath(name)
            if (OhosNativeBridge.isShareBridgeReady()) {
                // 系统分享面板分享日志文件 (对照 Android CrashLogsDialog.shareFile)
                OhosNativeBridge.shareFile(path, "text/plain")
            } else {
                // 桥未就绪降级 toast 提示 (分享不可用)
                Toasters.get().toast("已保存到 $path")
            }
        }
    }
}

/**
 * 鸿蒙文档授权体 URI (`file://docs/...`) 的前缀。
 *
 * `docs` 不是网络主机段, 而是文档授权体: 去前缀即存储挂载点真实路径
 * (与 ArkTS 媒体桥 MediaBridgeHandler.sandboxPathFromFileUri 同一规则)。
 */
internal const val OHOS_DOCS_URI_PREFIX = "file://docs"

/**
 * 文档授权体 URI → 存储挂载点真实路径 (去授权体前缀 + 百分号解码); 非该形态原样返回。
 *
 * URI 里的空格 / 中文等一律是百分号编码形式, 挂载点路径是解码后的形态。
 */
internal fun String.docsUriToMountPath(): String =
    if (startsWith(OHOS_DOCS_URI_PREFIX)) {
        removePrefix(OHOS_DOCS_URI_PREFIX).percentDecode()
    } else {
        this
    }

/**
 * 鸿蒙外部启动请求解析/投递 (对照 app 端 `Intent.toLaunchRequest`)。
 *
 * ArkTS 侧 Want 无法跨 napi 传对象, 约定只传 `want.uri`; 通知点击与主屏快捷方式等携带路由的
 * 场景约定前缀 `route:` (快捷方式的路由在 `want.parameters.route`, 由 EntryAbility 拼上同一前缀
 * 后投递, 见 shortcuts_config.json 的说明); Want 带了 MIME (`want.type`) 时约定
 * `mime:<type>|<uri>` 同串捎带
 * (两种前缀都只存在于本对象与 ArkTS EntryAbility 之间, napi 导出签名不变);
 * 系统分享进来的文本由 ArkTS 宿主加 `text:` 前缀 (文本可能含任意字符, 也与普通 uri 无法区分)。
 * 由 [io.legado.app.napi.LegadoNativeExports.handleLaunchRequest] 调用。
 */
object OhosLaunchRequests {

    /** URI → [LaunchRequest]; 无法识别返回 null。 */
    fun parse(uri: String): LaunchRequest? {
        val value = uri.trim()
        if (value.isEmpty()) return null
        if (value.startsWith(ROUTE_PREFIX)) {
            return navigateToRoute(value.removePrefix(ROUTE_PREFIX))
        }
        if (value.startsWith(TEXT_PREFIX)) {
            // 系统分享进来的文本 (ArkTS EntryAbility.handleSharedData 打的前缀): 文本可能含任意
            // 字符, 与普通 uri 无法区分, 故按已有 route:/mime: 同一路数显式标记。
            // 整段是链接当书籍地址添加 / 其余进搜索页的分流在 [LaunchRequest.ProcessText] 的消费方
            // 统一处置 (判据见 shared shareTextTarget), 各端入口只负责原样投递文本。
            val text = value.removePrefix(TEXT_PREFIX)
            if (text.isEmpty()) return null
            return LaunchRequest.ProcessText(text)
        }
        val hint = extractMimeHint(value)
        val address = hint?.second ?: value
        if (address.isEmpty()) return null
        // 外部明确告知 MIME 是视频: 直投播放态。判据仍用 shared 的 [VideoDirect] (与 iOS/桌面
        // 一份), 只是把 mimeIsVideo 替它填上; 地址不带可播 scheme 时 targetFor 返回 null,
        // 落回下面的常规分支 (不得因为一个 MIME 把路由偷换成视频)。
        if (hint != null && VideoDirect.isVideoMime(hint.first)) {
            // 标题只能取地址末段: 系统媒体库投来的形如 `file://media/Video/<fileId>`, 末段是
            // fileId 而不是 `x.mp4`, 所以从图库投视频时标题会显示成一串数字 (用户已拍板 1A:
            // 接受这个观感, 不加权限)。两条依据均取自本机 SDK 声明, 不是推断:
            //  - @ohos.app.ability.Want.d.ts:281-287 —— `parameters?: Record<string, Object>`,
            //    官方**未规定任何显示名/文件名键**, 拿它取名等于编造键名去赌;
            //  - @ohos.file.photoAccessHelper.d.ts —— `PhotoAsset.displayName` 存在 (:928),
            //    但取 PhotoAsset 的接口**每一条都标着 @permission ohos.permission.READ_IMAGEVIDEO**
            //    (:627/646/665/…), 那是 user_grant 受限权限 —— 为一个标题让阅读器弹一次
            //    “允许读取相册视频”并背上上架审核代价, 不对等, 故不走这条路。
            // 不要再“顺手补上真实文件名”: 要么加权限(行为变更, 需拍板), 要么维持现状。
            VideoDirect.targetFor(address, mimeIsVideo = true)?.let { direct ->
                return LaunchRequest.OpenRoute(AppRoute.VideoPlay(direct))
            }
        }
        val scheme = address.substringBefore("://", missingDelimiterValue = "").lowercase()
        return when (scheme) {
            // 鸿蒙文件关联给出的是 file://docs/... 授权体 URI, 与 Android content/file 同语义
            // (没扩展名又没 MIME 提示的视频只能走这条链, 由 shared 侧嗅探后报"不支持的文件")
            "file", "content", "app", "datashare" -> LaunchRequest.ImportFile(address)
            "" -> null
            else -> LaunchRequest.DeepLink(address)
        }
    }

    /** 投递到 [LaunchRequestBus]; 队列已关闭返回 false。 */
    fun post(request: LaunchRequest): Boolean =
        runCatching { LaunchRequestBus.dispatch(request) }.isSuccess

    /**
     * 解出同串捎带的 MIME 提示 (`mime:<type>|<address>`); 不是该形态返回 null。
     *
     * 为什么要捎: Want 对象过不了 napi (只能传一个字符串), 而 `file://media/Video/<fileId>`
     * 这类系统媒体库 URI 末段是 fileId 而不是 `x.mp4`, 光靠扩展名嗅探判不出视频,
     * 只有 want.type 能认。只按首个分隔符切一次, 后面整串当地址 (URI 里再出 '|' 不影响)。
     */
    private fun extractMimeHint(value: String): Pair<String, String>? {
        if (!value.startsWith(MIME_PREFIX)) return null
        val body = value.removePrefix(MIME_PREFIX)
        val separator = body.indexOf(MIME_SEPARATOR)
        if (separator <= 0) return null
        return body.substring(0, separator) to body.substring(separator + 1)
    }

    private const val ROUTE_PREFIX = "route:"
    private const val MIME_PREFIX = "mime:"
    private const val MIME_SEPARATOR = '|'
    private const val TEXT_PREFIX = "text:"
}

/** 权限请求 payload (Kotlin → ArkTS)。 */
@Serializable
private data class PermissionPayload(val permission: String)

/** 权限响应 (ArkTS → Kotlin): granted 为最终授权态。 */
@Serializable
private data class PermissionResponse(
    val ok: Boolean,
    val granted: Boolean? = null,
    val error: String? = null,
)

/** 通用媒体命令 (与 ArkTS MediaBridgeHandler 协议对齐, playerId 独占避免抢占书籍播放器)。 */
@Serializable
private data class MediaCommand(
    val action: String,
    val playerId: String = "platformMedia",
    val url: String? = null,
    val headers: Map<String, String>? = null,
)

/**
 * 鸿蒙文件选择: 接 [io.legado.app.help.file] 的 napi 桥实现 (对照 iOS IosFilePickerService)。
 *
 * DocumentViewPicker 返回的是 `file://docs/...` URI, 不能直接喂给 [File] (POSIX 路径);
 * 故 [pickFile]/[pickFiles] 选中后立刻经 [pickDocumentContent] 读出字节落盘到 cacheDir,
 * 返回沙盒内真实路径, 下游 BackupFileOps.readText 等按普通文件消费。
 */
private object OhosFilePickerService : FilePickerService {

    override fun pickFile(filter: FileFilter): String? =
        pickDocuments(filter.toUtis(), allowsMultiple = false)
            ?.firstOrNull()
            ?.let(::materialize)

    override fun pickFiles(filter: FileFilter): List<String> =
        pickDocuments(filter.toUtis(), allowsMultiple = true)
            .orEmpty()
            .mapNotNull(::materialize)

    /**
     * 单文件导出取目标路径: 弹目录选择器 ([pickDirectory], 对照原版导出形制 —— 先选目录,
     * 再把文件写进该目录, 见 HandleFileContract.EXPORT + FileUtils.exportFile), 返回该目录下的
     * 目标文件路径, 由调用方写盘。
     *
     * 写入用的是选择器当场给的临时授权 (授权持久化与 URI→路径转换在 [pickDirectory] 内完成),
     * 后续访问才需激活。
     *
     * @return 用户取消选目录返回 null (调用方静默)
     */
    override fun saveFile(suggestedName: String, defaultDir: String?): String? {
        val dir = defaultDir ?: pickDirectory(DirUsage.Export) ?: return null
        return "$dir/$suggestedName"
    }

    /**
     * 图片查看器长按保存落系统相册 (用户指定要求, 与原版 PhotoDialog 的目录记忆不同)。
     * 漫画图片保存不受本项影响, 始终走 [saveImageRememberingDir] 的目录记忆。
     */
    override val imageViewerSavesToAlbum: Boolean = true

    /**
     * 落系统相册。写不进相册 (未授予相册权限/图库拒绝/桥未就绪) 一律返回 false 并记日志, 由调用方
     * 如实报"保存失败"——不能改写进用户看不到的应用私有目录还报成功 (那个落点用户取不到,
     * 报成功就是谎报)。
     */
    override fun saveImageBytes(suggestedName: String, bytes: ByteArray): Boolean? {
        val ext = imageExtension(bytes, suggestedName).removePrefix(".")
        // Result.getOrElse 的入参是 Throwable (失败原因), 不是成功值 —— 取成功值要用 getOrDefault。
        // 两类失败分开如实记录: 抛异常走原因, 正常返回 false 则是未授权/被图库拒绝
        val result = runCatching { saveImageToAlbum(ext, bytes) }
        val failure = result.exceptionOrNull()
        if (failure != null) {
            AppLog.put("保存图片到相册失败\n$suggestedName\n${failure.message}", failure)
            return false
        }
        val saved = result.getOrDefault(false)
        if (!saved) {
            AppLog.put("保存图片到相册失败, 未授予相册权限或被图库拒绝\n$suggestedName")
        }
        return saved
    }

    /**
     * 支持把图片字节写进选定目录 ([saveImageRememberingDir] 的"选一次目录后直写"分支)。
     *
     * 选定目录在沙盒外, 写入前必须先激活其持久化目录授权 ([OhosDirAuthorizations]),
     * 否则重启后写入被拒 (对照 [OhosAuthorizedFileBookAccessor] 的书籍落盘同款处理);
     * 落盘走"同目录临时件 + 改名就位" (与备份 zip 写进用户选定目录同一机制:
     * BackupFileOps.copyFile → copyFileAtomically), 覆盖同名图时目标始终完整。
     */
    override val supportsDirWrite: Boolean = true

    override fun writeImageToDir(dir: String, fileName: String, bytes: ByteArray): Boolean =
        runCatching {
            OhosDirAuthorizations.ensureActivatedFor(dir, DirUsage.ImageSave)
            val destDir = File(dir).apply { mkdirs() }
            writeBytesAtomically(File(destDir, fileName).path, bytes)
        }.getOrDefault(false)

    /**
     * 目录可写性预检。
     *
     * 用户选定的备份目录在沙盒外, "已记住的备份路径"须先激活持久化授权才可写 —— 否则
     * 重启后这里会为真可写的目录报出 false, 把用户拉回重选目录 (对照
     * [io.legado.app.ui.route.BackupConfigRoute] 不可写即重选的分支)。
     * 激活按 [usage] 匹配: 该目录须在该用途下已授权。
     * 应用私有目录 [OhosDirAuthorizations.ensureActivatedFor] 直通; 私有目录之外未按该用途登记的
     * 路径报不可写。
     */
    override fun checkWrite(path: String, usage: DirUsage): Boolean = runCatching {
        OhosDirAuthorizations.ensureActivatedFor(path, usage)
        val testDir = File(path)
        if (!testDir.exists()) testDir.mkdirs()
        if (!testDir.isDirectory) return@runCatching false
        val testFile = File(testDir, ".write_test_${systemCurrentTimeMillis()}")
        testFile.writeText("test")
        testFile.delete()
        true
    }.getOrDefault(false)

    // 选目录: Tablet (path 非空) 为已持久化授权 + 官方转换的路径, 验证可访问后记入授权表
    // (带 [usage]: 该目录只放行同用途的访问);
    // Phone (path 为 null) 折回挂载点路径后同样记入授权表 (该设备无目录授权持久化能力,
    // 登记即该路径在本用途下的唯一授权, 不做可读性过滤)
    override fun pickDirectory(usage: DirUsage): String? {
        val picked = pickDirectoryPolicy() ?: return null
        val convertedPath = picked.path
        if (convertedPath == null) {
            val legacyPath = picked.uri.toLegacySandboxPath()
            OhosDirAuthorizations.remember(picked.uri, legacyPath, usage)
            return legacyPath
        }
        // 官方转换不校验可访问性: 此刻临时授权在, 目录仍不可读即真实失败, 不假成功
        if (!File(convertedPath).isDirectory) {
            AppLog.put("选中目录不可访问 path=$convertedPath")
            return null
        }
        OhosDirAuthorizations.remember(picked.uri, convertedPath, usage)
        return convertedPath
    }

    /** Phone 降级分支的 picker URI 折回: docs 授权体去前缀即挂载点路径 (与文件关联投递同一规则)。 */
    private fun String.toLegacySandboxPath(): String = when {
        startsWith(OHOS_DOCS_URI_PREFIX) -> docsUriToMountPath()
        startsWith("file://") -> removePrefix("file://")
        else -> this
    }

    // 物化副本清理 (对照 Android discardPickedFile): 只删 cacheDir/filePicker 下自建临时文件,
    // 用户原文件不碰; 不清理则反复换壁纸在缓存里累积原图副本
    override fun discardPickedFile(path: String) {
        val tempDir = AppFilesDirs.get().cacheDir + "/filePicker"
        val file = File(path)
        if (file.parentFile?.absolutePath == File(tempDir).absolutePath) {
            runCatching { file.delete() }
        }
    }

    /** URI → 沙盒缓存文件路径; 读取失败返回 null (与取消同样降级)。 */
    private fun materialize(uri: String): String? = runCatching {
        val bytes = pickDocumentContent(uri) ?: return null
        val name = uri.substringAfterLast('/').substringBefore('?')
            .ifBlank { "picked_${systemCurrentTimeMillis()}" }
        val dir = AppFilesDirs.get().cacheDir + "/filePicker"
        File(dir).mkdirs()
        val target = File(dir, name)
        target.writeBytes(bytes)
        target.path
    }.getOrNull()
}

// FileFilter 扩展名 → UTI (桥接层按 iOS UTI 风格取值, ArkTS FilePickerBridgeHandler 再映射到 MIME)
private fun FileFilter.toUtis(): List<String> = when {
    extensions.isEmpty() -> listOf("public.data")
    else -> extensions.mapNotNull { ext ->
        when (ext.lowercase()) {
            "txt", "log", "md" -> "public.plain-text"
            "json" -> "public.json"
            "xml" -> "public.xml"
            "png" -> "public.png"
            "jpg", "jpeg" -> "public.jpeg"
            "gif" -> "public.gif"
            "bmp" -> "public.bmp"
            "webp" -> "public.image"
            else -> null
        }
    }.distinct().ifEmpty { listOf("public.data") }
}

/** 系统栏图标内容色: 深色图标 (浅色底色) / 浅色图标 (深色底色) */
private const val ICON_DARK = "#FF000000"
private const val ICON_LIGHT = "#FFFFFFFF"

/** 图标内容色交还系统默认 (对照 @ohos.window SystemBarProperties 内容色默认值) */
private val DEFAULT_CONTENT_COLORS = mapOf(
    "statusBarContentColor" to "#E5FFFFFF",
    "navigationBarContentColor" to "#E5FFFFFF",
)
