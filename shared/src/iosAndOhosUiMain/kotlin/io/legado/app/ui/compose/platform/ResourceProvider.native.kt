package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.legado.app.ui.compose.theme.LocalAppColors

/**
 * iOS / 鸿蒙共用的 [rememberColor] actual (两端原实现逐字相同, 合并去重)。
 *
 * 同文件的 `rememberLauncherIconPainters` 两端实现不同 (iOS 读 NSBundle PNG,
 * 鸿蒙恒空列表), 仍留在各自 leaf —— 两个 expect 相互独立, actual 可落不同源集。
 */
@Composable
actual fun rememberColor(key: String): Color {
    // 共享色板单一数据源 (ColorPalette.kt): light/dark 按主题背景亮度分支,
    // 对齐 Android values/values-night 资源限定符语义
    return resolvePaletteColor(key, LocalAppColors.current.isDark)
}
