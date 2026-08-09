package io.legado.desktop.help

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.win32.StdCallLibrary
import io.legado.app.constant.AppLog
import java.util.concurrent.atomic.AtomicReference

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
 */
object DesktopKeepAwake {

    private const val ES_CONTINUOUS = 0x80000000.toInt()
    private const val ES_SYSTEM_REQUIRED = 0x00000001
    private const val ES_DISPLAY_REQUIRED = 0x00000002

    /** 类 Unix 平台上持有抑制的子进程 (Windows 为 null)。 */
    private val inhibitor = AtomicReference<Process?>(null)

    @Volatile
    private var enabled = false

    @Synchronized
    fun setKeepScreenOn(on: Boolean) {
        if (enabled == on) return
        enabled = on
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
