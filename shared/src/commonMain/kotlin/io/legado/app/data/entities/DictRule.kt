package io.legado.app.data.entities

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 字典规则
 *
 * 已从 jvmAndAndroidMain 下沉 commonMain。
 * search 方法依赖 AnalyzeUrlCore/AnalyzeRuleCore (jvmAndAndroidMain),
 * 已抽取到 jvmAndAndroidMain/DictRuleExt.kt 作为扩展函数。
 */
@Serializable
@Entity(tableName = "dictRules")
data class DictRule(
    @PrimaryKey
    var name: String = "",
    var urlRule: String = "",
    var showRule: String = "",
    @ColumnInfo(defaultValue = "1")
    var enabled: Boolean = true,
    @ColumnInfo(defaultValue = "0")
    var sortNumber: Int = 0
) {

    // 不覆写 equals/hashCode: 只比 name 会让改 urlRule/showRule/enabled 发射不出去;
    // 实例禁止作 HashSet 元素 / HashMap key

}
