package io.legado.app.data.entities

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import kotlinx.serialization.Serializable

@Suppress("ConstPropertyName")
@Serializable
@Entity(tableName = "book_groups")
data class BookGroup(
    @PrimaryKey
    val groupId: Long = 0b1,
    var groupName: String = "",
    var cover: String? = null,
    var order: Int = 0,
    @ColumnInfo(defaultValue = "1")
    var enableRefresh: Boolean = true,
    @ColumnInfo(defaultValue = "1")
    var show: Boolean = true,
    @ColumnInfo(defaultValue = "-1")
    var bookSort: Int = -1
) {

    companion object {
        const val IdRoot = -100L
        const val IdAll = -1L
        const val IdLocal = -2L

        /** 未分组（合并了原网络未分组 -4 与本地未分组 -5，沿用 -4） */
        const val IdUngrouped = -4L
        const val IdError = -11L
    }

    // 不覆写 equals/hashCode: 原手写 equals 比的正好是全部 7 个构造参数, 与 data class 生成版等价;
    // 字段是 var 且 GroupEditDialog 原地改, 结构 hashCode 会漂移, 故实例禁止作 HashSet 元素 / HashMap key

}
