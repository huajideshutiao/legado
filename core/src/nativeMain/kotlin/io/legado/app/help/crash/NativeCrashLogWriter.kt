@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package io.legado.app.help.crash

import io.legado.app.constant.AppConst
import io.legado.app.constant.fileNameFormat
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.utils.File
import io.legado.app.utils.systemCurrentTimeMillis
import kotlin.native.Platform
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * native (iOS/鸿蒙) 崩溃日志落盘与读取, 与 [IosCrashHandler] / [OhosCrashHandler] 配套。
 *
 * 只收异常钩子路径 ([writeException]) 与 iOS 的标记通道 ([IosSignalMarker], 处理器内只写标记文件):
 * 信号路径的原生现场只有裸地址、没有调用栈, 对定位无增量, 已不再落盘; 崩溃定位改由各端系统通道
 * 负责 (iOS 系统报告, 鸿蒙崩溃事件订阅)。
 *
 * # 为什么不能复用 [io.legado.app.help.log.NativeAppLogStore]
 * 那个 store 走 `io.legado.app.utils.File` (okio), 在崩溃现场有硬伤: 进程已处于异常态,
 * okio 的分配/异常路径可能二次崩溃。故这里整条包在 runCatching 里, 任何一步失败都静默。
 *
 * # 目录
 * `{filesDir}/logs/crash/`。iOS 的 filesDir 就是沙盒 Documents (Info.plist 已开
 * `UIFileSharingEnabled`), 用户可经系统"文件" App 直接取出, 不必连电脑抓 `.ips`。
 * `AppFilesDirs` 未注册时回退 [fallbackDir] (iOS: 沙盒 `Documents/logs/crash`, 与 filesDir
 * 同一路径; 鸿蒙: 进程工作目录) —— 回退不引入新的写权限依赖。
 */
object NativeCrashLogWriter {

    /** 崩溃日志文件名前缀, 供列表侧过滤 (与 JVM 侧同名文件同前缀)。 */
    private const val FILE_PREFIX = CRASH_LOG_FILE_PREFIX

    /** 保留天数, 与 app 端 CrashHandler / 桌面端 CrashLogWriter 同口径。 */
    private const val KEEP_DAYS = 7L

    private const val DAY_MILLIS = 86_400_000L

    /**
     * 崩溃日志目录。AppFilesDirs 未注册时回退 [fallbackDir]。
     *
     * 崩溃可能发生在 provider 注册之前 (见 `install` 的安装时机), 那时 `AppFilesDirs.get()`
     * 直接 error, 故必须 runCatching。
     */
    val crashDir: String
        get() = runCatching {
            AppFilesDirs.get().filesDir.trimEnd('/', '\\') + "/logs/crash"
        }.getOrDefault(fallbackDir)

    /** provider 未就绪时的回退目录。 */
    private val fallbackDir: String = nativeCrashHomeDir() + "/logs/crash"

    /**
     * 单个崩溃日志绝对路径 (供分享用)。
     *
     * 按候选目录顺序找第一个存在的: 崩溃可能发生在 provider 注册之前, 那次日志只落在回退
     * 目录里, 只认真实沙盒目录会定位不到它。
     */
    fun crashPath(name: String): String {
        for (dir in crashCandidateDirs()) {
            val file = File(dir, name)
            if (file.isFile) return file.absolutePath
        }
        return File(crashDir, name).absolutePath
    }

    // region 异常钩子落盘

    /**
     * 异常钩子落盘 (ObjC 未捕获异常 / Kotlin/Native 未捕获异常)。
     *
     * 任何一步失败都静默 —— 崩溃路径上不能再抛异常。返回写成功的文件名, 失败为 null。
     */
    fun writeException(kind: String, detail: String): String? = runCatching {
        // 异常退出标记: 下次启动提示用户查看崩溃日志 (对照 app 端 CrashHandler.handleException
        // 的 LocalConfig.appCrash = true)
        runCatching { CrashMarkerProviders.get().mark() }
        val dir = File(crashDir)
        dir.mkdirs()
        cleanupExpired()
        val name = fileName()
        File(dir, name).writeText(buildExceptionLog(kind, detail))
        name
    }.getOrNull()

    /** 文件名沿用全仓既有口径 (`crash-yy-MM-dd-HH-mm-ss-<millis>.log`)。 */
    fun fileName(now: Long = systemCurrentTimeMillis()): String =
        "$FILE_PREFIX${AppConst.fileNameFormat.format(now)}-$now.log"

    // endregion

    // region 崩溃日志读取 (供两端 CrashLogProvider 用)

    /**
     * 列出崩溃日志文件名 (按修改时间倒序); 目录不存在返回空表。
     *
     * 与写入侧同一组候选目录 (真实 filesDir 优先, 回退兜底): 崩溃可能发生在 provider 注册
     * 之前, 那时只能写进回退目录 —— 只读真实目录会让那些日志在列表里看不到。
     */
    fun listCrashLogs(): List<String> {
        // 长度为 0 的是写入中断留下的残片, 不作为崩溃日志列出
        return crashCandidateDirs()
            .flatMap { dir ->
                File(dir).listFiles { it.isFile && it.name.startsWith(FILE_PREFIX) && it.length() > 0 }
                    ?.toList()
                    .orEmpty()
            }
            .sortedByDescending { it.lastModified() }
            .distinctBy { it.name }
            .map { it.name }
    }

