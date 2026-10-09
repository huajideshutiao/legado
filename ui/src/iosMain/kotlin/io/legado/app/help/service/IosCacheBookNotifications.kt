package io.legado.app.help.service

import io.legado.app.constant.EventBus
import io.legado.app.constant.NotificationId
import io.legado.app.model.CacheBookShared
import io.legado.app.ui.IosNotificationService
import io.legado.app.ui.compose.platform.syncGetString
import io.legado.app.utils.FlowBus
import io.legado.app.utils.postEvent
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * iOS 端缓存书籍进度通知 (对照 app 端 `CacheBookService` 的通知)。
 *
 * app 端通知随 Service 生死: `onCreate` 起每秒刷 `CacheBook.downloadSummary` + 进度比,
 * `onDestroy` 收尾成"缓存完成"; 取消动作 = `IntentAction.stop` → `stopSelf()`。
 * iOS 无 Service, 通知态改由两个既有信号驱动, 不新造调度:
 * - **服务态**: 缓存服务启停入口 ([ServiceLauncher], 见 [IosCacheBookServiceLauncher]) ——
 *   与 app 端一致, 只有"从书架管理页启动缓存"这条路径才进通知 (预下载/阅读页单章缓存
 *   在原版并不启动该 Service, 因此同样不进通知)
 * - **进度**: 共享层既有事件 [EventBus.UP_DOWNLOAD] / [EventBus.UP_DOWNLOAD_STATE]
 *   (原版由 Service 每秒轮询取同一批 `CacheBookShared` 计数, 故文案/口径一致)
 *
 * 通知 id / 文案 / 取消链路全部复用既有的: [NotificationId.CacheBookService]、
 * `offline_cache` 字符串、[CacheBookShared] 的 `downloadSummary` / `downloadProgress`,
 * 取消走 [CacheBookShared.close] (与 `NativeServiceLauncher.stopCacheBookService` 同一调用)。
 */
internal object IosCacheBookNotifications {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 状态机锁: 进度事件在调度线程上到达, 服务启停在主线程上到达, 两者会并发。 */
    private val lock = SynchronizedObject()

    @Volatile
    private var observing = false

    /** 服务态 (对照 app 端 CacheBookService 存活): 非服务态下的事件不进通知。 */
    private var serviceRunning = false

    /** 进行中通知当前在通知栏上 (对照 app 端"进行中通知已发出")。 */
    private var progressShown = false

    /** 订阅缓存进度事件 (宿主启动早期一次, 见 [registerIosCacheBookServiceNotifications])。 */
    fun observe() {
        if (observing) return
        observing = true
        FlowBus.with(EventBus.UP_DOWNLOAD).onEach { refresh() }.launchIn(scope)
        FlowBus.with(EventBus.UP_DOWNLOAD_STATE).onEach { refresh() }.launchIn(scope)
    }

    /** 缓存服务启动 (对照 app 端 `CacheBookService.onCreate`)。 */
    fun onServiceStarted() = synchronized(lock) {
        serviceRunning = true
    }

    /**
     * 缓存服务停止 (对照 app 端 `CacheBookService.onDestroy`: 进行中通知切"缓存完成")。
     *
     * 调用方须先停下载 (见 [IosCacheBookServiceLauncher.stopCacheBookService]);
     * 本函数只做通知收尾。
     *
     * @return true = 进行中通知已收尾成"缓存完成"; false = 本进程没有在展示的进行中通知
     */
    fun onServiceStopped(): Boolean = synchronized(lock) {
        serviceRunning = false
        if (!progressShown) return@synchronized false
        progressShown = false
        publishFinish()
        true
    }

    /**
     * 单本移除后的收尾 (对照 app 端 `CacheBookService.removeDownload`:
     * 仅当下载队列已空才等同服务停止, 否则通知继续跟进度)。
     */
    fun onServiceRemoved() {
        if (CacheBookShared.cacheBookMap.isEmpty()) onServiceStopped() else refresh()
    }

