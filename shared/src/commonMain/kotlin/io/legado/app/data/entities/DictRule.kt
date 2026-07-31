package io.legado.app.data.entities

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 字典规则
 *
 * K5-c Phase 4: 已从 jvmAndAndroidMain 下沉 commonMain。
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

    override fun hashCode(): Int {
        return name.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (other is DictRule) {
            return name == other.name
        }
        return false
    }

}
