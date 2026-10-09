package io.legado.app.help.notification

import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.napi.NotificationButton
import io.legado.app.napi.OhosNativeBridge
import io.legado.app.ui.compose.platform.syncGetString
import io.legado.app.utils.postEvent

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
 * # 通知 id 管理
 * 固定用 [NotificationId.UpdateBookService] (与 app 端该通知同一 id): 进度从"更新目录"
 * 切到"强制刷新"只换标题, 不另起一条通知。
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

    override fun showProgress(title: String, content: String, progress: Int, max: Int) {
        // 拼接进度文本: "content (progress/max)" 或 "content" (与 desktop 端 DesktopNotificationProgress 一致)
        val progressText = if (max > 0) {
            "$content ($progress/$max)"
        } else {
            content
        }
        OhosNativeBridge.showNotification(
            id = NotificationId.UpdateBookService,
            title = title,
            content = progressText,
            progress = progress,
            max = max,
            // 常驻 + 只提醒一次 + 取消按钮, 对照 app 端 UpdateBookService 通知的三个 setter
            ongoing = true,
            alertOnce = true,
            buttons = listOf(
                NotificationButton(text = syncGetString("cancel"), action = IntentAction.stop)
            ),
        )
    }

    override fun cancel() {
        OhosNativeBridge.cancelNotification(NotificationId.UpdateBookService)
    }
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
    NotificationProgresses.register(OhosNotificationProgress())
}
