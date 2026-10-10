package io.legado.app.help.extension

import io.legado.app.help.extension.model.InstallStep
import io.legado.app.help.extension.model.MangaExtension
import kotlinx.coroutines.flow.Flow
import kotlin.concurrent.Volatile

/**
 * 插件源图标的像素边长上限 (px)。
 *
 * 依据: 自适应图标的图层规范尺寸是 108×108 dp (Android 文档 "Adaptive icons"), 最高密度档
 * xxxhdpi = 640 dpi = 4.0× (Android 文档 "Support different pixel densities"), 即最大 432px;
 * 传统启动图标 48dp 在 xxxhdpi 下 192px。512 覆盖全部合法启动图标并留余量, 更大的尺寸只可能
 * 来自损坏包或恶意包声明的图标。
 */
const val EXTENSION_ICON_MAX_PIXEL_SIZE = 512

/**
 * 插件图标条目的字节上限。
 *
 * 依据: [EXTENSION_ICON_MAX_PIXEL_SIZE] 见方的未压缩 RGBA 是 1MiB, 图像的编码头、逐行滤波字节
 * 与块结构再加少量, 故上限尺寸的图标不管用哪种格式编码都在 1MiB 出头; 取 2MiB 覆盖这些开销,
 * 同时挡住清单可以声明的任意大条目。
 */
const val EXTENSION_ICON_MAX_BYTES = 2 * 1024 * 1024

/**
 * 漫画/视频扩展宿主的平台面 (MangaExtensionManager 的平台注入点)。
 *
 * 管理器的装载编排/仓库/信任/注册表逻辑是平台无关的 (data jvmAndAndroidMain),
 * 与平台相关的部分收敛在本接口:
 * - 枚举装载: Android = PackageManager 扫已装包 + 私有扩展文件; 桌面 = 扫扩展
 *   目录内 .apk 文件 (JvmExtensionLoader, dex2jar 转换);
 * - 安装/卸载: Android 落 filesDir/exts + 广播重扫; 桌面落扩展目录 + 直接触发重扫;
 * - 广播监听: Android 在 [onInitialized] 注册安装事件广播, 桌面无广播为 no-op。
 *
 * 实现由宿主启动序列经 [MangaExtensionHostProviders.register] 注册
 * (app 端 AndroidMangaExtensionHost / 桌面端 DesktopMangaExtensionHost)。
 */
interface MangaExtensionHost {

    /**
     * 扫描全部扩展并装载。
     *
     * @param alreadyLoaded 已加载成功的扩展。apk 未变且仍通过全部校验时实现应
     *   原样复用 (源实例保持不变, 更新状态得以保留; 桌面端同时省去 dex2jar 重转)。
     */
    suspend fun loadExtensions(
        alreadyLoaded: Map<String, MangaExtension.Loaded>,
    ): List<MangaExtension.Installed>

    /** 下载并安装扩展, 进度经 [InstallStep] 流出; 收集方取消即中止。 */
    fun install(extension: MangaExtension.Available): Flow<InstallStep>

    /** 取消进行中的安装/更新。 */
    fun cancelInstall(pkgName: String)

    /**
     * 卸载扩展 (共享/私有形态由实现自行分派)。重扫由实现负责触发:
     * Android 私有扩展经既有广播链, 桌面删除文件后直接 [MangaExtensionManager.reloadExtensions]。
     */
    fun uninstall(extension: MangaExtension.Installed)

    /**
     * 读取插件包内的启动图标 (PNG 字节), 供插件源虚拟行行首图标缓存用。
     *
     * 包不存在、清单未声明图标、图标资源解析失败、图标尺寸超过 [EXTENSION_ICON_MAX_PIXEL_SIZE]
     * 一律返回 null —— 调用方回落书源名首字占位, 不抛异常。返回的字节恒为完整 PNG, 缓存层据此
     * 只按 PNG 判完好。读包与解码有实际 I/O, 调用方应在后台线程调用。
     */
    fun readExtensionIcon(pkgName: String): ByteArray?

    /**
     * 管理器首次初始化回调 (幂等语义由管理器保证, 只会触发一次)。
     * Android 在此注册安装事件广播接收器 (维持"扩展 UI 首入口才装广播"的既有时序);
     * 桌面无系统广播, no-op。
     */
    fun onInitialized() {}
}

object MangaExtensionHostProviders {
    @Volatile
    private var impl: MangaExtensionHost? = null

    /** 宿主启动早期注册一次 (app 端 MainActivity.initializePlatform / 桌面端 Main.kt)。 */
    fun register(impl: MangaExtensionHost) {
        this.impl = impl
    }

    fun getOrNull(): MangaExtensionHost? = impl
}