    /**
     * 通知上的"取消" (对照 app 端 `CacheBookService` 的 `IntentAction.stop`:
     * `stopSelf()` → `onDestroy` —— `CacheBook.close()` 停全部下载, 通知收尾成"缓存完成")。
     *
     * 停下载复用共享层取消原语 (与 `NativeServiceLauncher.stopCacheBookService` 同一调用),
     * 不另写取消逻辑; 不取 [ServiceLaunchers] 聚合: 该动作可能落在"应用被杀后由系统在后台拉起"
     * 的路径上, 此时聚合未必已注册 (与导出通知的取消动作同一处理)。
     */
    fun cancelDownloads() {
        // 先收尾通知态 (与进度刷新/服务启停共用同一把锁, 避免并发下残留一条已失效的进行中通知)
        val finishPublished = onServiceStopped()
        CacheBookShared.close()
        postEvent(EventBus.UP_DOWNLOAD, "")
        if (!finishPublished) {
            // 本进程没有进行中通知 (被杀后拉起的路径): 上面不会发出完成态, 撤掉那条失效的通知
            IosNotificationService.cancelNotification(NotificationId.CacheBookService)
        }
    }

    private fun refresh() {
        synchronized(lock) {
            if (!serviceRunning) return@synchronized
            if (CacheBookShared.isRun) {
                progressShown = true
                publishProgress()
                return@synchronized
            }
            // 队列已空 (app 端即 stopSelf → onDestroy): 同一条通知切完成态
            serviceRunning = false
            if (progressShown) {
                progressShown = false
                publishFinish()
            }
        }
    }

    private fun publishProgress() {
        // 正文与 app 端通知一致 (CacheBook.downloadSummary); 括号内为 app 端 shortText 同款 "完成/总数"
        val (done, total) = CacheBookShared.downloadProgress
        val content = CacheBookShared.downloadSummary + if (total > 0) " ($done/$total)" else ""
        IosNotificationService.notify(
            NotificationId.CacheBookService,
            syncGetString("offline_cache"),
            content,
        )
    }

    private fun publishFinish() {
        // 对照 app 端 upCacheBookFinishNotification: 同 id 变"缓存完成", 无取消动作
        IosNotificationService.notifyWithoutActions(
            NotificationId.CacheBookService,
            syncGetString("offline_cache"),
            CACHE_FINISH_TEXT,
        )
    }

    /** app 端 `upCacheBookFinishNotification` 里写死的正文。 */
    private const val CACHE_FINISH_TEXT = "缓存完成"
}

/**
 * 缓存服务启停入口的通知镜像 (对照 app 端通知随 `CacheBookService` 生死)。
 *
 * 为什么包一层而不是改 [NativeServiceLauncher]: nativeMain 的启动器由 iOS/鸿蒙共用,
 * 通知是 iOS 独有 (鸿蒙走 napi notificationManager, 另一套), 故差异留在 iosMain。
 */
private class IosCacheBookServiceLauncher(
    private val delegate: ServiceLauncher,
) : ServiceLauncher {

    override fun startCacheBookService(bookUrl: String, start: Int, end: Int) {
        // 先置服务态再入队: 入队是委托内部的协程, 其事件可能早于本次调用返回
        IosCacheBookNotifications.onServiceStarted()
        delegate.startCacheBookService(bookUrl, start, end)
    }

    override fun stopCacheBookService() {
        delegate.stopCacheBookService()
        IosCacheBookNotifications.onServiceStopped()
    }

    override fun removeCacheBookService(bookUrl: String) {
        delegate.removeCacheBookService(bookUrl)
        IosCacheBookNotifications.onServiceRemoved()
    }

    override fun startDownloadService(url: String, fileName: String) {
        delegate.startDownloadService(url, fileName)
    }
}

/**
 * iOS 宿主启动早期调用 (见 `io.legado.app.help.config.registerIosProviders`)。
 *
 * 须在 `registerIosServiceLauncher()` 之后: 本函数把已注册的启动器包一层缓存通知镜像
 * (nativeMain 的启动器不能写 iOS 通知, 见 [IosCacheBookServiceLauncher])。
 */
fun registerIosCacheBookServiceNotifications() {
    // 启动即收掉上一条"进行中"通知: iOS 无 Service, 进程死亡不会像 app 端前台服务那样
    // 带走它的通知 (被杀前的进行中通知会留在通知栏, 且其"取消"已无下载任务可停)
    IosNotificationService.cancelNotification(NotificationId.CacheBookService)
    IosCacheBookNotifications.observe()
    ServiceLaunchers.register(IosCacheBookServiceLauncher(ServiceLaunchers.get()))
}
