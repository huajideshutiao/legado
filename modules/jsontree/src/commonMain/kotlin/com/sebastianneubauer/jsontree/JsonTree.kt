package com.sebastianneubauer.jsontree

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import com.sebastianneubauer.jsontree.toJsonPath
import com.sebastianneubauer.jsontree.util.subtreeAt
import com.sebastianneubauer.jsontree.util.rememberCollapsableText
import com.sebastianneubauer.jsontree.util.rememberPrimitiveText
import com.sebastianneubauer.jsontree.generated.resources.Res
import com.sebastianneubauer.jsontree.generated.resources.jsontree_arrow_right
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.compose.resources.vectorResource

/**
 * Tree item reported by [JsonTree]'s long-press and right-click callbacks.
 *
 * @param path JSONPath of the item.
 * @param key Key of the item; `null` for the root item.
 * @param value Raw value of a primitive item; `null` for collapsable items.
 * @param quotedValue Value as rendered (strings wrapped in quotes), usable as a JSON snippet.
 * @param subtreeElement Subtree (incl. itself) of the parsed source, shared reference; `null` for
 * primitive items.
 *
 * 按引用相等而非结构相等: [subtreeElement] 可能是一棵很大的树, 而 kotlinx 的 `JsonObject.equals`
 * 是整树深比较 (无同一性短路, 实测 400KB 树约 0.8~3ms)。本类作为 Compose 参数参与相等性判断,
 * 若按结构比较, 每次重组都要比较整棵树。
 */
@Immutable
public class JsonTreeItem(
    public val path: String,
    public val key: String?,
    public val value: String?,
    public val quotedValue: String?,
    public val subtreeElement: JsonElement? = null,
)

/**
 * Renders JSON data as a formatted tree with collapsable objects and arrays.
 * Collapsed items display the amount of child items inside them.
 *
 * @param json The json data as a string.
 * @param jsonElement Already-parsed root node: when non-null it is used instead of parsing [json]
 * (which is then only display/copy text), enabling zero-reparse rendering of a subtree.
 * Its identity must be determined by ([json], [pathPrefix]): the tree rebuilds only when those
 * change, because [jsonElement] itself cannot be a cache key (comparing it is a whole-tree deep
 * compare).
 * @param onLoading A Composable which is shown during the initial loading of the tree.
 * @param modifier The Modifier for this Composable.
 * @param initialState The initial state of the tree before user interaction. One of [TreeState].
 * @param contentPadding The content padding for the scrollable container.
 * @param colors The color palette the tree uses. [defaultLightColors], [defaultDarkColors] or a
 * custom instance of [TreeColors].
 * @param icon The icon which is shown in front of collapsable items. Default value is an arrow icon.
 * @param iconSize The size of the [icon]. This size is also used to calculate indents.
 * @param textStyle The style which is used for all texts in the tree.
 * @param showIndices If true, arrays will show the index in front of each item.
 * @param showItemCount If true, arrays and objects will show their amount of child items when collapsed.
 * @param expandSingleChildren If true, children of collapsable items that have no siblings will be
 * automatically expanded with their parent.
 * @param lazyListState The `LazyListState` which is used for the JsonTree list.
 * @param onError A callback which is called when the json can't be parsed and thus won't
 * be rendered. Receives the throwable of the error.
 * @param itemMenu Content of a per-item context menu (opened by long-press or right-click),
 * rendered inside a material DropdownMenu anchored to the pressed row itself, so positioning
 * (below the row, flipping above when it does not fit) and the enter/exit animation are the
 * official ones. The slot receives the pressed [JsonTreeItem] and a dismiss callback. Brackets
 * have no menu. `null` disables item menus.
 * @param showRowIndication Whether rows show the press indication from [LocalIndication]
 * (material ripple). Hosts on E-Ink displays pass `false`: the ripple leaves ghosting on e-paper,
 * where this app disables animation everywhere else too.
 * @param pathPrefix JSONPath of the root node of [jsonElement] (`"$"` for a whole document).
 * Prepended to every item path, so a subtree rendered on its own still reports and copies
 * paths relative to the original document.
 * @param onRootParsed Called on the main thread after the whole document was parsed from [json]
 * (not on subtree-pass-through renders), with the parsed root. Hosts use it to drill into any
 * path later (e.g. breadcrumb focus jumps) without re-parsing.
 */
