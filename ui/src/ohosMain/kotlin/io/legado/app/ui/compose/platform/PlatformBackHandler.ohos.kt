package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalArkUIViewController
import kotlinx.coroutines.flow.Flow

/**
 * [PlatformBackHandler] 的鸿蒙 actual: 注册到 ArkUI [OnBackPressedDispatcher]。
 *
 * 返回链: ArkTS Index.ets `Compose(onBackPressed:)` → `controller.onBackPress()`
 * → dispatcher 逆序遍历回调, 返回 true 即消费; 全部不消费时经 messenger 回落 ArkTS 默认行为。
 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val dispatcher = LocalArkUIViewController.current.onBackPressedDispatcher
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnBack by rememberUpdatedState(onBack)
    DisposableEffect(dispatcher) {
        // 恒注册 (不随 enabled 增删), 保证 dispatcher 内顺序始终等于页面组合顺序
        val cancel = dispatcher.addOnBackPressedCallback {
            if (currentEnabled) {
                currentOnBack()
                true
            } else false
        }
        onDispose { cancel() }
    }
}

/**
 * [PlatformPredictiveBackHandler] 的鸿蒙 actual: no-op。
 * ArkUI 的返回手势经 `OnBackPressedDispatcher` 只有"按下"没有进度, 由 [PlatformBackHandler] 承接;
 * [PlatformPredictiveBackHandler] 的 onBackClick 出口在本端恒不被调用。
 */
@Composable
actual fun PlatformPredictiveBackHandler(
    enabled: Boolean,
    onGesture: suspend (gesture: Flow<Float>) -> Boolean,
    onBackClick: () -> Unit,
) {
    // No-op: 无跟手进度可消费
}
