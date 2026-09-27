package com.sebastianneubauer.jsontree.util

import com.sebastianneubauer.jsontree.JsonTreeElement
import com.sebastianneubauer.jsontree.JsonTreeElement.Collapsable.Array
import com.sebastianneubauer.jsontree.JsonTreeElement.Collapsable.Object
import com.sebastianneubauer.jsontree.JsonTreeElement.EndBracket
import com.sebastianneubauer.jsontree.JsonTreeElement.ParentType
import com.sebastianneubauer.jsontree.JsonTreeElement.Primitive
import com.sebastianneubauer.jsontree.JsonTreeElement.Primitive.Type
import com.sebastianneubauer.jsontree.PathSegment
import com.sebastianneubauer.jsontree.TreeState
import com.sebastianneubauer.jsontree.endBracket
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * String 的 JSON 转义内容 (不含首尾引号)。转义规则交由 kotlinx 的 JSON 字符串编码路径
 * ([JsonPrimitive.toString] 内部 printQuoted), 覆盖引号/反斜杠/控制字符/代理对全量规则。
 */
internal fun String.toJsonEscaped(): String = JsonPrimitive(this).toString().removeSurrounding("\"")

internal enum class Expansion {
    /**
     * No children are expanded.
     */
    None,

    /**
     * All children are expanded.
     */
    All,

    /**
     * Only children without siblings are expanded.
     */
    SingleOnly
}

/**
 * Expands a JsonTreeElement and its children depending on which [expansion] is chosen.
 *
 * `Expansion.None` -> Children will not be expanded.
 *
 * `Expansion.All` -> Children will be expanded recursively.
 *
 * `Expansion.SingleOnly` -> Only children without siblings will be expanded.
 */
internal fun JsonTreeElement.expand(
    expansion: Expansion,
): JsonTreeElement {
    return when (this) {
        is Array -> this.copy(
            state = TreeState.EXPANDED,
            children = when (expansion) {
                Expansion.None -> children
                Expansion.All -> children.expandChildren(singleChildrenOnly = false)
                Expansion.SingleOnly -> children.expandChildren(singleChildrenOnly = true)
            }
        )

        is Object -> this.copy(
            state = TreeState.EXPANDED,
            children = when (expansion) {
                Expansion.None -> children
                Expansion.All -> children.expandChildren(singleChildrenOnly = false)
                Expansion.SingleOnly -> children.expandChildren(singleChildrenOnly = true)
            }
        )

        is Primitive,
        is EndBracket -> this
    }
}

private fun Map<String, JsonTreeElement>.expandChildren(
    singleChildrenOnly: Boolean
): Map<String, JsonTreeElement> {
    return if (singleChildrenOnly && this.size > 1) {
        this
    } else {
        mapValues {
            when (val child = it.value) {
                is Primitive -> child
                is EndBracket -> child
                is Array -> {
                    if (child.state == TreeState.COLLAPSED) {
                        child.copy(
                            state = TreeState.EXPANDED,
                            children = child.children.expandChildren(singleChildrenOnly)
                        )
                    } else {
                        child
                    }
                }
                is Object -> {
                    if (child.state == TreeState.COLLAPSED) {
                        child.copy(
                            state = TreeState.EXPANDED,
                            children = child.children.expandChildren(singleChildrenOnly)
                        )
                    } else {
                        child
                    }
                }
            }
        }
    }
}

/**
 * Collapses a JsonTreeElement and all its children.
 */
internal fun JsonTreeElement.collapse(): JsonTreeElement {
    return when (this) {
        is Array -> this.copy(
            state = TreeState.COLLAPSED,
            children = children.collapseChildren()
        )

        is Object -> this.copy(
            state = TreeState.COLLAPSED,
            children = children.collapseChildren()
        )

        is Primitive,
        is EndBracket -> this
    }
}

