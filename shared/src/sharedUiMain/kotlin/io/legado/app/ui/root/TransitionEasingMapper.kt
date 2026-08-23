package io.legado.app.ui.root

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

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
    is TransitionEasing.Spring -> springEasing(response, damping)
}

/**
 * 阻尼谐振弹簧曲线, 与澎湃 OS 转场插值器逐字等价
 * (miui-services.jar `AppTransitionInjector$ActivityTranstionInterpolator`):
 * 常量沿用其自带的截断值 (e / 2π / 4π), 质量 m=1、初始位移 initial=-1。
 *
 * f(t) = e^(r·t) · (c1·cos(w·t) + c2·sin(w·t)) + 1, t 为归一化进度。
 */
private fun springEasing(response: Float, damping: Float): Easing {
    val k = (6.28319 / response).pow(2.0)
    val c = damping * 12.5664 / response
    val w = sqrt(4.0 * k - c * c) / 2.0
    val r = -c / 2.0
    val c1 = -1.0
    val c2 = r / w
    return Easing { fraction ->
        val t = fraction.toDouble()
        (2.71828.pow(r * t) * (c1 * cos(w * t) + c2 * sin(w * t)) + 1.0).toFloat()
    }
}
