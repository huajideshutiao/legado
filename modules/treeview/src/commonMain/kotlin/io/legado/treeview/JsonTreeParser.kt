package io.legado.treeview

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import io.legado.treeview.JsonTreeElement.Collapsable.Array
import io.legado.treeview.JsonTreeElement.Collapsable.Object
import io.legado.treeview.JsonTreeElement.EndBracket
import io.legado.treeview.JsonTreeElement.ParentType
import io.legado.treeview.JsonTreeElement.Primitive
import io.legado.treeview.JsonTreeParserState.Loading
import io.legado.treeview.JsonTreeParserState.Parsing.Error
import io.legado.treeview.JsonTreeParserState.Ready
import io.legado.treeview.util.Expansion
import io.legado.treeview.util.IdGenerator
import io.legado.treeview.util.collapse
import io.legado.treeview.util.expand
import io.legado.treeview.util.toJsonTreeElement
import io.legado.treeview.util.toList
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
        // 解析与建树整段兜底: 建树含 O(n·depth) 的路径构造, 超深/超大文档可能 OOM/StackOverflow,
        // 漏到协程外会直接冒到平台默认异常处理器 (Android 崩)。宿主已有 onError 展示通道。
        val state = runCatching {
            val element = jsonElement ?: lenientJson.parseToJsonElement(json)
            Ready(
                list = element
                    .toJsonTreeElement(
                        idGenerator = IdGenerator(),
                        state = initialState,
                        level = 0,
                        key = null,
                        isLastItem = true,
                        parentType = ParentType.NONE
                    ).toList(),
                jsonElement = element,
            )
        }.getOrElse { throwable ->
            Error(throwable)
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
