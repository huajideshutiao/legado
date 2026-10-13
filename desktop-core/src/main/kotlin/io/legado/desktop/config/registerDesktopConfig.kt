package io.legado.desktop.config

import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.FileThemeConfigProvider
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.config.ReadBookConfigProviders
import io.legado.app.help.config.ReadBookConfigShared
import io.legado.app.help.config.ThemeConfigProviders

/**
 * 桌面端启动早期注册所有 config 相关 provider（含备份格式兼容性补齐）。
 *
 * - [PreferenceProviders]: 注入 [DesktopPreferenceProvider] (java.util.prefs.Preferences 实现)
 * - [AppConfigProviders]: 注入 [DesktopAppConfigAccessor] (5 个只读配置项)
 * - [ReadBookConfigProviders]: 注入 [ReadBookConfigShared] (内存 configList, 供 BackupShared 备份 readConfig.json)
 * - [ThemeConfigProviders]: 注入 [FileThemeConfigProvider] (themeConfig.json 文件持久化, 主题增删/应用/备份恢复可用)
 *
 * 调用时机: 桌面端 main 入口, 任何 shared 调用之前。
 * 模式参考 app 端 `registerAndroidWebBookProviders` / `registerAndroidPasswordProvider`。
 *
 * @return 构造好的 [ReadBookConfigShared] 实例 (已注册到 [ReadBookConfigProviders])
 */
fun registerDesktopConfig(): ReadBookConfigShared {
    PreferenceProviders.register(DesktopPreferenceProvider())
    val appConfig = DesktopAppConfigAccessor()
    AppConfigProviders.register(appConfig)

    // 备份格式兼容性: 注册 ReadBookConfigProviders + ThemeConfigProviders
    // 让 BackupShared 能全局访问 readBookConfig.configList / shareConfig / themeConfigList
    val readBookConfig = ReadBookConfigShared(PreferenceProviders.get())
    ReadBookConfigProviders.register(readBookConfig)
    val themeConfig = FileThemeConfigProvider()
    ThemeConfigProviders.register(themeConfig)

    // 启动期按当前日/夜重算主题色 (对照原版 `ThemeConfig.applyDayNightInit`: 只重算不广播,
    // 此时还没有界面可重建)。不做这一步时, 上次会话写下的「已应用色」会一直显示到系统外观下次变化。
    themeConfig.applyThemeModeQuiet()
    // 系统外观变化时重算 + 广播: 与 Android 系统外观变化走的同一条链 (失效系统深浅判定缓存 →
    // 按新日/夜重算主题色 → 重建界面)。注册时机在界面组合之前, 与原生窗口消息钩子无关。
    DesktopSystemThemeWatcher.install(appConfig, themeConfig)

    return readBookConfig
}
