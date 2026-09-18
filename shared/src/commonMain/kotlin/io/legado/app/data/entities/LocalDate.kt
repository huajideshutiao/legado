package io.legado.app.data.entities

import io.legado.app.utils.systemCurrentTimeMillis
import io.legado.app.utils.yearMonthDayFromMillis

/**
 * Book.ReadConfig.startDate 的日期类型, 纯 Kotlin 公历日期 (年月日三字段)。
 *
 * 不用 java.time.LocalDate: Android minSdk 24 低于 java.time 的 API 26, 引用它会把整个
 * desugar_jdk_libs 的 java.time 运行时 (j$/time 约 170KB) 打进 APK。三端共用本实现后
 * 该依赖可整体移除。
 *
 * commonMain 不能直接声明带属性的 expect class (actual typealias 到 Java 类时 getter
 * 不被识别为 val 成员), 故所有访问经 top-level fun: [localDateNow] / [localDateOf] /
 * [localDateParseOrNull] / [toYearMonthDay]。
 */
class LocalDate(val year: Int, val month: Int, val day: Int) {
    /** ISO-8601 本地日期: 年补零到 4 位, 月/日各补零到 2 位。 */
    override fun toString(): String {
        val y = year.toString().padStart(4, '0')
        val m = month.toString().padStart(2, '0')
        val d = day.toString().padStart(2, '0')
        return "$y-$m-$d"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LocalDate) return false
        return year == other.year && month == other.month && day == other.day
    }

    override fun hashCode(): Int = 31 * (31 * year + month) + day
}

/**
 * 当前本地日期。
 */
fun localDateNow(): LocalDate {
    val (y, m, d) = yearMonthDayFromMillis(systemCurrentTimeMillis())
    return LocalDate(y, m, d)
}

fun localDateOf(year: Int, month: Int, dayOfMonth: Int): LocalDate {
    require(month in 1..12) { "Invalid value for MonthOfYear (valid values 1 - 12): $month" }
    require(dayOfMonth in 1..daysInMonth(year, month)) {
        "Invalid date '$month/$dayOfMonth' as it does not exist in year $year"
    }
    return LocalDate(year, month, dayOfMonth)
}

/**
 * 严格解析 yyyy-MM-dd: 段数、数字、日期合法性 (闰年/大小月) 全部校验, 非法返回 null。
 * 对齐 java.time.LocalDate.parse 的严格语义, 只是失败不抛而是返回 null。
 */
fun localDateParseOrNull(text: String): LocalDate? {
    val parts = text.trim().split("-")
    if (parts.size != 3) return null
    val y = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    val d = parts[2].toIntOrNull() ?: return null
    return runCatching { localDateOf(y, m, d) }.getOrNull()
}

/** 公历某月的天数。 */
internal fun daysInMonth(year: Int, month: Int): Int = when (month) {
    1, 3, 5, 7, 8, 10, 12 -> 31
    4, 6, 9, 11 -> 30
    else -> if (isLeapYear(year)) 29 else 28
}

internal fun isLeapYear(year: Int): Boolean =
    (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

/**
 * 拆出 year/month/day, 供 LocalDateAsGsonSerializer 读写。
 */
fun LocalDate.toYearMonthDay(): Triple<Int, Int, Int> = Triple(year, month, day)
