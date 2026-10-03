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
    /** 0=书签 1=划线; 旧备份 json 无此字段, 默认值保证反序列化兼容 */
    @ColumnInfo(defaultValue = "0")
    var type: Int = 0,
    /** 划线终点章内字符偏移(半开区间, 口径同 TextLine.chapterPosition); 书签恒 0 */
    @ColumnInfo(defaultValue = "0")
    var endPos: Int = 0,
    /** 色档索引(见阅读层 HighlightPalette); 旧数据/书签恒 0 */
    @ColumnInfo(defaultValue = "0")
    var colorIndex: Int = 0
) {
    companion object {
        const val TYPE_BOOKMARK = 0
        const val TYPE_UNDERLINE = 1
    }
}