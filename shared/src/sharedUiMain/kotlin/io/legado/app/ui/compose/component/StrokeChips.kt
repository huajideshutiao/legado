package io.legado.app.ui.compose.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
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

/**
 * 复刻 StrokeTextView（描边小按钮）：1dp 描边圆角，按压用 Compose 默认指示。
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
    @OptIn(ExperimentalFoundationApi::class)
    Text(
        text = text,
        color = textColor,
        fontSize = 14.sp,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(shape)
            .border(DesignTokens.strokeThin, textColor, shape)
            .combinedClickable(
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
