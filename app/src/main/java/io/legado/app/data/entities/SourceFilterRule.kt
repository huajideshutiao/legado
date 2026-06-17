package io.legado.app.data.entities

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.legado.app.utils.splitNotBlank
import kotlinx.parcelize.Parcelize
import java.util.UUID

/**
 * 搜索 / 发现共用的结果过滤规则。
 * - fields：作用字段 NAME / AUTHOR / INTRO / KIND / WORD_COUNT，逗号分隔，至少一项；
 *   任一字段被正则命中即视为规则命中，规则命中即丢弃；
 * - scope：作用范围，沿用 SearchScope 字符串协议：
 *   空 → 全部书源；"g1,g2" → 分组；"name::url" 或 "n1::u1|n2::u2" → 单/多书源。
 */
@Parcelize
@Entity(tableName = "source_filter_rules")
data class SourceFilterRule(
    @PrimaryKey
    var id: String = UUID.randomUUID().toString(),
    @ColumnInfo(defaultValue = "")
    var name: String = "",
    @ColumnInfo(defaultValue = "1")
    var enabled: Boolean = true,
    @ColumnInfo(defaultValue = "")
    var pattern: String = "",
    @ColumnInfo(defaultValue = "")
    var fields: String = "",
    @ColumnInfo(defaultValue = "")
    var scope: String = "",
    @ColumnInfo(name = "sortOrder", defaultValue = "0")
    var order: Int = 0,
    @ColumnInfo(defaultValue = "0")
    var createTime: Long = System.currentTimeMillis(),
) : Parcelable {

    enum class Field { NAME, AUTHOR, INTRO, KIND, WORD_COUNT }

    /** 解析后的作用范围。`None` 表示原串非空但无效，规则不生效。 */
    sealed interface Scope {
        data object All : Scope
        data object None : Scope
        data class Source(val url: String) : Scope
        data class Groups(val names: Set<String>) : Scope
    }

    companion object {

        fun parseFields(raw: String): Set<Field> {
            if (raw.isBlank()) return emptySet()
            return raw.splitNotBlank(",").mapNotNullTo(HashSet()) { token ->
                runCatching { Field.valueOf(token.trim()) }.getOrNull()
            }
        }

        fun formatFields(fields: Collection<Field>): String =
            fields.joinToString(",") { it.name }

        fun parseScope(raw: String): Scope {
            if (raw.isBlank()) return Scope.All
            if (raw.contains("::")) {
                val url = raw.substringAfter("::", "")
                return if (url.isEmpty()) Scope.None else Scope.Source(url)
            }
            val groups = raw.splitNotBlank(",")
                .mapNotNullTo(HashSet()) { it.trim().takeIf(String::isNotEmpty) }
            return if (groups.isEmpty()) Scope.None else Scope.Groups(groups)
        }
    }
}
