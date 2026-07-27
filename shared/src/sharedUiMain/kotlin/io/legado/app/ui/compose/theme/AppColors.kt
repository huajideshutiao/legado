package io.legado.app.ui.compose.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * 对齐 ThemeStore 全部对外颜色的不可变主题色集合。
 * primaryText/isDark 复刻「由背景色亮度反推」语义(ColorUtils.isColorLight)，非系统夜间模式。
 * fillet 为圆角底色(= bottomBackground)，供 filletBackground 语义使用。
 *
 * KMP 共享: 从 app/ui/compose/theme 下沉到 shared/commonMain, app + desktop 直接复用。
 */
@Immutable
data class AppColors(
    val accent: Color,
    val background: Color,
    val bottomBackground: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val menuText: Color,
    val summaryText: Color,
    // 对齐 ate_control_normal_light/dark: 用于 TextField unfocused 下划线/label/placeholder
    val controlNormal: Color,
    // 对齐 ate_text_disabled_light/dark: 用于 TextField disabled 状态文字/下划线/label/placeholder
    val textDisabled: Color,
    val statusBar: Color,
    val navigationBar: Color,
    val fillet: Color,
    val isDark: Boolean,
)
