package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIKeyboardDidHideNotification
import platform.UIKit.UIKeyboardWillShowNotification

/**
 * [rememberImeHiding] 的 iOS actual:
 * iOS 无软键盘 inset/IME 动画概念, 恒 false (imeDismissPadding 天然 no-op)。
 */
@Composable
actual fun rememberImeHiding(): Boolean = false

/**
 * [rememberImeAnimating] 的 iOS actual: 无软键盘/IME 动画概念, 恒 false。
 */
@Composable
actual fun rememberImeAnimating(): Boolean = false

/**
 * [shouldConsumeImeInsets] 的 iOS actual: 恒 true。
 *
 * CMP 1.11.1 iOS 键盘弹出时窗口不收缩, WindowInsets.ime 全量派发
 * (KeyboardInsets 监听 UIKeyboardWillChangeFrame + 动画曲线/时长过渡),
 * 应用侧需自行消费 —— imeDismissPadding 生效后帮助栏/编辑区贴键盘上方且
 * 跟随键盘动画, 对齐 Android 15+ edge-to-edge 语义。
 */
actual fun shouldConsumeImeInsets(): Boolean = true


/**
 * [rememberImeVisible] 的 iOS actual: 监听 UIKit 键盘通知的事件性布尔。
 *
 * 对齐 Android actual 语义: UIKeyboardWillShow 置 true (弹出即视为可见),
 * UIKeyboardDidHide 置 false (收起动画结束才不可见), 动画期间布尔不变 ——
 * 相同值写入 state 不触发重组, 只在翻转时更新一次。
 * (CMP iOS 亦有 WindowInsets.ime, 但通知方案不依赖其动画期数值行为, 事件语义最稳)
 */
@Composable
actual fun rememberImeVisible(): Boolean {
    var imeVisible by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val showObserver = center.addObserverForName(
            name = UIKeyboardWillShowNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ -> imeVisible = true }
        val hideObserver = center.addObserverForName(
            name = UIKeyboardDidHideNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ -> imeVisible = false }
        onDispose {
            center.removeObserver(showObserver)
            center.removeObserver(hideObserver)
        }
    }
    return imeVisible
}
