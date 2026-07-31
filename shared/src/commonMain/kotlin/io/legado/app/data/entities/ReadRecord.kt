package io.legado.app.data.entities

import androidx.room3.Entity
import io.legado.app.utils.systemCurrentTimeMillis
import io.legado.app.utils.yearMonthDayFromMillis

/**
 * 阅读记录：每次阅读会话一行，主键 (bookName, day, startSec)，day 形如 20260525。
 * 时长由 endSec - startSec 计算，秒级精度。
 */
@Entity(tableName = "readRecord", primaryKeys = ["bookName", "day", "startSec"])
data class ReadRecord(
    var bookName: String = "",
    var day: Int = 0,
    var startSec: Long = 0L,
    var endSec: Long = 0L
) {
    companion object {
        /** 把秒时间戳转成本地日期 yyyyMMdd 整数键 */
        fun dayKey(timeSec: Long = systemCurrentTimeMillis() / 1000): Int {
            // Calendar.getInstance() + cal.get(YEAR/MONTH/DAY_OF_MONTH) 改走
            // yearMonthMonthDayFromMillis expect/actual，避免 commonMain 引入 java.util.Calendar
            val (y, m, d) = yearMonthDayFromMillis(timeSec * 1000L)
            return y * 10000 + m * 100 + d
        }
    }
}
