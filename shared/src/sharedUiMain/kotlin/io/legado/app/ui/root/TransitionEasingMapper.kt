package io.legado.app.ui.root

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing

/**
 * 平台插值器描述 → compose Easing 集中映射 (唯一映射点)。
 *
 * commonMain 无 compose 依赖, 平台 spec 用 [TransitionEasing] 描述插值器;
 * shared 动画层 (LegadoApp/AppDialog) 统一经此映射后消费 compose Easing。
 * CMP animation-core 将 AccelerateEasing/DecelerateEasing 收为 internal,
 * 这里按系统 accelerate_quad/decelerate_quad 插值器语义本地定义 (同 AppDialog 先例)。
 */
fun TransitionEasing.toComposeEasing(): Easing = when (this) {
    TransitionEasing.Linear -> LinearEasing
    TransitionEasing.FastOutSlowIn -> FastOutSlowInEasing
    TransitionEasing.DecelerateQuad -> Easing { 1f - (1f - it) * (1f - it) }
    TransitionEasing.AccelerateQuad -> Easing { it * it }
    is TransitionEasing.CubicBezier -> CubicBezierEasing(x1, y1, x2, y2)
}
