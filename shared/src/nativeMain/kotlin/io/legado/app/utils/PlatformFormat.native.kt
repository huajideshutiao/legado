package io.legado.app.utils

import kotlin.math.roundToInt

/**
 * String.format(Locale.ROOT, "%.0f", value) 的 iOS/鸿蒙 actual 实现。
 *
 * 详见 commonMain/utils/PlatformFormat.kt expect 注释。
 * 纯 Kotlin 实现: roundToInt (HALF_UP) + toString, 与 JVM String.format(Locale.ROOT, "%.0f", ...)
 * 在大多数情况下行为一致 (差异: JVM 用 HALF_EVEN, 这里用 HALF_UP; 对正数 0.5 / 1.5 等边界值差 1,
 *  业务可接受 - JS Number.toString 行为本身不区分这两种 rounding)。
 */
actual fun formatDoubleNoDecimal(value: Double): String {
    if (value.isNaN()) return "NaN"
    if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
    return value.roundToInt().toString()
}
