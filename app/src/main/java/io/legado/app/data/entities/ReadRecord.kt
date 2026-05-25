package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import java.util.Calendar

/**
 * 阅读记录：每本书按日期分行，主键 (bookName, day)，day 形如 20260525。
 * 「累计时长」「最后阅读日」均由 DAO 聚合查询导出，不再单独存字段。
 */
@Entity(tableName = "readRecord", primaryKeys = ["bookName", "day"])
data class ReadRecord(
    var bookName: String = "",
    var day: Int = 0,
    @ColumnInfo(defaultValue = "0")
    var readTime: Long = 0L
) {
    companion object {
        /** 把毫秒时间戳转成本地日期 yyyyMMdd 整数键 */
        fun dayKey(timeMillis: Long = System.currentTimeMillis()): Int {
            val cal = Calendar.getInstance()
            cal.timeInMillis = timeMillis
            val y = cal.get(Calendar.YEAR)
            val m = cal.get(Calendar.MONTH) + 1
            val d = cal.get(Calendar.DAY_OF_MONTH)
            return y * 10000 + m * 100 + d
        }
    }
}
