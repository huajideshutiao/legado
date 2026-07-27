package io.legado.app.ui.replace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.copyToClipboard
import io.legado.app.help.openURL
import io.legado.app.help.readFromClipboard
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.replace.edit.ReplaceEditViewModelShared
import io.legado.app.utils.GSON

/**
 * 鸿蒙端替换规则编辑 Screen 入口 (包装 shared/sharedUiMain 的 [ReplaceEditScreen])。
 *
 * 对照 iOS [IosReplaceEditScreen] 的包装模式, 鸿蒙端在 OhosNavHost 的 REPLACE_EDIT
 * 路由分支调用本入口。
 *
 * 仅做鸿蒙平台适配, 业务展示与交互逻辑全部下沉到 shared/sharedUiMain:
 * - **VM**: [ReplaceEditViewModelShared] (scope + clipTextProvider, clipTextProvider 走系统剪贴板);
 * - **剪贴板**: onCopyRule 用 [copyToClipboard], onPasteRule 用 [readFromClipboard];
 * - **帮助**: [openURL] 跳转正则教程;
 * - **保存**: 调 VM.save 触发落库 + onSaved 回调切回列表路由。
 *
 * 代码编辑器: shared/sharedUiMain 的 [ReplaceEditScreen] 内部用 Md2TextField 承载
 * 正则/替换文本编辑 (KMP 共享, 非平台 CodeView), 鸿蒙端直接复用, 不需要单独的
 * material3 TextField 替换 (CodeView 是 app 端 Android 专属控件, KMP 版已移除)。
 *
 * @param ruleId 编辑规则 id (-1 = 新增, >0 = 编辑现有规则)
 * @param onBack 返回回调 (切回 REPLACE_RULE 路由, 不保存)
 * @param onSaved 保存成功回调 (切回 REPLACE_RULE 路由)
 */
@Composable
fun OhosReplaceEditScreen(
    ruleId: Long,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // 文案 (onCopyRule lambda 非 @Composable, 预先 remember)
    val copiedRuleText = rememberString("copied_rule_to_clipboard")
    // clipTextProvider: 读系统剪贴板 (供 VM.pasteRule 解析 JSON 用)
    val viewModel = remember(scope) {
        ReplaceEditViewModelShared(
            scope = scope,
            clipTextProvider = { readFromClipboard() },
        )
    }
    // 加载规则数据 (ruleId 变化时重新加载)
    LaunchedEffect(ruleId) {
        viewModel.initData(id = ruleId) { }
    }

    ReplaceEditScreen(
        viewModel = viewModel,
        onBack = onBack,
        onSaved = onSaved,
        onCopyRule = { rule: ReplaceRule ->
            val json = GSON.toJson(rule)
            copyToClipboard(json)
            Toasters.get().toast(copiedRuleText)
        },
        onPasteRule = { success: (ReplaceRule) -> Unit ->
            viewModel.pasteRule(success)
        },
        onHelp = {
            openURL("https://www.runoob.com/regexp/regexp-tutorial.html")
        },
    )
}