@Composable
public fun JsonTree(
    json: String,
    jsonElement: JsonElement? = null,
    onLoading: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    initialState: TreeState = TreeState.FIRST_ITEM_EXPANDED,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    colors: TreeColors = defaultLightColors,
    icon: ImageVector = vectorResource(Res.drawable.jsontree_arrow_right),
    iconSize: Dp = 20.dp,
    textStyle: TextStyle = LocalTextStyle.current,
    showIndices: Boolean = false,
    showItemCount: Boolean = true,
    expandSingleChildren: Boolean = false,
    lazyListState: LazyListState = rememberLazyListState(),
    onError: (Throwable) -> Unit = {},
    itemMenu: (@Composable ColumnScope.(JsonTreeItem, () -> Unit) -> Unit)? = null,
    showRowIndication: Boolean = true,
    pathPrefix: String = "$",
    onRootParsed: ((JsonElement) -> Unit)? = null,
) {
    val coroutineScope = rememberCoroutineScope()

    // 缓存键只用 json 与 pathPrefix (都是廉价可比较的 String): jsonElement 是整树深比较,
    // 不能入键; 按本参数契约, 同一 (json, pathPrefix) 下的根节点唯一, 故无需它入键。
    val jsonParser = remember(json, pathPrefix) {
        JsonTreeParser(
            json = json,
            jsonElement = jsonElement,
            defaultDispatcher = Dispatchers.Default,
            mainDispatcher = Dispatchers.Main
        )
    }

    LaunchedEffect(jsonParser, initialState) {
        jsonParser.init(initialState)
        // 仅整文档解析时回调根 (子树直通渲染时 jsonElement 非空, 不回调),
        // 供宿主对任意前缀路径下钻 (面包屑聚焦跳转), 零重复解析
        if (jsonElement == null) {
            (jsonParser.state.value as? JsonTreeParserState.Ready)?.let { state ->
                onRootParsed?.invoke(state.jsonElement)
            }
        }
    }

    when (val state = jsonParser.state.value) {
        is JsonTreeParserState.Ready -> {
            Box(modifier = modifier) {
                JsonTreeList(
                    state = state,
                    contentPadding = contentPadding,
                    colors = colors,
                    icon = icon,
                    iconSize = iconSize,
                    textStyle = textStyle,
                    showIndices = showIndices,
                    showItemCount = showItemCount,
                    lazyListState = lazyListState,
                    itemMenu = itemMenu,
                    showRowIndication = showRowIndication,
                    pathPrefix = pathPrefix,
                    onClick = {
                        coroutineScope.launch {
                            jsonParser.expandOrCollapseItem(
                                item = it,
                                expandSingleChildren = expandSingleChildren
                            )
                        }
                    }
                )
            }
        }
        is JsonTreeParserState.Loading -> onLoading()
        is JsonTreeParserState.Parsing.Error -> onError(state.throwable)
        is JsonTreeParserState.Parsing.Parsed -> error("Unexpected state $state")
    }
}

@Composable
private fun JsonTreeList(
    state: JsonTreeParserState.Ready,
    contentPadding: PaddingValues,
    colors: TreeColors,
    icon: ImageVector,
    iconSize: Dp,
    textStyle: TextStyle,
    showIndices: Boolean,
    showItemCount: Boolean,
    lazyListState: LazyListState,
    itemMenu: (@Composable ColumnScope.(JsonTreeItem, () -> Unit) -> Unit)?,
    showRowIndication: Boolean,
    pathPrefix: String,
    onClick: (JsonTreeElement) -> Unit,
) {
    val items = state.list

    LazyColumn(
        state = lazyListState,
        contentPadding = contentPadding
    ) {
        itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
            // 路径文本随层级增长, 每行只算一次 (原先三个分支各算一次且每次重组重算)
            val path = remember(item.path, pathPrefix) {
                pathPrefix + item.path.toJsonPath().removePrefix("$")
            }
            when (item) {
                is JsonTreeElement.Collapsable.Array -> {
                    val coloredText = rememberCollapsableText(
                        type = CollapsableType.ARRAY,
                        key = item.key,
                        childItemCount = item.children.size,
                        state = item.state,
                        colors = colors,
                        isLastItem = item.isLastItem,
                        showIndices = showIndices,
                        showItemCount = showItemCount,
                        parentType = item.parentType,
                    )

                    Collapsable(
                        state = item.state,
                        text = coloredText,
                        indent = if (index == 0 || index == items.lastIndex) {
                            0.dp
                        } else {
                            item.level * iconSize
                        },
                        colors = colors,
                        textStyle = textStyle,
                        icon = icon,
                        iconSize = iconSize,
                        path = path,
                        key = item.key,
                        itemMenu = itemMenu,
                        showRowIndication = showRowIndication,
                        onClick = { onClick(item) },
                        subtreeElement = { state.jsonElement.subtreeAt(item.path) },
                    )
                }
                is JsonTreeElement.Collapsable.Object -> {
                    val coloredText = rememberCollapsableText(
                        type = CollapsableType.OBJECT,
                        key = item.key,
                        childItemCount = item.children.size,
                        state = item.state,
                        colors = colors,
                        isLastItem = item.isLastItem,
                        showIndices = showIndices,
                        showItemCount = showItemCount,
                        parentType = item.parentType
                    )

                    Collapsable(
                        state = item.state,
                        text = coloredText,
                        indent = if (index == 0 || index == items.lastIndex) {
                            0.dp
                        } else {
                            item.level * iconSize
                        },
                        colors = colors,
                        textStyle = textStyle,
                        icon = icon,
                        iconSize = iconSize,
                        path = path,
                        key = item.key,
                        itemMenu = itemMenu,
                        showRowIndication = showRowIndication,
                        onClick = { onClick(item) },
                        subtreeElement = { state.jsonElement.subtreeAt(item.path) },
                    )
                }
                is JsonTreeElement.Primitive -> {
                    val coloredText = rememberPrimitiveText(
                        key = item.key,
                        value = item.value,
                        type = item.type,
                        colors = colors,
                        isLastItem = item.isLastItem,
                        showIndices = showIndices,
                        parentType = item.parentType
                    )

                    // quotedValue 需为可直接粘贴的合法 JSON 字面量: 值内容经 JSON 转义规则处理
                    val quotedValue = when (item.type) {
                        JsonTreeElement.Primitive.Type.STRING -> JsonPrimitive(item.value).toString()
                        else -> item.value
                    }

                    Primitive(
                        text = coloredText,
                        textStyle = textStyle,
                        indent = if (index == 0 || index == items.lastIndex) {
                            0.dp
                        } else {
                            (item.level * iconSize) + iconSize
                        },
                        path = path,
                        key = item.key,
                        value = item.value,
                        quotedValue = quotedValue,
                        itemMenu = itemMenu,
                        showRowIndication = showRowIndication,
                        iconSize = iconSize,
                    )
                }
                is JsonTreeElement.EndBracket -> {
                    Bracket(
                        type = item.type,
                        colors = colors,
                        textStyle = textStyle,
                        indent = if (index == 0 || index == items.lastIndex) {
                            iconSize
                        } else {
                            (item.level * iconSize) + iconSize
                        },
                        isLastItem = item.isLastItem
                    )
                }
            }
        }
    }
}

