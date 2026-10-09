package io.legado.app.help.notification

import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.napi.NotificationButton
import io.legado.app.napi.OhosNativeBridge
import io.legado.app.ui.compose.platform.syncGetString
import io.legado.app.ui.root.AppForegroundState
import io.legado.app.utils.postEvent
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * 可见性订阅作用域 (进程级一次, 与通知 provider 同寿)。
 */
private val visibilityScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * [NotificationProgress] 的鸿蒙 (OHOS) 实现 (KP7+ 已真实化)。
 *
 * # 实现方式: napi_threadsafe_function 桥接
 * 鸿蒙 `@ohos.notificationManager` 仅提供 ArkTS API, 无 NDK C 接口;
 * Kotlin/Native 无法直接调用, 通过 [OhosNativeBridge] 反向 napi 桥接 (Kotlin → ArkTS),
 * 用 threadsafe_function 把下载进度等 worker 线程的调用调度到 ArkTS 主线程执行。
 *
 * # 调用链
 * `KMP 下载逻辑` → `NotificationProgresses.get().showProgress(...)` → [OhosNotificationProgress.showProgress] →
 * [OhosNativeBridge.showNotification] → (JSON 序列化, action=SHOW) → `notificationTsfn(json)` →
 * (legado_napi.cpp threadsafe_function 跨线程调度) →
 * `EntryAbility.ets 回调` → `notificationManager.publish({ id, content: { ... } })`
 *
 * `cancel()` 同理, action=CANCEL → `notificationManager.cancel(id)`
 *
 * # 通知形态 (对照 app 端 `UpdateBookService` 的通知)
 * 常驻 (`setOngoing(true)`) + 只提醒一次 (`setOnlyAlertOnce(true)`) + "取消"按钮
 * (`addAction(..., IntentAction.stop)`); 取消动作由宿主经统一平台事件通道回传
 * (type="notificationAction", 见 [cancelOhosUpdateBookRefresh])。
 *
 * # 可见性 (对照 app 端 MainActivity.onStart/onStop → MainViewModel.updateUpdateNotification)
 * 应用可见时不呈现该通知 (原版 `isActivityVisible=true` 直接 `stopService`), 不可见且有任务时
 * 按最近一次进度重发; 订阅见 [registerOhosNotificationProgress]。
 *
 * # 通知 id 管理
 * 固定用 [NotificationId.UpdateBookService] (与 app 端该通知同一 id): 进度从"更新目录"
 * 切到"强制刷新"只换标题, 不另起一条通知。
 *
 * # 长时任务
 * 不申请保活 (长时任务文档"使用场景"表无一可用类型): "更新目录/强制刷新"是批量元数据请求,
 * 不是"非托管形式的上传、下载"(dataTransfer 的官方场景), 且该类型要求应用持续更新进度
 * (首次发布后 10 分钟无更新即被删通知停任务, 之后每次更新把时限重置为 15 分钟),
 * 与请求-响应型流量对不上;
 * `taskKeeping` (计算任务) 有设备/ACL 门槛 (API<=20 仅 PC/2in1, API>=21 非 PC 需受限 ACL 权限
 * `ohos.permission.KEEP_BACKGROUND_RUNNING_SYSTEM`)。
 *
 * # 降级策略
 * [OhosNativeBridge] 未注册 tsfn 时内部丢弃命令 (兼容当前未接入 napi 阶段)。
 *
 * # 权限与配置
 * module.json5 不声明通知权限 (本机 SDK 无 `ohos.permission.NOTIFICATION`); 未获用户授权时
 * publish 以 1600004/1600020 被拒, 由 ArkTS 侧记日志 (见 EntryAbility 的 publish catch)。
 *
 * # 进度显示
 * 正文用 "content (progress/max)" 文本 (与 desktop 端一致); progress/max 同时下发给 ArkTS
 * 侧的 downloadTemplate 进度条 (对照 app 端 `setLiveProgress`)。
 *
 * 降级策略对齐 desktop 端 [io.legado.app.help.notification.DesktopNotificationProgress]
 * 无 SystemTray 时退化为记日志; iOS 端用 NSLog 降级。
 * 模式参考 `registerAndroidMediaNotificationProvider`。
 */
class OhosNotificationProgress : NotificationProgress {

    /** 状态锁: 进度事件在引擎回调线程上到达, 可见性变化在订阅协程线程上到达。 */
    private val lock = SynchronizedObject()

    /** 应用是否可见 (对照原版 MainViewModel.isActivityVisible: 可见时不呈现进度通知)。 */
    private var appVisible = true

    /** 最近一次进度: 可见性变化时据它恢复通知 (对照原版按当前计数重发同一条通知)。 */
    private var lastProgress: ProgressSnapshot? = null

