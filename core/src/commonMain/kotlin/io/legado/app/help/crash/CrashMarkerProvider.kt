package io.legado.app.help.crash

import io.legado.app.help.config.LocalConfigKeys
import io.legado.app.help.config.LocalConfigProviders
import kotlin.concurrent.Volatile
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * "上次运行异常退出"标记 (崩溃后重启提示的数据源, 对照原版 `LocalConfig.appCrash`)。
 *
 * # 为什么需要抽象
 * 原版只有 Android: `CrashHandler.handleException` 置 `LocalConfig.appCrash = true`,
 * `MainActivity.notifyAppCrash` 读它并清掉。四端对齐后写方落在四个崩溃处理器里,
 * 而 native 端与 Android 原生信号的**信号处理器路径**无法走偏好存储 (不能分配、不能加锁),
 * 只能落标记文件 (见 [CrashMarkerFile]); 故把"置位/读取并清除"抽成 provider。
 *
 * # 默认实现
 * 未注册时用 [LocalConfigProviders] 的 `appCrash` 键 —— 与原版同键同存储。
 * 有信号路径的端 (Android/iOS/鸿蒙) 各自注册能同时看偏好与文件的实现。
 */
interface CrashMarkerProvider {

    /** 置位标记 (崩溃落盘时调用)。 */
    fun mark()

    /** 读取并清除标记 (启动期提示时调用); 无标记返回 false。 */
    fun consumePending(): Boolean
}

/**
 * 崩溃日志文件名前缀 (四端一致)。
 *
 * 崩溃目录里除了崩溃现场还放着"上次异常退出"标记, 读取侧靠前缀分开两者。
 * jvmMain [io.legado.app.help.crash.CrashLogWriter] 与 nativeMain
 * [io.legado.app.help.crash.NativeCrashLogWriter] 各自的 `FILE_PREFIX` 均引用本常量,
 * 避免三处各写一份字面量。
 */
const val CRASH_LOG_FILE_PREFIX = "crash-"

/**
 * 异常退出标记文件 (信号处理器专用通道)。
 *
 * 文件名与内容与各端 native 信号处理器逐字一致 (`pending-crash`, 内容 `1\n`),
 * 见 `native_crash_handler.cpp` (Android) 与 `NativeCrashLogWriter` (iOS/鸿蒙)。
 */
object CrashMarkerFile {

    /** 标记文件名 (与各端 native 侧同值)。 */
    const val FILE_NAME = "pending-crash"

    /** 读取并删除 [dir] 下的标记文件; 无标记返回 false。 */
    fun consume(dir: String?): Boolean {
        if (dir.isNullOrBlank()) return false
        return runCatching {
            val fs = FileSystem.SYSTEM
            val marker = (dir.trimEnd('/', '\\') + "/" + FILE_NAME).toPath()
            if (fs.metadataOrNull(marker)?.isRegularFile != true) return false
            fs.delete(marker, mustExist = false)
            true
        }.getOrDefault(false)
    }
}
/** [CrashMarkerProvider] 容器 (provider 注入模式, 宿主启动早期注册一次)。 */
object CrashMarkerProviders {

    @Volatile
    private var impl: CrashMarkerProvider? = null

    fun register(provider: CrashMarkerProvider) {
        impl = provider
    }

    fun get(): CrashMarkerProvider = impl ?: LocalConfigCrashMarker
}

/**
 * 默认实现: [LocalConfigProviders] 的 `appCrash` 键。
 *
 * 与原版 `LocalConfig.appCrash` 同键同存储 —— Android 端照旧写它, 桌面端与 native
 * 异常钩子 (非信号路径) 也写它。
 */
private object LocalConfigCrashMarker : CrashMarkerProvider {

    override fun mark() {
        runCatching {
            LocalConfigProviders.get().putBoolean(LocalConfigKeys.appCrash, true)
        }
    }

    override fun consumePending(): Boolean = runCatching {
        val store = LocalConfigProviders.get()
        if (!store.getBoolean(LocalConfigKeys.appCrash, false)) return false
        store.putBoolean(LocalConfigKeys.appCrash, false)
        true
    }.getOrDefault(false)
}
