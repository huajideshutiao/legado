package io.legado.app.ui.compose.component

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.OutlinedButton
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSizeIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.LocalEInk

/**
 * 风格锁定 MD2 的 M3 组件包装件（plan §1.8）：中和 pill 形状/tonal 染色，
 * 界面代码一律经此使用，禁止裸用默认形状的 M3 组件。
 */

/** 描边按钮：复刻 Widget.Arco.Button.Outline(accent 描边 + accent 字，8dp 圆角)，禁用时置灰 */
@Composable
fun AppOutlinedButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (enabled) colors.accent else colors.secondaryText.copy(alpha = 0.3f)),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = colors.accent,
            disabledContentColor = colors.secondaryText.copy(alpha = 0.5f),
        ),
    ) {
        Text(text)
    }
}

/**
 * 复刻 selector_fillet_btn_bg + item_fillet_text：半透明 btn_bg 填充 + 8dp 圆角 + 4dp inset，
 * 按压切 arco_fill_3；字色 secondaryText 14sp 自然行高。contentPadding 语义同原 XML(自视图外缘计，
 * 含 4dp inset)，内层实际内边距要减掉 inset，否则 chip 会比原生大一圈。
 */
@Composable
fun AppFilletTextButton(
    text: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp), // arco lg × md
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val isDark = AppTheme.colors.isDark
    // btn_bg: light @color/btn_bg #100e0e0e / night #14e0e0e0
    val normalBg = if (isDark) Color(0x14e0e0e0) else Color(0x100e0e0e)
    // 按压: arco_fill_3 light #FFE6E6E6 / night #FF2A2A2A
    val pressedBg = if (isDark) Color(0xFF2A2A2A) else Color(0xFFE6E6E6)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val layoutDirection = LocalLayoutDirection.current
    val innerPadding = remember(contentPadding, layoutDirection) {
        PaddingValues(
            start = (contentPadding.calculateStartPadding(layoutDirection) - 4.dp).coerceAtLeast(0.dp),
            top = (contentPadding.calculateTopPadding() - 4.dp).coerceAtLeast(0.dp),
            end = (contentPadding.calculateEndPadding(layoutDirection) - 4.dp).coerceAtLeast(0.dp),
            bottom = (contentPadding.calculateBottomPadding() - 4.dp).coerceAtLeast(0.dp),
        )
    }
    Box(
        modifier
            .padding(4.dp) // inset 4dp
            .clip(RoundedCornerShape(8.dp)) // arco_radius_default
            .background(if (pressed) pressedBg else normalBg)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .padding(innerPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = AppTheme.colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // 不继承 LocalTextStyle：M3 bodyLarge 的 24sp lineHeight 会把小 chip 撑高
            style = TextStyle(fontSize = 14.sp),
        )
    }
}
@Composable
fun AppTextButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = AppTheme.colors.accent,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = color),
    ) {
        Text(text)
    }
}

