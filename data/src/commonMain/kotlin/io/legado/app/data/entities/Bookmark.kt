@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.legado.app.data.entities

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import kotlin.time.Clock
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "bookmarks",
    indices = [(Index(value = ["bookName", "bookAuthor"], unique = false))]
)
data class Bookmark(
    @PrimaryKey
    val time: Long = Clock.System.now().toEpochMilliseconds(),
    val bookName: String = "",
    val bookAuthor: String = "",
    var chapterIndex: Int = 0,
    var chapterPos: Int = 0,
    var chapterName: String = "",
    var bookText: String = "",
    var content: String = "",
    /** 0=书签 1=批注; 书签旧备份 json 无此字段, 默认值保证反序列化兼容 */
    @ColumnInfo(defaultValue = "0")
    var type: Int = 0,
    /** 批注终点章内字符偏移(半开区间, 口径同 TextLine.chapterPosition); 书签恒 0 */
    @ColumnInfo(defaultValue = "0")
    var endPos: Int = 0,
    /** 上色颜色 (ARGB, 0x50 半透明色块); null = 不上色 (默认); 书签恒 null */
    @ColumnInfo(defaultValue = "0")
    var color: Int? = null,
    /** 线型 (阅读层 HighlightLineStyle): 0 无线, 1 下划线 (默认), 2 波浪线, 3 删除线 */
    @ColumnInfo(defaultValue = "0")
    var lineStyle: Int = 1
) {
    companion object {
        const val TYPE_BOOKMARK = 0
        const val TYPE_UNDERLINE = 1
    }
}