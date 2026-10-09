package io.legado.app.help.service

import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.model.CacheBookShared
import io.legado.app.napi.NotificationButton
import io.legado.app.napi.OhosNativeBridge
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
 * 鸿蒙端缓存书籍进度通知 (对照 app 端 `CacheBookService` 的通知)。
 *
 * app 端通知随 Service 生死: `onCreate` 起每秒刷 `CacheBook.downloadSummary` + 进度比,
 * `onDestroy` 收尾成"缓存完成"; 取消动作 = `IntentAction.stop` → `stopSelf()`。
 * 鸿蒙无 Service, 通知态由两个既有信号驱动, 不新造调度:
 * - **服务态**: 缓存服务启停入口 ([ServiceLauncher], 见 [OhosCacheBookServiceLauncher]) ——
 *   与 app 端一致, 起缓存服务的路径才进通知 (书架管理页、阅读页"离线缓存"都经该启动器;
 *   目录更新后的自动预下载与阅读中的前后章预下载在原版也不启动该 Service, 不进通知)
 * - **进度**: 共享层既有事件 [EventBus.UP_DOWNLOAD] / [EventBus.UP_DOWNLOAD_STATE]
 *   (原版由 Service 每秒轮询取同一批 [CacheBookShared] 计数, 故文案/口径一致)
 *
 * 通知 id / 文案 / 取消链路全部复用既有的: [NotificationId.CacheBookService]、
 * `offline_cache` 字符串、[CacheBookShared] 的 `downloadSummary` / `downloadProgress`,
 * 取消走 [CacheBookShared.close] (与 `NativeServiceLauncher.stopCacheBookService` 同一调用)。
 */
internal object OhosCacheBookNotifications {

    /**
     * 缓存书籍的长时任务类型 (module.json5 的 backgroundModes 项, 必须与之逐字一致)。
     *
     * 选 `dataTransfer` 的依据 (长时任务文档"使用场景"表): "非托管形式的上传、下载, 如在浏览器
     * 后台上传或下载数据" —— 缓存就是非托管(不交 @ohos.request)的持续下载, 且进度确定
     * ([CacheBookShared.downloadProgress] 的 完成/总数)。该类型的额外硬要求: 应用必须持续更新
     * 进度, 且更新必须是实况窗 —— 文案与进度写入系统代理创建的那条实况窗通知的
     * `downloadTemplate`, 并按系统时限的节奏心跳式续推 (见 ContinuousTaskArbiter;
     * 首次发布后 10 分钟无更新即被删通知停任务, 之后每次更新把时限重置为 15 分钟,
     * 进度 100 时为 30 分钟)。
     *
     * ⚠ 保活随时可能被系统挂起或取消 (低速、一致性管控等), 且多数原因不回传应用: 系统只回传
     * "移除通知"与"冻结"两类原因, 开源侧只有 SYSTEM_CANCEL_DATA_TRANSFER_LOW_SPEED /
     * SYSTEM_SUSPEND_DATA_TRANSFER_LOW_SPEED 两个原因码、没有任何阈值实现 (触发方是系统调度
     * 服务)。丢的只是保活, 下载本身继续跑。
     */
    const val CONTINUOUS_TASK_MODE = "dataTransfer"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 状态机锁: 进度事件在调度线程上到达, 服务启停在调用线程上到达, 两者会并发。 */
    private val lock = SynchronizedObject()

    @Volatile
    private var observing = false

    /** 服务态 (对照 app 端 CacheBookService 存活): 非服务态下的事件不进通知。 */
    private var serviceRunning = false

    /** 进行中通知当前在通知栏上 (对照 app 端"进行中通知已发出")。 */
    private var progressShown = false

