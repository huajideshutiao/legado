package io.legado.app.data.entities

/**
 * UI 展示用聚合数据：lastRead 为当日最后一次阅读的毫秒时间戳，0 表示历史数据缺失。
 */
data class ReadRecordShow(
    var bookName: String,
    var readTime: Long,
    var lastRead: Long
)
