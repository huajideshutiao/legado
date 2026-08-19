package io.legado.app.ui.dict

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.lookup_word
import org.jetbrains.compose.resources.stringResource

/**
 * 词典查询对话框宿主 (KMP 共享, 对照 app 端 [io.legado.app.ui.dict.DictDialog] 对话框形态)。
 *
 * 原版"长按选字 → 查词"打开词典查询对话框 (本地/在线词典规则), 本宿主把 [DictDialogContent]
 * (规则 Tab + 查询结果 HTML, 已下沉 shared) 包上统一对话框外壳, 供各端 TextSelectionDialog 的
 * 查词按钮 (onDict) 直接调用:
 * - 外壳: [AppDialog] 居中对话框 + [appDialogSize] (宽 0.9 屏宽上限 800dp, 高自适应封顶 0.7 屏高)
 * - 标题栏: 查词 (对照原版查词入口语义), 返回即关闭
 * - 业务: [DictViewModelShared] 本地构造 (查询规则加载/查询取消旧任务全部 shared, 无平台依赖)
 *
 * @param word 待查单词 (调用方保证非空, 对照 app 端 DictDialog 的空 word 校验在弹窗前完成)
 * @param onDismiss 关闭回调 (标题栏返回/点外部/返回键)
 */
@Composable
fun DictDialogHost(
    word: String,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember(word) { DictViewModelShared(scope) }

    AppDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        AppTheme {
            Surface(
                shape = DesignTokens.dialogShape,
                color = AppTheme.colors.fillet,
                modifier = Modifier.appDialogSize(),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    DialogTitleBar(
                        title = stringResource(Res.string.lookup_word),
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
}