    /** 订阅缓存进度事件 (宿主启动早期一次, 见 [registerOhosCacheBookServiceNotifications])。 */
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
     * 调用方须先停下载 (见 [OhosCacheBookServiceLauncher.stopCacheBookService]);
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
     * 不另写取消逻辑。
     */
    fun cancelDownloads() {
        // 先收尾通知态 (与进度刷新/服务启停共用同一把锁, 避免并发下残留一条已失效的进行中通知)
        val finishPublished = onServiceStopped()
        CacheBookShared.close()
        postEvent(EventBus.UP_DOWNLOAD, "")
        if (!finishPublished) {
            // 本进程没有进行中通知 (进程被回收后由系统拉起通知的路径): 上面不会发出完成态,
            // 撤掉那条已无下载任务可停的通知
            OhosNativeBridge.cancelNotification(NotificationId.CacheBookService)
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
        OhosNativeBridge.showNotification(
            id = NotificationId.CacheBookService,
            title = syncGetString("offline_cache"),
            content = content,
            progress = done,
            max = total,
            // 常驻 + 只提醒一次 + 取消按钮, 对照 app 端通知的三个 setter
            ongoing = true,
            alertOnce = true,
            buttons = listOf(
                NotificationButton(text = syncGetString("cancel"), action = IntentAction.stop)
            ),
            // 长时任务: 保活这条下载 (音频/朗读在播放时由宿主仲裁为不申请, 只发本通知)
            continuousTask = CONTINUOUS_TASK_MODE,
        )
    }

    private fun publishFinish() {
        // 对照 app 端 upCacheBookFinishNotification: 同 id 变"缓存完成", 无取消动作、自动消失
        OhosNativeBridge.showNotification(
            id = NotificationId.CacheBookService,
            title = syncGetString("offline_cache"),
            content = CACHE_FINISH_TEXT,
            progress = 0,
            max = 0,
        )
    }

    /** app 端 `upCacheBookFinishNotification` 里写死的正文。 */
    private const val CACHE_FINISH_TEXT = "缓存完成"
}

/**
 * 缓存服务启停入口的通知镜像 (对照 app 端通知随 `CacheBookService` 生死)。
 *
 * 为什么包一层而不是改 [NativeServiceLauncher]: nativeMain 的启动器由 iOS/鸿蒙共用,
 * 通知各端一套, 故差异留在各端源集。
 */
private class OhosCacheBookServiceLauncher(
    private val delegate: ServiceLauncher,
) : ServiceLauncher {

    override fun startCacheBookService(bookUrl: String, start: Int, end: Int) {
        // 先置服务态再入队: 入队是委托内部的协程, 其事件可能早于本次调用返回
        OhosCacheBookNotifications.onServiceStarted()
        delegate.startCacheBookService(bookUrl, start, end)
    }

    override fun stopCacheBookService() {
        delegate.stopCacheBookService()
        OhosCacheBookNotifications.onServiceStopped()
    }

    override fun removeCacheBookService(bookUrl: String) {
        delegate.removeCacheBookService(bookUrl)
        OhosCacheBookNotifications.onServiceRemoved()
    }

    override fun startDownloadService(url: String, fileName: String) {
        delegate.startDownloadService(url, fileName)
    }
}

/**
 * 鸿蒙宿主启动早期调用 (见 `io.legado.app.help.config.registerOhosProviders`)。
 *
 * 须在 `registerOhosServiceLauncher()` 之后: 本函数把已注册的启动器包一层缓存通知镜像
 * (nativeMain 的启动器不能写鸿蒙通知, 见 [OhosCacheBookServiceLauncher])。
 */
fun registerOhosCacheBookServiceNotifications() {
    // 启动即收掉上一条"进行中"通知: 鸿蒙无 Service, 进程被回收不带走通知
    // (被杀前的进行中通知会留在通知栏, 且其"取消"已无下载任务可停)
    OhosNativeBridge.cancelNotification(NotificationId.CacheBookService)
    OhosCacheBookNotifications.observe()
    ServiceLaunchers.register(OhosCacheBookServiceLauncher(ServiceLaunchers.get()))
}
