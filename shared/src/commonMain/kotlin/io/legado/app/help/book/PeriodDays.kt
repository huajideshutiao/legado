package io.legado.app.help.book

import io.legado.app.data.entities.LocalDate
import io.legado.app.data.entities.toYearMonthDay

/**
 * 两个日期之间按"年/月/日拆分"后的日分量差。
 *
 * 语义等同于 java.time.Period.between(start, end).days: 跨整年/整月时日分量为 0,
 * 日不足时向月份借位。模拟阅读进度按此值解锁章节 (见 [Book.simulatedTotalChapterNum])。
 */
fun periodDaysBetween(start: LocalDate?, end: LocalDate): Int {
    val s = start ?: throw NullPointerException("start")
    val (sy, sm, sd) = s.toYearMonthDay()
    val (ey, em, ed) = end.toYearMonthDay()
    val totalMonths = (ey * 12L + em) - (sy * 12L + sm)
    var days = ed - sd
    if (totalMonths > 0 && days < 0) {
        // 借位: end 所在月前一月中与 start 同日 (超月截断) 的日期到 end 的天数差
        val m0 = sm - 1L + (totalMonths - 1)
        val y2 = sy + (m0 / 12).toInt()
        val m2 = (m0 % 12).toInt() + 1
        val maxD = daysInMonth(y2, m2)
        val calcD = if (sd > maxD) maxD else sd
        days = civilToDays(ey, em, ed) - civilToDays(y2, m2, calcD)
    } else if (totalMonths < 0 && days > 0) {
        days -= daysInMonth(ey, em)
    }
    return days
}

/** 公历日期 → 自 1970-01-01 起的天数 (Howard Hinnant days_from_civil 算法)。 */
private fun civilToDays(y: Int, m: Int, d: Int): Int {
    val yAdj = if (m <= 2) y - 1 else y
    val era = if (yAdj >= 0) yAdj / 400 else (yAdj - 399) / 400
    val yoe = yAdj - era * 400
    val mAdj = if (m > 2) m - 3 else m + 9
    val doy = (153 * mAdj + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097 + doe - 719_468
}

/** 公历某月的天数。 */
private fun daysInMonth(y: Int, m: Int): Int = when (m) {
    1, 3, 5, 7, 8, 10, 12 -> 31
    4, 6, 9, 11 -> 30
    else -> if (isLeapYear(y)) 29 else 28
}

private fun isLeapYear(y: Int): Boolean = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
