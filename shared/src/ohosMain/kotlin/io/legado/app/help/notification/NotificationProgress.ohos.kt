package io.legado.app.help.notification

import kotlin.concurrent.Volatile

import io.legado.app.napi.OhosNativeBridge

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
 * # 通知 id 管理
 * id 用 `title.hashCode()` 稳定生成 (同一 title 多次 showProgress 更新同一通知);
 * [cancel] 取消上次 [showProgress] 记录的 id (interface 无 title 参数, 需缓存 lastId)。
 *
 * # 降级策略
 * [OhosNativeBridge] 未注册 tsfn 时内部降级为 println (兼容当前未接入 napi 阶段)。
 *
 * # 权限与配置
 * - module.json5 需声明 `ohos.permission.NOTIFICATION` 权限
 * - 首次发布前需调 `notificationManager.requestEnableNotification()` 请求用户授权
 *   (在 EntryAbility.onCreate 注册回调后一次性请求)
 *
 * # 进度显示
 * 当前用 BasicText 显示 "content (progress/max)" 文本 (与 desktop 端一致);
 * 后续可升级 ProgressBarTemplate 显示真实进度条 (复杂度更高, 待 KP8+)。
 *
 * 降级策略对齐 desktop 端 [io.legado.app.help.notification.DesktopNotificationProgress]
 * 无 SystemTray 时退化为 println; iOS 端用 NSLog 降级 (鸿蒙无 NSLog 等价物, 用 println 兜底)。
 * 模式参考 `registerAndroidMediaNotificationProvider`。
 */
class OhosNotificationProgress : NotificationProgress {

    /** 上次 showProgress 的通知 id (cancel 用, title.hashCode() 稳定生成)。 */
    @Volatile
    private var lastId: Int? = null

    override fun showProgress(title: String, content: String, progress: Int, max: Int) {
        // 拼接进度文本: "content (progress/max)" 或 "content" (与 desktop 端 DesktopNotificationProgress 一致)
        val progressText = if (max > 0) {
            "$content ($progress/$max)"
        } else {
            content
        }
        // id 用 title.hashCode() 稳定生成 (同一 title 多次更新覆盖同一通知)
        val id = title.hashCode()
        lastId = id
        OhosNativeBridge.showNotification(id, title, progressText, progress, max)
    }

    override fun cancel() {
        // interface 无 title 参数, 用缓存的 lastId 取消; 未调过 showProgress 时 no-op
        val id = lastId
        if (id != null) {
            OhosNativeBridge.cancelNotification(id)
            lastId = null
        }
    }
}

/**
 * 鸿蒙宿主启动早期注册 [NotificationProgress] 的实现。
 *
 * 调用时机: 鸿蒙 app 启动早期, 在任何 commonMain 代码调用 `NotificationProgresses.get()` 之前。
 * 真实 tsfn 注入由 EntryAbility.onCreate 调 `legado.registerNotificationCallback` 完成,
 * 此处仅注册 [OhosNotificationProgress] (内部走 [OhosNativeBridge], 未注入 tsfn 时降级 println)。
 *
 * 模式参考 `registerAndroidMediaNotificationProvider`。
 */
fun registerOhosNotificationProgress() {
    NotificationProgresses.register(OhosNotificationProgress())
}
