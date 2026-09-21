package io.legado.app.help.service

import kotlin.concurrent.Volatile

/**
 * Android Service 启动抽象 (shared commonMain)。
 *
 * # 背景
 * 安卓端 `CacheBookService` / `UpdateBookService` / `DownloadService` 是 Android
 * Service, 依赖 `android.content.Context.startService`。桌面 JVM 端没有 Service
 * 概念, 用 `CoroutineScope` 直接调度; iOS/鸿蒙共用 nativeMain `NativeServiceLauncher`
 * (同样走协程调度)。
 *
 * # 设计
 * - 接口方法对应 app 端三个 Service 的启动/停止入口
 * - 桌面端实现持有 `CoroutineScope`, 直接 launch 协程执行等价任务
 * - 各端注册 actual 实现, shared 内通过 [ServiceLaunchers.get] 获取
 *
 * 模式参考 [io.legado.app.help.config.AppConfigProviders]。
 */
interface ServiceLauncher {

    /** 启动缓存书籍服务 (对应 app 端 `CacheBookService`, action=start)。 */
    fun startCacheBookService(bookUrl: String, start: Int, end: Int)

    /** 停止缓存书籍服务 (对应 app 端 `CacheBookService`, action=stop)。 */
    fun stopCacheBookService()

    /**
     * 移除单本书的缓存下载任务 (对应 app 端 `CacheBookService`, action=remove)。
     *
     * 与 [stopCacheBookService] 区别: stop 停止整个 Service (所有书),
     * remove 仅移除指定 bookUrl 的下载任务, 其他书继续。
     */
    fun removeCacheBookService(bookUrl: String)

    /** 启动更新书籍服务 (对应 app 端 `UpdateBookService`)。 */
    fun startUpdateBookService()

    /** 停止更新书籍服务。 */
    fun stopUpdateBookService()

    /**
     * 启动下载服务 (对应 app 端 `DownloadService`)。
     * 安卓实现走系统 `DownloadManager`, 桌面端用 [io.legado.app.help.file.FileDownloader] 写文件。
     */
    fun startDownloadService(url: String, fileName: String)
}

/**
 * [ServiceLauncher] 容器 (provider 注入模式)。
 *
 * 宿主启动早期注册一次 (App.onCreate / desktop main), shared 内通过 [get] 获取。
 * 未注册时调用 [get] 抛 [IllegalStateException]。
 *
 * 安卓端调用 `registerAndroidServiceLauncher(context)` (见 androidMain),
 * 桌面端调用 `registerDesktopServiceLauncher(scope)` (见 jvmMain)。
 */
object ServiceLaunchers {

    @Volatile
    private var impl: ServiceLauncher? = null

    /** 宿主启动早期注册一次 (任何 Service 启动调用之前)。 */
    fun register(impl: ServiceLauncher) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): ServiceLauncher =
        impl ?: error("ServiceLaunchers not registered; call registerAndroidServiceLauncher() or registerDesktopServiceLauncher() first")

    /** 仅测试场景: 清空注册 (生产代码勿调用)。 */
    fun reset() {
        impl = null
    }
}
