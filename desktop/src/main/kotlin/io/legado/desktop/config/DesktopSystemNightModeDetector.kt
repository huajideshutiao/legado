package io.legado.desktop.config

import com.sun.jna.Platform
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import io.legado.app.constant.AppLog
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

/**
 * 读一次系统深色模式 (本进程首次调用会连带完成 JNA 的原生调用初始化)。
 *
 * 为什么单独成函数而不只留在注 detector 的 lambda 里: 启动期需要把它**提前到后台线程真调一次**
 * (见 Main.kt 调 DesktopCore.warmUpNativeDependencies 的位置)。实测依据: 阶段0 的
 * `config: AppConfig` 段稳定花 81~86ms, 而把 `theme/Mode` 改成"1"(走不到这里) 后同段只剩 11ms;
 * 只 `Class.forName(Advapi32Util)` 预热无效 (第八轮实测仍 81ms) —— 贵的是首次真正调用,
 * 不是类加载, 所以必须真跑一次本函数。
 */
fun probeSystemNightMode(): Boolean =
    if (!Platform.isWindows()) {
        false
    } else {
        runCatching {
            Advapi32Util.registryGetIntValue(
                WinReg.HKEY_CURRENT_USER,
                "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                "AppsUseLightTheme",
            ) == 0
        }
            // 失败不静默回落浅色: 全局主题/标题栏按钮态/isDark 都跟着它, 系统实为深色时
            // 会整体反色且无任何可查痕迹
            .onFailure { AppLog.put("读取系统深色模式失败, 按浅色处理", it) }
            .getOrDefault(false)
    }

fun registerDesktopSystemNightModeDetector() {
    // 结果不缓存在这里: DesktopAppConfigAccessor 自己带 TTL 缓存, 本函数只负责"怎么读"
    systemNightModeDetector = ::probeSystemNightMode
}
