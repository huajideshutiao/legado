package io.legado.app.ui.compose.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSizeIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.compose.theme.LocalEInk

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
            // M2 outlinedButtonColors 默认 backgroundColor=surface (不透明), 会盖住页面壁纸;
            // outlined 语义应为镂空 (仅描边), 与壁纸页/透明容器配套。M2 参数名是 backgroundColor
            backgroundColor = Color.Transparent,
            contentColor = colors.accent,
            disabledContentColor = colors.secondaryText.copy(alpha = 0.5f),
        ),
    ) {
        Text(text)
    }
}

// 胶囊基础几何: 内边距语义同原 XML (自视图外缘计, 含 inset), 内层要减掉 inset 否则比原生大一圈。
// 内边距 internal: 简介行内胶囊要按它算 InlineTextContent 占位尺寸, 避免调用点再写一份。
private val filletInset = 4.dp
internal val filletChipPaddingH = 16.dp // arco lg
internal val filletChipPaddingV = 12.dp // arco md

/**
 * 复刻 selector_fillet_btn_bg + item_fillet_text：半透明 btn_bg 填充 + 8dp 圆角 + 4dp inset，
 * 按压切 arco_fill_3；secondaryText 14sp 自然行高。
 *
 * master 端各屏共用的 fillet 胶囊 (item_fillet_text / IntroButtonSpan / setUpExploreOptions /
 * activity_source_debug) 在 Compose 下统一收拢到本组件：基础样式 (字色/内边距/背景/圆角/字号)
 * 一律组件内定，调用点不传；只有语义差异走参数：
 * - [alpha]/[bold]：对齐 setUpExploreOptions 标题 chip 与搜索选项的 0.8/1.0/0.5 语义
 *   (KindChip 组名 label、SearchOptionChip 选中/未选中、ExploreOptionsRow 标题)
 * - [onLongClick]：收藏/历史词长按删除等
 * - [onClick] 为 null 时纯展示不可点 (书籍详情分类组名 label)
 * - [focusable] 为 false 时桌面端不抢输入法焦点 (书源调试 HelpPanel 场景)
 */
@Composable
fun AppFilletTextButton(
    text: String,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    bold: Boolean = false,
    focusable: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val isDark = AppTheme.colors.isDark
    // btn_bg: light @color/btn_bg #100e0e0e / night #14e0e0e0
    val normalBg = if (isDark) Color(0x14e0e0e0) else Color(0x100e0e0e)
    // 按压: 恢复 Arco 化之前的 btn_bg_press_2 (light #20000000 / night #20ffffff,
    // 半透明加深/提亮一档, 壁纸页保持镂空; Arco 化换成的 arco_fill_3 不透明实色在壁纸场景跳变)
    val pressedBg = if (isDark) Color(0x20ffffff) else Color(0x20000000)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .alpha(alpha)
            .padding(filletInset)
            .clip(DesignTokens.shapeDefault)
            .background(if (pressed) pressedBg else normalBg)
            .then(
                if (onClick != null || onLongClick != null) Modifier.combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = { onClick?.invoke() },
                    onLongClick = onLongClick,
                ) else Modifier
            )
            .then(if (focusable) Modifier else Modifier.focusProperties { canFocus = false })
            .padding(
                horizontal = filletChipPaddingH - filletInset,
                vertical = filletChipPaddingV - filletInset,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = AppTheme.colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (bold) FontWeight.Bold else null,
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

/**
 * 输入框：MD2 下划线形态 (委托 [AppTextField])，accent 聚焦下划线+浮动 label，对齐
 * TextInputLayout(boxBackgroundMode=none) 行为。
 *
 * 曾用名 AppOutlinedTextField (误导: 实为下划线形态, 非 outlined 边框盒, 2026 重命名)。
 * 相比直接调 [AppTextField] 的增值: 统一 16sp 字号 + 常用参数 (label/placeholder/
 * readOnly/singleLine/visualTransformation/trailingIcon) 透传, 表单/密码框/对话框字段共用。
 */
@Composable
fun AppUnderlineTextField(
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
