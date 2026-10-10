@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.help.notification

import io.legado.app.constant.EventBus
import io.legado.app.constant.NotificationId
import io.legado.app.help.log.iosConsoleLog
import io.legado.app.ui.root.AppForegroundState
import io.legado.app.utils.postEvent
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import platform.Foundation.NSError
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter

/**
 * 可见性订阅作用域 (进程级一次, 与通知 provider 同寿)。
 */
private val visibilityScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * [NotificationProgress] 的 iOS 真实实现 (KP4)。
 *
 * # 当前实现 (UNUserNotificationCenter 真实本地通知)
 * 用 [UNUserNotificationCenter] 发送本地通知显示进度, 替代原 NSLog 降级。
 * 注册时请求通知权限 (alert+sound), showProgress 用调用方传入的通知 id 换算出的 identifier
 * ([IosNotificationIdentifiers.of]) 覆盖式发送 (每次先 remove 再 add, 模拟"更新同一条通知"行为,
 * 与 Android `NotificationManager.notify(id, ...)` 一致)。
 *
 * # 设计要点
 * - 权限请求: [registerIosNotificationProgress] 中异步请求 alert+sound 权限 (不阻塞, 首次弹系统权限对话框)
 * - 通知 ID: 由调用方传入 ([io.legado.app.constant.NotificationId] 常量) 经 [IosNotificationIdentifiers.of]
 *   换算 (与 IosNotificationService 同一换算), 同一 id 复用同一条通知 (覆盖式更新, 与 Android 通知 ID 一致),
 *   不同 id 各占一条互不覆盖
 * - 取消动作: 分类按通知 id 取 ([IosNotificationCategories.of]), 通知挂本节通知对应的分类
 *   (如 UpdateBookService → UpdateBook 分类, 带一个"取消"按钮, 对照 app 端该通知上的
 *   IntentAction.stop 动作, 处理见 [cancelIosUpdateBookRefresh]); 无取消动作的通知 id 不挂分类
 * - 可见性: 应用可见时不呈现通知、不可见时按最近一次进度恢复 (对照 app 端
 *   MainActivity.onStart/onStop → MainViewModel.updateUpdateNotification); 可见性取共享层唯一真源
 *   [AppForegroundState.isForeground]
 * - showProgress: 创建 [UNMutableNotificationContent] (title + body=进度文本 + 分类) + [UNNotificationRequest]
 *   (trigger=null 立即触发), add 到 center; 失败退化 NSLog
 * - cancel: removeDeliveredNotificationsWithIdentifiers (移除已展示的通知) +
 *   removePendingNotificationRequestsWithIdentifiers (移除待发送的, 防止 trigger 延迟)
 * - 进度文本拼接: "content (progress/max)" 或 "content" (max<=0 时), 与 desktop 端
 *   [io.legado.app.help.notification.DesktopNotificationProgress] 一致
 * - 失败兜底: 任意步骤异常 (权限未授权 / 通知 center 不可用) 退化为 NSLog, 保留原日志降级行为
 *
 * # 注意
 * - iOS 本地通知需用户授权 (首次 showProgress 前 registerIosNotificationProgress 已请求权限);
 *   未授权时 addNotificationRequest 静默失败 (iOS 不崩, 通知不显示), 走 NSLog 兜底
 * - trigger=null 表示立即触发 (非定时); 用 UNTimeIntervalNotificationTrigger 会延迟, 不适合进度更新
 * - 通知中心在模拟器上行为有限 (不显示 banner, 但可见于通知中心列表), 真机正常
 *
 * # 后续 (KP4+)
 * - 接入进度条通知: iOS 不支持 Android setProgress 的横条, 仅能文本 "(progress/max)";
 *   后续可用 UNNotificationContentInterruptionLevel + Live Activity (iOS 16+) 实现动态进度, 复杂度高
 * - 通知分组: 多本书缓存并发时, 可按 bookUrl 分组通知 (当前 P0 单条复用)
 *
 * 模式参考 `registerAndroidMediaNotificationProvider`;
 * 降级策略对齐 desktop 端 [io.legado.app.help.notification.DesktopNotificationProgress] 无 SystemTray 时退化为 println。
 *
 * # macOS 编译验证 (Windows 无法编译 iOS target)
 * ```
 * ./gradlew :core:compileKotlinIosArm64
 * ```
 */
