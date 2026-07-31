// I18N KEYS (新增, 待 ResourceProvider.jvm.kt 补全桌面端字面量):
// - effective_replaces: "起效的替换" (已存在 jvmMain)
// - add: "添加" (已存在 jvmMain)
// - close: "关闭" (已存在 jvmMain)
// - source_filter_rule_manage: "管理全部" (已存在 jvmMain)
// - empty: "空空如也" (已存在 jvmMain)
//
// PAINTER KEYS (新增, 待 ResourceProvider.jvm.kt 补全桌面端图标):
// - ic_add: 添加 (已存在 jvmMain)

package io.legado.app.ui.book.read

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.add
import legado.shared.generated.resources.close
import legado.shared.generated.resources.effective_replaces
import legado.shared.generated.resources.empty
import legado.shared.generated.resources.ic_add
import legado.shared.generated.resources.source_filter_rule_manage
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 净化替换临时生效对话框 (KMP 共享, app + desktop 复用)。
 *
 * 对应 app 端 `io.legado.app.ui.book.read.EffectiveReplacesDialog`,
 * 但去掉对 Android Fragment / activityViewModels / registerForActivityResult /
 * Intent(ReplaceRuleActivity) / ReadBookViewModel 的依赖,
 * 改为纯 @Composable + 回调形式:
 * - 调用方传入 [book] (上下文, 用于未来扩展标题展示书名) 与 [items] (当前章节起效的替换规则列表,
 *   含 chineseConvert 项时由调用方附加, 与 EffectiveReplacesScreen 设计一致)
 * - 用户点击单条规则通过 [onItemClick] 回调 (与原版 `onItemClick(it)` 对齐)
 * - 用户点击右上角"+"通过 [onAddRule] 回调 (与原版 `addRule()` 对齐)
 * - 用户点击底部"管理全部"通过 [onManageAll] 回调 (与原版 `manageActivity.launch(...)` 对齐)
 * - [onDismiss] 关闭回调 (与原版 `dismiss()` 对齐)
 *
 * # 与已下沉的 EffectiveReplacesScreen 的关系
 *
 * shared 中已存在 `EffectiveReplacesScreen` (同包, 参数 items + 4 回调, 无 book 上下文),
 * 本 [EffectiveReplacesDialog] 是其 Dialog 包装版本:
 * - 增加 [book] 参数作为上下文 (供未来扩展标题展示书名, 当前未使用)
 * - 用 Dialog + Surface 包裹, 提供 Arco Design 16dp 圆角容器 (EffectiveReplacesScreen 是裸 Column)
 * - LazyColumn 列出命中规则 (与 EffectiveReplacesScreen 一致, 与任务要求"用 LazyColumn 列出命中规则"对齐)
 *
 * 调用方选择:
 * - app 端 thin wrapper (DialogFragment) 可直接复用 EffectiveReplacesScreen (已是下沉的正文 Composable)
 * - 桌面端 / 需要 Dialog 形式的场景用 [EffectiveReplacesDialog] (本函数)
 *
 * # 原业务逻辑保留
 *
 * - DialogTitleBar + 右上角"+"按钮 (与原版 DialogTitleBar + IconButton(ic_add) 对齐)
 * - LazyColumn 列出 items, 每行点击触发 [onItemClick] (与原版 clickable + onItemClick 对齐)
 * - 空列表展示"空空如也"占位 (与原版 EffectiveReplacesScreen empty 分支对齐)
 * - 底部"关闭" + "管理全部"按钮 (与原版 Row + AppTextButton 对齐)
 *
 * # 样式 (Arco Design 规范)
 *
 * - 圆角 arco_radius_lg = 16dp: Dialog Surface 圆角
 * - 无阴影 (Surface 默认无阴影)
 *
 * @param book 当前书籍 (上下文, 供未来扩展标题展示书名, 当前未使用)
 * @param items 当前章节起效的替换规则列表 (含 chineseConvert 项时由调用方附加)
 * @param onAddRule 点击右上角"+"新增规则
 * @param onItemClick 点击单条规则
 * @param onManageAll 点击底部"管理全部"
 * @param onDismiss 关闭回调
 */
@Composable
fun EffectiveReplacesDialog(
    book: Book,
    items: List<ReplaceRule>,
    onAddRule: () -> Unit,
    onItemClick: (ReplaceRule) -> Unit,
    onManageAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors

    AppDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        Surface(
            shape = DesignTokens.dialogShape,
            color = colors.background,
            // 原版 isFullHeight = true, 高度固定 0.8 屏高
            modifier = Modifier.appDialogSize(fullHeight = true).padding(16.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                DialogTitleBar(
                    title = stringResource(Res.string.effective_replaces),
                    onBack = onDismiss,
                    actions = {
                        // 右上角"+"按钮 (与原版 IconButton + ic_add 对齐)
                        IconButton(onClick = onAddRule) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_add),
                                contentDescription = stringResource(Res.string.add),
                                tint = colors.primaryText,
                            )
                        }
                    },
                )

                // 命中规则列表 (与原版 LazyColumn + itemsIndexed 对齐; 全高对话框下撑满剩余高度)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        itemsIndexed(items) { _, item ->
                            Text(
                                text = item.name,
                                color = colors.primaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onItemClick(item) }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    // 空列表占位 (与原版 EffectiveReplacesScreen empty 分支对齐)
                    if (items.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.empty),
                            color = colors.secondaryText,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(16.dp),
                        )
                    }
                }

                // 底部按钮栏 (与原版 Row + 关闭 + 管理全部 对齐)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppTextButton(
                        text = stringResource(Res.string.close),
                        color = colors.secondaryText,
                        onClick = onDismiss,
                    )
                    AppTextButton(
                        text = stringResource(Res.string.source_filter_rule_manage),
                        onClick = onManageAll,
                    )
                }
            }
        }
    }
}
