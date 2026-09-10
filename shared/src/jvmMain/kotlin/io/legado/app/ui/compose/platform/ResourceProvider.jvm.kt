package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import io.legado.app.ui.compose.theme.LocalAppColors

@Composable
actual fun rememberColor(key: String): Color {
    // 共享色板单一数据源 (ColorPalette.kt): light/dark 按主题背景亮度分支,
    // 对齐 Android values/values-night 资源限定符语义
    return resolvePaletteColor(key, LocalAppColors.current.isDark)
}

/**
 * 桌面端无桌面图标概念 (对照 Android 自适应图标 / iOS 交替图标), 恒返回空列表;
 * 主题设置页"换图标"项已由 launcherIconChangeSupported=false 隐藏 (见 ThemeConfigRoute),
 * 且 ThemeConfigScreen 仅在 iconChangeSupported 时求值 iconPainters, 本函数在桌面端不会被调用。
 */
@Composable
actual fun rememberLauncherIconPainters(iconValues: List<String>): List<Painter?> = emptyList()


