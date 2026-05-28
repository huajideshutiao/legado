package io.legado.app.data.entities

/**
 * UI 展示用聚合数据：readTime 为阅读时长（秒），lastRead 为最后阅读时间戳（秒），0 表示历史数据缺失。
 */
data class ReadRecordShow(
    var bookName: String,
    var readTime: Long,  // 秒
    var lastRead: Long,  // 秒
    var day: Int = 0     // yyyyMMdd，perDayMode 下与 perDayMap 分组 key 一致
)
