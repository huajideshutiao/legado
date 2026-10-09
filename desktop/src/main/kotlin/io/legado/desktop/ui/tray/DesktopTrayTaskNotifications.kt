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
 *   [CacheBookTrayLauncher]) —— 起缓存服务的路径才进通知 (书架管理页、阅读页"离线缓存"
 *   都经该启动器; 目录更新后的自动预下载与阅读中的前后章预下载不经它, 与 app 端一致不进通知);
 *   进度取共享层既有事件 [EventBus.UP_DOWNLOAD] / [EventBus.UP_DOWNLOAD_STATE]
 *   (原版由服务每秒轮询取同一批 `CacheBook` 计数, 故文案/口径一致),
 *   收尾判据是"调度 job 的起止事件到达时下载池已无在途任务" (对照 app 端 `CacheBookService.download()`
 *   里 `startProcessJob` 返回后 `stopSelf()` → `onDestroy`; 入队失败/无可下载项时池空, 即为此条)
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
     * 本次会话的调度真的接到过活 (对照 app 端 `CacheBookService.download()` 已进入 `startProcessJob`)。
     *
     * 启动到委托侧入队之间的"无在途任务"不能当收尾 (书模型刚建立的那一瞬同样是无在途),
     * 故进度事件只在本次调度接到过活之后才能据"无在途"收尾。
     */
    @Volatile
    private var cacheJobSeen = false

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
        FlowBus.with(EventBus.UP_DOWNLOAD_STATE).onEach { onCacheScheduleStateChanged() }.launchIn(scope)
        FlowBus.with(EventBus.CHECK_SOURCE).onEach { upCheckSource() }.launchIn(scope)
        FlowBus.with(EventBus.CHECK_SOURCE_DONE).onEach { downCheckSource() }.launchIn(scope)
    }

    /** 缓存服务启动 (对照 app 端 `CacheBookService.onCreate` → isRun=true + 前台通知上屏)。 */
    private fun onCacheServiceStarted() {
        cacheServiceStarted = true
        cacheJobSeen = false
        DesktopMediaTray.cacheBook = cacheBookBinding()
    }

    /** 缓存服务停止 (对照 app 端 `CacheBookService.onDestroy`)。 */
    private fun onCacheServiceStopped() {
        cacheServiceStarted = false
        cacheJobSeen = false
        DesktopMediaTray.cacheBook = null
    }

    /**
     * 缓存进度/池状态变化: 跟进度; 本次调度接到过活之后, 无在途任务即收尾。
     *
     * 与 [onCacheScheduleStateChanged] 各自覆盖对方拿不到事件的一侧: 调度一事未接到的会话
     * (入队失败/无可下载项) 没有进度事件, 只能由调度事件判; 池里留着别人建的模型而调度已跑完时
     * 只有进度事件会到。
     */
    private fun refreshCacheBook() {
        if (!cacheServiceStarted) return
        if (!CacheBookShared.isRun && cacheJobSeen) {
            onCacheServiceStopped()
            return
        }
        DesktopMediaTray.cacheBook = cacheBookBinding()
    }

    /**
     * 共享调度 job 起/止 (对照 app 端 `CacheBookService.download()`: `CacheBook.startProcessJob()`
     * 返回就 `stopSelf()` → `onDestroy`)。
     *
     * 判据取"无在途任务": 入队失败 (书不在库) 与无可下载项的书都会让该状态成立, 因此这两条失败
     * 路径也必然收尾; 有排队/在途任务时该事件只是进度刷新 (启动到委托侧入队之间不会出现本事件:
     * 委托是先入队、后跑调度)。
     *
     * 其它来源的调度事件 (上一轮会话的遗留、书架自动更新起过调度) 在极窄窗口内落到新一轮启动之后时,
     * 会把新一轮会话一并收掉 (与 iOS/鸿蒙镜像同一暴露面); 该窗口不会超过委托侧取书这一步, 且收尾只
     * 影响托盘呈现 —— 下载本身继续, 下一次经启动器起缓存会重新上屏。
     */
    private fun onCacheScheduleStateChanged() {
        if (!cacheServiceStarted) return
        if (!CacheBookShared.isRun) {
            onCacheServiceStopped()
            return
        }
        cacheJobSeen = true
        DesktopMediaTray.cacheBook = cacheBookBinding()
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
        // 单本移除: 下载池空了才等同服务停止 (对照 app 端 removeDownload 的"池空才 stopSelf");
        // 池里还有书时通知继续跟进度 (进度由 UP_DOWNLOAD 事件刷新)
        if (CacheBookShared.cacheBookMap.isEmpty()) onServiceStopped()
    }

    override fun startDownloadService(url: String, fileName: String) {
        delegate.startDownloadService(url, fileName)
    }
}
