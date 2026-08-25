package io.legado.app.ui.root

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.fileNameFormat
import io.legado.app.help.FileUtilsCommon
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.config.resolveImagePath
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.model.bakeCoverImageFile
import io.legado.app.model.bakedImagePath
import io.legado.app.model.probeDecodeImage
import io.legado.app.utils.ScreenInfoProviders
import io.legado.app.utils.systemCurrentTimeMillis
import kotlin.concurrent.Volatile

/**
 * 平台服务聚合入口。每项能力保持小接口、单一职责，避免成为 God Interface。
 * 平台入口在系统启动时注册具体实现。
 */
interface PlatformServices {
    val capabilities: PlatformCapabilities
    val files: FilePickerService
    val sharing: ShareService
    val browser: BrowserService
    val permissions: PermissionService
    val window: WindowController
    val keyboard: KeyboardController
    val media: MediaService
    val notifications: NotificationService
    val externalRequests: ExternalRequestService
    val crashLogs: CrashLogProvider
}

object PlatformServiceProviders {
    @Volatile
    private var impl: PlatformServices? = null

    fun register(services: PlatformServices) {
        impl = services
    }

    fun get(): PlatformServices = impl
        ?: error("PlatformServices must be registered by the system entry")

    fun getOrNull(): PlatformServices? = impl
}

/** 文件选择器：单选/多选/保存。 */
interface FilePickerService {
    fun pickFile(filter: FileFilter): String?
    fun pickFiles(filter: FileFilter): List<String>
    fun saveFile(suggestedName: String, defaultDir: String? = null): String?

    /**
     * 保存图片字节到用户选择的位置（图片查看器长按保存，对照 master PhotoDialog.doSaveImage）。
     * 各端: app=SAF CreateDocument → contentResolver 写入; desktop=保存对话框 → File 写入。
     * 返回: true=写入成功; false=写入失败; null=用户取消选择位置（调用方应静默）。
     * 未实现端返回 false（调用方 toast 失败）。
     */
    fun saveImageBytes(suggestedName: String, bytes: ByteArray): Boolean? = false

    /**
     * 是否支持"把字节写进指定目录" (决定图片保存能不能记住上次目录):
     * true 时 [saveImageRememberingDir] 走"选一次目录后直接落盘",
     * false 时回落 [saveImageBytes] 的每次选位置。
     */
    val supportsDirWrite: Boolean get() = false

    /**
     * 把图片字节写进指定目录 (对照 app 端 FileUtils.saveImage(dirUri))。
     *
     * @param dir 目录标识 (Android SAF tree uri / 桌面绝对路径)
     * @param fileName 文件名 (含扩展名)
     * @return 写入是否成功 (目录被删/权限丢失返回 false, 调用方清掉目录记忆重选)
     */
    fun writeImageToDir(dir: String, fileName: String, bytes: ByteArray): Boolean = false

    /**
     * 保存图片字节, 目录记忆语义对照 app 端 (ACache `imagePathKey`): 上次保存目录还记着就直接
     * 写进去, 没有就先让用户选一次并记住; 写入失败清掉记忆下次重选。不支持目录写入的平台
     * ([supportsDirWrite]=false) 回落 [saveImageBytes] 的"每次选位置"。
     *
     * 阻塞 IO + 可能弹平台选择器 (实现内部 runBlocking 等主线程回调), 必须在 IO 线程调用。
     *
     * @param forcePickDir true = 忽略记忆目录强制重选 (WebView 长按菜单的"选择文件夹")
     * @return true=写入成功, false=写入失败, null=用户取消选目录 (调用方静默)
     */
    fun saveImageRememberingDir(
        fileName: String,
        bytes: ByteArray,
        forcePickDir: Boolean = false,
    ): Boolean? {
        if (!supportsDirWrite) return saveImageBytes(fileName, bytes)
        val prefs = PreferenceProviders.get()
        val remembered = if (forcePickDir) {
            null
        } else {
            prefs.getString(AppConst.imagePathKey).takeIf { it.isNotEmpty() }
        }
        val dir = remembered
            ?: pickDirectory()?.also { prefs.putString(AppConst.imagePathKey, it) }
            ?: return null
        if (writeImageToDir(dir, fileName, bytes)) return true
        // 目录被删/权限丢失: 忘掉记忆目录, 下次重新选 (对照 app 端 onError → ACache.remove)
        prefs.remove(AppConst.imagePathKey)
        return false
    }

