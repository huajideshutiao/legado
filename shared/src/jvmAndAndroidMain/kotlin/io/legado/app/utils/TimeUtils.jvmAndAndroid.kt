package io.legado.app.utils

import java.util.Calendar

/**
 * TimeUtils 的 JVM 半区 actual（android + jvm 共用）。
 *
 * systemCurrentTimeMillis 委托 java.lang.System.currentTimeMillis()（wall clock，与原直接调用行为一致）；
 * yearMonthDayFromMillis 委托 java.util.Calendar（保留原 ReadRecord.dayKey 的本地时区语义）。
 */

actual fun systemCurrentTimeMillis(): Long = System.currentTimeMillis()

actual fun systemNanoTime(): Long = System.nanoTime()

actual fun yearMonthDayFromMillis(epochMillis: Long): Triple<Int, Int, Int> {
    val cal = Calendar.getInstance()
    cal.timeInMillis = epochMillis
    val y = cal.get(Calendar.YEAR)
    val m = cal.get(Calendar.MONTH) + 1
    val d = cal.get(Calendar.DAY_OF_MONTH)
    return Triple(y, m, d)
}

/**
 * 与 app 端 Restore.restoreOldRecord 的 prevDay 同语义:
 * cal.set(y, m-1, d) + cal.add(Calendar.DAY_OF_MONTH, -1) + 重新读 YEAR/MONTH/DAY_OF_MONTH。
 */
actual fun prevDayKey(dayKey: Int): Int {
    val cal = Calendar.getInstance()
    cal.clear()
    cal.set(dayKey / 10000, (dayKey / 100) % 100 - 1, dayKey % 100)
    cal.add(Calendar.DAY_OF_MONTH, -1)
    val y = cal.get(Calendar.YEAR)
    val m = cal.get(Calendar.MONTH) + 1
    val d = cal.get(Calendar.DAY_OF_MONTH)
    return y * 10000 + m * 100 + d
}

/**
 * 与 app 端 Restore.restoreOldRecord 的 midnightSec 同语义:
 * cal.clear() + cal.set(y, m-1, d) + cal.timeInMillis / 1000。
 */
actual fun midnightSecFromDayKey(dayKey: Int): Long {
    val cal = Calendar.getInstance()
    cal.clear()
    cal.set(dayKey / 10000, (dayKey / 100) % 100 - 1, dayKey % 100)
    return cal.timeInMillis / 1000
}

/**
 * 漫画信息条 HH:mm: 用 java.util.Calendar 本地时区读取 HOUR_OF_DAY/MINUTE。
 */
actual fun formatTimeOfDay(epochMillis: Long): String {
    val cal = Calendar.getInstance()
    cal.timeInMillis = epochMillis
    return "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
}
