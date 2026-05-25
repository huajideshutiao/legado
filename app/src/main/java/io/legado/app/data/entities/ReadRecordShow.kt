package io.legado.app.data.entities

/**
 * UI 展示用聚合数据：lastRead 为本地日期 yyyyMMdd 整数键。
 */
data class ReadRecordShow(
    var bookName: String,
    var readTime: Long,
    var lastRead: Int
)
