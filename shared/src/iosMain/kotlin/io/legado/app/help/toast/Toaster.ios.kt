@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.help.toast

import io.legado.app.help.topMostViewController
import io.legado.app.ui.compose.platform.sharedStringTable
import platform.Foundation.NSLog
import platform.UIKit.UIAlertAction
import platform.UIKit.UIAlertActionStyle
import platform.UIKit.UIAlertController
import platform.UIKit.UIAlertControllerStyle
import platform.UIKit.addAction
import platform.UIKit.presentViewController
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * [Toaster] 的 iOS 真实实现 (KP4)。
 *
 * # 当前实现 (UIAlertController 真实 UI)
 * 用 [UIAlertController] (.Alert 风格) 弹出消息, 经 [dispatch_async] 到主线程
 * 在当前最顶层 [UIViewController] 上 present (替代原 NSLog 降级)。
 *
 * # 设计要点
 * - 调用线程不限: 用 [dispatch_async] 切到主线程 (UIKit present 必须主线程)
 * - 降级到 NSLog: 当拿不到 root vc / 已有 modal 在 present / present 失败时,
 *   退化为 [NSLog] 输出系统日志 (保留原 NSLog 降级能力, 与 desktop 无 SystemTray 时退化为 println 一致)
 * - 防重入: 若当前已有 presentedViewController, 不再叠加 alert (避免 UI 阻塞), 走 NSLog 兜底
 * - 短/长: UIAlertController 无"短/长"概念, toastLong 仅在 NSLog 兜底时加 `[LONG]` 区分
 *   (与 desktop 端 TrayIcon 无短长概念一致)
 *
 * # 获取最顶层 vc 的策略
 * iOS 13+ `keyWindow` 已 deprecated, 但 Kotlin/Native `UIApplication.sharedApplication().keyWindow`
 * 仍可用; 若拿不到 (iOS 13+ 多 scene 场景), 走 [findKeyWindow] 返回 null 让上层 NSLog 兜底
 * (遍历 connectedScenes 复杂度高, 且 Kotlin/Native UIScene API 映射不全, 此处简化)。
 *
 * # 后续 (KP4+)
 * UIAlertController 是模态弹窗 (阻塞用户操作), 高频 toast 体验不如 Android Toast (非模态悬浮)。
 * 后续可改用临时 UIWindow + UILabel 实现非模态 toast (更接近 Android 行为), 但实现复杂度高,
 * 当前 KP4 先用 UIAlertController (满足"真实 UI"要求), NSLog 兜底保证不崩。
 *
 * 模式参考 `registerAndroidMediaNotificationProvider` (app 端 help/media/);
 * 降级策略对齐 desktop 端 [io.legado.app.help.toast.DesktopToaster] 无 SystemTray 时退化为 println。
 *
 * # macOS 编译验证 (Windows 无法编译 iOS target)
 * ```
 * ./gradlew :shared:compileKotlinIosArm64
 * ```
 */
class IosToaster : Toaster {

    override fun toast(message: String) {
        showToast(message, isLong = false)
    }

    override fun toastLong(message: String) {
        showToast(message, isLong = true)
    }

    /**
     * 显示消息: 切到主线程用 [UIAlertController] present, 失败退化 [NSLog]。
     */
    private fun showToast(message: String, isLong: Boolean) {
        // 切到主线程: UIKit present 必须主线程 (dispatch_async 非阻塞, 调用立即返回)
        dispatch_async(dispatch_get_main_queue()) {
            val rootVc = topMostViewController()
            if (rootVc == null) {
                // 拿不到 root vc: 退化为 NSLog (与原 NSLog 降级实现一致)
                logFallback(message, isLong)
                return@dispatch_async
            }
            // 防重入: 已有 modal 在 present 时不再叠加 (避免 UI 阻塞), 走 NSLog
            if (rootVc.presentedViewController != null) {
                logFallback(message, isLong)
                return@dispatch_async
            }
            // 弹出 UIAlertController (.Alert 风格, 单个"确定"按钮自动 dismiss)
            val alert = UIAlertController(
                title = null,
                message = message,
                preferredStyle = UIAlertControllerStyle.Alert,
            )
            alert.addAction(
                UIAlertAction(
                    title = sharedStringTable["ok"],
                    style = UIAlertActionStyle.Default,
                    handler = { _ -> },
                )
            )
            try {
                rootVc.presentViewController(alert, animated = true, completion = null)
            } catch (_: Exception) {
                // present 失败: 退化为 NSLog
                logFallback(message, isLong)
            }
        }
    }

    /** NSLog 降级兜底 (与原 P0 NSLog 实现一致, 用 %@ 避免 % 被解释为格式符)。 */
    private fun logFallback(message: String, isLong: Boolean) {
        val tag = if (isLong) "[ios-toast-long]" else "[ios-toast]"
        NSLog("%@ %@", tag, message)
    }
}

/**
 * iOS 宿主启动早期注册 [Toaster] 的真实实现。
 *
 * 调用时机: iOS app 启动早期, 在任何 commonMain 代码调用 `Toasters.get()` 之前。
 *
 * 模式参考 `registerAndroidMediaNotificationProvider`。
 */
fun registerIosToaster() {
    Toasters.register(IosToaster())
}