class IosNotificationProgress : NotificationProgress {

    /** 状态锁: 进度事件在引擎回调线程上到达, 可见性变化在订阅协程线程上到达。 */
    private val lock = SynchronizedObject()

    /** 应用是否可见 (对照原版 MainViewModel.isActivityVisible: 可见时不呈现进度通知)。 */
    private var appVisible = true

    /** 各通知 id 的最近一次进度: 可见性变化时据它恢复通知
     * (对照原版按当前计数重发同一条通知)。 */
    private val progressById = mutableMapOf<Int, ProgressSnapshot>()

    override fun showProgress(id: Int, title: String, content: String, progress: Int, max: Int) {
        val snapshot = ProgressSnapshot(id, title, content, progress, max)
        val visible = synchronized(lock) {
            progressById[id] = snapshot
            appVisible
        }
        if (visible) {
            // 可见时不呈现 (对照原版 updateUpdateNotification 的 isActivityVisible 分支: 撤掉通知)
            cancelIosProgressNotification(id)
            return
        }
        publish(snapshot)
    }

    override fun cancel(id: Int) {
        // 任务结束: 清掉留存进度, 之后可见性变化不再恢复通知 (对照原版无任务时不 startService)
        synchronized(lock) { progressById.remove(id) }
        cancelIosProgressNotification(id)
        iosConsoleLog("[ios-notification] cancel id=$id")
    }

    /**
     * 应用可见性变化 (对照原版 MainActivity.onStart/onStop → updateUpdateNotification):
     * 可见→撤掉全部在跟的进度通知、不可见→按各自最近一次进度恢复。
     * 订阅见 [registerIosNotificationProgress]。
     */
    internal fun setAppVisible(visible: Boolean) {
        val progress = synchronized(lock) {
            appVisible = visible
            progressById.values.toList()
        }
        if (visible) {
            progress.forEach { cancelIosProgressNotification(it.id) }
            return
        }
        progress.forEach { publish(it) }
    }

    /** 发送/覆盖本条通知 (失败退化 NSLog, 保留原日志降级行为)。 */
    private fun publish(snapshot: ProgressSnapshot) {
        // 拼接进度文本: "content (progress/max)" 或 "content" (与 desktop 端 DesktopNotificationProgress 一致)
        val progressText = if (snapshot.max > 0) {
            "${snapshot.content} (${snapshot.progress}/${snapshot.max})"
        } else {
            snapshot.content
        }
        // 真实通知: runCatching 包裹, 失败退化 NSLog (保留原日志降级行为)
        runCatching {
            val center = UNUserNotificationCenter.currentNotificationCenter()
            val contentObj = UNMutableNotificationContent().apply {
                setTitle(snapshot.title)
                setBody(progressText)
                // 分类提供"取消"按钮 (须先经 IosNotificationPublisher.installCategories 注册)
                IosNotificationCategories.of(snapshot.id)?.let { setCategoryIdentifier(it) }
            }
            // trigger=null 立即触发 (非定时); identifier 按通知 id 换算, 同一条通知覆盖式更新
            val identifier = IosNotificationIdentifiers.of(snapshot.id)
            val request = UNNotificationRequest.requestWithIdentifier(
                identifier = identifier,
                content = contentObj,
                trigger = null,
            )
            // 先移除已发送的同 id 通知 (避免通知中心列表堆积, 模拟"更新同一条")
            center.removeDeliveredNotificationsWithIdentifiers(listOf(identifier))
            // addNotificationRequest 尾部 completionHandler 可 trailing lambda; error 非 nil 表示添加失败
            center.addNotificationRequest(request) { error ->
                if (error != null) {
                    iosConsoleLog("[ios-notification] add request error: ${error.localizedDescription}")
                }
            }
        }.onFailure {
            // 退化: NSLog 输出系统日志
            iosConsoleLog("[ios-notification] ${snapshot.title} | $progressText")
        }
    }

