package io.legado.app.ui.compose.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.DropdownMenu
import androidx.compose.material.Surface
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
    // MD2 DropdownMenu 不支持 shape/containerColor/shadowElevation，用 Surface 包裹 content 复刻视觉
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = AppTheme.colors.bottomBackground,
            elevation = 8.dp,
        ) {
            Column {
                content()
            }
        }
    }
}