    /** 读取单个崩溃日志内容; 不存在或读取失败返回 null。 */
    fun readCrashLog(name: String): String? {
        val file = File(crashPath(name))
        return if (file.isFile) runCatching { file.readText() }.getOrNull() else null
    }

    /** 清空全部崩溃日志 (覆盖全部候选目录)。 */
    fun clearCrashLogs() {
        crashCandidateDirs().forEach { dir ->
            File(dir).listFiles { it.isFile && it.name.startsWith(FILE_PREFIX) }
                ?.forEach { it.delete() }
        }
    }

    /** 删除 7 天前的崩溃日志 (对齐 app 端 exceedMillis 清理); 覆盖全部候选目录。 */
    private fun cleanupExpired() {
        runCatching {
            val exceed = systemCurrentTimeMillis() - KEEP_DAYS * DAY_MILLIS
            crashCandidateDirs().forEach { path ->
                File(path).listFiles { it.isFile && it.name.startsWith(FILE_PREFIX) }
                    ?.forEach { if (it.lastModified() < exceed) it.delete() }
            }
        }
    }

    // endregion

    /**
     * 候选崩溃目录 (真实 filesDir 优先, 回退目录兜底; 去重保持稳定顺序)。
     */
    /**
     * 崩溃日志候选目录 (真实 filesDir 优先, 回退目录兜底; 去重保持稳定顺序)。
     *
     * 供标记通道用: iOS 的原生信号处理器只写异常退出标记 ([IosSignalMarker]), 标记与崩溃日志
     * 同目录, 读取侧按同一组候选目录查。
     */
    fun crashCandidateDirs(): List<String> = currentCrashPaths()

    /** 当前候选落盘路径 (真实 filesDir 优先, 回退目录兜底; 去重保持稳定顺序)。 */
    private fun currentCrashPaths(): List<String> =
        runCatching { listOf(crashDir, fallbackDir).distinct() }
            .getOrDefault(listOf(fallbackDir))

    // region 文本组装

    private fun buildExceptionLog(kind: String, detail: String): String {
        val sb = StringBuilder(detail.length + 512)
        sb.append("crashKind=").append(kind).append('\n')
        sb.append(paramsText())
        sb.append(detail)
        if (!detail.endsWith('\n')) sb.append('\n')
        return sb.toString()
    }

    /**
     * 崩溃参数头 (对照 app 端 CrashHandler.paramsMap / 桌面端 DesktopCrashHandler.paramsMap)。
     *
     * 崩溃现场环境只能尽力而为: 任何一项取不到就跳过, 不允许因取参反而抛异常。
     */
    private fun paramsText(): String = buildString {
        for ((key, value) in runtimeParams()) {
            append(key).append('=').append(value).append('\n')
        }
    }

    private fun runtimeParams(): Map<String, String> = buildMap {
        runCatching {
            put("TIME", AppConst.fileNameFormat.format(systemCurrentTimeMillis()))
            put("APP_VERSION", nativeCrashAppVersionName())
            put("APP_BUILD", nativeCrashAppBuildNumber())
            put("APP_PACKAGE", nativeCrashAppBundleId())
            put("ARCH", Platform.cpuArchitecture.name)
            putAll(nativeCrashPlatformParams())
        }
    }

    // endregion
}

/**
 * Kotlin/Native 未捕获异常钩子 (iOS/鸿蒙共用, 两端各调一次同一份实现)。
 *
 * 钩子是进程级单值, 两端各写一份容易漂移; 它跑在普通上下文里, 可以正常分配与调 okio,
 * 故走 [NativeCrashLogWriter.writeException] 拿到带函数名的调用栈。
 */
internal fun installNativeUnhandledHook() {
    kotlin.native.setUnhandledExceptionHook { throwable ->
        NativeCrashLogWriter.writeException(
            kind = "kotlin-native-unhandled",
            detail = buildString {
                append("Kotlin unhandled exception\n")
                append(runCatching { throwable.stackTraceToString() }.getOrElse { "$throwable" })
            },
        )
    }
}

/**
 * 进程沙盒内与 `filesDir` 等价的根目录 (iOS: 容器根的 Documents 子目录; 鸿蒙: 当前工作目录)。
 *
 * iOS 上 `NSHomeDirectory()` 返回的是**容器根** (`.../Application/<UUID>`), 不是 Documents。
 * 直接拿它拼 `/logs/crash` 会把日志建到容器根, 而那里不在文件共享范围内 (只有 Documents 经
 * `UIFileSharingEnabled` 暴露), 用户与崩溃日志读取端都拿不到 —— 实测模拟器容器里同时出现了
 * 容器根 `logs/crash` 与 `Documents/logs/crash` 两个目录。
 */
internal expect fun nativeCrashHomeDir(): String

/** 系统与机型信息 (崩溃参数头)。 */
internal expect fun nativeCrashPlatformParams(): Map<String, String>

internal expect fun nativeCrashAppVersionName(): String

internal expect fun nativeCrashAppBuildNumber(): String

internal expect fun nativeCrashAppBundleId(): String