@Composable
private fun Collapsable(
    state: TreeState,
    text: AnnotatedString,
    indent: Dp,
    colors: TreeColors,
    textStyle: TextStyle,
    icon: ImageVector,
    iconSize: Dp,
    path: String,
    key: String?,
    value: String? = null,
    quotedValue: String? = null,
    itemMenu: (@Composable ColumnScope.(JsonTreeItem, () -> Unit) -> Unit)?,
    showRowIndication: Boolean,
    onClick: () -> Unit,
    subtreeElement: () -> JsonElement?,
) {
    TreeRow(
        indent = indent,
        icon = icon,
        iconSize = iconSize,
        iconTint = colors.iconColor,
        iconRotationDegrees = if (state == TreeState.COLLAPSED) 0F else 90F,
        text = text,
        textStyle = textStyle,
        showRowIndication = showRowIndication,
        onClick = onClick,
        // payload 构造在菜单组合期执行: subtreeElement 的下钻只在菜单打开时发生 (惰性保持)
        menu = if (itemMenu != null) {
            { dismiss ->
                itemMenu(JsonTreeItem(path, key, value, quotedValue, subtreeElement()), dismiss)
            }
        } else {
            null
        },
    )
}

@Composable
private fun Primitive(
    text: AnnotatedString,
    textStyle: TextStyle,
    indent: Dp,
    path: String,
    key: String?,
    value: String? = null,
    quotedValue: String? = null,
    itemMenu: (@Composable ColumnScope.(JsonTreeItem, () -> Unit) -> Unit)?,
    showRowIndication: Boolean,
    iconSize: Dp,
) {
    TreeRow(
        indent = indent,
        icon = null,
        iconSize = iconSize,
        iconTint = Color.Unspecified,
        iconRotationDegrees = 0F,
        text = text,
        textStyle = textStyle,
        showRowIndication = showRowIndication,
        // 原始值行无主键动作, 仅长按/右键菜单
        onClick = {},
        // 上游行为: 原始值行无图标占位, 文本直接从缩进起点排
        iconSpace = false,
        menu = if (itemMenu != null) {
            { dismiss -> itemMenu(JsonTreeItem(path, key, value, quotedValue), dismiss) }
        } else {
            null
        },
    )
}

@Composable
private fun Bracket(
    type: JsonTreeElement.EndBracket.Type,
    isLastItem: Boolean,
    indent: Dp,
    colors: TreeColors,
    textStyle: TextStyle,
) {
    val closingBracket = if (type == JsonTreeElement.EndBracket.Type.OBJECT) "}" else "]"

    Text(
        modifier = Modifier.padding(start = indent),
        text = if (!isLastItem) "$closingBracket," else closingBracket,
        color = colors.symbolColor,
        style = textStyle
    )
}