    override fun showProgress(title: String, content: String, progress: Int, max: Int) {
        val snapshot = ProgressSnapshot(title, content, progress, max)
        val visible = synchronized(lock) {
            lastProgress = snapshot
            appVisible
        }
        if (visible) {
            // 可见时不呈现 (对照原版 updateUpdateNotification 的 isActivityVisible 分支: 撤掉通知)
            OhosNativeBridge.cancelNotification(NotificationId.UpdateBookService)
            return
        }
        publish(snapshot)
    }

    override fun cancel() {
        // 任务结束: 清掉留存进度, 之后可见性变化不再恢复通知 (对照原版无任务时不 startService)
        synchronized(lock) { lastProgress = null }
        OhosNativeBridge.cancelNotification(NotificationId.UpdateBookService)
    }

    /**
     * 应用可见性变化 (对照原版 MainActivity.onStart/onStop → updateUpdateNotification):
     * 可见→撤掉通知、不可见→按最近一次进度恢复。订阅见 [registerOhosNotificationProgress]。
     */
    internal fun setAppVisible(visible: Boolean) {
        val snapshot = synchronized(lock) {
            appVisible = visible
            lastProgress
        }
        if (visible || snapshot == null) {
            // 可见即撤掉 (顺带收掉上个进程残留的通知); 无留存进度则无可恢复
            OhosNativeBridge.cancelNotification(NotificationId.UpdateBookService)
            return
        }
        publish(snapshot)
    }

    /** 发送/覆盖本条通知 (未注册 tsfn 时由 [OhosNativeBridge] 丢弃)。 */
    private fun publish(snapshot: ProgressSnapshot) {
        // 拼接进度文本: "content (progress/max)" 或 "content" (与 desktop 端 DesktopNotificationProgress 一致)
        val progressText = if (snapshot.max > 0) {
            "${snapshot.content} (${snapshot.progress}/${snapshot.max})"
        } else {
            snapshot.content
        }
        OhosNativeBridge.showNotification(
            id = NotificationId.UpdateBookService,
            title = snapshot.title,
            content = progressText,
            progress = snapshot.progress,
            max = snapshot.max,
            // 常驻 + 只提醒一次 + 取消按钮, 对照 app 端 UpdateBookService 通知的三个 setter
            ongoing = true,
            alertOnce = true,
            buttons = listOf(
                NotificationButton(text = syncGetString("cancel"), action = IntentAction.stop)
            ),
        )
    }

    /** 最近一次进度的快照 (可见性变化时重发用)。 */
    private data class ProgressSnapshot(
        val title: String,
        val content: String,
        val progress: Int,
        val max: Int,
    )
}

/**
 * 更新目录进度通知上的"取消"动作 (对照 app 端 `UpdateBookService` 的 IntentAction.stop 分支:
 * `postEvent(EventBus.STOP_UP_BOOK, "")` + `stopSelf()`, 服务销毁时撤掉通知)。
 *
 * 取消任务复用共享层既有链路: [EventBus.STOP_UP_BOOK] 事件由 `UpdateBookShared` 监听并取消
 * upToc / 强制刷新 / 预下载任务 (对照 app 端 MainViewModel 同名监听), 本函数不另写取消逻辑。
 */
fun cancelOhosUpdateBookRefresh() {
    postEvent(EventBus.STOP_UP_BOOK, "")
    OhosNativeBridge.cancelNotification(NotificationId.UpdateBookService)
}

/**
 * 鸿蒙宿主启动早期注册 [NotificationProgress] 的实现。
 *
 * 调用时机: 鸿蒙 app 启动早期, 在任何 commonMain 代码调用 `NotificationProgresses.get()` 之前。
 * 真实 tsfn 注入由 EntryAbility.onCreate 调 `legado.registerNotificationCallback` 完成,
 * 此处仅注册 [OhosNotificationProgress] (内部走 [OhosNativeBridge], 未注入 tsfn 时丢弃命令)。
 *
 * 模式参考 `registerAndroidMediaNotificationProvider`。
 */
fun registerOhosNotificationProgress() {
    val impl = OhosNotificationProgress()
    NotificationProgresses.register(impl)
    // 可见性变化立刻刷新通知 (对照原版 MainActivity.onStart/onStop → updateUpdateNotification):
    // 可见性取共享层唯一真源 [AppForegroundState.isForeground] (鸿蒙由 ArkTS 的
    // onForeground/onBackground 经统一平台事件通道置位)。StateFlow 首值即当前态 (启动时为可见),
    // 启动那一次会收掉上个进程残留的通知 (鸿蒙无前台服务, 进程死亡不带走通知)。
    AppForegroundState.isForeground
        .onEach { visible -> impl.setAppVisible(visible) }
        .launchIn(visibilityScope)
}
