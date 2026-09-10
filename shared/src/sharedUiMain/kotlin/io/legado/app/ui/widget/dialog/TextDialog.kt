package io.legado.app.ui.widget.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.SelectableText
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.root.PlatformCapabilityProviders
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.copy
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.text_too_large
import org.jetbrains.compose.resources.stringResource

private const val MAX_TEXT_LENGTH = 32 * 1024

/**
 * 通用文本展示对话框 (四端唯一入口, 日志堆栈详情 / 崩溃日志内容 / 书源源码 / 分类错误详情共用)。
 *
 * 布局 = AppDialog + Surface(fullHeight) + Column(DialogTitleBar 带返回 / 正文 weight(1f) / 按钮行钉底)。
 * 对齐原版 `BaseDialogFragment(R.layout.dialog_text_view)` + `isFullHeight = true` + `setupTitleBar`:
 * 原版日志堆栈详情与崩溃日志内容查看本就是同一个 TextDialog, 故此处只保留一份实现。
 * 按钮行: 复制靠左, 取消/确定靠右 (同 AppAlertDialogContent 的 contextual 槽位布局)。
 *
 * 不用 M2 AlertDialog: 其 BaselineLayout 在 CMP 桌面按"未钳制的标题+正文高"汇报, 长文本时
 * 对话框超 Surface 封顶, 滚动视口 > 可视区, 滚动错位 (内容下移/顶部空白/按钮被推出屏幕外,
 * 用户多轮实测复现)。weight+内部滚动方案视口恒定 (正文区 = 对话框剩余空间)。
 * 正文选择用 [SelectableText] (readOnly BasicTextField, 拖选/拖手柄越界自动滚动,
 * 对齐原版原生 TextView)。
 *
 * @param title 标题栏文案 (原版取文件名 / "Log" / "html" / "ERROR")
 * @param content 正文, 超过 [MAX_TEXT_LENGTH] 截断并追加提示 (对齐原版"数据太大"分支)
 * @param onDismiss 关闭 (返回箭头 / 点击对话框外部 / 取消 / 确定 四处同一回调, 原版无按钮语义区分)
 */
@Composable
fun TextDialog(
    title: String,
    content: String,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    val okText = stringResource(Res.string.ok)
    val cancelText = stringResource(Res.string.cancel)
    val copyText = stringResource(Res.string.copy)
    val tooLargeText = stringResource(Res.string.text_too_large)

    AppDialog(onDismissRequest = onDismiss, properties = AppDialogSizes.properties()) {
        Surface(
            modifier = Modifier.appDialogSize(fullHeight = true),
            shape = DesignTokens.shapeDefault,
            color = colors.fillet,
        ) {
            Column(Modifier.fillMaxWidth()) {
                DialogTitleBar(
                    title = title,
                    onBack = onDismiss,
                )
                // 正文区: weight 占标题栏与按钮行之间的剩余空间 (视口恒定), 超长内部滚动
                val displayText = if (content.length >= MAX_TEXT_LENGTH) {
                    content.take(MAX_TEXT_LENGTH) + "\n\n" + tooLargeText
                } else {
                    content
                }
                SelectableText(
                    text = displayText,
                    color = colors.secondaryText,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = DesignTokens.spacingDefault),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = DesignTokens.spacingDefault),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    AppTextButton(text = copyText) {
                        PlatformCapabilityProviders.get().copyToClipboard(content)
                    }
                    Spacer(Modifier.weight(1f))
                    AppTextButton(text = cancelText, color = colors.secondaryText) { onDismiss() }
                    AppTextButton(text = okText) { onDismiss() }
                }
            }
        }
    }
}
