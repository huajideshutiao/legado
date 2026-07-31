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

/**
 * `java.lang.String.format(this, *args)` 的 Kotlin/Native actual (nativeMain, iOS/鸿蒙共用)。
 *
 * Kotlin/Native 标准库没有 `String.format` (klib dump 实证), 但 iOS/鸿蒙 UI 层有大量
 * 文案模板依赖它。本函数按顺序替换占位符, 覆盖项目实际用到的全部规格。
 *
 * # 支持范围 (项目现状扫描: %s / %d / %02d / %03d / %.1f / %.2f / %%)
 * - `%s` → `arg.toString()` (null → "null", 与 JVM Formatter 一致)
 * - `%d` → 整数十进制, 支持 `0` 填充与宽度 (如 `%02d`)
 * - `%f` → 定点小数, 支持精度 (如 `%.1f`), 缺省精度 6 位 (与 JVM 一致)
 * - `%%` → 单个字面 `%`
 *
 * # 与 JVM Formatter 的差异
 * - 不支持索引式 `%1$s` 与 `-`/`+`/`,` 等 flag: 遇到无法识别的规格原样保留该片段
 *   (JVM 会抛 UnknownFormatConversionException)。全表零使用, 新增此类模板时需在此扩展。
 * - 实参多于占位符时忽略多余项 (JVM 同为忽略); 少于占位符时保留未消费的占位符原文
 *   (JVM 抛 MissingFormatArgumentException) — 保留原文更利于线上排查文案缺参。
 */
actual fun String.format(vararg args: Any?): String {
    if (args.isEmpty() && !contains('%')) return this
    val sb = StringBuilder(length + 16)
    var argIndex = 0
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c != '%' || i == lastIndex) {
            sb.append(c)
            i++
            continue
        }
        // 解析 %[0][width][.precision]conv
        var j = i + 1
        if (this[j] == '%') {
            sb.append('%')
            i = j + 1
            continue
        }
        val zeroPad = this[j] == '0'
        if (zeroPad) j++
        var width = 0
        while (j < length && this[j].isDigit()) {
            width = width * 10 + (this[j] - '0')
            j++
        }
        var precision = -1
        if (j < length && this[j] == '.') {
            j++
            precision = 0
            while (j < length && this[j].isDigit()) {
                precision = precision * 10 + (this[j] - '0')
                j++
            }
        }
        val conv = if (j < length) this[j] else ' '
        if (conv != 's' && conv != 'd' && conv != 'f') {
            // 未支持的规格: 原样输出 '%', 继续逐字符扫描
            sb.append(c)
            i++
            continue
        }
        if (argIndex >= args.size) {
            // 缺参: 保留占位符原文 (便于定位文案与实参不匹配)
            sb.append(this, i, j + 1)
            i = j + 1
            continue
        }
        val arg = args[argIndex++]
        val text = when (conv) {
            'f' -> formatFixed(arg, if (precision < 0) 6 else precision)
            else -> arg.toString()
        }
        sb.append(if (text.length >= width) text else text.padStart(width, if (zeroPad) '0' else ' '))
        i = j + 1
    }
    return sb.toString()
}

/** `%.Nf` 定点格式化 (HALF_UP, 与 JVM Formatter 的 RoundingMode.HALF_UP 一致)。 */
private fun formatFixed(arg: Any?, precision: Int): String {
    val v = (arg as? Number)?.toDouble() ?: return arg.toString()
    if (v.isNaN()) return "NaN"
    if (v.isInfinite()) return if (v > 0) "Infinity" else "-Infinity"
    val neg = v < 0
    var scale = 1L
    repeat(precision) { scale *= 10 }
    val scaled = kotlin.math.round(kotlin.math.abs(v) * scale).toLong()
    val intPart = scaled / scale
    val sign = if (neg) "-" else ""
    if (precision == 0) return "$sign$intPart"
    val frac = (scaled % scale).toString().padStart(precision, '0')
    return "$sign$intPart.$frac"
}

/** 旧名保留 (NativeFileBookAccessor / NativeQuickJsSharedJsScopeProvider 在用), 委托 [format]。 */
fun String.formatNative(vararg args: Any?): String = format(*args)
