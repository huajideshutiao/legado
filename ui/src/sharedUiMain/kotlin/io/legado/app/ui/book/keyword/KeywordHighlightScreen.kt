package io.legado.app.ui.book.keyword

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.KeywordHighlight
import io.legado.app.ui.book.read.page.overlay.HighlightPalette
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppSwitch
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.add
import legado.ui.generated.resources.cancel
import legado.ui.generated.resources.delete
import legado.ui.generated.resources.edit
import legado.ui.generated.resources.ic_add
import legado.ui.generated.resources.ic_edit
import legado.ui.generated.resources.ic_more_vert
import legado.ui.generated.resources.keyword_highlight
import legado.ui.generated.resources.keyword_highlight_empty
import legado.ui.generated.resources.keyword_highlight_scope_all
import legado.ui.generated.resources.keyword_highlight_scope_limited
import legado.ui.generated.resources.more_menu
import legado.ui.generated.resources.ok
import legado.ui.generated.resources.sure_del
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 关键词高亮列表页 (划线/关键词高亮共用色档表 HighlightPalette 的管理端)。
 *
 * UI 形态对齐源过滤规则/替换规则等列表管理页惯例: 标题栏 + 列表 + 单条开关/编辑/删除;
 * 规则量级小, 不引入批量选择与拖拽排序 (sortOrder 仅控制新增排队与匹配次序)。
 */
@Composable
fun KeywordHighlightScreen(
    state: KeywordHighlightUiState,
    actions: KeywordHighlightUiActions,
    modifier: Modifier = Modifier,
) {
    var pendingDeleteRule by remember { mutableStateOf<KeywordHighlight?>(null) }
    val navPad = WindowInsets.navigationBars.asPaddingValues()

    Column(modifier.fillMaxSize()) {
        AppTitleBar(
            title = stringResource(Res.string.keyword_highlight),
            onBack = { actions.onBack() },
        ) {
            IconButton(onClick = { actions.onAddRule() }) {
                Icon(
                    painter = painterResource(Res.drawable.ic_add),
                    contentDescription = stringResource(Res.string.add),
                    tint = AppTheme.colors.primaryText,
                )
            }
        }
        if (state.rules.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(Res.string.keyword_highlight_empty),
                    color = AppTheme.colors.secondaryText,
                    fontSize = 14.sp,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = navPad.calculateBottomPadding()),
            ) {
                itemsIndexed(state.rules, key = { _, item -> item.id }) { _, rule ->
                    KeywordHighlightItem(
                        rule = rule,
                        actions = actions,
                        onDelete = { pendingDeleteRule = rule },
                    )
                }
            }
        }
    }

    pendingDeleteRule?.let { rule ->
        AppAlertDialog(
            onDismissRequest = { pendingDeleteRule = null },
            title = stringResource(Res.string.delete),
            message = stringResource(Res.string.sure_del) + "\n" + rule.word,
            okButton = AlertButton(stringResource(Res.string.ok)) {
                actions.onDeleteRule(rule)
            },
            cancelButton = AlertButton(stringResource(Res.string.cancel)) {},
        )
    }
}

/**
 * 关键词高亮管理页交互回调。
 */
interface KeywordHighlightUiActions {
    /** 返回上一页 */
    fun onBack()

    /** 新增规则 (弹编辑对话框) */
    fun onAddRule()

    /** 编辑规则 (弹编辑对话框) */
    fun onEditRule(rule: KeywordHighlight)

    /** 单条启用开关切换 */
    fun onToggleEnabled(rule: KeywordHighlight, enabled: Boolean)

    /** 单条删除 (确认对话框 OK 后调用) */
    fun onDeleteRule(rule: KeywordHighlight)
}

/** 单条规则: 色档圆点 + 关键词(启用时带下划线样式示意) + 启用开关 + 编辑 + 删除 */
@Composable
private fun KeywordHighlightItem(
    rule: KeywordHighlight,
    actions: KeywordHighlightUiActions,
    onDelete: () -> Unit,
) {
    val colors = AppTheme.colors
    var showMenu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { actions.onEditRule(rule) }
            .padding(horizontal = DesignTokens.spacingLg, vertical = DesignTokens.spacingDefault),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 色档视觉标识 (与管理页选色/回显色块同源)
        Box(
            Modifier
                .size(12.dp)
                .background(HighlightPalette.colorOf(rule.colorIndex), CircleShape),
        )
        Spacer(Modifier.width(DesignTokens.spacingDefault))
        // 副标题: 作用范围摘要 (全局 / 限定 N 项), 形态对齐列表项主副标题惯例
        Column(Modifier.weight(1f)) {
            Text(
                text = rule.word,
                color = if (rule.isEnabled) colors.primaryText else colors.secondaryText,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (rule.underline) TextDecoration.Underline else null,
            )
            Text(
                text = scopeSummary(rule),
                color = colors.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AppSwitch(
            checked = rule.isEnabled,
            onCheckedChange = { actions.onToggleEnabled(rule, it) },
        )
        IconButton(onClick = { actions.onEditRule(rule) }) {
            Icon(
                painter = painterResource(Res.drawable.ic_edit),
                contentDescription = stringResource(Res.string.edit),
                tint = colors.primaryText,
            )
        }
        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(
                    painter = painterResource(Res.drawable.ic_more_vert),
                    contentDescription = stringResource(Res.string.more_menu),
                    tint = colors.primaryText,
                )
            }
            AppDropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(onClick = { showMenu = false; onDelete() }) {
                    Text(stringResource(Res.string.delete), color = colors.primaryText)
                }
            }
        }
    }
}

/**
 * 范围摘要: scope 空/null → 全局; 非空 → 按常见分隔符 (逗号/顿号/分号/换行/竖线)
 * 计数展示 "限定 N 项"。仅展示口径, 匹配端是整串子串包含, 不依赖分隔符。
 */
@Composable
private fun scopeSummary(rule: KeywordHighlight): String {
    val scopeText = rule.scope
    if (scopeText.isNullOrEmpty()) {
        return stringResource(Res.string.keyword_highlight_scope_all)
    }
    val count = scopeText.split(',', '，', ';', '；', '\n', '|').count { it.isNotBlank() }
    return stringResource(Res.string.keyword_highlight_scope_limited, count)
}
