package io.legado.app.ui.compose.component

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
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
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        shape = RoundedCornerShape(8.dp),
        containerColor = AppTheme.colors.bottomBackground,
        shadowElevation = 8.dp,
        content = content,
    )
}
