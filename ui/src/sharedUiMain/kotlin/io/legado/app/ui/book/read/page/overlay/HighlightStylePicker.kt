package io.legado.app.ui.book.read.page.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.legado.app.ui.compose.component.AppChipRow
import io.legado.app.ui.compose.component.AppChipRowOption
import io.legado.app.ui.compose.preference.ColorPickerDialog
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.highlight_color
import legado.ui.generated.resources.highlight_style_none
import legado.ui.generated.resources.highlight_style_strikethrough
import legado.ui.generated.resources.highlight_style_underline
import legado.ui.generated.resources.highlight_style_wavy
import org.jetbrains.compose.resources.stringResource

// 批注与关键词高亮共用的选择件 (存储/选择/绘制同源)。

/** 新建/重新上色的默认色 (与实体默认一致) */
const val DefaultHighlightColor = 0x50FFE082

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

/** 线型 chips 行: 无/下划线/波浪线/删除线, 选中全亮未选中半透明 (AppChipRow 语义) */
@Composable
fun HighlightLineStyleRow(
    selected: Int,
    onLineStyleChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    AppChipRow(modifier = modifier) {
        AppChipRowOption(
            text = stringResource(Res.string.highlight_style_none),
            selected = selected == HighlightLineStyle.NONE,
        ) { onLineStyleChange(HighlightLineStyle.NONE) }
        AppChipRowOption(
            text = stringResource(Res.string.highlight_style_underline),
            selected = selected == HighlightLineStyle.UNDERLINE,
        ) { onLineStyleChange(HighlightLineStyle.UNDERLINE) }
        AppChipRowOption(
            text = stringResource(Res.string.highlight_style_wavy),
            selected = selected == HighlightLineStyle.WAVY,
        ) { onLineStyleChange(HighlightLineStyle.WAVY) }
        AppChipRowOption(
            text = stringResource(Res.string.highlight_style_strikethrough),
            selected = selected == HighlightLineStyle.STRIKETHROUGH,
        ) { onLineStyleChange(HighlightLineStyle.STRIKETHROUGH) }
    }
}
