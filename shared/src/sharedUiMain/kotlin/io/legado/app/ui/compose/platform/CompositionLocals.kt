package io.legado.app.ui.compose.platform

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 跨平台 Provider 注入点。AppTheme 在 commonMain 通过 CompositionLocal 取依赖，
 * 各平台在 Compose 入口用 CompositionLocalProvider 注入 actual 实例：
 * - Android: app 模块的 AndroidThemeStoreProvider / AndroidAppConfigProvider / AndroidEventBusProvider
 * - 桌面 jvm: DesktopThemeStoreProvider 等（含 mutableStateOf 可切换主题）
 * - iOS/鸿蒙: stub 实现（KP3/KP4 替换）
 *
 * 不用 expect/actual 是因为 interface 本身已是平台无关契约，actual 实例由各平台
 * 自行构造注入，避免 shared androidMain 反向依赖 app 模块。
 */
val LocalThemeStoreProvider = staticCompositionLocalOf<ThemeStoreProvider> {
    error("ThemeStoreProvider not provided, wrap content in AppTheme with CompositionLocalProvider")
}
val LocalAppConfigProvider = staticCompositionLocalOf<AppConfigProvider> {
    error("AppConfigProvider not provided, wrap content in AppTheme with CompositionLocalProvider")
}
val LocalEventBusProvider = staticCompositionLocalOf<EventBusProvider> {
    error("EventBusProvider not provided, wrap content in AppTheme with CompositionLocalProvider")
}

val LocalPreferenceStoreProvider = staticCompositionLocalOf<PreferenceStoreProvider> {
    error("PreferenceStoreProvider not provided, wrap content in AppTheme with CompositionLocalProvider")
}
