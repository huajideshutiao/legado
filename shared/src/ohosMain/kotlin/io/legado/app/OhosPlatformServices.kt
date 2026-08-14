package io.legado.app

import io.legado.app.help.copyToClipboard
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.file.pickDocumentContent
import io.legado.app.help.log.OhosCrashLogs
import io.legado.app.help.file.pickDocuments
import io.legado.app.help.file.pickDirectory as pickDirectoryDocument
import io.legado.app.help.openURL
import io.legado.app.help.toast.Toasters
import io.legado.app.napi.OhosNativeBridge
import io.legado.app.ui.OhosPlatformCapabilities
import io.legado.app.ui.root.BrowserService
import io.legado.app.ui.root.CrashLogProvider
import io.legado.app.ui.root.ExternalRequestService
import io.legado.app.ui.root.FileFilter
import io.legado.app.ui.root.FilePickerService
import io.legado.app.ui.root.KeyboardController
import io.legado.app.ui.root.LaunchRequest
import io.legado.app.ui.root.LaunchRequestBus
import io.legado.app.ui.root.MediaService
import io.legado.app.ui.root.NotificationService
import io.legado.app.ui.root.OrientationPolicy
import io.legado.app.ui.root.PermissionService
import io.legado.app.ui.root.PlatformCapabilities
import io.legado.app.ui.root.PlatformServices
import io.legado.app.ui.root.ShareService
import io.legado.app.ui.root.SoftInputPolicy
import io.legado.app.ui.root.SystemBarsPolicy
import io.legado.app.ui.root.WindowController
import io.legado.app.utils.File
import io.legado.app.utils.KS_JSON
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * 鸿蒙端 [PlatformServices] 实现: 各能力经 [OhosNativeBridge] napi 桥接到 ArkTS。
 * 经 PlatformServiceProviders.register 注册后供 shared LegadoApp 使用。
 *
 * 桥未注入 tsfn 时统一降级 (println / 剪贴板 / 返回 false), 与 Toast/Window 同策略,
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
    // 桥未就绪时降级 println (兼容 napi 未接入阶段)
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
            // 桥未注册 (EntryAbility 未 registerWindowCallback) 时降级 println。
            val visible = policy == SystemBarsPolicy.Default || policy == SystemBarsPolicy.Visible
            OhosNativeBridge.setWindowSystemBarEnable(visible)
        }
    }

    // 软键盘走 @ohos.inputMethod + UIContext.setKeyboardAvoidMode (对照 Android IMM + softInputMode)
    override val keyboard: KeyboardController = object : KeyboardController {
        override fun hideSoftInput() = OhosNativeBridge.hideSoftInput()

        override fun showSoftInput() = OhosNativeBridge.showSoftInput()

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
     * 音频书/HttpTTS/视频书实例。headers 鸿蒙 AVPlayer 无对应入参, 忽略 (对照 Android 端同为空实现)。
     */
    override val media: MediaService = object : MediaService {
        override fun playMedia(url: String, headers: Map<String, String>) {
            send(MediaCommand(action = "setSourceUrl", url = url))
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

    // 崩溃日志: 从 {filesDir}/logs 收集 appLog-*.txt (OhosAppLogHost 在 recordLog 开启时落盘;
    // 对照 Android CrashViewModel.initData 从 externalCacheDir/crash 收集的接口语义)
    override val crashLogs: CrashLogProvider = object : CrashLogProvider {
        override suspend fun loadCrashLogs(): List<CrashLogProvider.CrashLogEntry> =
            OhosCrashLogs.listLogs().map { CrashLogProvider.CrashLogEntry(it) }

        override suspend fun readCrashLog(name: String): String? = OhosCrashLogs.readLog(name)

        override suspend fun clearCrashLogs() = OhosCrashLogs.clearLogs()

        override fun shareCrashLog(name: String) {
            val path = "${AppFilesDirs.get().filesDir}/logs/$name"
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
 * 鸿蒙外部启动请求解析/投递 (对照 app 端 `Intent.toLaunchRequest`)。
 *
 * ArkTS 侧 Want 无法跨 napi 传对象, 约定只传 `want.uri`; 通知点击等携带路由的场景
 * 约定前缀 `route:`。由 [io.legado.app.napi.LegadoNativeExports.handleLaunchRequest] 调用。
 */
object OhosLaunchRequests {

    /** URI → [LaunchRequest]; 无法识别返回 null。 */
    fun parse(uri: String): LaunchRequest? {
        val value = uri.trim()
        if (value.isEmpty()) return null
        if (value.startsWith(ROUTE_PREFIX)) {
            return value.removePrefix(ROUTE_PREFIX).takeIf { it.isNotEmpty() }
                ?.let(LaunchRequest::NavigateTo)
        }
        val scheme = value.substringBefore("://", missingDelimiterValue = "").lowercase()
        return when (scheme) {
            // 鸿蒙文件关联给出的是 file://docs/... 授权体 URI, 与 Android content/file 同语义
            "file", "content", "app", "datashare" -> LaunchRequest.ImportFile(value)
            "" -> null
            else -> LaunchRequest.DeepLink(value)
        }
    }

    /** 投递到 [LaunchRequestBus]; 队列已关闭返回 false。 */
    fun post(request: LaunchRequest): Boolean =
        runCatching { LaunchRequestBus.dispatch(request) }.isSuccess

    private const val ROUTE_PREFIX = "route:"
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
     * 鸿蒙 DocumentViewPicker.save 需在弹窗时就交出字节, 与"先要路径后写入"的接口顺序相反,
     * 故同 iOS: 返回沙盒 filesDir/export 下的可写路径, 用户可在"文件"应用中取用。
     */
    override fun saveFile(suggestedName: String, defaultDir: String?): String? {
        val dir = defaultDir ?: (AppFilesDirs.get().filesDir + "/export")
        return runCatching {
            File(dir).mkdirs()
            "$dir/$suggestedName"
        }.getOrNull()
    }

    override fun pickDirectory(): String? = pickDirectoryDocument()?.toSandboxPath()

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

    /**
     * 目录无内容可读回, 只能把 picker URI 折回 POSIX 路径:
     * docs 授权体 `file://docs/storage/...` 去掉前缀即挂载点真实路径, 其他 scheme 原样透传。
     */
    private fun String.toSandboxPath(): String = when {
        startsWith("file://docs") -> removePrefix("file://docs")
        startsWith("file://") -> removePrefix("file://")
        else -> this
    }
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
