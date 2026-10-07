package io.legado.app.ui.compose.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
 * 阅读页批注气泡 / 关键词高亮编辑 / 书架布局对话框 / 筛选选项共用: 标签占满剩余宽度, 右侧当前值
 * + ▾ (值区与菜单由 [AppDropdownAnchor] 提供, 多选筛选行共用同一外观), 点开下拉选中即回调。
 * 标签字号由调用方给 (不给则随上下文文字风格); 是否占满一行由调用方经 [modifier] 决定
 * (行内联用默认 wrap content)。[onLabelClick] 非空时标签可点 (筛选行用它重置到默认)。
 */
@Composable
fun AppDropdownField(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    labelFontSize: TextUnit = TextUnit.Unspecified,
    onLabelClick: (() -> Unit)? = null,
) {
    val colors = AppTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (label != null) {
            Text(
                text = label,
                color = colors.primaryText,
                fontSize = labelFontSize,
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (onLabelClick != null) Modifier.clickable(onClick = onLabelClick)
                        else Modifier
                    ),
            )
        }
        AppDropdownAnchor(
            valueText = options.getOrElse(selectedIndex) { "" },
            menuContent = { dismiss -> AppSingleChoiceMenu(options, onSelect, dismiss) },
        )
    }
}

/**
 * 单选菜单内容: 逐项点选即回调并收起 ([AppDropdownField] 与筛选行的整行锚点共用)。
 */
@Composable
internal fun AppSingleChoiceMenu(
    options: List<String>,
    onSelect: (Int) -> Unit,
    dismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    options.forEachIndexed { index, item ->
        DropdownMenuItem(onClick = { dismiss(); onSelect(index) }) {
            Text(item, color = colors.primaryText)
        }
    }
}

/**
 * 下拉锚点: 当前值 + ▾, 点开菜单。[AppDropdownField] (单选选中即收起) 与筛选行
 * (多选点选不收起) 共用同一外观, 差别只在 [menuContent]。
 *
 * [leading] 非空时与值区同处一个热区 (筛选行把标题放进来, 整行点开菜单, 行内不留死区)。
 * [horizontalArrangement] 供热区占满整行时把值 + ▾ 推到行尾。
 */
@Composable
internal fun AppDropdownAnchor(
    valueText: String,
    modifier: Modifier = Modifier,
    leading: (@Composable RowScope.() -> Unit)? = null,
    horizontalArrangement: Arrangement.Horizontal? = null,
    menuContent: @Composable (dismiss: () -> Unit) -> Unit,
) {
    val colors = AppTheme.colors
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier
                .clickable { expanded = true }
                .padding(horizontal = DesignTokens.spacingXs, vertical = DesignTokens.spacingXs),
            horizontalArrangement = horizontalArrangement ?: Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.invoke(this)
            Text(
                text = valueText,
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
            menuContent { expanded = false }
        }
    }
}
