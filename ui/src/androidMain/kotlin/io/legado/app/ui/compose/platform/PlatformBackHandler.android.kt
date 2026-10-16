package io.legado.app.ui.compose.platform

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import io.legado.app.constant.PreferKey
import io.legado.app.ui.compose.preference.rememberPrefState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [PlatformBackHandler] 的 Android actual:
 * 委托 `androidx.activity.compose.BackHandler`, 拦截系统返回键/返回手势。
 *
 * 预测式返回开关打开时不再注册页面级回调 (enabled 恒为 false): 返回统一由根层跟手通道接管,
 * 松手后才走统一返回链 (覆盖物层 → 弹层 → 页面级拦截 → 出栈) —— 这也是 iOS 侧的形态。
 * 再注册一个页面级回调只会把它抢走: 该页面的手势退化成"按下即返回", 跟手观感消失。
 * 开关关闭时本 actual 与本改动前逐字一致 (系统按页面级回调派发)。
 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val predictiveBack by rememberPrefState(PreferKey.predictiveBack) {
        it.getBoolean(PreferKey.predictiveBack, true)
    }
    androidx.activity.compose.BackHandler(enabled = enabled && !predictiveBack, onBack = onBack)
}

/**
 * [PlatformPredictiveBackHandler] 的 Android actual:
 * 委托 `androidx.activity.compose.PredictiveBackHandler`。
 *
 * 进度值来自系统 (API 34 起的返回动画事件), 库把这套事件接到派发器上, 本实现只消费流。
 * API≤33 的设备没有进度事件, 每次返回都以空流到达 —— 那时本开关只剩"根层接管 + 松手判定"这一半
 * (设置里的文案已写明跟手需要 Android 14 及以上)。
 * 与 iOS 的关键差别: 按键/硬件返回与旧式返回手势**不给进度事件**, 到达时是一条空流 ——
 * 那是真的一次返回, 走 [onBackClick]; iOS 那侧的空流是"识别器失败被当成完成上报", 必须忽略,
 * 所以该出口只有 Android 有真实调用方。
 *
 * 开关关闭时同样不注册 (与 [PlatformBackHandler] 的让位规则配套): 页面级回调照旧接管, 行为不变。
 */
@Composable
actual fun PlatformPredictiveBackHandler(
    enabled: Boolean,
    onGesture: suspend (gesture: Flow<Float>) -> Boolean,
    onBackClick: () -> Unit,
) {
    val predictiveBack by rememberPrefState(PreferKey.predictiveBack) {
        it.getBoolean(PreferKey.predictiveBack, true)
    }
    val currentOnGesture by rememberUpdatedState(onGesture)
    val currentOnBackClick by rememberUpdatedState(onBackClick)
    PredictiveBackHandler(enabled = enabled && predictiveBack) { progress ->
        // 手势取消时驱动会把这个取消异常原样抛出, 直接穿过本 lambda, 因此不会被误判成"点击式返回"
        if (!currentOnGesture(progress.map { it.progress })) currentOnBackClick()
    }
}
