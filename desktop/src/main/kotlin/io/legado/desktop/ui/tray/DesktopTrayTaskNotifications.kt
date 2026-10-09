package io.legado.desktop.ui.tray

import io.legado.app.constant.EventBus
import io.legado.app.help.service.ServiceLauncher
import io.legado.app.help.service.ServiceLaunchers
import io.legado.app.model.CacheBookShared
import io.legado.app.ui.compose.platform.syncGetString
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.utils.FlowBus
import io.legado.desktop.help.source.DesktopCheckSource
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * 缓存书籍 / 书源校验两条后台任务的托盘绑定 (对照 app 端 `CacheBookService` /
 * `CheckSourceService` 的常驻通知)。
 *
 * 桌面端无 Android Service, 两条任务的"服务存活"分别由既有信号界定, 不新造调度:
 * - **缓存书籍**: 与 iOS 端同一形制, 把已注册的 [ServiceLauncher] 包一层 (见
 *   [CacheBookTrayLauncher]) —— 只有经启动器启动缓存的路径才进通知
 *   (阅读页"离线缓存"直接走 [CacheBookShared], 原版那条路径也起服务, 本仓现状各端一致不起);
 *   进度取共享层既有事件 [EventBus.UP_DOWNLOAD] / [EventBus.UP_DOWNLOAD_STATE]
 *   (原版由服务每秒轮询取同一批 `CacheBook` 计数, 故文案/口径一致)
 * - **书源校验**: 进度事件 ([EventBus.CHECK_SOURCE] / [EventBus.CHECK_SOURCE_DONE]) 即任务边界
 *   (原版通知随服务生死, 桌面端校验跑在进程级协程域, 事件是该域的唯一对外信号)
 *
 * 取消动作复用既有取消链路: 缓存走 [ServiceLaunchers.get] 的 `stopCacheBookService`
 * (与书架管理页"停止下载"同一调用), 校验走 [PlatformCapabilityProviders] 的 `cancelCheckSource`
 * (与书源管理页"取消"同一调用), 本文件不另写取消逻辑。
 */
object DesktopTrayTaskNotifications {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var installed = false

    /** 缓存服务态 (对照 app 端 `CacheBookService` 存活): 只有经启动器启动缓存的路径才置位。 */
    @Volatile
    private var cacheServiceStarted = false

    /**
     * 本次服务期内队列曾非空。
     *
     * 启动到委托侧入队之间 (以及上一轮排空后的收尾事件) 队列本就是空的, 那一段不能当
     * "已完成" 收尾, 否则新一轮刚启动就会被收掉。
     */
    @Volatile
    private var cacheQueueSeen = false

    /** 托盘安装后调用一次 (见 desktop `Main.kt`); 幂等。 */
    fun install() {
        if (installed) return
        installed = true
        // 缓存服务启停镜像: 必须在任何 startCacheBookService 之前, 故包在已注册启动器之外
        ServiceLaunchers.register(
            CacheBookTrayLauncher(
                delegate = ServiceLaunchers.get(),
                onServiceStarted = { onCacheServiceStarted() },
                onServiceStopped = { onCacheServiceStopped() },
            )
        )
        FlowBus.with(EventBus.UP_DOWNLOAD).onEach { refreshCacheBook() }.launchIn(scope)
        FlowBus.with(EventBus.UP_DOWNLOAD_STATE).onEach { refreshCacheBook() }.launchIn(scope)
        FlowBus.with(EventBus.CHECK_SOURCE).onEach { upCheckSource() }.launchIn(scope)
        FlowBus.with(EventBus.CHECK_SOURCE_DONE).onEach { downCheckSource() }.launchIn(scope)
    }

    /** 缓存服务启动 (对照 app 端 `CacheBookService.onCreate` → isRun=true + startForeground)。 */
    private fun onCacheServiceStarted() {
        cacheServiceStarted = true
        cacheQueueSeen = false
        DesktopMediaTray.cacheBook = cacheBookBinding()
    }

