package io.legado.app.ui.compose.platform

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.legado.app.help.config.ThemeConfigProviders

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
     * 委托 [io.legado.app.help.config.FileThemeConfigProvider.applyDayNight]: 它按目标模式读
     * 已配置的自定义色 (cAccent/cNAccent 等, 未配置回落内置默认) 写全部 6 个 ThemeStore 键 +
     * emit RECREATE。本类曾自带一套硬编码色 (且漏写 accent), 与该实现两套夜间色打架。
     */
    fun updateDark(dark: Boolean) {
        if (isDark == dark) return
        ThemeConfigProviders.get().applyDayNight(dark)
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
