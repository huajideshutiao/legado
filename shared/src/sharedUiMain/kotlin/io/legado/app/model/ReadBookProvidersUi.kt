package io.legado.app.model

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * ReadBookProvider 的 CompositionLocal 注入点。
 *
 * Compose 内通过 `LocalReadBookProvider.current.readBook` 取 [ReadBookShared] 实例,
 * 在未用 CompositionLocalProvider 包裹时抛错 (与 LocalThemeStoreProvider 等保持一致)。
 *
 * KP5: 从 commonMain 的 ReadBookProviders.kt 拆分到 sharedUiMain, 让 ohos/linuxArm64
 * 不依赖 Compose 也能编译 (staticCompositionLocalOf 属于 androidx.compose.runtime)。
 * interface ReadBookProvider 保留在 commonMain, 本文件仅承载 Compose 注入点。
 */
val LocalReadBookProvider = staticCompositionLocalOf<ReadBookProvider> {
    error("ReadBookProvider not provided, wrap content in AppTheme with CompositionLocalProvider")
}
