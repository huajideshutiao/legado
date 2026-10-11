package io.legado.desktop.help

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.win32.StdCallLibrary
import io.legado.app.constant.AppLog
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

/**
 * 桌面端"屏幕常亮" (app 端 `Activity.keepScreenOn` / FLAG_KEEP_SCREEN_ON 的桌面等价)。
 *
 * 阅读/朗读期间阻止系统休眠与息屏, 退出时恢复。各平台机制:
 * - Windows: `SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED)`,
 *   线程级状态标记, 关闭时置回 `ES_CONTINUOUS`
 * - macOS: `caffeinate -d` 子进程, 关闭时销毁进程
 * - Linux: `systemd-inhibit --what=idle --who=legado sleep infinity` 子进程, 同上
 *
 * 非幂等调用安全: 重复开/关只作用一次。
 *
 * 下发由 [requestKeepScreenOn] (窗口策略请求) 与 [setWindowVisible] (窗口显隐) 两个入口合成:
 * 窗口不可见期间不压常亮, 两者任一变化都当场重算, 不等界面重组 (收起托盘/最小化后没有帧)。
 */
object DesktopKeepAwake {

    private const val ES_CONTINUOUS = 0x80000000.toInt()
    private const val ES_SYSTEM_REQUIRED = 0x00000001
    private const val ES_DISPLAY_REQUIRED = 0x00000002

    /** 类 Unix 平台上持有抑制的子进程 (Windows 为 null)。 */
    private val inhibitor = AtomicReference<Process?>(null)

    /** 平台侧当前是否真的在阻止休眠/熄屏 (下发去重)。 */
    @Volatile
    private var enabled = false

    /** 窗口策略最近一次下发的常亮请求 (阅读页常亮开关 或 自动翻页在跑)。 */
    @Volatile
    private var requested = false

    /** 主窗口是否对用户可见 (收进托盘 / 最小化 = false)。 */
    @Volatile
    private var windowVisible = true

    /**
     * 下发常亮请求。
     *
     * 真正是否压住系统由 [windowVisible] 决定: 界面收进托盘或最小化后不再重组, 下发时机
     * 不能依赖下一次重组 —— 不可见时立即释放, 窗口回来后按这里记下的请求补下发。
     */
    @Synchronized
    fun requestKeepScreenOn(on: Boolean) {
        requested = on
        apply()
    }

    /** 主窗口显隐翻转 (收进托盘 / 最小化 / 恢复) 时立即按当前请求重算。 */
    @Synchronized
    fun setWindowVisible(visible: Boolean) {
        windowVisible = visible
        apply()
    }

    /** 窗口不可见时一律不收常亮: 界面都看不见, 还压着系统不熄屏是错的。 */
    private fun apply() = setEnabled(requested && windowVisible)

    @Synchronized
    private fun setEnabled(on: Boolean) {
        if (enabled == on) return
        enabled = on
        // 平台调用统一收口到 EDT: Windows 的 SetThreadExecutionState 是线程级状态, 开在哪个
        // 线程就必须在同一个线程关 (否则打开它的线程仍持有请求, 关不掉)。窗口显隐会从托盘菜单、
        // 单实例转发前置、窗口事件等多个线程进来, 不收敛线程就会出现隐藏后系统仍不熄屏。
        if (SwingUtilities.isEventDispatchThread()) {
            applyNative(on)
        } else {
            SwingUtilities.invokeLater { applyNative(on) }
        }
    }

    private fun applyNative(on: Boolean) {
        runCatching {
            when {
                Platform.isWindows() -> setWindows(on)
                else -> setUnix(on)
            }
        }.onFailure {
            enabled = !on
            AppLog.put("设置屏幕常亮失败: ${it.localizedMessage}", it)
        }
    }

    private fun setWindows(on: Boolean) {
        val flags = if (on) {
            ES_CONTINUOUS or ES_SYSTEM_REQUIRED or ES_DISPLAY_REQUIRED
        } else {
            ES_CONTINUOUS
        }
        Kernel32KeepAwake.INSTANCE.SetThreadExecutionState(flags)
    }

    private fun setUnix(on: Boolean) {
        if (!on) {
            inhibitor.getAndSet(null)?.destroy()
            return
        }
        val command = if (Platform.isMac()) {
            listOf("caffeinate", "-d")
        } else {
            listOf("systemd-inhibit", "--what=idle", "--who=legado", "sleep", "infinity")
        }
        val process = ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        // 进程退出时一并收掉抑制进程 (JVM 崩溃退出不会走 destroy)
        process.toHandle().onExit().thenRun { inhibitor.compareAndSet(process, null) }
        inhibitor.getAndSet(process)?.destroy()
    }
}

/** `SetThreadExecutionState` 声明 (jna-platform 的 Kernel32 接口未暴露该函数)。 */
private interface Kernel32KeepAwake : StdCallLibrary {
    fun SetThreadExecutionState(esFlags: Int): Int

    companion object {
        val INSTANCE: Kernel32KeepAwake by lazy {
            Native.load("kernel32", Kernel32KeepAwake::class.java)
        }
    }
}