/** 开关：自绘复刻 SwitchCompat/MD2 形态（细 track + 圆 thumb 带阴影），
 *  着色对齐 TintHelper.setTint(SwitchCompat)：勾选 track=accent α0.5 / thumb=accent，
 *  未选/禁用取 ate_switch_* 值。滑动动画走 animateDpAsState，E-Ink 禁动画。 */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = AppTheme.colors
    val isDark = colors.isDark
    // ate_switch_track_normal light #43000000 / dark #4DFFFFFF；disabled light #1F000000 / dark #1AFFFFFF
    val trackNormal = if (isDark) Color(0x4DFFFFFF) else Color(0x43000000)
    val trackDisabled = if (isDark) Color(0x1AFFFFFF) else Color(0x1F000000)
    // ate_switch_thumb_normal light #FFFAFAFA / dark #FFBDBDBD；disabled light #FFBDBDBD / dark #FF424242
    val thumbNormal = if (isDark) Color(0xFFBDBDBD) else Color(0xFFFAFAFA)
    val thumbDisabled = if (isDark) Color(0xFF424242) else Color(0xFFBDBDBD)

    val trackColor = when {
        !enabled -> trackDisabled
        checked -> colors.accent.copy(alpha = 0.5f) // compatSwitch track alpha 0.5
        else -> trackNormal
    }
    val thumbColor = when {
        !enabled -> thumbDisabled
        checked -> colors.accent
        else -> thumbNormal
    }

    val trackWidth = 34.dp
    val trackHeight = 14.dp
    val thumbSize = 20.dp
    val boxWidth = 36.dp // thumb 20dp 在 track 两端各溢出 1dp
    val travel = boxWidth - thumbSize // 16dp
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) travel else 0.dp,
        animationSpec = if (LocalEInk.current) snap() else spring(),
        label = "thumbOffset",
    )

    // 视觉盒固定 36×20(作为居中内容),命中区放大不撑高行
    val visual: @Composable () -> Unit = {
        Box(
            Modifier.size(width = boxWidth, height = thumbSize),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(width = trackWidth, height = trackHeight)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(trackColor),
            )
            Box(
                Modifier
                    .offset(x = thumbOffset)
                    .size(thumbSize)
                    .shadow(2.dp, CircleShape)
                    .background(thumbColor, CircleShape),
            )
        }
    }

    // 外层测量恒为 36×20 保持行高;命中区扩到 ≥48dp(SwitchCompat 触摸目标),
    // requiredSizeIn 让 toggleable 层溢出居中而不影响外层测量。
    Box(
        modifier.size(width = boxWidth, height = thumbSize),
        contentAlignment = Alignment.Center,
    ) {
        if (onCheckedChange != null) {
            Box(
                Modifier
                    .requiredSizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .toggleable(
                        value = checked,
                        enabled = enabled,
                        role = Role.Switch,
                        onValueChange = onCheckedChange,
                    ),
                contentAlignment = Alignment.Center,
            ) { visual() }
        } else {
            visual()
        }
    }
}

/** 复选框：accent 勾选色对齐 CheckBox 主题着色 */
@Composable
fun AppCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = AppTheme.colors
    Checkbox(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = CheckboxDefaults.colors(
            checkedColor = colors.accent,
            uncheckedColor = colors.secondaryText,
            checkmarkColor = colors.background,
        ),
    )
}

/**
 * 菜单勾选框：复刻 AppCompat MenuItem `android:checkable` 的经典 MD2 小方框（右置）。
 * 手绘 18dp 方框 + 2dp 圆角 + 2dp 描边，无 M3 大圆角/状态层放大（避免撑高菜单行）；
 * 勾选填 accent(ThemeStore 动态)、未选描边 secondaryText。仅用于 DropdownMenu 语境。
 */
@Composable
fun AppMenuCheckbox(
    checked: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val accent = colors.accent
    val stroke = colors.secondaryText
    val mark = colors.background
    Canvas(modifier.size(18.dp)) {
        val corner = CornerRadius(2.dp.toPx())
        val strokeWidth = 2.dp.toPx()
        if (checked) {
            drawRoundRect(color = accent, cornerRadius = corner)
            // 勾：left 24% → 中下 42% → right 76%（对照 MD2 checkmark 比例）
            val w = size.width
            val h = size.height
            val path = Path().apply {
                moveTo(w * 0.24f, h * 0.52f)
                lineTo(w * 0.42f, h * 0.70f)
                lineTo(w * 0.76f, h * 0.28f)
            }
            drawPath(
                path = path,
                color = mark,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        } else {
            val inset = strokeWidth / 2f
            drawRoundRect(
                color = stroke,
                topLeft = Offset(inset, inset),
                size = Size(size.width - strokeWidth, size.height - strokeWidth),
                cornerRadius = corner,
                style = Stroke(width = strokeWidth),
            )
        }
    }
}

/** 输入框：MD2 视觉 (委托 [Md2TextField])，accent 聚焦色 + 4dp 圆角，对齐 TextInputLayout 主题行为 */
@Composable
fun AppOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    readOnly: Boolean = false,
    singleLine: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    Md2TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        readOnly = readOnly,
        label = label,
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
        textStyle = LocalTextStyle.current.copy(fontSize = 16.sp),
    )
}
