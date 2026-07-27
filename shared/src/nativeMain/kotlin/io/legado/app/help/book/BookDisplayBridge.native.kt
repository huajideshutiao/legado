package io.legado.app.help.book

import io.legado.app.data.entities.LocalDate
import io.legado.app.data.entities.toYearMonthDay
import io.legado.app.utils.ChineseUtils

/**
 * BookDisplayBridge actual (iOS / 鸿蒙)。
 *
 * 详见 commonMain/help/book/BookDisplayBridge.kt expect 注释。
 *
 * - chineseT2S/chineseS2T: 委托 commonMain 的 ChineseUtils (expect object, 由繁简子代理提供 actual);
 *   本子代理仅处理 periodDaysBetween, 但为避免编译失败, chineseT2S/S2T 也写好委托调用,
 *   等 commonMain 的 expect object ChineseUtils 添加后即可编译通过。
 *
 * - periodDaysBetween: 纯 Kotlin 公历日期差计算, 与 java.time.Period.between(start, end).days 行为一致
 *   (仅返回天数差, 不考虑年/月分量; 算法: 两个 epoch 天数相减)。
 */
actual fun chineseT2S(content: String): String = ChineseUtils.t2s(content)

actual fun chineseS2T(content: String): String = ChineseUtils.s2t(content)

actual fun periodDaysBetween(start: LocalDate?, end: LocalDate): Int {
    // 复刻 java.time.Period.between(start, end).days 行为
    // start == null 抛 NPE (与 java.time.Period.between(null, end) 行为一致)
    val s = start ?: throw NullPointerException("start")
    val (sy, sm, sd) = s.toYearMonthDay()
    val (ey, em, ed) = end.toYearMonthDay()
    // 公历日期 → 天数 (Howard Hinnant civil_from_days 反算)
    val sDays = civilToDays(sy, sm, sd)
    val eDays = civilToDays(ey, em, ed)
    return eDays - sDays
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
