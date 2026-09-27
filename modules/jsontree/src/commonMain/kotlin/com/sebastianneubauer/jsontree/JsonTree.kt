package com.sebastianneubauer.jsontree

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.Icon
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import com.sebastianneubauer.jsontree.toJsonPath
import com.sebastianneubauer.jsontree.util.rememberCollapsableText
import com.sebastianneubauer.jsontree.util.rememberPrimitiveText
import com.sebastianneubauer.jsontree.generated.resources.Res
import com.sebastianneubauer.jsontree.generated.resources.jsontree_arrow_right
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.compose.resources.vectorResource
import kotlin.math.roundToInt

/**
 * Tree item reported by [JsonTree]'s long-press and right-click callbacks.
 *
 * @param path JSONPath of the item.
 * @param key Key of the item; `null` for the root item.
 * @param value Raw value of a primitive item; `null` for collapsable items.
 * @param quotedValue Value as rendered (strings wrapped in quotes), usable as a JSON snippet.
 * @param offsetInWindow Press position (right-click) or row position (long-press) in window coordinates.
 */
public data class JsonTreeItem(
    public val path: String,
    public val key: String?,
    public val value: String?,
    public val quotedValue: String?,
    public val offsetInWindow: IntOffset,
)

/**
 * Renders JSON data as a formatted tree with collapsable objects and arrays.
 * Collapsed items display the amount of child items inside them.
 *
 * @param json The json data as a string.
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
 * @param onItemLongClick A callback which receives the long-pressed item as [JsonTreeItem].
 * Collapsable and primitive items both report; brackets are not clickable.
 * @param onItemContextMenu A callback which receives the right-pressed item as [JsonTreeItem],
 * with the press position in [JsonTreeItem.offsetInWindow]. Brackets are not right-clickable.
 */
@Composable
public fun JsonTree(
    json: String,
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
    onItemLongClick: (JsonTreeItem) -> Unit = {},
    onItemContextMenu: (JsonTreeItem) -> Unit = {},
) {
    val coroutineScope = rememberCoroutineScope()

    val jsonParser = remember(json) {
        JsonTreeParser(
            json = json,
            defaultDispatcher = Dispatchers.Default,
            mainDispatcher = Dispatchers.Main
        )
    }

    LaunchedEffect(jsonParser, initialState) {
        jsonParser.init(initialState)
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
                    onItemLongClick = onItemLongClick,
                    onItemContextMenu = onItemContextMenu,
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
    onItemLongClick: (JsonTreeItem) -> Unit,
    onItemContextMenu: (JsonTreeItem) -> Unit,
    onClick: (JsonTreeElement) -> Unit,
) {
    val items = state.list

    LazyColumn(
        state = lazyListState,
        contentPadding = contentPadding
    ) {
        itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
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
                        path = item.path.toJsonPath(),
                        key = item.key,
                        onContextMenu = onItemContextMenu,
                        onLongClick = onItemLongClick,
                        onClick = { onClick(item) }
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
                        path = item.path.toJsonPath(),
                        key = item.key,
                        onContextMenu = onItemContextMenu,
                        onLongClick = onItemLongClick,
                        onClick = { onClick(item) }
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
                        path = item.path.toJsonPath(),
                        key = item.key,
                        value = item.value,
                        quotedValue = quotedValue,
                        onContextMenu = onItemContextMenu,
                        onLongClick = onItemLongClick,
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
    onContextMenu: (JsonTreeItem) -> Unit,
    onLongClick: (JsonTreeItem) -> Unit,
    onClick: () -> Unit,
) {
    val rowOffsetInWindow = remember { mutableStateOf(IntOffset.Zero) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indent)
            .onGloballyPositioned { coordinates ->
                val pos = coordinates.positionInWindow()
                rowOffsetInWindow.value = IntOffset(pos.x.roundToInt(), pos.y.roundToInt())
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Press) continue
                        val change = event.changes.firstOrNull() ?: continue
                        if (!event.buttons.isSecondaryPressed) continue
                        val press = IntOffset(
                            change.position.x.roundToInt(),
                            change.position.y.roundToInt(),
                        )
                        onContextMenu(
                            JsonTreeItem(path, key, value, quotedValue, rowOffsetInWindow.value + press)
                        )
                    }
                }
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = {
                    onLongClick(JsonTreeItem(path, key, value, quotedValue, rowOffsetInWindow.value))
                },
                onClick = onClick
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer(rotationZ = if (state == TreeState.COLLAPSED) 0F else 90F),
            imageVector = icon,
            tint = colors.iconColor,
            contentDescription = null
        )

        Text(text = text, style = textStyle)
    }
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
    onContextMenu: (JsonTreeItem) -> Unit,
    onLongClick: (JsonTreeItem) -> Unit,
) {
    val rowOffsetInWindow = remember { mutableStateOf(IntOffset.Zero) }
    Text(
        modifier = Modifier
            .padding(start = indent)
            .onGloballyPositioned { coordinates ->
                val pos = coordinates.positionInWindow()
                rowOffsetInWindow.value = IntOffset(pos.x.roundToInt(), pos.y.roundToInt())
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Press) continue
                        val change = event.changes.firstOrNull() ?: continue
                        if (!event.buttons.isSecondaryPressed) continue
                        val press = IntOffset(
                            change.position.x.roundToInt(),
                            change.position.y.roundToInt(),
                        )
                        onContextMenu(
                            JsonTreeItem(path, key, value, quotedValue, rowOffsetInWindow.value + press)
                        )
                    }
                }
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = {
                    onLongClick(JsonTreeItem(path, key, value, quotedValue, rowOffsetInWindow.value))
                },
                onClick = {}
            ),
        text = text,
        style = textStyle
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
