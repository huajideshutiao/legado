package io.legado.app.ui.book.read.page.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.component.AppDropdownField
import io.legado.app.ui.compose.component.AppSwitch
import io.legado.app.ui.compose.preference.ColorPickerDialog
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.highlight_color
import legado.ui.generated.resources.highlight_colored
import legado.ui.generated.resources.highlight_style
import legado.ui.generated.resources.highlight_style_none
import legado.ui.generated.resources.highlight_style_strikethrough
import legado.ui.generated.resources.highlight_style_underline
import legado.ui.generated.resources.highlight_style_wavy
import org.jetbrains.compose.resources.stringResource

// 批注与关键词高亮共用的选择件 (存储/选择/绘制同源)。

/** 新建/重新上色的默认色 (与实体默认一致) */
const val DefaultHighlightColor = 0x50FFE082

/**
 * 高亮样式行: 取色圆点 → 上色开关 → 线型文案 + 下拉 (批注气泡、关键词高亮编辑共用)。
 *
 * @param trailing 行尾附件 (批注气泡的删除入口), 传了则右对齐
 */
@Composable
fun HighlightStyleRow(
    color: Int?,
    onColorChange: (Int?) -> Unit,
    lineStyle: Int,
    onLineStyleChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    labelFontSize: TextUnit = 14.sp,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val coloredLabel = stringResource(Res.string.highlight_colored)
        HighlightStyleButton(color = color, onColorPicked = onColorChange)
        Spacer(Modifier.width(DesignTokens.spacingXs))
        // 上色开关: 关 = color 存 null (不画色块, 只剩线型); 开 = 无色时取默认色
        AppSwitch(
            checked = color != null,
            onCheckedChange = { on ->
                onColorChange(if (on) (color ?: DefaultHighlightColor) else null)
            },
            modifier = Modifier.semantics { contentDescription = coloredLabel },
        )
        Spacer(Modifier.width(DesignTokens.spacingDefault))
        Text(
            text = stringResource(Res.string.highlight_style),
            color = AppTheme.colors.primaryText,
            fontSize = labelFontSize,
        )
        Spacer(Modifier.width(DesignTokens.spacingXs))
        // 线型: 与回显同源 HighlightLineStyle, 改动即落库
        AppDropdownField(
            options = HighlightLineStyleLabels(),
            selectedIndex = HighlightLineStyleValues.indexOf(lineStyle).coerceAtLeast(0),
            onSelect = { onLineStyleChange(HighlightLineStyleValues[it]) },
        )
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}

/** 上色圆点: 有色实心/无色空心, 点击弹取色盘 (confirm 统一压 0x50 alpha, 保证不压字形) */
@Composable
fun HighlightStyleButton(
    color: Int?,
    onColorPicked: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPicker by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(28.dp)
            .clip(CircleShape)
            .then(if (color != null) Modifier.background(Color(color)) else Modifier)
            .border(DesignTokens.strokeThin, AppTheme.colors.secondaryText, CircleShape)
            .clickable { showPicker = true },
    )
    if (showPicker) {
        ColorPickerDialog(
            initColor = color ?: DefaultHighlightColor,
            title = stringResource(Res.string.highlight_color),
            onDismissRequest = { showPicker = false },
            onConfirm = {
                showPicker = false
                // 统一压 0x50 alpha; 位运算精确置位 (withAlpha(0x50/255f) 浮点换算会落 79)
                onColorPicked(it and 0x00FFFFFF or 0x50000000)
            },
        )
    }
}

/** 下拉选项文案 (下标与 [HighlightLineStyleValues] 同序) */
@Composable
private fun HighlightLineStyleLabels(): List<String> = listOf(
    stringResource(Res.string.highlight_style_none),
    stringResource(Res.string.highlight_style_underline),
    stringResource(Res.string.highlight_style_wavy),
    stringResource(Res.string.highlight_style_strikethrough),
)

/** 下拉下标 → 线型取值 (两者同序: 无/下划线/波浪线/删除线) */
private val HighlightLineStyleValues = listOf(
    HighlightLineStyle.NONE,
    HighlightLineStyle.UNDERLINE,
    HighlightLineStyle.WAVY,
    HighlightLineStyle.STRIKETHROUGH,
)
