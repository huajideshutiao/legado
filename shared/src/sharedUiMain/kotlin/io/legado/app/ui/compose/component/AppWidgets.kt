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
import androidx.compose.runtime.setValue
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
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.compose.theme.LocalEInk
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * MD2 风格组件包装件（plan §1.8）：包装 androidx.compose.material (M2) 组件并
 * 统一 Arco 配色/形状，界面代码一律经此使用，禁止裸用默认样式的 material 组件。
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
        shape = DesignTokens.buttonShape,
        border = BorderStroke(DesignTokens.strokeThin, if (enabled) colors.accent else colors.secondaryText.copy(alpha = 0.3f)),
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
            .clip(DesignTokens.shapeDefault)
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
        shape = DesignTokens.buttonShape,
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

/** 输入框：MD2 下划线形态 (委托 [AppTextField])，accent 聚焦下划线+浮动 label，对齐 TextInputLayout(boxBackgroundMode=none) 行为 */
@Composable
fun AppOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    readOnly: Boolean = false,
    singleLine: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    AppTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        readOnly = readOnly,
        label = label,
        placeholder = placeholder,
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
        textStyle = LocalTextStyle.current.copy(fontSize = 16.sp),
    )
}

// ===== @Preview 合并自 androidMain 的 compose/component/AppWidgetsPreviews.kt =====

/**
 * [AppWidgets.kt] 中各 Composable 的 @Preview。
 * - [AppOutlinedButton]: 描边按钮
 * - [AppFilletTextButton]: 圆角填充小按钮 (chip 形态)
 * - [AppTextButton]: 文本按钮
 * - [AppSwitch]: 自绘开关
 * - [AppCheckbox]: 复选框
 * - [AppMenuCheckbox]: 菜单勾选框 (DropdownMenu 内用)
 * - [AppOutlinedTextField]: 输入框
 */

@Preview
@Composable
fun AppOutlinedButtonPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        AppOutlinedButton(text = "描边按钮", onClick = {})
    }
}

@Preview
@Composable
fun AppOutlinedButtonDisabledPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        AppOutlinedButton(text = "禁用按钮", enabled = false, onClick = {})
    }
}

@Preview
@Composable
fun AppFilletTextButtonPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        AppFilletTextButton(text = "圆角小按钮", onClick = {})
    }
}

@Preview
@Composable
fun AppTextButtonPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        AppTextButton(text = "文本按钮", onClick = {})
    }
}

@Preview
@Composable
fun AppSwitchPreview() = LegadoThemePreview {
    var checked by remember { mutableStateOf(true) }
    Box(Modifier.padding(16.dp)) {
        AppSwitch(checked = checked, onCheckedChange = { checked = it })
    }
}

@Preview
@Composable
fun AppSwitchUncheckedPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        AppSwitch(checked = false, onCheckedChange = {})
    }
}

@Preview
@Composable
fun AppSwitchDisabledPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        AppSwitch(checked = true, onCheckedChange = null, enabled = false)
    }
}

@Preview
@Composable
fun AppCheckboxPreview() = LegadoThemePreview {
    var checked by remember { mutableStateOf(true) }
    Box(Modifier.padding(16.dp)) {
        AppCheckbox(checked = checked, onCheckedChange = { checked = it })
    }
}

@Preview
@Composable
fun AppMenuCheckboxCheckedPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        AppMenuCheckbox(checked = true)
    }
}

@Preview
@Composable
fun AppMenuCheckboxUncheckedPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        AppMenuCheckbox(checked = false)
    }
}

@Preview
@Composable
fun AppOutlinedTextFieldPreview() = LegadoThemePreview {
    var value by remember { mutableStateOf("输入内容") }
    Box(Modifier.padding(16.dp)) {
        AppOutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = "标签",
            singleLine = true,
            modifier = Modifier.width(200.dp),
        )
    }
}

@Preview
@Composable
fun AppOutlinedTextFieldPasswordPreview() = LegadoThemePreview {
    var value by remember { mutableStateOf("password") }
    Box(Modifier.padding(16.dp)) {
        AppOutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = "密码",
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.width(200.dp),
        )
    }
}

@Preview
@Composable
fun AppWidgetsGalleryPreview() = LegadoThemePreview {
    Column(
        Modifier.padding(16.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppOutlinedButton(text = "描边按钮", onClick = {})
        AppTextButton(text = "文本按钮", onClick = {})
        AppFilletTextButton(text = "圆角按钮", onClick = {})
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            AppSwitch(checked = true, onCheckedChange = {})
            Spacer(Modifier.width(16.dp))
            AppSwitch(checked = false, onCheckedChange = {})
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            AppCheckbox(checked = true, onCheckedChange = {})
            Spacer(Modifier.width(16.dp))
            AppMenuCheckbox(checked = true)
            Spacer(Modifier.width(16.dp))
            AppMenuCheckbox(checked = false)
        }
    }
}
