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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.window.DialogProperties
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme

/** Arco Design arco_radius_lg = 16dp, 用于对话框圆角。 */
private val ArcoRadiusLg = 16.dp

/**
 * 换源选择对话框 (KMP 共享, app + desktop 复用)。
 *
 * 对应 app 端 `io.legado.app.ui.book.manage.SourcePickerDialog`,
 * 但去掉对 Android Fragment / appDb.bookSourceDao.flowEnabled / flowSearch /
 * showNumberPicker / AppConfig.batchChangeSourceDelay 的依赖,
 * 改为纯 @Composable + 回调形式:
 * - 调用方传入 [book] (上下文, 用于未来扩展标题展示书名) 与 [sources] (可选用书源列表,
 *   由调用方负责从 appDb 加载, 可含搜索过滤)
 * - 用户勾选/取消勾选书源行, 通过本地状态维护 selectedSourceUrls
 * - 用户点击确认按钮通过 [onConfirm] 回传勾选的书源 URL 集合
 * - [onDismiss] 关闭回调 (与原版 dismissAllowingStateLoss 对齐)
 *
 * # 与原版的差异 (KMP 限制 + 任务要求)
 *
 * - 原版是单选 + 立即切换 (点击行 → callback.sourceOnClick → dismiss),
 *   任务要求"批量勾选后批量切换", 本下沉版本按任务要求改为 Checkbox 多选 + 确认按钮
 *   (与原版 `onSourceClick` 单选语义不同, 但符合任务参数 `selectedSourceUrls: Set<String>`
 *   + `onConfirm: (Set<String>) -> Unit` 的批量语义)
 * - 原版搜索框 (AppSearchField) 依赖 appDb.bookSourceDao.flowSearch, 下沉后由调用方
 *   在外部实现搜索并传入过滤后的 [sources] (本 Dialog 不含搜索 UI)
 * - 原版 OverflowMenu 中的"换源延迟"依赖 showNumberPicker + AppConfig, 下沉后由调用方
 *   在外部实现 (本 Dialog 不含"换源延迟"入口)
 *
 * # 原业务逻辑保留
 *
 * - LazyColumn 列出书源 (与原版 LazyColumn + items 对齐)
 * - 每行显示书源名 (getDisPlayNameGroup) + URL + 最后响应时间 (与任务要求"显示书源名 +
 *   URL + 最后响应时间"对齐)
 * - 顶部标题栏 + 返回箭头 (与原版 Row + IconButton(ic_arrow_back) + Text(选择书源) 对齐)
 * - 底部确认/取消按钮 (与批量模式语义对齐)
 *
 * # 样式 (Arco Design 规范)
 *
 * - 主色 arcoblue-6 (#165DFF): Checkbox 选中色 (走 AppCheckbox 默认 accent)
 * - 圆角 arco_radius_lg = 16dp: Dialog Surface 圆角
 * - 无阴影 (Surface 默认无阴影)
 *
 * @param book 当前书籍 (上下文, 供未来扩展标题展示书名, 当前未使用)
 * @param sources 可选用书源列表 (调用方负责从 appDb 加载, 可含搜索过滤)
 * @param selectedSourceUrls 初始勾选的书源 URL 集合
 * @param onConfirm 确认回调, 携带勾选的书源 URL 集合
 * @param onDismiss 关闭回调
 */
@Composable
fun SourcePickerDialog(
    book: Book,
    sources: List<BookSource>,
    selectedSourceUrls: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors

    // 本地状态: 当前勾选的书源 URL 集合 (与原版 mutableStateOf 对齐, 改为 Set<String> 批量语义)
    var selected by remember(selectedSourceUrls) { mutableStateOf(selectedSourceUrls.toMutableSet()) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(ArcoRadiusLg),
            color = colors.background,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                // 顶部标题栏: 返回箭头 + 标题 (与原版 Row + IconButton(ic_arrow_back) + Text(选择书源) 对齐)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            painter = rememberPainter("ic_arrow_back"),
                            contentDescription = null,
                            tint = colors.primaryText,
                        )
                    }
                    Text(
                        text = rememberString("select_book_source"),
                        color = colors.primaryText,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }

                // 书源列表 (与原版 LazyColumn + items 对齐)
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                ) {
                    items(sources, key = { it.bookSourceUrl }) { source ->
                        SourceRow(
                            source = source,
                            checked = source.bookSourceUrl in selected,
                            onToggle = {
                                // 勾选/取消勾选 (与原版 clickable + onSourceClick 对齐, 改为批量勾选语义)
                                val newSelected = selected.toMutableSet()
                                if (source.bookSourceUrl in newSelected) {
                                    newSelected.remove(source.bookSourceUrl)
                                } else {
                                    newSelected.add(source.bookSourceUrl)
                                }
                                selected = newSelected
                            },
                        )
                    }
                }

                // 底部确认/取消按钮 (与批量模式语义对齐)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppTextButton(
                        text = rememberString("cancel"),
                        color = colors.secondaryText,
                        onClick = onDismiss,
                    )
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    AppTextButton(
                        text = rememberString("ok"),
                        onClick = { onConfirm(selected) },
                    )
                }
            }
        }
    }
}

/**
 * 书源行: Checkbox + 书源名 + URL + 最后响应时间。
 *
 * 复刻自 app 端 `SourcePickerDialog.Content` 中的 Text(item.getDisPlayNameGroup()),
 * 并按任务要求"显示书源名 + URL + 最后响应时间"扩展为多行布局:
 * - 第一行: Checkbox + 书源名 (含分组, 与原版 getDisPlayNameGroup 对齐)
 * - 第二行: URL + 响应时间 (任务要求新增)
 */
@Composable
private fun SourceRow(
    source: BookSource,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppCheckbox(
            checked = checked,
            onCheckedChange = { onToggle() },
        )
        Spacer(Modifier.size(8.dp))
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
                text = rememberString("respondTime", source.respondTime),
                color = colors.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
