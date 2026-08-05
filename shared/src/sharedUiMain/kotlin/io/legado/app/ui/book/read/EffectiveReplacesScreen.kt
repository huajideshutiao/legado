package io.legado.app.ui.book.read

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.add
import legado.shared.generated.resources.close
import legado.shared.generated.resources.effective_replaces
import legado.shared.generated.resources.empty
import legado.shared.generated.resources.ic_add
import legado.shared.generated.resources.source_filter_rule_manage
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * 展示当前章节起效的替换规则对话框正文 Composable。
 *
 * 下沉自 app 端 `EffectiveReplacesDialog`，原 DialogFragment 宿主由 app 端 thin wrapper 保留：
 * - `BaseComposeDialogFragment` 提供 isFullHeight / 窗口尺寸 / Provider 注入
 * - `registerForActivityResult` + `Intent(ReplaceRuleActivity)` 通过
 *   [onAddRule] / [onManageAll] 回调由 wrapper 桥接（这些 AndroidX API 不能下沉,
 *   ReplaceEdit 已路由化）
 * - `ReadBookViewModel.replaceRuleChanged()` 通过 [onDismiss] 由 wrapper 在 onDismiss 时
 *   根据 isEdit 标志调用（编辑/管理 Activity 返回 RESULT_OK 时设置 isEdit）
 * - `ChineseUtils.showConverterSelector` 通过 [onItemClick] 由 wrapper 判断 chineseConvert
 *   项后桥接（chineseConvert 项作为 items 的一部分由 wrapper 构造时附加）
 *
 * 资源访问替换：
 * - `getString(R.string.effective_replaces)` → `stringResource(Res.string.effective_replaces)`
 * - `painterResource(R.drawable.ic_add)` → `painterResource(Res.drawable.ic_add)`
 * - `stringResource(R.string.add/empty/close/source_filter_rule_manage)` → `rememberString(...)`
 *
 * @param items 当前章节起效的替换规则列表（含 chineseConvert 项时由 wrapper 附加）
 * @param onAddRule 点击右上角"+"新增规则（wrapper 启动 ReplaceEditRoute）
 * @param onItemClick 点击单条规则（wrapper 启动 ReplaceEditRoute 或 ChineseConvert alert）
 * @param onManageAll 点击底部"管理全部"（wrapper 启动 ReplaceRuleActivity）
 * @param onDismiss 关闭回调（对应 `dismiss()`）
 */
@Composable
fun EffectiveReplacesScreen(
    items: List<ReplaceRule>,
    onAddRule: () -> Unit,
    onItemClick: (ReplaceRule) -> Unit,
    onManageAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    Column(Modifier.fillMaxWidth()) {
        DialogTitleBar(
            title = stringResource(Res.string.effective_replaces),
            onBack = onDismiss,
            actions = {
                IconButton(onClick = onAddRule) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_add),
                        contentDescription = stringResource(Res.string.add),
                        tint = colors.primaryText,
                    )
                }
            },
        )
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            LazyColumn {
                itemsIndexed(items) { _, item ->
                    Text(
                        text = item.name,
                        color = colors.primaryText,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onItemClick(item) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
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

// ===== @Preview 合并自 androidMain 的 book/read/ReadDialogsPreviews.kt (EffectiveReplacesScreen) =====

// ===== EffectiveReplacesScreen =====


private val previewReplaceRules = listOf(
    ReplaceRule(
        id = 1L,
        name = "净化广告",
        pattern = "<ad>.*?</ad>",
        replacement = "",
    ),
    ReplaceRule(
        id = 2L,
        name = "繁简转换",
        pattern = "[繁體]",
        replacement = "[简体]",
    ),
    ReplaceRule(
        id = 3L,
        name = "去除水印",
        pattern = "本章未完.*?请翻页",
        replacement = "",
    ),
)


@Preview
@Composable
fun EffectiveReplacesScreenPreview() = LegadoThemePreview {
    EffectiveReplacesScreen(
        items = previewReplaceRules,
        onAddRule = {},
        onItemClick = {},
        onManageAll = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun EffectiveReplacesScreenEmptyPreview() = LegadoThemePreview {
    EffectiveReplacesScreen(
        items = emptyList(),
        onAddRule = {},
        onItemClick = {},
        onManageAll = {},
        onDismiss = {},
    )
}

