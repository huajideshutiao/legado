@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.help.service

import io.legado.app.constant.AppLog
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.config.registerIosProviders
import io.legado.app.model.CacheBookShared
import kotlin.concurrent.Volatile
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTask
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSError
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid

/**
 * iOS 端"尽力而为"后台缓存续跑 (对照 app 端 CacheBookService 前台服务)。
 *
 * iOS 没有常驻前台 Service, 退后台后进程会被挂起, [CacheBookShared] 的下载协程随之停摆。
 * 这里用系统给的两个时间窗把缓存推进下去, 两条通道都只走 [ServiceLaunchers] /
 * [CacheBookShared] 的既有入口, 不新增调度逻辑:
 *
 * 1. **退后台收尾**: [UIApplication.beginBackgroundTaskWithExpirationHandler] 申请约 30s,
 *    把在飞章节跑完; 到期 handler 里 [CacheBookShared.setWorkingState] false 停派新章节并
 *    [UIApplication.endBackgroundTask] (不结束会被系统强杀)。
 * 2. **BGProcessingTask**: 退后台时还有剩余任务就提交请求, 系统择机唤起后按快照重新入队
 *    继续下载; 到期没干完就再提交一次 (链式续约)。回前台时取消挂起的请求。
 *
 * 注册时机: [BGTaskScheduler.registerForTaskWithIdentifier] 必须在 didFinishLaunching 返回前
 * 调用, 由 iosApp/iOSApp.swift 的 AppDelegate 调 [registerIosBackgroundTasks] 完成。
 */
object IosBackgroundTasks {

    /**
     * BGProcessingTask 标识, 必须与 Info.plist `BGTaskSchedulerPermittedIdentifiers` 逐字一致,
     * 对不上系统会在 register 时直接抛 NSException。
     */
    const val CACHE_BOOK_TASK_ID: String = "shutiao.reader.cachebook"

    /** 待续缓存书籍的 bookUrl 快照 key (进程被系统回收后靠它重新入队)。 */
    private const val PENDING_KEY = "iosBackgroundCacheBookPending"

    private const val PENDING_SEP = "\n"

    /** 收尾窗口剩余不足这么多秒就主动收手, 留出 endBackgroundTask 的余量。 */
    private const val MIN_REMAINING_SECONDS = 5.0

    // UIKit 调用与状态位统一在主线程串行 (下载本身跑在 NativeServiceLauncher 自己的 Default scope)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    private var registered = false

    @Volatile
    private var processingTaskActive = false

    // 以下三个只在主线程读写 (通知观察者走 mainQueue, scope 是 Dispatchers.Main)
    private var finishTaskId: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid
    private var finishWatchJob: Job? = null
    private var processingTaskCompleted = false

    /**
     * 缓存下载是否在推进 (供 `IosReadBookPlatform.isCacheBookServiceRun`)。
     *
     * iOS 无 CacheBookService, 用调度队列运行态 + 后台任务在飞标记等价 (对照 desktop
     * `DesktopCacheBook.isRun`)。
     */
    val isCacheBookRunning: Boolean
        get() = CacheBookShared.isRun || processingTaskActive

    /** 宿主 didFinishLaunching 内调用一次 (见 [registerIosBackgroundTasks])。 */
    fun register() {
        if (registered) return
        registered = true
        BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(
            CACHE_BOOK_TASK_ID,
            usingQueue = null,
        ) { task -> onProcessingTaskLaunch(task) }
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(
            UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ -> onEnterBackground() }
        center.addObserverForName(
            UIApplicationWillEnterForegroundNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ -> onEnterForeground() }
    }

    // region 通道 1: 退后台收尾

    private fun onEnterBackground() {
        if (!CacheBookShared.isRun) return
        savePending()
        beginFinishWindow()
        submitProcessingRequest()
    }

    private fun beginFinishWindow() {
        if (finishTaskId != UIBackgroundTaskInvalid) return
        finishTaskId = UIApplication.sharedApplication().beginBackgroundTaskWithExpirationHandler {
            CacheBookShared.setWorkingState(false)
            endFinishWindow()
        }
        finishWatchJob?.cancel()
        finishWatchJob = scope.launch {
            val app = UIApplication.sharedApplication()
            while (isActive && CacheBookShared.isRun &&
                app.backgroundTimeRemaining > MIN_REMAINING_SECONDS
            ) {
                delay(1000)
            }
            CacheBookShared.setWorkingState(false)
            endFinishWindow()
        }
    }

    private fun endFinishWindow() {
        val id = finishTaskId
        if (id == UIBackgroundTaskInvalid) return
        finishTaskId = UIBackgroundTaskInvalid
        UIApplication.sharedApplication().endBackgroundTask(id)
    }

