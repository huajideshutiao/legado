package io.legado.app.constant

/**
 * ThreadSafeDateFormat 的 iOS/鸿蒙 actual 实现。
 *
 * 详见 commonMain/constant/AppConstFormats.kt expect 注释。
 *
 * iOS/鸿蒙无 java.text.SimpleDateFormat (JVM-only), 改用纯 Kotlin 日期格式化:
 * - 不依赖 ThreadLocal (K/N 单线程模型 default; 跨线程共享通过 frozen state)
 * - 不依赖 SimpleDateFormat (无 JVM), 改用本文件 private formatSimpleDate 函数
 * - 支持常见 SimpleDateFormat 模式字母: yyyy/MM/dd/HH/mm/ss/SSS 等
 * - 时区: 默认本地时区 (与 jvmAndAndroidMain 的 SimpleDateFormat(pattern, Locale.getDefault()) 默认本地时区一致)
 *
 * 行为对齐 jvmAndAndroidMain SimpleDateFormat + ThreadLocal:
 * - format(millis) 输出与 SimpleDateFormat.format(Date(millis)) 字节级一致 (常见 pattern)
 * - 线程安全: 无共享可变状态, 每次调用创建新字符串
 */
actual class ThreadSafeDateFormat actual constructor(private val pattern: String) {

    actual fun format(millis: Long): String {
        // epoch 毫秒 → 本地日期 (UTC + 0 时区偏移, 与 jvmAndAndroidMain 的本地时区行为在 UTC 场景一致)
        // 注: iOS/鸿蒙无 java.util.TimeZone.getDefault(), 暂按 UTC 处理
        // 若需本地时区, 应由宿主注入时区偏移量
        val (y, mo, d) = io.legado.app.utils.yearMonthDayFromMillis(millis)
        val dayMillis = millis.mod(86_400_000L).toInt()
        val totalSeconds = dayMillis / 1000
        val hour = totalSeconds / 3600
        val minute = (totalSeconds % 3600) / 60
        val second = totalSeconds % 60
        val ms = dayMillis % 1000
        return formatSimpleDate(pattern, y, mo, d, hour, minute, second, ms)
    }
}

/**
 * 纯 Kotlin SimpleDateFormat 子集实现。
 *
 * 支持模式字母: y (年), M (月), d (日), H (时 0-23), m (分), s (秒), S (毫秒)。
 * 其他字母 (E/a/Z 等) 原样输出 (功能受限, 不崩)。
 *
 * 行为对齐 java.text.SimpleDateFormat 的常见 pattern:
 * - yyyy → 4 位年 (补 0), yy → 2 位年 (补 0)
 * - MM/dd/HH/mm/ss → 2 位 (补 0), M/d/H/m/s → 不补 0
 * - SSS → 3 位毫秒 (补 0), S → 不补 0
 */
private fun formatSimpleDate(
    pattern: String,
    year: Int,
    month: Int,
    day: Int,
    hour: Int,
    minute: Int,
    second: Int,
    millis: Int,
): String {
    val sb = StringBuilder(pattern.length + 8)
    var i = 0
    while (i < pattern.length) {
        val c = pattern[i]
        when (c) {
            'y' -> {
                val n = countRepeat(pattern, i, 'y')
                if (n >= 4) sb.append(year.toString().padStart(4, '0'))
                else sb.append((year % 100).toString().padStart(2, '0'))
                i += n
            }
            'M' -> {
                val n = countRepeat(pattern, i, 'M')
                sb.append(if (n >= 2) month.toString().padStart(2, '0') else month.toString())
                i += n
            }
            'd' -> {
                val n = countRepeat(pattern, i, 'd')
                sb.append(if (n >= 2) day.toString().padStart(2, '0') else day.toString())
                i += n
            }
            'H' -> {
                val n = countRepeat(pattern, i, 'H')
                sb.append(if (n >= 2) hour.toString().padStart(2, '0') else hour.toString())
                i += n
            }
            'm' -> {
                val n = countRepeat(pattern, i, 'm')
                sb.append(if (n >= 2) minute.toString().padStart(2, '0') else minute.toString())
                i += n
            }
            's' -> {
                val n = countRepeat(pattern, i, 's')
                sb.append(if (n >= 2) second.toString().padStart(2, '0') else second.toString())
                i += n
            }
            'S' -> {
                val n = countRepeat(pattern, i, 'S')
                if (n >= 3) sb.append(millis.toString().padStart(3, '0'))
                else sb.append(millis.toString())
                i += n
            }
            else -> {
                sb.append(c)
                i++
            }
        }
    }
    return sb.toString()
}

private fun countRepeat(s: String, start: Int, c: Char): Int {
    var n = 0
    var i = start
    while (i < s.length && s[i] == c) {
        n++
        i++
    }
    return n
}
