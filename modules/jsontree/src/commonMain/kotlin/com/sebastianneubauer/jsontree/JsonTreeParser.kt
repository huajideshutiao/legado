package com.sebastianneubauer.jsontree

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.sebastianneubauer.jsontree.JsonTreeElement.Collapsable.Array
import com.sebastianneubauer.jsontree.JsonTreeElement.Collapsable.Object
import com.sebastianneubauer.jsontree.JsonTreeElement.EndBracket
import com.sebastianneubauer.jsontree.JsonTreeElement.ParentType
import com.sebastianneubauer.jsontree.JsonTreeElement.Primitive
import com.sebastianneubauer.jsontree.JsonTreeParserState.Loading
import com.sebastianneubauer.jsontree.JsonTreeParserState.Parsing.Error
import com.sebastianneubauer.jsontree.JsonTreeParserState.Parsing.Parsed
import com.sebastianneubauer.jsontree.JsonTreeParserState.Ready
import com.sebastianneubauer.jsontree.util.Expansion
import com.sebastianneubauer.jsontree.util.IdGenerator
import com.sebastianneubauer.jsontree.util.collapse
import com.sebastianneubauer.jsontree.util.expand
import com.sebastianneubauer.jsontree.util.toJsonTreeElement
import com.sebastianneubauer.jsontree.util.toList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * 宽松解析口径: 手写 JSON 常带尾逗号与注释, 与宿主全仓导入口径 (lenient + 注释 + 尾逗号) 同容错。
 */
@OptIn(ExperimentalSerializationApi::class)
private val lenientJson = Json {
    isLenient = true
    allowComments = true
    allowTrailingComma = true
}

internal class JsonTreeParser(
    private val json: String,
    /**
     * 已解析的根节点: 非空时跳过 [json] 的解析直接建树 (如宿主持有多棵解析产物的子树直通场景);
     * 空时从 [json] 解析。
     */
    private val jsonElement: JsonElement? = null,
    private val defaultDispatcher: CoroutineDispatcher,
    private val mainDispatcher: CoroutineDispatcher,
) {
    private var parserState = mutableStateOf<JsonTreeParserState>(Loading)
    val state: State<JsonTreeParserState> = parserState

    suspend fun init(initialState: TreeState) = withContext(defaultDispatcher) {
        val parsingState = runCatching {
            Parsed(jsonElement ?: lenientJson.parseToJsonElement(json))
        }.getOrElse { throwable ->
            Error(throwable)
        }

        val state = when (parsingState) {
            is Parsed -> {
                Ready(
                    list = parsingState.jsonElement
                        .toJsonTreeElement(
                            idGenerator = IdGenerator(),
                            state = initialState,
                            level = 0,
                            key = null,
                            isLastItem = true,
                            parentType = ParentType.NONE
                        ).toList(),
                    jsonElement = parsingState.jsonElement,
                )
            }
            is Error -> parsingState
        }

        withContext(mainDispatcher) {
            parserState.value = state
        }
    }

    suspend fun expandOrCollapseItem(
        item: JsonTreeElement,
        expandSingleChildren: Boolean
    ) = withContext(defaultDispatcher) {
        val state = parserState.value
        check(state is Ready)

        val newList = when (item) {
            is Primitive -> error("Primitive can't be clicked")
            is EndBracket -> error("EndBracket can't be clicked")
            is Array -> {
                when (item.state) {
                    TreeState.COLLAPSED -> state.list.expandItem(item, expandSingleChildren)
                    TreeState.EXPANDED,
                    TreeState.FIRST_ITEM_EXPANDED -> state.list.collapseItem(item)
                }
            }
            is Object -> {
                when (item.state) {
                    TreeState.COLLAPSED -> state.list.expandItem(item, expandSingleChildren)
                    TreeState.EXPANDED,
                    TreeState.FIRST_ITEM_EXPANDED -> state.list.collapseItem(item)
                }
            }
        }

        withContext(mainDispatcher) {
            parserState.value = Ready(list = newList, jsonElement = state.jsonElement)
        }
    }

    private fun List<JsonTreeElement>.collapseItem(
        item: JsonTreeElement.Collapsable
    ): List<JsonTreeElement> {
        return toMutableList().apply {
            val newItem = item.collapse()
            val itemIndex = indexOfFirst { it.id == item.id }
            val endBracketIndex = indexOfFirst { it.id == item.endBracket.id }
            subList(itemIndex, endBracketIndex + 1).clear()
            add(itemIndex, newItem)
        }
    }

    private fun List<JsonTreeElement>.expandItem(
        item: JsonTreeElement.Collapsable,
        expandSingleChildren: Boolean
    ): List<JsonTreeElement> {
        return toMutableList().apply {
            val newItems = item
                .expand(expansion = if (expandSingleChildren) Expansion.SingleOnly else Expansion.None)
                .toList()

            val itemIndex = indexOfFirst { it.id == item.id }
            removeAt(itemIndex)
            addAll(itemIndex, newItems)
        }
    }
}
