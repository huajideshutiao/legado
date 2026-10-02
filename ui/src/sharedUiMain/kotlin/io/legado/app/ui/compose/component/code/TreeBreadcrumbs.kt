package io.legado.app.ui.compose.component.code

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens

/**
 * 树视图面包屑 (JSON 树与 HTML 树共用): 首项为整份文档, 其后每级一个可点标签,
 * 点任意一级即退回该级。[enabledLabels] 为 null 时全部可点 (HTML 真实路径),
 * 否则逐项控制 (JSON 真实路径中被跳过的中间层不可点, 置灰显示)。
 */
@Composable
internal fun TreeBreadcrumb(
    rootLabel: String,
    labels: List<String>,
    onRootClick: () -> Unit,
    onLabelClick: (Int) -> Unit,
    enabledLabels: List<Boolean>? = null,
) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = DesignTokens.spacingDefault, vertical = DesignTokens.spacingXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BreadcrumbLabel(text = rootLabel, onClick = onRootClick)
        labels.forEachIndexed { index, label ->
            Text(
                text = "›",
                color = colors.secondaryText,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            val enabled = enabledLabels?.getOrNull(index) ?: true
            BreadcrumbLabel(label, enabled) { onLabelClick(index) }
        }
    }
}

@Composable
private fun BreadcrumbLabel(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Text(
        text = text,
        color = if (enabled) colors.accent else colors.secondaryText,
        fontSize = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .widthIn(max = 160.dp)
            .clickable(enabled = enabled, onClick = onClick),
    )
}
