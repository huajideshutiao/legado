package io.legado.desktop.ui

import io.legado.app.constant.AppLog
import io.legado.desktop.config.DesktopSystemThemeWatcher
import io.legado.desktop.help.win.WM_SETTINGCHANGE

/**
 * 桌面端系统深浅色变化来源: 主窗口收到的系统设置变更消息。
 *
 * Windows 切换深/浅色时向所有顶层窗口广播 [WM_SETTINGCHANGE] (见 [WM_SETTINGCHANGE] 注释),
 * 由窗口原生消息桥 (wndchrome) 转发上来。消息只说明"设置变了", 具体是深是浅由
 * [DesktopSystemThemeWatcher.systemThemeChanged] 读注册表判定。
 *
 * 非 Windows 平台无等价消息 (macOS/Linux 的系统深浅色本端也不探测, 见
 * DesktopAppConfigAccessor 的说明), 不注册, 不报错。
 *
 * 处理器跑在原生窗口线程, 不是 EDT: [DesktopSystemThemeWatcher.systemThemeChanged] 内部
 * 只做注册表读取与广播事件, 不碰 Swing/Compose 状态, 故不投递。
 */
fun installSystemThemeWatcher() {
    if (!com.sun.jna.Platform.isWindows()) return
    val registered = DesktopWindowChromeNative.addMessageHandler { msg, _, _ ->
        if (msg != WM_SETTINGCHANGE) return@addMessageHandler false
        DesktopSystemThemeWatcher.systemThemeChanged()
        // 系统外观变化与窗口无关, 但仍沿子类化链转发: 原生侧还要借这条消息重建字体与重排
        false
    }
    if (!registered) {
        AppLog.put("系统深浅色变化监听未注册: 窗口消息钩子不可用, 系统深浅色变化时界面不会跟随")
    }
}
