package io.legado.app.utils

import kotlin.math.roundToLong

/**
 * ConvertExtensions 平台 actual (iOS / 鸿蒙)。
 *
 * 详见 commonMain/utils/ConvertExtensions.kt 的 expect 注释。
 * 纯 Kotlin 实现: 千位分隔符 + 最多 2 位小数 (RoundingMode HALF_EVEN),
 * 与 jvmAndAndroidMain `DecimalFormat("#,##0.##")` 字节级一致。
 */
internal actual fun formatFileSizeDecimal(value: Double): String {
    if (value.isNaN() || value.isInfinite()) return value.toString()
    // HALF_EVEN 四舍五入到 2 位小数: 用 roundToLong 等价 Math.rint (HALF_EVEN)
    val negative = value < 0
    val absValue = if (negative) -value else value
    val scaled = (absValue * 100.0).roundToLong()
    val intPart = scaled / 100
    val fracPart = (scaled % 100).toInt()
    // 整数部分千位分隔符分组
    val grouped = buildString {
        val digits = intPart.toString()
        val firstGroupLen = if (digits.length % 3 == 0) 3 else digits.length % 3
        append(digits.substring(0, firstGroupLen))
        var i = firstGroupLen
        while (i < digits.length) {
            append(",")
            append(digits.substring(i, i + 3))
            i += 3
        }
    }
    val body = when {
        fracPart == 0 -> grouped
        fracPart % 10 == 0 -> "$grouped.${fracPart / 10}"
        else -> {
            val fracStr = if (fracPart < 10) "0$fracPart" else fracPart.toString()
            "$grouped.$fracStr"
        }
    }
    return if (negative) "-$body" else body
}
