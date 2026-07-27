package io.legado.app.ui.compose.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.DropdownMenu
import androidx.compose.material.LocalContentColor
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import io.legado.app.ui.compose.theme.AppTheme

/**
 * 统一容器样式的下拉菜单，复刻 View PopupMenu 的 BottomBackgroundDrawable：
 * bottomBackground 填充 + 8dp 圆角 + popup_menu_elevation(8dp)。
 * 容器色显式走 AppTheme 语义，不依赖 M3 surfaceContainer 映射（壁纸/E-Ink 下稳定）。
 */
@Composable
fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 覆盖 MD2 DropdownMenu 外层 Surface 的 surface 色，避免顶部/底部 8dp padding 区域漏底色
    // MaterialTheme 必须在 DropdownMenu 外层，Surface 的 color 在 content lambda 调用前就已求值
    MaterialTheme(colors = MaterialTheme.colors.copy(surface = AppTheme.colors.bottomBackground)) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            offset = offset,
        ) {
            CompositionLocalProvider(LocalContentColor provides AppTheme.colors.menuText) {
                Column {
                    content()
                }
            }
        }
    }
}
