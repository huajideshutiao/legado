package io.legado.app.help.config

import io.legado.app.ui.compose.platform.PreferenceStoreProvider

/**
 * 桌面 JVM 端 [ReadConfigProviders] 实现。
 *
 * 与 `DesktopThemeStoreProvider` / `DesktopPreferenceStoreProvider` 一致：
 * 桌面端无 SharedPreferences / readConfig.json 底座，构造时传入
 * [prefs]（通常是 `DesktopPreferenceStoreProvider` 内存 Map 实例），
 * 内部构造 [ReadBookConfigShared] 与 [ReadTipConfigShared]。
 *
 * 调用方在桌面 Compose 入口注入：
 * ```kotlin
 * val desktopPrefs = DesktopPreferenceStoreProvider()
 * val readConfigProviders = DesktopReadConfigProviders(desktopPrefs)
 * CompositionLocalProvider(LocalReadConfigProviders provides readConfigProviders) {
 *     AppWindow()
 * }
 * ```
 *
 * 不持有任何状态，所有配置读写都委托给 [prefs]，进程结束即丢失
 * （与 `DesktopPreferenceStoreProvider` 的内存 Map 行为一致）。
 */
class DesktopReadConfigProviders(
    prefs: PreferenceStoreProvider,
) : ReadConfigProviders {

    override val readBookConfig: ReadBookConfigShared = ReadBookConfigShared(prefs)

    override val readTipConfig: ReadTipConfigShared =
        ReadTipConfigShared(readBookConfig)
}
