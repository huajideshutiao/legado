package io.legado.app.help.config

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 跨平台 Compose 入口的 [ReadConfigProviders] 注入点。
 *
 * 各平台在 Compose 根（如 `AppTheme`）用 `CompositionLocalProvider` 注入 actual 实例：
 * ```kotlin
 * CompositionLocalProvider(LocalReadConfigProviders provides desktopReadConfigProviders) {
 *     AppContent()
 * }
 * ```
 *
 * 未注入时取值会抛 `IllegalStateException`，帮助早期发现遗漏。
 *
 * KP5: 从 commonMain 的 ReadConfigProviders.kt 拆分到 sharedUiMain, 让 ohos/linuxArm64
 * 不依赖 Compose 也能编译 (staticCompositionLocalOf 属于 androidx.compose.runtime)。
 * interface ReadConfigProviders 和工厂函数保留在 commonMain (工厂依赖 PreferenceProviders 单例,
 * 是纯接口, 也在 commonMain), 本文件仅承载 Compose 注入点。
 */
val LocalReadConfigProviders = staticCompositionLocalOf<ReadConfigProviders> {
    error("ReadConfigProviders not provided, wrap content with CompositionLocalProvider")
}
