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
