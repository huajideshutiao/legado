@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.legado.app.data.entities

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import kotlinx.serialization.Serializable
import kotlin.time.Clock


@Serializable
@Entity(tableName = "search_keywords", withoutRowId = true)
data class SearchKeyword(
    /** 搜索关键词 */
    @PrimaryKey
    var word: String = "",
    /** 使用次数 */
    var usage: Int = 1,
    /** 最后一次使用时间 */
    var lastUseTime: Long = Clock.System.now().toEpochMilliseconds()
)
