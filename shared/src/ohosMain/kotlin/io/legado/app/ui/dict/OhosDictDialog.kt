package io.legado.app.ui.dict

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens

/**
 * 鸿蒙端"查词"对话框 (对照 iOS 端 IosDictDialog 薄壳模式)。
 *
 * 包装 sharedUiMain 的 [DictDialogContent] (规则 Tab + 查询结果 HTML),
 * 本端只做 Dialog 外壳 + 标题栏 + VM 注入; word 由调用方保证非空。
 */
@Composable
fun OhosDictDialog(
    word: String,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    // 复用 commonMain 下沉的查词 VM, 注入 Compose 协程作用域 (对照 IosDictDialog)
    val scope = rememberCoroutineScope()
    val viewModel = remember { DictViewModelShared(scope) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = DesignTokens.dialogShape,
            color = colors.background,
            modifier = Modifier.fillMaxWidth(0.8f),
        ) {
            Column(Modifier.fillMaxWidth()) {
                DialogTitleBar(
                    title = word,
                    onBack = onDismiss,
                )
                DictDialogContent(
                    word = word,
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
