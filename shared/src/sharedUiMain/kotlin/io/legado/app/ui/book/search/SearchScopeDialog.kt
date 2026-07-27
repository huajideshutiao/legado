package io.legado.app.ui.book.search

// I18N KEYS (need to register in ResourceProvider.jvm.kt):
//   "search_scope" to "搜索范围"

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.legado.app.data.entities.BookGroup
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme

/** Arco Design arcoblue-6 主色 (#165DFF)。 */
private val ArcoBlue6 = Color(0xFF165DFF)

/** Arco Design arco_radius_lg = 16dp。 */
private val ArcoRadiusLg = 16.dp

/**
 * 搜索范围选择对话框 (KMP 共享, app + desktop 复用)。
 *
 * 对应 app 端 `io.legado.app.ui.book.search.SearchScopeDialog`, 但去掉对
 * BaseComposeDialogFragment / appDb / flowWithLifecycleAndDatabaseChange 的依赖,
 * 改为纯 @Composable + 回调形式:
 * - 调用方传入 [groups] (书源分组列表) 和 [selectedGroupIds] (已选分组 ID 集合)
 * - LazyColumn + Checkbox 列出所有分组, 勾选/取消勾选
 * - "全部书源" 按钮: 清空选择, 调用 [onConfirm] 传空集 (对齐原 SearchScope("") 语义)
 * - 确定时把选中的分组 ID 集合通过 [onConfirm] 回传
 *
 * 原 app 端还支持书源模式 (RadioChip 切换 + 搜索筛选), 该模式依赖 appDb.bookSourceDao
 * 的 flow 查询, 无法 KMP 化, 由调用方在 app 端单独处理。
 *
 * @param groups 所有可选的书源分组列表
 * @param selectedGroupIds 初始已选中的分组 ID 集合
 * @param onConfirm 用户点击确定, 参数为选中的分组 ID 集合 (空集 = 全部书源)
 * @param onDismiss 用户取消 (返回按钮 / 点击对话框外部 / 取消按钮)
 */
@Composable
fun SearchScopeDialog(
    groups: List<BookGroup>,
    selectedGroupIds: Set<Long>,
    onConfirm: (Set<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    val titleText = rememberString("search_scope")
    val allSourceText = rememberString("all_source")
    val cancelText = rememberString("cancel")
    val okText = rememberString("ok")

    // 可变的选中集合 (对齐原 selectGroups, 保留勾选顺序)
    var selectedIds by remember { mutableStateOf(selectedGroupIds.toMutableSet()) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(ArcoRadiusLg),
            color = colors.background,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxWidth()) {
                DialogTitleBar(
                    title = titleText,
                    onBack = onDismiss,
                )

                // 分组列表: LazyColumn + Checkbox (任务要求)
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                ) {
                    items(groups, key = { it.groupId }) { group ->
                        val checked = group.groupId in selectedIds
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = checked,
                                    role = Role.Checkbox,
                                ) { isChecked ->
                                    selectedIds = if (isChecked) {
                                        // LinkedHashSet 保留勾选顺序 (对齐原 selectGroups + group 顺序)
                                        LinkedHashSet(selectedIds).apply { add(group.groupId) }
                                    } else {
                                        selectedIds.filterNot { it == group.groupId }.toMutableSet()
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppCheckbox(checked = checked, onCheckedChange = null)
                            Text(
                                text = group.groupName,
                                color = colors.primaryText,
                                fontSize = 16.sp,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }

                // 底部按钮栏: 全部书源 | 弹性间距 | 取消 | 确定
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.bottomBackground)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // "全部书源" 按钮: 清空选择并确认 (对齐原 callback.onSearchScopeOk(SearchScope("")))
                    AppTextButton(text = allSourceText, color = ArcoBlue6) {
                        onConfirm(emptySet())
                    }
                    Spacer(Modifier.weight(1f))
                    AppTextButton(text = cancelText, color = colors.secondaryText) { onDismiss() }
                    AppTextButton(text = okText, color = ArcoBlue6) {
                        onConfirm(selectedIds.toSet())
                    }
                }
            }
        }
    }
}