    // 选目录 (对照 app 端 HandleFileContract.DIR_SYS / OpenDocumentTree),
    // 各端按需实现, 默认返回 null 由调用方降级
    fun pickDirectory(): String? = null

    /**
     * 备份目录可写性预检 (对照 app 端 BackupConfigFragment.backup 的 FileDoc.checkWrite)。
     * Android SAF content:// 目录用 DocumentFile 判断可写性, 不可写返回 false 由调用方引导重新选目录;
     * 无 SAF 概念的平台 (桌面等普通路径) 默认视为可写。
     */
    fun checkWrite(path: String): Boolean = true

    /**
     * 处理启动闪屏背景图 (选图后调用)：原图复制进图集目录 (备份链路) + 按本端启动界面
     * 尺寸居中裁剪+缩小烘焙产物写缓存 (使用链路)，返回**裸文件名相对引用**供调用方写 pref。
     *
     * # 保存机制 (与主题背景图对齐)
     * - 原图: 图集目录内容特征值命名 `<字节数>.<原扩展名>` (随备份 zip 打包
     *   —— 烘焙产物是按当时屏幕尺寸裁剪的派生物，丢了/换设备可由原图重烘焙；
     *   pref 存裸文件名, 经 resolveImagePath 解析到 customImg 图集目录);
     * - 使用: 缓存根同名 customImg 子目录 `{cache}/customImg/<stem>.webp`
     *   (见 [io.legado.app.model.bakedImagePath])，启动时直接解码小图不再碰原图；缓存被清由渲染端现场重烘焙一次。
     * 日/夜同规则, 同图 (同字节数) 可共用同一文件, 各键独立引用。成功后删除 [oldPath] 对应旧原图及旧产物 (当前 pref 里的旧引用，
     * 对照原版 setCoverFromUri 开头的删旧逻辑)。
     * 未实现端返回 null；实现端原图导入失败提示 + 记 AppLog 后返回 null —— 调用方回落原路径。
     * 阻塞 IO，必须在 IO 线程调用。
     */
    fun processWelcomeImage(srcPath: String, oldPath: String?, isNight: Boolean): String? = null

    /**
     * 丢弃 [pickFile] 为了给出本地路径而物化的临时副本 (Android SAF 走 `cacheDir/file_picker`)。
     * 图片导入类调用方把内容复制进持久目录后应当调一次, 否则每选一次就在缓存里留一份原图。
     * 实现端**只允许**删自己物化的临时文件, 不碰用户原文件; 无物化概念的平台默认 no-op。
     */
    fun discardPickedFile(path: String) = Unit

    /**
     * 导入主题背景图（选图后调用）：原图复制进图集目录 + 按本端屏幕尺寸烘焙清晰产物写缓存。
     *
     * # 图集命名 (内容特征值)
     * 原图落 `customImg/<文件字节数>.<原扩展名>` ([importImageSetFile] 的 baseName=null 模式,
     * 同字节数视为同内容**复用覆盖**, 不同大小各留一份; 字节数非哈希, 极端碰撞会覆盖,
     * 概率可忽略)。日/夜共用同一图集, 同名文件不区分日夜 —— 日夜引用各自独立存键,
     * 同图时两键指向同一文件, 备份打包该目录一份即可。
     *
     * # 保存机制 (与启动图对齐, [io.legado.app.model.bakedImagePath])
     * 原图随备份 zip 打包不会丢; 产物按本端**屏幕真实全屏像素**（iOS UIScreen.nativeBounds /
     * 鸿蒙显示物理像素 / 桌面 Toolkit screenSize / 安卓真实屏幕）居中裁剪 + 不放大缩放
     * （超过屏幕才缩到屏幕，源图小于屏幕保持原尺寸，放大交给显示层 Crop）写缓存根同名
     * customImg 子目录（纯派生物，按源特征值派生名，不进备份）。渲染端优先读产物
     * （[io.legado.app.ui.root.WallpaperLayer]），缓存被清时回落按窗口解码原图。
     *
     * 返回值为**裸文件名相对引用** (`<size>.<ext>`, 文件在 customImg 图集目录, 目录前缀不进
     * 引用) 供调用方写 bgImage/bgImageN 键。
     * 烘焙失败不影响导入结果（渲染端读不到产物时回落原图解码），记 AppLog；
     * 读源/写盘失败返回 null，调用方保持原路径不变。阻塞 IO，必须在 IO 线程调用。
     */
    fun importBackgroundImage(srcPath: String, isNight: Boolean): String? {
        // 内容特征值命名 (非固定名): 换图不同大小各留一文件, 同大小复用; 旧文件交由
        // clearBg 白名单清理, 不在导入时删——日夜可能引用同一份文件, 导入侧删会误伤
        val ref = importImageSetFile(srcPath)
            ?: return null
        val abs = resolveImagePath(ref) ?: return ref
        // 屏幕尺寸未注册/非法时跳过烘焙 (渲染端回落原图解码, 不影响导入)
        val si = runCatching { ScreenInfoProviders.get() }.getOrNull()
        if (si != null && si.screenWidthPx > 0 && si.screenHeightPx > 0) {
            if (!bakeCoverImageFile(
                    abs,
                    bakedImagePath(abs),
                    si.screenWidthPx,
                    si.screenHeightPx
                )
            ) {
                AppLog.put("主题背景图烘焙失败: $abs")
            }
        }
        return ref
    }
}

