package io.legado.app.utils

import kotlin.math.roundToInt

/**
 * `formatPercentUs` 的 iOS/鸿蒙 actual 实现 (nativeMain 中间源集共用)。
 *
 * 纯 Kotlin 实现, 不依赖 `java.util.Locale`。
 * iOS/鸿蒙两端 actual 实现完全一致 (Kotlin/Native target 同样不支持 java.*),
 * 下沉到 nativeMain 共用。
 *
 * 详见 commonMain/utils/PercentFormat.kt expect 注释。
 *
 * 算法: `(value * 1000.0).roundToInt() / 10.0`
 * - 先把 0~1 的 value 放大 1000 倍 (即百分比 * 10),
 * - roundToInt() 四舍五入到整数 (HALF_UP, 与 JVM String.format 的 HALF_EVEN 在 .5 边界略有差异, 业务可接受),
 * - 再除以 10.0 得到 1 位小数的 Double。
 *
 * 例:
 * - 0.456  -> 456.0  -> 456  -> 45.6  -> "45.6%"
 * - 0.4565 -> 456.5 -> 457  -> 45.7  -> "45.7%"
 * - 1.0    -> 1000  -> 1000 -> 100.0 -> "100.0%"
 *
 * 字符串模板 `"$v%"` 对 45.6 输出 "45.6%", 对 45.0 输出 "45.0%" (Double.toString 行为,
 * 与 JVM String.format("%.1f%%") 一致)。
 *
 * -0.0 边界处理: 当 rounded == 0.0 (包括 -0.0) 时强制返回 0.0, 避免 "-0.0%"。
 */
actual fun formatPercentUs(value: Double): String {
    val rounded = (value * 1000.0).roundToInt() / 10.0
    // 处理 -0.0 边界, 避免 "-0.0%"
    val v = if (rounded == 0.0) 0.0 else rounded
    return "$v%"
}
