package io.legado.treeview.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import io.legado.treeview.CollapsableType
import io.legado.treeview.JsonTreeElement.Primitive.Type
import io.legado.treeview.JsonTreeElement.ParentType
import io.legado.treeview.TreeColors
import io.legado.treeview.TreeState
import io.legado.treeview.generated.resources.Res
import io.legado.treeview.generated.resources.treeview_collapsable_items
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun rememberCollapsableText(
    type: CollapsableType,
    key: String?,
    childItemCount: Int,
    state: TreeState,
    colors: TreeColors,
    isLastItem: Boolean,
    showIndices: Boolean,
    showItemCount: Boolean,
    parentType: ParentType,
): AnnotatedString {
    val itemCount = stringResource(Res.string.treeview_collapsable_items, childItemCount)

    return remember(
        type,
        key,
        childItemCount,
        state,
        colors,
        isLastItem,
        showIndices,
        showItemCount,
        parentType,
    ) {
        val openBracket = if (type == CollapsableType.OBJECT) "{" else "["
        val closingBracket = if (type == CollapsableType.OBJECT) "}" else "]"

        buildAnnotatedString {
            key?.let { key ->
                if (parentType == ParentType.ARRAY && showIndices) {
                    withStyle(SpanStyle(color = colors.indexColor)) {
                        append(key)
                    }
                    withStyle(SpanStyle(color = colors.symbolColor)) {
                        append(": ")
                    }
                } else if (parentType != ParentType.ARRAY) {
                    withStyle(SpanStyle(color = colors.keyColor)) {
                        append("\"${key.toJsonEscaped()}\"")
                    }

                    withStyle(SpanStyle(color = colors.symbolColor)) {
                        append(": ")
                    }
                }
            }

            withStyle(SpanStyle(color = colors.symbolColor)) {
                append(openBracket)
            }

            if (state == TreeState.COLLAPSED) {
                if (showItemCount) {
                    withStyle(SpanStyle(color = colors.symbolColor)) {
                        append(itemCount)
                    }
                } else {
                    withStyle(SpanStyle(color = colors.symbolColor)) {
                        append(" ... ")
                    }
                }

                withStyle(SpanStyle(color = colors.symbolColor)) {
                    append(if (!isLastItem) "$closingBracket," else closingBracket)
                }
            }
        }
    }
}

@Composable
internal fun rememberPrimitiveText(
    key: String?,
    value: String,
    type: Type,
    colors: TreeColors,
    isLastItem: Boolean,
    showIndices: Boolean,
    parentType: ParentType,
): AnnotatedString {
    return remember(
        key,
        value,
        type,
        colors,
        isLastItem,
        showIndices,
        parentType,
    ) {
        buildAnnotatedString {
            key?.let {
                if (parentType == ParentType.ARRAY && showIndices) {
                    withStyle(SpanStyle(color = colors.indexColor)) {
                        append(it)
                    }
                    withStyle(SpanStyle(color = colors.symbolColor)) {
                        append(": ")
                    }
                } else if (parentType != ParentType.ARRAY) {
                    withStyle(SpanStyle(color = colors.keyColor)) {
                        append("\"${it.toJsonEscaped()}\"")
                    }

                    withStyle(SpanStyle(color = colors.symbolColor)) {
                        append(": ")
                    }
                }
            }

            val valueColor = when(type) {
                Type.STRING -> colors.stringValueColor
                Type.BOOLEAN -> colors.booleanValueColor
                Type.NUMBER -> colors.numberValueColor
                Type.OTHER -> colors.nullValueColor
            }

            withStyle(SpanStyle(color = valueColor)) {
                if(type == Type.STRING) {
                    append("\"${value.toJsonEscaped()}\"")
                } else {
                    append(value)
                }
            }

            if (!isLastItem) {
                withStyle(SpanStyle(color = colors.symbolColor)) {
                    append(",")
                }
            }
        }
    }
}