/**
 * 图片扩展名归一化: `jpeg/jpe/jfif` → `jpg` (同一张图因源文件名/物化名后缀不同,
 * 不归一化会把同内容分裂成 `<size>.jpeg`/`<size>.jpg` 两个图集文件, 特征值去重失效)。
 */
fun normalizeImageSuffix(suffix: String): String = when (suffix.lowercase()) {
    "jpeg", "jpe", "jfif" -> "jpg"
    else -> suffix
}

/**
 * 图集导入 (主题背景图/启动图共用): 源图试解码拦截后复制进 `customImg` 图集目录,
 * **内容特征值命名** `<字节数>.<原扩展名>` (同字节数视为同内容复用覆盖, 不同大小各留一份;
 * 非哈希, 极端撞长度会覆盖, 概率可忽略; 后缀经 [normalizeImageSuffix] 归一化)。日/夜/启动封面共用同一文件, 各键独立引用。
 *
 * 统一放 `{externalFiles|files}/customImg` (封面图集/启动图/阅读背景 novelBg 子目录同根,
 * 跨端备份打包同一目录)。
 *
 * @return 裸文件名相对引用 `<fileName>` (目录前缀不进入引用, 经 resolveImagePath
 * 解析到图集目录; 跨机/跨端恢复无需改路径); 读源/写盘/解码失败返回 null (调用方保持原状态)
 */
fun importImageSetFile(srcPath: String): String? = runCatching {
    val bytes = FileUtilsCommon.readBytes(srcPath) ?: return@runCatching null
    if (bytes.isEmpty()) return@runCatching null
    // 试解码拦截 (HEIC 等本端解不开的相册格式): 返回 null 走导入失败提示,
    // 避免「复制成功但解码器解不出, 显示端永远空白」的静默坑
    if (!probeDecodeImage(bytes)) return@runCatching null
    val base = AppFilesDirs.get().externalFilesDir ?: AppFilesDirs.get().filesDir
    val dir = FileUtilsCommon.getPath(base, "customImg")
    FileUtilsCommon.createFolderIfNotExist(dir)
    // 扩展名取**文件名**里的, 不是整条路径的 —— 目录名含点 (如 D:\my.photos\image)
    // 时对整条路径取 substringAfterLast('.') 会得到 "photos\image" 这种非法片段
    val srcName = srcPath.substringAfterLast('/').substringAfterLast('\\')
    val suffix = normalizeImageSuffix(srcName.substringAfterLast('.', "").take(8))
    val fileName = bytes.size.toString() + if (suffix.isBlank()) "" else ".$suffix"
    val dest = FileUtilsCommon.getPath(dir, fileName)
    // 同名 (同字节数=同内容) 已存在则复用不重复拷贝 (拷贝数=图集文件数, 换同名图零 IO)
    if (!FileUtilsCommon.exist(dest)) {
        if (!FileUtilsCommon.writeBytes(dest, bytes)) return@runCatching null
    }
    fileName
}.getOrNull()

/**
 * 保存用的文件名: 时间戳 + 源地址扩展名 (对照 app 端 FileUtils.saveImage 的默认命名;
 * 扩展名不像扩展名时统一 .jpg)。
 */
fun imageSaveFileName(src: String): String {
    val ext = src.substringAfterLast('.', "")
        .takeIf { it.isNotEmpty() && it.length <= 5 && it.all { c -> c.isLetterOrDigit() } }
        ?.let { ".$it" }
        ?: ".jpg"
    return "${AppConst.fileNameFormat.format(systemCurrentTimeMillis())}$ext"
}

