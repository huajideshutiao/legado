package io.legado.app.ui.dict

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens

/**
 * iOS 端"查词"对话框 (对照 desktop 端 DictDialog)。
 *
 * # 职责
 *
 * 包装 shared/sharedUiMain 的 [DictDialogContent] (业务核心 + UI 下沉), iOS 端只做平台适配:
 * - **Dialog 外壳**: [Dialog] + [Surface] (圆角 16dp, 与 [io.legado.app.ui.reader.IosTextSelectionDialog] 一致)
 * - **标题栏**: [DialogTitleBar] 标题为待查单词 (app 端无标题栏, iOS 端按 IosTextSelectionDialog 模式补齐,
 *   与 desktop 端 DictDialog 对齐)
 * - **VM**: [DictViewModelShared] (commonMain, 注入 `rememberCoroutineScope` 协程作用域,
 *   对照 desktop DictDialog 的注入模式)
 *
 * # 与 app/desktop 端差异
 *
 * - app 端用 BaseComposeDialogFragment (Fragment 提供 Dialog 窗口); iOS 端用 [Dialog] + [Surface]
 * - app 端 word 通过 Fragment arguments 传入; iOS 端通过函数参数直接传入
 * - app 端 word 校验 (toastOnUi + dismiss) 在调用方完成; iOS 端由调用方
 *   ([io.legado.app.ui.reader.IosReaderScreen]) 保证 word 非空
 * - desktop 端用 stripHtml 把 HTML 转纯文本 (无 fromHtml); iOS 端走 shared DictDialogContent
 *   内置的 [androidx.compose.ui.text.AnnotatedString.fromHtml] (与 app 端一致, 链接可点击)
 *
 * @param word 待查单词 (调用方保证非空)
 * @param onDismiss 关闭回调
 */
@Composable
fun IosDictDialog(
    word: String,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    // 复用 commonMain 下沉的查词 VM, 注入 Compose 协程作用域
    // (对照 desktop DictDialog.kt: rememberCoroutineScope() + DictViewModelShared 注入)
    val scope = rememberCoroutineScope()
    val viewModel = remember { DictViewModelShared(scope) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = DesignTokens.dialogShape,
            color = colors.background,
            modifier = Modifier.fillMaxWidth(0.8f),
        ) {
            Column(Modifier.fillMaxWidth()) {
                // 标题栏: 标题为待查单词 (app 端无标题栏, iOS 端按 IosTextSelectionDialog 模式补齐)
                DialogTitleBar(
                    title = word,
                    onBack = onDismiss,
                )
                // 下沉内容: 规则 Tab + 查询结果 HTML (与 app 端 DictDialog.Content 完全一致)
                DictDialogContent(
                    word = word,
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
