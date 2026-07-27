package io.legado.app.ui.compose.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.ThemeStoreProvider
import io.legado.app.ui.compose.platform.platformTextStyleNoFontPadding

/**
 * E-Ink 模式标记，供组件去动画/黑白化。
 *
 * default=false：commonMain 无法访问 [io.legado.app.help.config.AppConfig]，
 * 实际值在 [AppTheme] 内通过 [LocalAppConfigProvider] provides；未包在 AppTheme
 * 内的子树用 false 兜底（与 Android 端非 E-Ink 模式行为一致）。
 */
val LocalEInk = compositionLocalOf { false }

val LocalAppColors = staticCompositionLocalOf<AppColors> {
    error("AppColors not provided, wrap content in AppTheme")
}

object AppTheme {
    val colors: AppColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAppColors.current
}

/**
 * 从 [ThemeStoreProvider] 读当前主题色。primaryText/secondaryText/isDark 复刻
 * MaterialValueHelper「背景色亮度反推」语义(旧 isDarkTheme 命名反转，实为背景是浅色)。
 *
 * 用 [Color.luminance] 替代 Android 专属 [io.legado.app.utils.ColorUtils.isColorLight]
 * (后者依赖 androidx.core.graphics.ColorUtils)，commonMain 直接消费 Compose Color。
 */
private fun readAppColors(themeStore: ThemeStoreProvider): AppColors {
    val bg = themeStore.backgroundColor
    val bgIsLight = bg.luminance() >= 0.5f
    val bottomBg = themeStore.bottomBackground
    return AppColors(
        accent = themeStore.accentColor,
        background = bg,
        bottomBackground = bottomBg,
        // 对齐 md_light_primary_text(#DE000000)/md_dark_primary_text(#FFFFFFFF)
        primaryText = if (bgIsLight) Color(0xDE000000) else Color(0xFFFFFFFF),
        // 对齐 md_light_secondary(#8A000000)/getSecondaryTextColor 深底分支(白)
        secondaryText = if (bgIsLight) Color(0x8A000000) else Color(0xFFFFFFFF),
        statusBar = themeStore.statusBarColor,
        navigationBar = themeStore.navigationBarColor,
        fillet = bottomBg,
        isDark = !bgIsLight,
    )
}

/** AppColors → M3 ColorScheme，供 material3 内置组件取色 */
private fun AppColors.toColorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = if (accent.luminance() >= 0.5f) Color(0xDE000000) else Color(0xFFFFFFFF),
        secondary = accent,
        background = background,
        onBackground = primaryText,
        surface = background,
        onSurface = primaryText,
        surfaceVariant = bottomBackground,
        onSurfaceVariant = secondaryText,
        // 风格锁定 MD2：禁 tonal elevation 染色；容器族(菜单/弹窗底)统一走底栏色
        surfaceTint = Color.Transparent,
        surfaceContainerLowest = bottomBackground,
        surfaceContainerLow = bottomBackground,
        surfaceContainer = bottomBackground,
        surfaceContainerHigh = bottomBackground,
        surfaceContainerHighest = bottomBackground,
    )
}

/**
 * 默认文本对齐 View TextView：14sp/自然行高/零字距。压制 M3 bodyLarge 的隐式默认
 * (16sp/24sp 行高/0.5 字距)——它会把所有只设 fontSize 的 Text 撑高。
 * includeFontPadding=false 同原 XML 各处显式声明；显式设 style/字号齐全的 M3 组件不受影响。
 *
 * platformStyle 走 [platformTextStyleNoFontPadding] expect/actual: Android actual
 * 返回 `PlatformTextStyle(includeFontPadding = false)`, 其他平台返回 null (无此概念)。
 */
private val defaultTextStyle = TextStyle(
    fontSize = 14.sp,
    platformStyle = platformTextStyleNoFontPadding(),
)

/** 风格锁定 MD2：全档小圆角。普通控件 4dp 系，dialog/按钮对齐 filletBackground 实际半径 8dp */
private val appShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

/**
 * Compose 主题入口：提供 AppTheme.colors / LocalEInk，并映射 MaterialTheme。
 *
 * 依赖通过 CompositionLocal 注入（[LocalThemeStoreProvider] / [LocalAppConfigProvider]
 * / [LocalEventBusProvider]），各平台在 Compose 入口用 CompositionLocalProvider
 * 注入 actual 实例。观察 [EventBusProvider.recreateEvent] 即时刷新颜色 State——变色即
 * 重组，不依赖 Activity recreate。
 *
 * @see io.legado.app.ui.compose.platform
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val themeStore = LocalThemeStoreProvider.current
    val appConfig = LocalAppConfigProvider.current
    val eventBus = LocalEventBusProvider.current

    var colors by remember(themeStore) { mutableStateOf(readAppColors(themeStore)) }
    // 观察 recreateEvent：发射时重读 ThemeStore 刷新 colors，触发重组
    LaunchedEffect(themeStore, eventBus) {
        eventBus.recreateEvent.collect {
            colors = readAppColors(themeStore)
        }
    }
    val textToolbar = remember { ComposeTextToolbar() }
    CompositionLocalProvider(
        LocalAppColors provides colors,
        LocalEInk provides appConfig.isEInkMode,
        LocalTextToolbar provides textToolbar,
    ) {
        MaterialTheme(
            colorScheme = colors.toColorScheme(),
            shapes = appShapes,
        ) {
            // 直接 provides 整体替换(ProvideTextStyle 是 merge, 压不掉 bodyLarge 的行高/字距)
            CompositionLocalProvider(LocalTextStyle provides defaultTextStyle) {
                content()
                // 自绘文本选择菜单宿主：全局接管系统 ActionMode，逐界面零改动
                textToolbar.Host()
            }
        }
    }
}
