package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * 桌面端无系统返回键, ESC/Backspace 等价于返回按钮
 */
fun Modifier.handleBackKey(
    onBack: () -> Unit,
    onRefresh: () -> Boolean = { false },
): Modifier = this.onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    when (event.key) {
        Key.Escape, Key.Backspace -> {
            onBack()
            true
        }

        Key.F5 -> onRefresh()
        else -> false
    }
}

/** 页面级返回拦截器栈, 按注册顺序存放, 分发时栈顶 (最后注册的页面) 优先。 */
private val backInterceptors = mutableListOf<() -> Boolean>()

/**
 * 页面级返回拦截: 同时覆盖 Android 系统返回键与桌面 ESC/Backspace。
 *
 * [PlatformBackHandler] 只在 Android 生效, 桌面/iOS/鸿蒙的返回键走
 * [handleBackKey] → [dispatchBackKey], 故这里额外注册到拦截器栈。
 */
@Composable
fun AppBackHandler(enabled: Boolean, onBack: () -> Unit) {
    PlatformBackHandler(enabled = enabled, onBack = onBack)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnBack by rememberUpdatedState(onBack)
    DisposableEffect(Unit) {
        // 恒注册 (不随 enabled 增删), 保证栈内顺序始终等于页面组合顺序
        val interceptor: () -> Boolean = {
            if (currentEnabled) {
                currentOnBack()
                true
            } else false
        }
        backInterceptors += interceptor
        onDispose { backInterceptors -= interceptor }
    }
}

/** 把返回键分发给拦截器栈, 返回 true 表示已消费 (调用方不再出栈)。 */
fun dispatchBackKey(): Boolean = backInterceptors.asReversed().any { it() }
