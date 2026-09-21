@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.legado.app.data.entities

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import kotlinx.serialization.Serializable
import kotlin.time.Clock


@Serializable
@Entity(tableName = "txtTocRules")
data class TxtTocRule(
    @PrimaryKey
    var id: Long = Clock.System.now().toEpochMilliseconds(),
    var name: String = "",
    var rule: String = "",
    var example: String? = null,
    var serialNumber: Int = -1,
    var enable: Boolean = true
) {

    // 不覆写 equals/hashCode: 理由同 ReplaceRule (只比 id 会吞掉内容变更,
    // 且导入时的整体比对 it != local 会恒为 false); 实例禁止作 HashSet/HashMap key

}