/** 分享：文本与文件。 */
interface ShareService {
    fun shareText(text: String)
    fun shareFile(filePath: String, mimeType: String)
}

/** 浏览器：外链与内置页。 */
interface BrowserService {
    fun openUrl(url: String)
    fun openUrlInApp(url: String)
}

/** 权限：查询与请求。 */
interface PermissionService {
    fun hasPermission(permission: String): Boolean
    fun requestPermission(permission: String): Boolean
}

/** 窗口控制：全屏/常亮/方向/系统栏。 */
interface WindowController {
    /**
     * 路由策略里的 fullscreen 是"沉浸式隐藏系统栏"语义 (对照 app 端 Activity.fullScreen)。
     * 无系统栏的平台 (desktop) 置 false, 避免被当成"窗口最大化"; 手动全屏 (F11) 不受影响。
     */
    val appliesPolicyFullscreen: Boolean get() = true

    fun setFullscreen(enabled: Boolean)
    fun setKeepScreenOn(enabled: Boolean)
    fun setOrientation(policy: OrientationPolicy)
    fun setSystemBars(policy: SystemBarsPolicy)
}

/** 软输入法控制（hide/show/resize）。 */
interface KeyboardController {
    fun hideSoftInput()
    fun showSoftInput()
    fun setSoftInputPolicy(policy: SoftInputPolicy)
}

/** 媒体播放控制。 */
interface MediaService {
    fun playMedia(url: String, headers: Map<String, String>)
    fun pauseMedia()
    fun stopMedia()
}

/** 通知：发送与取消。 */
interface NotificationService {
    fun notify(id: Int, title: String, content: String)
    fun cancelNotification(id: Int)
}

/** 外部请求：解析与处理启动请求。 */
interface ExternalRequestService {
    fun parseLaunchRequest(request: Any): LaunchRequest?
    fun handleLaunchRequest(request: LaunchRequest): Boolean
}

/**
 * 崩溃日志提供者：读取崩溃日志文件列表、读文件内容、清空、分享。
 *
 * 对照 app 端 CrashLogsDialog.CrashViewModel 的逻辑:
 * - initData: 从 externalCacheDir/crash 和备份路径收集崩溃日志文件
 * - readFile: 读取单个崩溃日志文件内容
 * - clearCrashLog: 删除所有崩溃日志文件
 * - 分享: 通过 ShareService 分享文件
 *
 * 各端 actual 实现:
 * - androidMain: 外部缓存目录 + SAF 备份路径
 * - desktopMain: 用户目录/logs/crash
 * - iOS/鸿蒙: 暂返回空列表 (后续按需实现)
 */
interface CrashLogProvider {
    /** 崩溃日志条目 (仅文件名, 与 CrashLogItem 一致) */
    data class CrashLogEntry(val name: String)

    /** 收集崩溃日志文件列表 (对照 CrashViewModel.initData) */
    suspend fun loadCrashLogs(): List<CrashLogEntry>

    /** 读取单个崩溃日志文件内容 (对照 CrashViewModel.readFile) */
    suspend fun readCrashLog(name: String): String?

    /** 清空所有崩溃日志 (对照 CrashViewModel.clearCrashLog) */
    suspend fun clearCrashLogs()

    /** 分享单个崩溃日志文件 (对照 CrashLogsDialog.shareFile) */
    fun shareCrashLog(name: String)
}

/** 文件类型过滤：MIME 与扩展名任一命中即接受。 */
data class FileFilter(
    val mimeTypes: List<String> = emptyList(),
    val extensions: List<String> = emptyList(),
) {
    companion object {
        val Any = FileFilter()
        val Images = FileFilter(
            mimeTypes = listOf("image/*"),
            extensions = listOf("png", "jpg", "jpeg", "gif", "webp", "bmp"),
        )
        val Text = FileFilter(
            mimeTypes = listOf("text/*"),
            extensions = listOf("txt", "log", "md", "json", "xml"),
        )
    }
}

enum class OrientationPolicy { Unspecified, Portrait, Landscape, Sensor, ReversePortrait }

enum class SystemBarsPolicy {
    Default, Hidden, Visible, Immersive,

    /** 仅隐藏状态栏（阅读页 hideStatusBar 独立配置） */
    HiddenStatusBar,

    /** 仅隐藏导航栏（阅读页 hideNavigationBar 独立配置） */
    HiddenNavigationBar,
}

enum class SoftInputPolicy { Default, Resize, Pan, Hidden }
