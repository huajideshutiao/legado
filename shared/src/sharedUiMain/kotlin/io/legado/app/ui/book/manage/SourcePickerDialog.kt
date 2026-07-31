// I18N KEYS (新增, 待 ResourceProvider.jvm.kt 补全桌面端字面量):
// - select_book_source: "选择书源"
// - search_book_source: "搜索书源" (已存在 jvmMain)
// - change_source_delay: "换源延迟"
// - respond_time_ms: "响应时间：%1$d ms" (复用 jvmMain respondTime, 带 formatArgs)
// - ok: "确定" (已存在 jvmMain)
// - cancel: "取消" (已存在 jvmMain)
//
// PAINTER KEYS (新增, 待 ResourceProvider.jvm.kt 补全桌面端图标):
// - ic_arrow_back: 返回箭头 (已存在 jvmMain)

package io.legado.app.ui.book.manage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.legado.app.data.entities.BookSource
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppSearchField
import io.legado.app.ui.compose.component.FastScrollLazyColumn
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.dialog.NumberPickerDialog
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.change_source_delay
import legado.shared.generated.resources.ic_arrow_back
import legado.shared.generated.resources.respondTime
import legado.shared.generated.resources.search_book_source
import legado.shared.generated.resources.select_book_source
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 换源选择对话框 (KMP 共享, app + desktop 复用)。
 *
 * 对照 app 端 `io.legado.app.ui.book.manage.SourcePickerDialog`：
 * - 搜索框即时筛选启用书源；
 * - 点击书源后立即回调并关闭；
 * - 溢出菜单可设置批量换源延迟；
 * - 列表补充显示 URL 与最近响应时间。
 *
 * 数据加载与延迟持久化由调用方负责，组件只维护查询和弹窗状态。
 */
@Composable
fun SourcePickerDialog(
    sources: List<BookSource>,
    initialDelay: Int,
    onSourceSelected: (BookSource) -> Unit,
    onDelayChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors

    var searchKey by remember { mutableStateOf("") }
    var showDelayPicker by remember { mutableStateOf(false) }
    val filteredSources = remember(sources, searchKey) {
        if (searchKey.isBlank()) sources else sources.filter {
            it.bookSourceName.contains(searchKey, ignoreCase = true) ||
                it.bookSourceGroup.orEmpty().contains(searchKey, ignoreCase = true) ||
                it.bookSourceUrl.contains(searchKey, ignoreCase = true) ||
                it.bookSourceComment.orEmpty().contains(searchKey, ignoreCase = true)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        Surface(
            shape = DesignTokens.dialogShape,
            color = colors.background,
            modifier = Modifier.appDialogSize(fullHeight = true).padding(16.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                // 顶部标题栏: 返回箭头 + 标题 (与原版 Row + IconButton(ic_arrow_back) + Text(选择书源) 对齐)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_arrow_back),
                            contentDescription = null,
                            tint = colors.primaryText,
                        )
                    }
                    Text(
                        text = stringResource(Res.string.select_book_source),
                        color = colors.primaryText,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    OverflowMenu { dismiss ->
                        DropdownMenuItem(onClick = {
                            dismiss()
                            showDelayPicker = true
                        }) {
                            Text(stringResource(Res.string.change_source_delay))
                        }
                    }
                }

                AppSearchField(
                    value = searchKey,
                    onValueChange = { searchKey = it },
                    hint = stringResource(Res.string.search_book_source),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )

                // 书源列表 (与原版 LazyColumn + items 对齐; 全高对话框下列表撑满剩余高度)
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

/**
 * 书源行: 书源名 + URL + 最后响应时间。
 *
 * 复刻自 app 端 `SourcePickerDialog.Content` 中的 Text(item.getDisPlayNameGroup()),
 * 并按任务要求"显示书源名 + URL + 最后响应时间"扩展为多行布局:
 * - 第一行: 书源名 (含分组, 与原版 getDisPlayNameGroup 对齐)
 * - 第二行: URL + 响应时间 (任务要求新增)
 */
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            // 第一行: 书源名 (含分组, 与原版 getDisPlayNameGroup 对齐)
            Text(
                text = source.getDisPlayNameGroup(),
                color = colors.primaryText,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 第二行: URL + 响应时间 (任务要求新增, 显示书源 URL 与最后响应时间)
            Text(
                text = "${source.bookSourceUrl}  ${
                    stringResource(
                        Res.string.respondTime,
                        source.respondTime
                    )
                }",
                color = colors.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
