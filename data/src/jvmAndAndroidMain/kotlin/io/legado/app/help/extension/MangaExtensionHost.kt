package io.legado.app.help.extension

import io.legado.app.help.extension.model.InstallStep
import io.legado.app.help.extension.model.MangaExtension
import kotlinx.coroutines.flow.Flow
import kotlin.concurrent.Volatile

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
