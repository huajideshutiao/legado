package io.legado.app.ui.compose.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * 对话框标准操作栏 (底栏按钮行)。
 *
 * 只负责按钮排布, **不加任何内边距**: 按钮四周的 8dp 留白由对话框壳体统一给
 * ([AppDialogScaffold] 的底栏槽, 或手写壳体自己在行外给的 8dp), 避免同一个 8dp 叠两次。
 *
 * 按钮一律使用 [AppTextButton] (视觉高度随文字, 不设最小高)。
 * 排布默认靠右 ([Arrangement.End])。需要左侧按钮时在 [actions] 里用
 * `Spacer(Modifier.weight(1f))` 把左侧按钮顶到最左 (项目既有写法), 右侧按钮仍贴右缘。
 */
@Composable
fun AppDialogBar(
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
        content = actions,
    )
}