private fun Map<String, JsonTreeElement>.collapseChildren(): Map<String, JsonTreeElement> {
    return mapValues {
        when (val child = it.value) {
            is Primitive -> child
            is EndBracket -> child
            is Array -> {
                if (child.state != TreeState.COLLAPSED) {
                    child.copy(
                        state = TreeState.COLLAPSED,
                        children = child.children.collapseChildren()
                    )
                } else {
                    child
                }
            }
            is Object -> {
                if (child.state != TreeState.COLLAPSED) {
                    child.copy(
                        state = TreeState.COLLAPSED,
                        children = child.children.collapseChildren()
                    )
                } else {
                    child
                }
            }
        }
    }
}

/**
 * Converts a JsonTreeElement into a list which can be rendered.
 */
internal fun JsonTreeElement.toList(): List<JsonTreeElement> {
    val list = mutableListOf<JsonTreeElement>()

    fun addToList(element: JsonTreeElement) {
        when (element) {
            is EndBracket -> list.add(element)
            is Primitive -> list.add(element)
            is Array -> {
                list.add(element)
                if (element.state != TreeState.COLLAPSED) {
                    element.children.forEach {
                        addToList(it.value)
                    }
                    list.add(element.endBracket)
                }
            }
            is Object -> {
                list.add(element)
                if (element.state != TreeState.COLLAPSED) {
                    element.children.forEach {
                        addToList(it.value)
                    }
                    list.add(element.endBracket)
                }
            }
        }
    }

    addToList(this)
    return list
}

/**
 * Converts a [JsonElement] to a [JsonTreeElement].
 */
internal fun JsonElement.toJsonTreeElement(
    idGenerator: IdGenerator,
    state: TreeState,
    level: Int,
    key: String?,
    isLastItem: Boolean,
    parentType: ParentType,
    path: List<PathSegment> = emptyList(),
): JsonTreeElement {
    return when (this) {
        is JsonPrimitive -> {
            Primitive(
                id = idGenerator.incrementAndGet().toString(),
                level = level,
                key = key,
                value = content,
                isLastItem = isLastItem,
                parentType = parentType,
                path = path,
                type = when {
                    isString -> Type.STRING
                    booleanOrNull != null -> Type.BOOLEAN
                    doubleOrNull != null ||
                            intOrNull != null ||
                            floatOrNull != null ||
                            longOrNull != null -> Type.NUMBER
                    else -> Type.OTHER
                },
            )
        }
        is JsonArray -> {
            val childElements = jsonArray.mapIndexed { index, item ->
                Pair(
                    index.toString(),
                    item.toJsonTreeElement(
                        idGenerator = idGenerator,
                        state = if (state == TreeState.FIRST_ITEM_EXPANDED) TreeState.COLLAPSED else state,
                        level = level + 1,
                        key = index.toString(),
                        isLastItem = index == (jsonArray.size - 1),
                        parentType = ParentType.ARRAY,
                        path = path + PathSegment.Index(index),
                    )
                )
            }
                .toMap()

            Array(
                id = idGenerator.incrementAndGet().toString(),
                level = level,
                state = state,
                key = key,
                children = childElements,
                isLastItem = isLastItem,
                parentType = parentType,
                path = path,
            )
        }
        is JsonObject -> {
            val entryList = jsonObject.entries.toList()
            val childElements = buildMap {
                entryList.forEachIndexed { entryIndex, entry ->
                    put(
                        entry.key,
                        entry.value.toJsonTreeElement(
                            idGenerator = idGenerator,
                            state = if (state == TreeState.FIRST_ITEM_EXPANDED) TreeState.COLLAPSED else state,
                            level = level + 1,
                            key = entry.key,
                            isLastItem = entryIndex == entryList.lastIndex,
                            parentType = ParentType.OBJECT,
                            path = path + PathSegment.Key(entry.key),
                        )
                    )
                }
            }

            Object(
                id = idGenerator.incrementAndGet().toString(),
                level = level,
                state = state,
                key = key,
                children = childElements,
                isLastItem = isLastItem,
                parentType = parentType,
                path = path,
            )
        }
    }
}