    /** 缓存服务停止 (对照 app 端 `CacheBookService.onDestroy`)。 */
    private fun onCacheServiceStopped() {
        cacheServiceStarted = false
        cacheQueueSeen = false
        DesktopMediaTray.cacheBook = null
    }

    /** 缓存进度变化: 跟进度, 队列排空则收尾 (对照 app 端 startProcessJob 跑完后 stopSelf)。 */
    private fun refreshCacheBook() {
        if (!cacheServiceStarted) return
        if (CacheBookShared.isRun) {
            cacheQueueSeen = true
            DesktopMediaTray.cacheBook = cacheBookBinding()
            return
        }
        if (!cacheQueueSeen) return
        onCacheServiceStopped()
    }

    /**
     * 书源校验进度变化: 首个进度事件建立绑定并逐次重写。
     *
     * 事件本身即"校验在跑"的判据 (原版通知在服务 onCreate 时就上屏; 桌面端校验起点就是这个
     * 初始帧事件, 见 [DesktopCheckSource.start])。
     */
    private fun upCheckSource() {
        val progress = DesktopCheckSource.lastProgress ?: return
        val (name, count, total) = progress
        DesktopMediaTray.checkSource = ProgressTrayBinding(
            title = syncGetString("check_book_source"),
            // 正文取 app 端同 key 文案 (与 app 端通知正文同源)
            content = syncGetString("progress_show", name, count, total).trim(),
            cancel = { PlatformCapabilityProviders.get().cancelCheckSource() },
            // 对照 app 端校验通知的 contentIntent (BookSourceActivity → 书源管理页)
            route = AppRoute.BookSourceManage,
        )
    }

    private fun downCheckSource() {
        DesktopMediaTray.checkSource = null
    }

    /**
     * 缓存书籍绑定: 标题/正文口径与 app 端通知一致 (标题 `offline_cache`; 正文
     * `CacheBook.downloadSummary` + 进度比, app 端把进度比放进 shortText; 队列尚未开始时文案为
     * `service_starting`, 与 app 端创建服务时的初始正文同源)。
     */
    private fun cacheBookBinding(): ProgressTrayBinding {
        val (done, total) = CacheBookShared.downloadProgress
        return ProgressTrayBinding(
            title = syncGetString("offline_cache"),
            content = if (CacheBookShared.isRun) {
                "${CacheBookShared.downloadSummary} ($done/$total)"
            } else {
                syncGetString("service_starting")
            },
            // 既有取消链路: 与书架管理页"停止下载"同一调用
            cancel = { ServiceLaunchers.get().stopCacheBookService() },
            // 原版缓存通知的 setContentIntent 是注释掉的 → 左键无跳转
            route = null,
        )
    }
}

/**
 * 缓存服务启停的托盘镜像 (对照 app 端通知随 `CacheBookService` 生死)。
 *
 * 为什么包一层而不是改 `DesktopServiceLauncher`: 启动器在 `:core` 的 jvmMain, 看不到托盘;
 * 且"哪条路径进的缓存"决定要不要上通知 (与 iOS 端 `IosCacheBookServiceLauncher` 同一处理)。
 */
private class CacheBookTrayLauncher(
    private val delegate: ServiceLauncher,
    private val onServiceStarted: () -> Unit,
    private val onServiceStopped: () -> Unit,
) : ServiceLauncher {

    override fun startCacheBookService(bookUrl: String, start: Int, end: Int) {
        // 先置服务态再入队: 入队是委托内部的协程, 其事件可能早于本次调用返回
        onServiceStarted()
        delegate.startCacheBookService(bookUrl, start, end)
    }

    override fun stopCacheBookService() {
        delegate.stopCacheBookService()
        onServiceStopped()
    }

    override fun removeCacheBookService(bookUrl: String) {
        delegate.removeCacheBookService(bookUrl)
        // 单本移除: 队列还有活则通知继续跟进度 (对照 app 端 removeDownload 的"队列空才 stopSelf")
        if (!CacheBookShared.isRun) onServiceStopped()
    }

    override fun startDownloadService(url: String, fileName: String) {
        delegate.startDownloadService(url, fileName)
    }
}
