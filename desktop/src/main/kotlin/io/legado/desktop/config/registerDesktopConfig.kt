package io.legado.desktop.config

import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.config.ReadBookConfigProviders
import io.legado.app.help.config.ReadBookConfigShared
import io.legado.app.help.config.ThemeConfigData
import io.legado.app.help.config.ThemeConfigProvider
import io.legado.app.help.config.ThemeConfigProviders
import io.legado.app.ui.compose.platform.DesktopPreferenceStoreProvider
import io.legado.app.ui.compose.platform.PreferenceStoreProvider

/**
 * 桌面端启动早期注册所有 config 相关 provider（KP1.4 + 备份格式兼容性补齐）。
 *
 * - [PreferenceProviders]: 注入 [DesktopPreferenceProvider] (java.util.prefs.Preferences 实现)
 * - [AppConfigProviders]: 注入 [DesktopAppConfigAccessor] (5 个只读配置项)
 * - [ReadBookConfigProviders]: 注入 [ReadBookConfigShared] (内存 configList, 供 BackupShared 备份 readConfig.json)
 * - [ThemeConfigProviders]: 注入 [DesktopThemeConfigProvider] (内存 configList, 供 BackupShared 备份 themeConfig.json)
 *
 * 调用时机: 桌面端 main 入口, 任何 shared 调用之前。
 * 模式参考 app 端 `registerAndroidWebBookProviders` / `registerAndroidPasswordProvider`。
 *
 * @param preferenceStoreProvider 桌面端 [PreferenceStoreProvider] 实例 (由 Main.kt 顶层
 *   `remember` 构造, 供 [ReadBookConfigShared] 读写样式配置, 同一实例在 Compose Window
 *   内复用以保证 UI 与备份逻辑状态同步)
 * @return 构造好的 [ReadBookConfigShared] 实例 (供 Main.kt 构造 DesktopReadConfigProviders 用)
 */
fun registerDesktopConfig(
    preferenceStoreProvider: PreferenceStoreProvider = DesktopPreferenceStoreProvider(),
): ReadBookConfigShared {
    PreferenceProviders.register(DesktopPreferenceProvider())
    AppConfigProviders.register(DesktopAppConfigAccessor())

    // 备份格式兼容性: 注册 ReadBookConfigProviders + ThemeConfigProviders
    // 让 BackupShared 能全局访问 readBookConfig.configList / shareConfig / themeConfigList
    val readBookConfig = ReadBookConfigShared(preferenceStoreProvider)
    ReadBookConfigProviders.register(readBookConfig)
    ThemeConfigProviders.register(DesktopThemeConfigProvider())

    return readBookConfig
}

/**
 * 桌面端 [ThemeConfigProvider] 实现 (内存版, 供 BackupShared 备份 themeConfig.json)。
 *
 * 桌面端无 Android ThemeConfig 单例 (依赖 Context/ThemeStore/AppCompatDelegate/Drawable 等),
 * 用内存 `MutableList<ThemeConfigData>` 简化实现:
 * - [getConfigList]: 返回内存列表副本
 * - [addConfig]: 按 themeName 去重 (同名覆盖, 否则追加)
 *
 * 模式参考 app 端 `ThemeConfigProviderImpl` (app 端 actual 包装 `ThemeConfig` 单例)。
 */
private class DesktopThemeConfigProvider : ThemeConfigProvider {

    private val configList: MutableList<ThemeConfigData> = mutableListOf()

    override fun getConfigList(): List<ThemeConfigData> = configList.toList()

    override fun addConfig(config: ThemeConfigData) {
        val index = configList.indexOfFirst { it.themeName == config.themeName }
        if (index >= 0) {
            configList[index] = config
        } else {
            configList.add(config)
        }
    }
}
