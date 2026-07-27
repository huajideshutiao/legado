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
 * `java.lang.String.format(this, *args)` 的 Kotlin/Native 替代 (nativeMain, iOS/鸿蒙共用)。
 *
 * Kotlin/Native 标准库没有 `String.format` (klib dump 实证), 但 iOS/鸿蒙 UI 层有 ~67 处
 * 文案模板依赖它。本函数按顺序替换占位符, 覆盖项目实际用到的全部规格。
 *
 * # 支持范围 (项目现状: SharedStringTable 全表仅 %s / %d, 无索引式 %1$s, 无 %%)
 * - `%s` → `arg.toString()` (null → "null", 与 JVM Formatter 一致)
 * - `%d` → 整数十进制 (与 JVM 一致; 非数字实参原样 toString, 不抛 IllegalFormatConversionException)
 * - `%%` → 单个字面 `%`
 *
 * # 与 JVM Formatter 的差异
 * - 不支持宽度/精度/flag (如 `%5s` / `%.2f`) 与索引式 `%1$s`: 遇到无法识别的规格
 *   原样保留该片段并跳过 (JVM 会抛 UnknownFormatConversionException)。
 *   全表零使用, 故不可见; 新增此类模板时需在此扩展。
 * - 实参多于占位符时忽略多余项 (JVM 同为忽略); 少于占位符时保留未消费的占位符原文
 *   (JVM 抛 MissingFormatArgumentException) — 保留原文更利于线上排查文案缺参。
 */
fun String.formatNative(vararg args: Any?): String {
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
        when (this[i + 1]) {
            '%' -> {
                sb.append('%')
                i += 2
            }
            's', 'd' -> {
                if (argIndex < args.size) {
                    sb.append(args[argIndex++].toString())
                } else {
                    // 缺参: 保留占位符原文 (便于定位文案与实参不匹配)
                    sb.append(c).append(this[i + 1])
                }
                i += 2
            }
            // 未支持的规格 (宽度/精度/索引式等): 原样输出 '%', 继续逐字符扫描
            else -> {
                sb.append(c)
                i++
            }
        }
    }
    return sb.toString()
}
