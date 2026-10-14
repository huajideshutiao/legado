package io.legado.app.ui.compose.platform

import android.view.View
import android.view.ViewParent
import android.view.Window
import androidx.compose.ui.window.DialogWindowProvider

/**
 * 从 Dialog 内容里的 View 上溯, 取承载它的对话框 Window (Compose 的 DialogLayout 实现
 * [DialogWindowProvider])。不在 Dialog 内时返回 null。
 *
 * 各端窗口适配共用 (暗化 / 系统栏 / 圆角等窗口级设置): 必须在内容已 attach 后调用
 * (组合内的 Effect 里), 此时父链上才有 DialogLayout。
 */
internal fun View.findDialogWindow(): Window? {
    var current: ViewParent? = parent
    while (current != null) {
        (current as? DialogWindowProvider)?.let { return it.window }
        current = current.parent
    }
    return null
}
