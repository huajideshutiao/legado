package io.legado.app.ui.compose.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.utils.ColorUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * 复刻 StrokeTextView（描边小按钮）：1dp 描边圆角 + 按压半透明底（transparent30）。
 * 阅读页底部弹窗中 isBottomBackground=true 时文字/描边取底栏反推文字色，由调用方传 [textColor]。
 */
@Composable
fun StrokeTextChip(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = AppTheme.colors.secondaryText,
    cornerRadius: androidx.compose.ui.unit.Dp = DesignTokens.radiusSm,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    @OptIn(ExperimentalFoundationApi::class)
    Text(
        text = text,
        color = textColor,
        fontSize = 14.sp,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(shape)
            .background(if (pressed) Color(0x30000000) else Color.Transparent)
            .border(DesignTokens.strokeThin, textColor, shape)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/**
 * 复刻 ThemeRadioNoButton（无圆点的分段单选钮）：2dp 圆角 + 2dp 描边；
 * 选中 accent 实底、文字按 accent 亮度取黑/白；未选中描边/文字用 [textColor]。
 */
@Composable
fun RadioChip(
    text: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    textColor: Color = AppTheme.colors.primaryText,
    onClick: () -> Unit,
) {
    val accent = AppTheme.colors.accent
    val checkedText = if (ColorUtils.isColorLight(accent.toArgb())) Color.Black else Color.White
    val shape = RoundedCornerShape(2.dp)
    Text(
        text = text,
        color = if (checked) checkedText else textColor,
        fontSize = 14.sp,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(shape)
            .background(if (checked) accent else Color.Transparent)
            .border(DesignTokens.strokeMedium, if (checked) accent else textColor, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(4.dp),
    )
}

// ===== @Preview 合并自 androidMain 的 compose/component/StrokeChipsPreviews.kt =====

/**
 * [StrokeChips.kt] 中各 Composable 的 @Preview。
 * - [StrokeTextChip]: 描边小按钮 (阅读页底部弹窗用)
 * - [RadioChip]: 分段单选钮 (无圆点)
 *
 * 注: 用 FlowRow 排列多 chip 以预览各种状态。FlowRow 在 compose-foundation 1.7+ 可用。
 */

@Preview
@Composable
fun StrokeTextChipPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        StrokeTextChip(text = "描边按钮", onClick = {})
    }
}

@Preview
@Composable
fun StrokeTextChipGroupPreview() = LegadoThemePreview {
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    FlowRow(
        modifier = Modifier.padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StrokeTextChip(text = "chip1", onClick = {})
        StrokeTextChip(text = "chip2", onClick = {})
        StrokeTextChip(text = "chip3", onClick = {})
        StrokeTextChip(text = "长一点的 chip", onClick = {})
    }
}

@Preview
@Composable
fun RadioChipCheckedPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        RadioChip(text = "选中", checked = true, onClick = {})
    }
}

@Preview
@Composable
fun RadioChipUncheckedPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        RadioChip(text = "未选中", checked = false, onClick = {})
    }
}

@Preview
@Composable
fun RadioChipGroupPreview() = LegadoThemePreview {
    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RadioChip(text = "选项1", checked = true, onClick = {})
        RadioChip(text = "选项2", checked = false, onClick = {})
        RadioChip(text = "选项3", checked = false, onClick = {})
    }
}

@Preview
@Composable
fun RadioChipGroupDarkPreview() = LegadoThemePreview(dark = true) {
    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RadioChip(text = "选项1", checked = true, onClick = {})
        RadioChip(text = "选项2", checked = false, onClick = {})
    }
}
