package com.sebastianneubauer.jsontree

import androidx.compose.runtime.Immutable

/**
 * 从根到某个节点的一段路径。
 *
 * 数组下标与对象键名分开表达: 前者拼 `[n]`, 后者按 [toJsonPath] 的规则拼接。
 */
internal sealed interface PathSegment {
    data class Key(val name: String) : PathSegment
    data class Index(val idx: Int) : PathSegment
}

/**
 * 把路径段序列拼成 JSONPath 文本。
 *
 * 键名默认用点号写法 (`$.a.b`)。以下字符会让点号写法解析成别的路径或取不到值
 * (逐字符在 rjpath 上实测), 出现其一即整段改用方括号字面量:
 *
 * - `.` 被当作层级分隔符;
 * - `*` 被当作通配符;
 * - `[` / `]` 被当作下标或切片语法;
 * - `'` / `"` 切换引号态, 奇数个会把后续分隔符一并吞掉;
 * - `\\` 进入转义态, 把紧随的分隔符当普通字符吃掉;
 * - `$` 出现在非首位时报错。
 *
 * 方括号用哪种引号包裹: 键名含 `"` 但不含 `'` 时用单引号, 否则用双引号。
 * 因为 rjpath 对两种引号都不做转义解码, 只能挑键名里没出现的那种作边界;
 * 两种引号都出现时无解 (实测 86 例中仅此类与单个 `\\` 键名共 3 例无法回查),
 * 见同包 VENDORED.md 的已知限制。
 */
internal fun List<PathSegment>.toJsonPath(): String {
    val sb = StringBuilder("$")
    for (segment in this) {
        when (segment) {
            is PathSegment.Index -> sb.append('[').append(segment.idx).append(']')
            is PathSegment.Key -> {
                val name = segment.name
                if (name.isEmpty() || name.any { it in DOT_UNSAFE_CHARS }) {
                    val quote = if ('"' in name && '\'' !in name) '\'' else '"'
                    sb.append('[').append(quote).append(name).append(quote).append(']')
                } else {
                    sb.append('.').append(name)
                }
            }
        }
    }
    return sb.toString()
}

private val DOT_UNSAFE_CHARS = charArrayOf('.', '*', '[', ']', '\'', '"', '\\', '$')

@Immutable
internal sealed interface JsonTreeElement {
    val id: String
    val level: Int
    val isLastItem: Boolean

    /**
     * 从根到本节点的路径段。根节点为空列表。
     *
     * 折叠/展开只改变渲染列表, 不改本字段, 所以折叠状态下长按仍能拿到完整路径。
     */
    val path: List<PathSegment>

    enum class ParentType { NONE, ARRAY, OBJECT }

    data class Primitive(
        override val id: String,
        override val level: Int,
        override val isLastItem: Boolean,
        val key: String?,
        val type: Type,
        val value: String,
        val parentType: ParentType,
        override val path: List<PathSegment> = emptyList(),
    ) : JsonTreeElement {
        enum class Type { STRING, BOOLEAN, NUMBER, OTHER }
    }

    @Immutable
    sealed interface Collapsable : JsonTreeElement {
        val state: TreeState
        val children: Map<String, JsonTreeElement>

        data class Object(
            override val id: String,
            override val level: Int,
            override val state: TreeState,
            override val children: Map<String, JsonTreeElement>,
            override val isLastItem: Boolean,
            val key: String?,
            val parentType: ParentType,
            override val path: List<PathSegment> = emptyList(),
        ) : Collapsable

        data class Array(
            override val id: String,
            override val level: Int,
            override val state: TreeState,
            override val children: Map<String, JsonTreeElement>,
            override val isLastItem: Boolean,
            val key: String?,
            val parentType: ParentType,
            override val path: List<PathSegment> = emptyList(),
        ) : Collapsable
    }

    data class EndBracket(
        override val id: String,
        override val level: Int,
        override val isLastItem: Boolean,
        val type: Type,
        override val path: List<PathSegment> = emptyList(),
    ) : JsonTreeElement {
        enum class Type { ARRAY, OBJECT }
    }
}

internal val JsonTreeElement.Collapsable.endBracket: JsonTreeElement.EndBracket
    get() = JsonTreeElement.EndBracket(
        id = "$id-b",
        level = level,
        isLastItem = isLastItem,
        path = path,
        type = when (this) {
            is JsonTreeElement.Collapsable.Object -> JsonTreeElement.EndBracket.Type.OBJECT
            is JsonTreeElement.Collapsable.Array -> JsonTreeElement.EndBracket.Type.ARRAY
        }
    )
