package io.legado.app.ui.compose.platform

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.config.ThemeConfigProviders
import io.legado.app.help.config.themeModeFor

/**
 * 桌面 JVM 端主题数据: 读取全部复用 [SharedThemeStoreProvider] (三端同一份),
 * 只额外提供标题栏日夜切换按钮需要的 [isDark] 与 [toggleDark]。
 */
class DesktopThemeStoreProvider : SharedThemeStoreProvider() {

    /**
     * 是否深色主题 (与 [io.legado.app.help.config.AppConfigAccessor.isNightTheme] 同源)。
     * 只判 `themeMode == "2"` 会漏掉「跟随系统」(桌面端会读注册表), 于是系统深色 +
     * themeMode="0" 时界面已是深色而本值仍为 false: 标题栏图标反向、切换按钮第一下等于
     * 空操作 (用户实测"要按两下")。
     */
    val isDark: Boolean get() = isNight

    /** 标题栏日夜按钮: 切到相反模式 */
    fun toggleDark() = updateDark(!isDark)

    /**
     * 显式设置深/浅色主题。
     *
     * themeMode 走 [themeModeFor]: 目标与系统一致时落回「跟随系统」, 否则写显式档 ——
     * 「我的」里的「主题模式」因此始终显示一个与当前显示一致的明确档位, 且来回切不会把
     * 用户的「跟随系统」永久写死。随后 [io.legado.app.help.config.ThemeConfigProvider.applyThemeMode]
     * 按新 themeMode 读已配置的自定义色 (cAccent/cNAccent 等, 未配置回落内置默认) 写
     * ThemeStore 并 emit RECREATE。
     */
    fun updateDark(dark: Boolean) {
        if (isDark == dark) return
        PreferenceProviders.get().putString(PreferKey.themeMode, themeModeFor(dark))
        ThemeConfigProviders.get().applyThemeMode()
    }
}

/** 桌面端 AppConfig：读取复用 [SharedAppConfigProvider], 只多一个 E-Ink 调试覆盖 */
class DesktopAppConfigProvider : SharedAppConfigProvider() {
    /** 调试覆盖 (null = 跟随「主题模式」设置), 见 [updateEInk] */
    private var eInkOverride: Boolean? by mutableStateOf(null)

    override val isEInkMode: Boolean
        // 同源 themeMode == "3": 设置项选了 E-Ink 就该去动画/黑白化, 不能只认调试开关
        get() = eInkOverride ?: super.isEInkMode

    /** 手动覆盖 E-Ink 模式用于调试组件去动画/黑白化; 传 null 恢复跟随「主题模式」设置 */
    fun updateEInk(value: Boolean?) {
        eInkOverride = value
    }
}
