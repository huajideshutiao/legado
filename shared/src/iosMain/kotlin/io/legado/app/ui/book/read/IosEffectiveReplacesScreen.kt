package io.legado.app.ui.book.read

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.platform.rememberString

/**
 * iOS 端"起效替换规则"对话框入口 (包装 shared/sharedUiMain 的 [EffectiveReplacesScreen])。
 *
 * 包装为全屏 Dialog (对照 desktop IosBookSourceEditScreen 的 Dialog 包装模式)。
 * 列表数据由调用方注入 (阅读页可从 ReadBookViewModel 拉取当前章节起效规则)。
 *
 * @param items 当前章节起效的替换规则列表
 * @param onDismiss 关闭回调
 * @param onAddRule 新增规则 (跳转 ReplaceEdit 路由, 由 IosNavHost 注入)
 * @param onItemClick 点击单条规则 (跳转 ReplaceEdit 路由, 由 IosNavHost 注入)
 * @param onManageAll 管理全部 (跳转 ReplaceRule 路由, 由 IosNavHost 注入)
 */
@Composable
fun IosEffectiveReplacesScreen(
    items: List<ReplaceRule>,
    onDismiss: () -> Unit,
    onAddRule: (() -> Unit)? = null,
    onItemClick: ((ReplaceRule) -> Unit)? = null,
    onManageAll: (() -> Unit)? = null,
) {
    // iOS 端待接入提示文案 (默认参数 lambda 非 @Composable, 需在函数体内缓存)
    val addRuleText = rememberString("ios_add_replace_rule_not_implemented")
    val editRuleText = rememberString("ios_edit_replace_rule_not_implemented")
    val manageRulesText = rememberString("ios_manage_replace_rules_not_implemented")
    val effectiveAddRule = onAddRule ?: { Toasters.get().toast(addRuleText) }
    val effectiveItemClick = onItemClick ?: { _ -> Toasters.get().toast(editRuleText) }
    val effectiveManageAll = onManageAll ?: { Toasters.get().toast(manageRulesText) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        EffectiveReplacesScreen(
            items = items,
            onAddRule = effectiveAddRule,
            onItemClick = effectiveItemClick,
            onManageAll = effectiveManageAll,
            onDismiss = onDismiss,
        )
    }
}
