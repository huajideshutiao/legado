package io.legado.app.ui.widget.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.loading
import org.jetbrains.compose.resources.stringResource

/**
 * 加载等待对话框内容 (KMP 共享, app + desktop + iOS 复用)。
 *
 * 对应 app 端 `io.legado.app.ui.widget.dialog.WaitDialog` 的 UI 部分 (Row +
 * CircularProgressIndicator + Text), 去掉对 Android ComponentDialog / FragmentActivity /
 * FragmentManager 的依赖, 改为纯 @Composable:
 * - app 端 [WaitDialog] 命令式类保留 (兼容现有 `WaitDialog.from(activity)` 调用方),
 *   其 `setComposeContent { ... }` 内部复用本 [WaitDialogContent];
 * - desktop / iOS 端直接用本 [WaitDialogContent] 或包装版 [WaitDialog] @Composable。
 *
 * UI 逐项对齐 app 端 (严禁改变样式):
 * - 容器: Row padding=16dp, 垂直居中, 水平居中
 * - 指示器: CircularProgressIndicator size=30dp, color=accent, strokeWidth=2dp
 * - 间距: Spacer width=8dp
 * - 文本: message, color=primaryText
 *
 * @param message 等待提示文案 (默认取 i18n key "loading")
 * @param modifier 外部修饰符 (app 端命令式包装不传, 默认 Modifier)
 */
@Composable
fun WaitDialogContent(
    message: String,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    Row(
        modifier = modifier.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(30.dp),
            color = colors.accent,
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.width(8.dp))
        Text(message, color = colors.primaryText)
    }
}

/**
 * 加载等待对话框 (KMP 共享, desktop / iOS 直接复用)。
 *
 * 用 [AppDialog] 包裹 [WaitDialogContent], 提供声明式 API:
 * - `dismissOnClickOutside = false` 对齐 app 端 `setCanceledOnTouchOutside(false)`
 * - `dismissOnBackPress = true` 对齐 app 端默认返回键关闭
 *
 * app 端不使用本函数 (app 端用命令式 [WaitDialog] 类 + ComponentDialog 容器,
 * 保持 `WaitDialog.from(activity)` API 兼容); desktop / iOS 端可直接调用。
 *
 * @param visible 是否显示
 * @param message 提示文案 (默认取 i18n key "loading")
 * @param onDismissRequest 用户按返回键触发关闭时回调 (点击外部不触发, 对齐 app 端)
 */
@Composable
fun WaitDialog(
    visible: Boolean,
    message: String = stringResource(Res.string.loading),
    onDismissRequest: () -> Unit,
) {
    if (!visible) return
    // 原版特例: 转圈小窗不走 AppDialogSizes 的 0.9 屏宽钳制, 保持内容自适应
    AppDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        val colors = AppTheme.colors
        Surface(shape = DesignTokens.dialogShape, color = colors.fillet) {
            WaitDialogContent(message = message)
        }
    }
}
