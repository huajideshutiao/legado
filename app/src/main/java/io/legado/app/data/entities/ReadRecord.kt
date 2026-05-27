package io.legado.app.data.entities

import androidx.room.Entity
import java.util.Calendar

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
        fun dayKey(timeSec: Long = System.currentTimeMillis() / 1000): Int {
            val cal = Calendar.getInstance()
            cal.timeInMillis = timeSec * 1000L
            val y = cal.get(Calendar.YEAR)
            val m = cal.get(Calendar.MONTH) + 1
            val d = cal.get(Calendar.DAY_OF_MONTH)
            return y * 10000 + m * 100 + d
        }
    }
}
