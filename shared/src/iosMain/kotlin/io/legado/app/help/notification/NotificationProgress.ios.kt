@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.help.notification

import platform.Foundation.NSLog
import platform.Foundation.NSError
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.addNotificationRequest
import platform.UserNotifications.currentNotificationCenter
import platform.UserNotifications.removeDeliveredNotificationsWithIdentifiers
import platform.UserNotifications.removePendingNotificationRequestsWithIdentifiers
import platform.UserNotifications.requestAuthorizationWithOptions

/**
 * [NotificationProgress] 的 iOS 真实实现 (KP4)。
 *
 * # 当前实现 (UNUserNotificationCenter 真实本地通知)
 * 用 [UNUserNotificationCenter] 发送本地通知显示进度, 替代原 NSLog 降级。
 * 注册时请求通知权限 (alert+sound), showProgress 用固定 identifier 的 [UNNotificationRequest]
 * 覆盖式发送 (每次先 remove 再 add, 模拟"更新同一条通知"行为, 与 Android
 * `NotificationManager.notify(id, ...)` 一致)。
 *
 * # 设计要点
 * - 权限请求: [registerIosNotificationProgress] 中异步请求 alert+sound 权限 (不阻塞, 首次弹系统权限对话框)
 * - 通知 ID: 固定 [NOTIFICATION_ID] ("legado-progress"), 复用同一条通知 (覆盖式更新, 与 Android 通知 ID 一致)
 * - showProgress: 创建 [UNMutableNotificationContent] (title + body=进度文本) + [UNNotificationRequest]
 *   (trigger=null 立即触发), add 到 center; 失败退化 NSLog
 * - cancel: removeDeliveredNotificationsWithIdentifiers (移除已展示的通知) +
 *   removePendingNotificationRequestsWithIdentifiers (移除待发送的, 防止 trigger 延迟)
 * - 进度文本拼接: "content (progress/max)" 或 "content" (max<=0 时), 与 desktop 端
 *   [io.legado.app.help.notification.DesktopNotificationProgress] 一致
 * - 失败兜底: 任意步骤异常 (权限未授权 / 通知 center 不可用) 退化为 [NSLog], 保留原日志降级行为
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
 * ./gradlew :shared:compileKotlinIosArm64
 * ```
 */
class IosNotificationProgress : NotificationProgress {

    override fun showProgress(title: String, content: String, progress: Int, max: Int) {
        // 拼接进度文本: "content (progress/max)" 或 "content" (与 desktop 端 DesktopNotificationProgress 一致)
        val progressText = if (max > 0) {
            "$content ($progress/$max)"
        } else {
            content
        }
        // 真实通知: runCatching 包裹, 失败退化 NSLog (保留原日志降级行为)
        runCatching {
            val center = UNUserNotificationCenter.currentNotificationCenter()
            val contentObj = UNMutableNotificationContent().apply {
                this.title = title
                this.body = progressText
            }
            // trigger=null 立即触发 (非定时); 固定 identifier 覆盖式更新 (与 Android 通知 ID 复用一致)
            // 注: 若 Kotlin/Native 把 requestWithIdentifier: 工厂方法映射为 companion 函数,
            //     改 UNNotificationRequest.requestWithIdentifier(identifier=..., content=..., trigger=null)
            val request = UNNotificationRequest(
                identifier = NOTIFICATION_ID,
                content = contentObj,
                trigger = null,
            )
            // 先移除已发送的同 id 通知 (避免通知中心列表堆积, 模拟"更新同一条")
            center.removeDeliveredNotificationsWithIdentifiers(listOf(NOTIFICATION_ID))
            // addNotificationRequest 尾部 completionHandler 可 trailing lambda; error 非 nil 表示添加失败
            center.addNotificationRequest(request) { error ->
                if (error != null) {
                    NSLog("[ios-notification] add request error: %@", error.localizedDescription)
                }
            }
        }.onFailure {
            // 退化: NSLog 输出系统日志 (与原 NSLog 降级实现一致)
            NSLog("[ios-notification] %@ | %@", title, progressText)
        }
    }

    override fun cancel() {
        // 移除已展示的通知 + 待发送的请求 (防止 trigger 延迟)
        runCatching {
            val center = UNUserNotificationCenter.currentNotificationCenter()
            center.removeDeliveredNotificationsWithIdentifiers(listOf(NOTIFICATION_ID))
            center.removePendingNotificationRequestsWithIdentifiers(listOf(NOTIFICATION_ID))
        }.onFailure {
            NSLog("[ios-notification] cancel failed (fallback)")
        }
        NSLog("[ios-notification] cancel")
    }

    companion object {
        /** 固定通知标识 (复用同一条通知, 与 Android 通知 ID 概念一致)。 */
        private const val NOTIFICATION_ID = "legado-progress"
    }
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
                NSLog("[ios-notification] auth error: %@", error.localizedDescription)
            } else if (!granted) {
                NSLog("[ios-notification] user denied notification authorization")
            }
        }
    }.onFailure {
        NSLog("[ios-notification] requestAuthorization failed: %@", it.toString())
    }
    NotificationProgresses.register(IosNotificationProgress())
}
