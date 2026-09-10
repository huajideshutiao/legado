package io.legado.desktop.config

import com.sun.jna.Platform
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import io.legado.desktop.config.DesktopAppConfigAccessor.Companion.systemNightModeDetector

/**
 * 系统深色模式检测器 (Windows 注册表版, JNA)。
 *
 * 原 DesktopAppConfigAccessor 内联实现 —— 该文件随"无 UI 核心"抽取下沉 :desktop-core 后,
 * JNA 直调拆出留在 :desktop 并经 [systemNightModeDetector] 注入 (:desktop-core 的依赖闭包
 * 不得携带 jna, 供 :headless 复用)。
 *
 * 调用时机: desktop Main 阶段1 (registerCoreProviders 之前注册一次)。未注册时
 * DesktopAppConfigAccessor.detectSystemNightMode 回落 false (与 macOS/Linux 无注册表行为一致)。
 *
 * 仅 Windows: 读 `HKCU\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize`
 * 的 `AppsUseLightTheme` DWORD (0=深色, 1=浅色); 非 Windows 平台返回 false。
 */
fun registerDesktopSystemNightModeDetector() {
    systemNightModeDetector = {
        if (!Platform.isWindows()) {
            false
        } else {
            runCatching {
                Advapi32Util.registryGetIntValue(
                    WinReg.HKEY_CURRENT_USER,
                    "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "AppsUseLightTheme",
                ) == 0
            }.getOrDefault(false)
        }
    }
}
