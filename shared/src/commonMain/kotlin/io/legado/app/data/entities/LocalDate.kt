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
 * [toYearMonthDay]。
 */
class LocalDate(val year: Int, val month: Int, val day: Int) {
    override fun toString(): String {
        val m = if (month < 10) "0$month" else month.toString()
        val d = if (day < 10) "0$day" else day.toString()
        return "$year-$m-$d"
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

fun localDateOf(year: Int, month: Int, dayOfMonth: Int): LocalDate =
    LocalDate(year, month, dayOfMonth)

/**
 * 拆出 year/month/day, 供 LocalDateAsGsonSerializer 读写。
 */
fun LocalDate.toYearMonthDay(): Triple<Int, Int, Int> = Triple(year, month, day)
