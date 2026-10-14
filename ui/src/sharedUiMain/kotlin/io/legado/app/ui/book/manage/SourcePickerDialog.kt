// 本对话框引用的字符串与图标均取自 Compose Resources 按 key 索引的映射表
// (ui/src/commonMain/composeResources 为四端单一数据源, 无需各端补字面量):
// select_book_source / search_book_source / change_source_delay
package io.legado.app.ui.book.manage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.BookSource
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppPickDialog
import io.legado.app.ui.compose.component.FastScrollLazyColumn
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.component.rememberAppDialogSearchState
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.dialog.NumberPickerDialog
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.change_source_delay
import legado.ui.generated.resources.search_book_source
import legado.ui.generated.resources.select_book_source
import org.jetbrains.compose.resources.stringResource

/**
 * 换源选择对话框 (KMP 共享, app + desktop 复用)。
 *
 * 对照 app 端 `io.legado.app.ui.book.manage.SourcePickerDialog`：
 * - 搜索框在顶栏内 (与搜索范围对话框同一形制: 放大镜进搜索态, 退出叉退搜索态) 即时筛选启用书源；
 * - 点击书源后立即回调并关闭；
 * - 溢出菜单可设置批量换源间隔。
 *
 * 数据加载与延迟持久化由调用方负责，组件只维护查询和弹窗状态。
 *
 * @param showDelayMenu 是否展示溢出菜单中的换源间隔设置；仅换源语义需要，纯选源场景关掉。
 */
@Composable
fun SourcePickerDialog(
    sources: List<BookSource>,
    initialDelay: Int = 0,
    onSourceSelected: (BookSource) -> Unit,
    onDelayChange: (Int) -> Unit = {},
    showDelayMenu: Boolean = true,
    onDismiss: () -> Unit,
) {
    val searchState = rememberAppDialogSearchState()
    var showDelayPicker by remember { mutableStateOf(false) }
    val searchKey = searchState.query
    val filteredSources = remember(sources, searchKey) {
        if (searchKey.isBlank()) sources else sources.filter {
            it.bookSourceName.contains(searchKey, ignoreCase = true) ||
                it.bookSourceGroup.orEmpty().contains(searchKey, ignoreCase = true) ||
                it.bookSourceUrl.contains(searchKey, ignoreCase = true) ||
                it.bookSourceComment.orEmpty().contains(searchKey, ignoreCase = true)
        }
    }

    AppPickDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.select_book_source),
        searchState = searchState,
        searchHint = stringResource(Res.string.search_book_source),
        properties = AppDialogSizes.properties(),
        // 溢出菜单可设置批量换源间隔 (搜索态下与放大镜一并隐藏)
        titleActions = {
            if (showDelayMenu) {
                OverflowMenu { dismiss ->
                    DropdownMenuItem(onClick = {
                        dismiss()
                        showDelayPicker = true
                    }) {
                        Text(stringResource(Res.string.change_source_delay))
                    }
                }
            }
        },
    ) {
        FastScrollLazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            items(filteredSources, key = { it.bookSourceUrl }) { source ->
                SourceRow(
                    source = source,
                    onClick = {
                        onSourceSelected(source)
                        onDismiss()
                    },
                )
            }
        }
    }

    if (showDelayPicker) {
        NumberPickerDialog(
            title = stringResource(Res.string.change_source_delay),
            value = initialDelay,
            range = 0..9999,
            onConfirm = {
                onDelayChange(it)
                showDelayPicker = false
            },
            onDismiss = { showDelayPicker = false },
        )
    }
}

/** 书源行: 单行书源名 (含分组), 对照原版 TextView(item.getDisPlayNameGroup())。 */
@Composable
private fun SourceRow(
    source: BookSource,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = DesignTokens.spacingLg, vertical = DesignTokens.spacingMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = source.getDisPlayNameGroup(),
            color = colors.primaryText,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
