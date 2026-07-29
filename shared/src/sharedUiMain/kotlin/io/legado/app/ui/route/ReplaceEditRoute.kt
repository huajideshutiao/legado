package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import io.legado.app.ui.replace.ReplaceEditScreen
import io.legado.app.ui.replace.edit.ReplaceEditViewModelShared
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.RouteResultPayload
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.widget.dialog.HelpDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson

/**
 * 替换规则编辑 shared 路由入口。
 *
 * [ReplaceEditViewModelShared] 非 [io.legado.app.ui.root.ScreenModel] (组合委托模式,
 * scope/clipTextProvider 由宿主注入), 故用 [remember] 而非 [ScreenModelStore.getOrCreateTyped]。
 * 剪贴板通过 Compose [LocalClipboardManager] 访问, 帮助页通过 [HelpDialog] 渲染。
 */
@Composable
fun ReplaceEditRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val route = entry.route as AppRoute.ReplaceEdit
    val scope = rememberCoroutineScope()
    // Compose 剪贴板管理器 (KMP 可用, 替代 app 端 getClipText/sendToClip)
    val clipboardManager = LocalClipboardManager.current
    val clipTextProvider: () -> String? = { clipboardManager.getText()?.text }
    val viewModel = remember(route.ruleId, scope) {
        ReplaceEditViewModelShared(scope = scope, clipTextProvider = clipTextProvider)
    }

    // 加载规则 (id > 0 编辑已有, id <= 0 新增模板), 完成后 Screen 用 collectAsState 触发回填
    // pattern/isRegex/scope 由阅读页/替换管理页传入 (对照 app 端 startIntent 4 参数)
    LaunchedEffect(route.ruleId) {
        viewModel.initData(
            id = route.ruleId,
            pattern = route.pattern,
            isRegex = route.isRegex,
            scope = route.scope,
        ) { }
    }

    var showHelp by remember { mutableStateOf(false) }

    ReplaceEditScreen(
        viewModel = viewModel,
        onBack = { navigator.pop() },
        onSaved = { navigator.pop(RouteResultPayload.Ok) },
        onCopyRule = { rule ->
            clipboardManager.setText(AnnotatedString(GSON.toJson(rule)))
        },
        onPasteRule = { success ->
            viewModel.pasteRule(success)
        },
        onHelp = { showHelp = true },
    )

    if (showHelp) {
        HelpDialog("regexHelp") { showHelp = false }
    }
}
