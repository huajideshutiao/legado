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
 * # 支持范围 (项目现状扫描: %s / %d / %02d / %03d / %.1f / %.2f / %1$s / %1$d / %1$.Nf / %%)
 * - `%s` → `arg.toString()` (null → "null", 与 JVM Formatter 一致)
 * - `%d` → 整数十进制, 支持 `0` 填充与宽度 (如 `%02d`)
 * - `%f` → 定点小数, 支持精度 (如 `%.1f`), 缺省精度 6 位 (与 JVM 一致)
 * - 索引式 `%N$s` / `%N$d` / `%N$.Nf`: N 为 1-based 索引, 直接定位 args[N-1]
 *   (不消费按序计数器; strings.xml 有 60+ 条模板依赖此形式, 索引式与按序式可在同一串混用)
 * - `%%` → 单个字面 `%`
 *
 * # 与 JVM Formatter 的差异
 * - 不支持 `-`/`+`/`,` 等 flag: 遇到无法识别的规格原样保留该片段
 *   (JVM 会抛 UnknownFormatConversionException)。新增此类模板时需在此扩展。
 * - 实参多于占位符时忽略多余项 (JVM 同为忽略); 少于占位符时保留未消费的占位符原文
 *   (JVM 抛 MissingFormatArgumentException) — 保留原文更利于线上排查文案缺参。
 */
actual fun String.format(vararg args: Any?): String {
    if (args.isEmpty() && !contains('%')) return this
    val sb = StringBuilder(length + 16)
    var sequentialIndex = 0 // 按序占位符的消费计数 (索引式不前进, 与 JVM Formatter 一致)
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c != '%' || i == lastIndex) {
            sb.append(c)
            i++
            continue
        }
        // 解析 %[N$][0][width][.precision]conv
        var j = i + 1
        if (this[j] == '%') {
            sb.append('%')
            i = j + 1
            continue
        }
        // 索引式 N$ (1-based): 数字后跟 '$' 直接定位 args[N-1], 不消费按序计数器
        var explicitIndex = -1
        val numStart = j
        while (j < length && this[j].isDigit()) j++
        if (j > numStart && j < length && this[j] == '$') {
            explicitIndex = this.substring(numStart, j).toInt() - 1
            j++ // 消费 '$'
        } else {
            j = numStart // 非索引式, 回退让 width 解析重新消费这串数字
        }
        val zeroPad = j < length && this[j] == '0'
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
        // 取实参: 索引式直接定位, 否则按序消费
        val resolvedIndex = if (explicitIndex >= 0) explicitIndex else sequentialIndex
        if (resolvedIndex !in 0 until args.size) {
            // 缺参: 保留占位符原文 (便于定位文案与实参不匹配)
            sb.append(this, i, j + 1)
            i = j + 1
            continue
        }
        if (explicitIndex < 0) sequentialIndex++
        val arg = args[resolvedIndex]
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

/*
 * # 单元测试用例 (nativeMain 无测试源集, 以注释形式记录预期行为; jvmAndAndroidTest 端
 *   String.format 走 JVM 实现, 无法覆盖 native actual, 待补 nativeTest 源集后转写为真实用例)
 *
 * 索引式 (新增):
 * - "%1$s".format("a")                  == "a"
 * - "%2$d".format(0, 42)                == "42"               // 跳索引
 * - "%1$.1f".format(3.14)               == "3.1"              // 索引式 + 精度
 * - "%1$s 和 %2$d".format("a", 7)        == "a 和 7"           // 混合 s/d
 * - "%2$s-%1$s".format("a", "b")         == "b-a"              // 反序
 * - "全选（%1$d/%2$d）".format(3, 10)     == "全选（3/10）"      // strings.xml select_all_count
 * - "结果 %1$d, 当前进度 %2$d / %3$d: %4$s".format(1, 2, 3, "x")
 *                                        == "结果 1, 当前进度 2 / 3: x" // change_source_progress
 *
 * 按序式 (兼容回归):
 * - "%s".format("x")                    == "x"
 * - "%d".format(7)                      == "7"
 * - "%.1f".format(3.14)                 == "3.1"
 * - "%02d".format(3)                    == "03"               // 0 填充 + 宽度
 * - "%5s".format("ab")                   == "   ab"            // 空格填充
 * - "%s-%s".format("a", "b")             == "a-b"              // 多按序
 * - "100%%".format()                    == "100%"             // 字面 %
 *
 * 索引式与按序式混用:
 * - "%s %1$s".format("a")               == "a a"              // 按序消费 args[0], 索引式复用 args[0]
 *
 * 边界:
 * - "%1$s".format()                     == "%1$s"             // 缺参保留原文
 * - "%3$s".format("a", "b")              == "%3$s"             // 索引越界保留原文
 * - "%0$s".format("a")                  == "%0$s"             // 0 索引非法保留原文
 * - "%$s".format("a")                   == "%$s"             // 无数字前导的 '$' 视为未支持规格
 */
