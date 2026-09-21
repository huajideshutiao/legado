package io.legado.app.help.config

import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIScreen

/**
 * iOS actual: 从 UITraitCollection 读系统深浅色 (须主线程)。
 *
 * UITraitCollection 只保证主线程可读, 故只在启动早期与 Compose 侧 trait 变化回写时调用,
 * 业务侧一律读 [NativeSystemTheme.isNight] 内存缓存 (见 MainViewController 的 LocalSystemTheme 回写)。
 */
internal actual fun probeSystemNightMode(): Boolean? =
    UIScreen.mainScreen.traitCollection.userInterfaceStyle ==
        UIUserInterfaceStyle.UIUserInterfaceStyleDark
