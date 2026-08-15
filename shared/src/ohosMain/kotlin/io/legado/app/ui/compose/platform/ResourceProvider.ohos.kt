package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import io.legado.app.ui.compose.theme.LocalAppColors

/**
 * 鸿蒙端无备用桌面图标, 本函数恒返回空列表 (非未完成 stub, 见下):
 *
 * - HAP 内仅 AppScope/resources/base/media 下单一 layered 图标
 *   (app_icon_background + app_icon_foreground, 见 ohosApp/AppScope),
 *   无 Android mipmap launcher1/launcher4/launcher5 / iOS bundle Icon1/Icon4/Icon5 等价物;
 * - HarmonyOS 亦无运行时切换图标 API (对照 Android setComponentEnabledSetting /
 *   iOS setAlternateIconName), [io.legado.app.ui.root.PlatformCapabilities.launcherIconChangeSupported]
 *   默认 false, 主题设置页"换图标"项已隐藏 (见 ThemeConfigRoute);
 * - 且 ThemeConfigScreen 仅在 iconChangeSupported 时求值 iconPainters, 本函数在鸿蒙端
 *   实际不会被调用; 返回空列表与"无图标可预览"语义一致。
 */
@Composable
actual fun rememberLauncherIconPainters(iconValues: List<String>): List<Painter?> = emptyList()

@Composable
actual fun rememberColor(key: String): Color {
    // 共享色板单一数据源 (ColorPalette.kt): light/dark 按主题背景亮度分支,
    // 对齐 Android values/values-night 资源限定符语义
    return resolvePaletteColor(key, LocalAppColors.current.isDark)
}
