package io.legado.desktop.config

import io.legado.app.help.config.FileThemeConfigProvider

/**
 * 桌面端「系统深浅色变化」入口。
 *
 * Windows 在系统主题切换时向所有顶层窗口广播一条设置变更消息, 本类的 [systemThemeChanged]
 * 由窗口侧 (见 :desktop 的 DesktopWindowChromeNative 消息钩子) 在该消息到达时调用。
 * 调用点只在 :desktop (窗口原生消息在那边), 故本类自身不碰 JNA, :desktop-core 依赖闭包不变。
 *
 * 消息只说明"设置变了", 不携带是深还是浅, 所以先让系统深浅判定缓存失效, 再走与启动期
 * 同一条重算链 (按新日/夜重算并应用主题色 + 广播重建)。
 *
 * 与 [AppConfigProviders] 一样是进程级单例: 界面组合之前注册一次。
 */
object DesktopSystemThemeWatcher {

    @Volatile
    private var accessor: DesktopAppConfigAccessor? = null

    @Volatile
    private var themeConfig: FileThemeConfigProvider? = null

    /** 启动早期注册一次 (见 registerDesktopConfig)。 */
    fun install(
        accessor: DesktopAppConfigAccessor,
        themeConfig: FileThemeConfigProvider,
    ) {
        this.accessor = accessor
        this.themeConfig = themeConfig
    }

    /**
     * 系统设置变更 (深/浅色切换) 到达: 失效判定缓存 → 按新日/夜重算并应用主题色 → 广播重建。
     *
     * 主题模式为显式档 ("1"/"2"/"3") 时, 重算出的颜色与原来一致, 广播后界面重建一次,
     * 属显式档下无意义的重建; 但消息本身不区分"深色相关设置"与其它设置变更, 无法在此排除
     * (排除就得再读一次注册表比对, 而读注册表正是本函数要做的事)。
     */
    fun systemThemeChanged() {
        val accessor = accessor ?: return
        val themeConfig = themeConfig ?: return
        accessor.invalidateSystemNightMode()
        themeConfig.applyThemeMode()
    }
}
