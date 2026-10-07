package io.legado.app.ui.compose.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.ic_arrow_drop_down
import org.jetbrains.compose.resources.painterResource

/**
 * 标签 + 下拉选择单行 (对照原版 AppCompatSpinner 行, 菜单走自绘 [AppDropdownMenu])。
 *
 * 阅读页批注气泡 / 关键词高亮编辑 / 书架布局对话框共用: 标签占满剩余宽度, 右侧当前值 + ▾,
 * 点开下拉选中即回调。标签字号由调用方给 (不给则随上下文文字风格); 是否占满一行由
 * 调用方经 [modifier] 决定 (行内联用默认 wrap content)。
 */
@Composable
fun AppDropdownField(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    labelFontSize: TextUnit = TextUnit.Unspecified,
) {
    val colors = AppTheme.colors
    var expanded by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (label != null) {
            Text(
                text = label,
                color = colors.primaryText,
                fontSize = labelFontSize,
                modifier = Modifier.weight(1f),
            )
        }
        Box {
            Row(
                Modifier
                    .clickable { expanded = true }
                    .padding(horizontal = DesignTokens.spacingXs, vertical = DesignTokens.spacingXs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = options.getOrElse(selectedIndex) { "" },
                    color = colors.primaryText,
                    fontSize = 14.sp,
                )
                Icon(
                    painter = painterResource(Res.drawable.ic_arrow_drop_down),
                    contentDescription = null,
                    tint = colors.secondaryText,
                )
            }
            AppDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEachIndexed { index, item ->
                    DropdownMenuItem(onClick = { expanded = false; onSelect(index) }) {
                        Text(item, color = colors.primaryText)
                    }
                }
            }
        }
    }
}