    /** 最近一次进度的快照 (可见性变化时重发用)。 */
    private data class ProgressSnapshot(
        val id: Int,
        val title: String,
        val content: String,
        val progress: Int,
        val max: Int,
    )

    companion object {
        /**
         * 撤掉某标识的进度通知: 移除已展示的通知 + 待发送的请求 (防止 trigger 延迟)。
         *
         * 不依赖 [NotificationProgresses] 注册: 通知上的"取消"动作可能落在"应用被杀后由系统
         * 在后台拉起"的路径上, 该时机 provider 未必已注册。
         */
        fun cancelIosProgressNotification(id: Int) {
            val identifier = IosNotificationIdentifiers.of(id)
            runCatching {
                val center = UNUserNotificationCenter.currentNotificationCenter()
                center.removeDeliveredNotificationsWithIdentifiers(listOf(identifier))
                center.removePendingNotificationRequestsWithIdentifiers(listOf(identifier))
            }.onFailure {
                iosConsoleLog("[ios-notification] cancel failed (fallback)")
            }
        }
    }
}

/**
 * 更新目录进度通知上的"取消"动作 (对照 app 端 `UpdateBookService` 的 IntentAction.stop 分支:
 * `postEvent(EventBus.STOP_UP_BOOK, "")` + `stopSelf()`, 服务销毁时撤掉通知)。
 *
 * 取消任务复用共享层既有链路: [EventBus.STOP_UP_BOOK] 事件由 `UpdateBookShared` 监听并取消
 * upToc / 强制刷新 / 预下载任务 (对照 app 端 MainViewModel 同名监听), 本函数不另写取消逻辑。
 */
fun cancelIosUpdateBookRefresh() {
    postEvent(EventBus.STOP_UP_BOOK, "")
    IosNotificationProgress.cancelIosProgressNotification(NotificationId.UpdateBookService)
}

/**
 * iOS 宿主启动早期注册 [NotificationProgress] 的真实实现 + 请求通知权限。
 *
 * 调用时机: iOS app 启动早期, 在任何 commonMain 代码调用 `NotificationProgresses.get()` 之前。
 *
 * 注册时异步请求 alert+sound 通知权限 (不阻塞, 首次弹系统权限对话框);
 * 用户拒绝后 showProgress 走 NSLog 降级 (不崩)。
 *
 * 模式参考 `registerAndroidMediaNotificationProvider`。
 */
fun registerIosNotificationProgress() {
    // 异步请求通知权限 (alert + sound), 不阻塞注册流程
    // 注: 若 NS_OPTIONS 位运算 `or` 编译报错, 改单选 UNAuthorizationOptionAlert
    runCatching {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        center.requestAuthorizationWithOptions(
            UNAuthorizationOptionAlert or UNAuthorizationOptionSound,
        ) { granted, error ->
            if (error != null) {
                iosConsoleLog("[ios-notification] auth error: ${error.localizedDescription}")
            } else if (!granted) {
                iosConsoleLog("[ios-notification] user denied notification authorization")
            }
        }
    }.onFailure {
        iosConsoleLog("[ios-notification] requestAuthorization failed: $it")
    }
    val impl = IosNotificationProgress()
    NotificationProgresses.register(impl)
    // 可见性变化立刻刷新通知 (对照原版 MainActivity.onStart/onStop → updateUpdateNotification):
    // 可见性取共享层唯一真源 [AppForegroundState.isForeground] (iOS 由 IosBackgroundTasks 挂的
    // UIApplication 前后台通知置位)。StateFlow 首值即当前态 (启动时为可见), 启动那一次会撤掉
    // 本进程已在跟的进度通知 (上个进程残留的通知由各业务在启动注册时按自家 id 收掉)。
    AppForegroundState.isForeground
        .onEach { visible -> impl.setAppVisible(visible) }
        .launchIn(visibilityScope)
}
