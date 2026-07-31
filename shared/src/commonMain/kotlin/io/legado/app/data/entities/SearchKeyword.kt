@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.legado.app.data.entities

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import kotlin.time.Clock
import kotlinx.serialization.Serializable


@Serializable
@Entity(tableName = "search_keywords", indices = [(Index(value = ["word"], unique = true))])
data class SearchKeyword(
    /** 搜索关键词 */
    @PrimaryKey
    var word: String = "",
    /** 使用次数 */
    var usage: Int = 1,
    /** 最后一次使用时间 */
    var lastUseTime: Long = Clock.System.now().toEpochMilliseconds()
)