    private fun onEnterForeground() {
        finishWatchJob?.cancel()
        finishWatchJob = null
        endFinishWindow()
        // 前台由用户直接驱动, 挂起的后台请求没意义
        BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(CACHE_BOOK_TASK_ID)
        clearPending()
        CacheBookShared.setWorkingState(true)
    }

    // endregion

    // region 通道 2: BGProcessingTask

    private fun submitProcessingRequest() {
        val request = BGProcessingTaskRequest(CACHE_BOOK_TASK_ID)
        request.requiresNetworkConnectivity = true
        request.requiresExternalPower = false
        // 不设 earliestBeginDate: 缓存要的就是尽早续上, 具体时机本来就由系统按用量/电量裁决
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            val ok = BGTaskScheduler.sharedScheduler.submitTaskRequest(request, error.ptr)
            if (!ok) {
                AppLog.put(
                    "提交后台任务失败: ${error.value?.localizedDescription}",
                    tag = "IosBackgroundTasks",
                )
            }
        }
    }

    private fun onProcessingTaskLaunch(task: BGTask?) {
        if (task == null) return
        processingTaskActive = true
        processingTaskCompleted = false
        var work: Job? = null
        task.expirationHandler = {
            // 系统随时可能掐断: 先落快照再停派新章节, 已下好的正文早已落盘
            savePending()
            CacheBookShared.setWorkingState(false)
            work?.cancel()
            submitProcessingRequest()
            completeProcessingTask(task)
        }
        work = scope.launch {
            val finished = runCatching { resumePending() }.getOrDefault(false)
            if (!finished) submitProcessingRequest()
            completeProcessingTask(task)
        }
    }

    /** expirationHandler 与正常收尾都会走到这里, 重复调 setTaskCompleted 会崩, 用标记挡一次。 */
    private fun completeProcessingTask(task: BGTask) {
        if (processingTaskCompleted) return
        processingTaskCompleted = true
        processingTaskActive = false
        task.setTaskCompletedWithSuccess(true)
    }

    /**
     * 按快照恢复缓存调度并等到队列排空。
     *
     * 冷启动 (进程被回收后由系统唤起) 时 [CacheBookShared.cacheBookMap] 是空的, 只能按 bookUrl
     * 整本重新入队; 已缓存章节在 `CacheBookModelShared.download` 内走 hasContent 快路径跳过。
     *
     * @return true 表示队列已排空
     */
    private suspend fun resumePending(): Boolean {
        val pending = loadPending()
        if (pending.isEmpty() && !CacheBookShared.isRun) return true
        ensureProviders()
        CacheBookShared.setWorkingState(true)
        if (CacheBookShared.cacheBookMap.isEmpty()) {
            val launcher = ServiceLaunchers.get()
            // end<0 = 下载到最后一章 (NativeServiceLauncher.startCacheBookService 内 clamp)
            pending.forEach { launcher.startCacheBookService(it, 0, -1) }
            // 入队在 launcher 自己的协程里做, 等它把 model 放进 cacheBookMap 再判运行态
            delay(2000)
        }
        while (currentCoroutineContext().isActive && CacheBookShared.isRun) {
            delay(1000)
        }
        val finished = !CacheBookShared.isRun
        if (finished) clearPending()
        return finished
    }

    /**
     * BG 唤起时 Compose 场景可能压根没创建 (MainViewController 未跑), provider 还没注册,
     * 这里补一次 (各 provider 的 register 都是覆盖式, 与 MainViewController 重复调用等价)。
     */
    private fun ensureProviders() {
        if (runCatching { ServiceLaunchers.get() }.isFailure) {
            runCatching { registerIosProviders() }
        }
    }

    // endregion

    // region 待续任务快照

    private fun savePending() {
        val urls = CacheBookShared.cacheBookMap.keys.toList()
        runCatching {
            PreferenceProviders.get().putString(PENDING_KEY, urls.joinToString(PENDING_SEP))
        }
    }

    private fun loadPending(): List<String> = runCatching {
        PreferenceProviders.get().getString(PENDING_KEY)
            .split(PENDING_SEP)
            .filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    private fun clearPending() {
        runCatching { PreferenceProviders.get().remove(PENDING_KEY) }
    }

    // endregion
}

/**
 * Swift 宿主入口: 必须在 `application(_:didFinishLaunchingWithOptions:)` 返回前调用
 * (BGTaskScheduler 的注册时机硬约束), 见 iosApp/iOSApp.swift 的 AppDelegate。
 */
fun registerIosBackgroundTasks() {
    IosBackgroundTasks.register()
}
