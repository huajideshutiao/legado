@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package io.legado.app.help.crash

import io.legado.app.constant.AppConst
import io.legado.app.constant.fileNameFormat
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.utils.File
import io.legado.app.utils.systemCurrentTimeMillis
import kotlin.native.Platform
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.refTo
import platform.posix.O_APPEND
import platform.posix.O_CREAT
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.open
import platform.posix.write

/**
 * native (iOS/鸿蒙) 崩溃日志落盘与读取, 与 [IosCrashHandler] / [OhosCrashHandler] 配套。
 *
 * # 为什么不能复用 [io.legado.app.help.log.NativeAppLogStore]
 * 那个 store 走 `io.legado.app.utils.File` (okio), 在崩溃现场有两条硬伤: 进程已处于异常态,
 * okio 的分配/异常路径可能二次崩溃; 且信号处理器内只允许 async-signal-safe 调用。
 * 故落盘分两条路径 —— 异常钩子走 okio ([writeException]), 信号处理器走安装期预分配的
 * 字节缓冲 + POSIX `open/write/close` ([writeSignal])。
 *
 * # 目录
 * `{filesDir}/logs/crash/`。iOS 的 filesDir 就是沙盒 Documents (Info.plist 已开
 * `UIFileSharingEnabled`), 用户可经系统"文件" App 直接取出, 不必连电脑抓 `.ips`。
 * `AppFilesDirs` 未注册时回退 [fallbackDir] (iOS: 沙盒 `Documents/logs/crash`, 与 filesDir
 * 同一路径; 鸿蒙: 进程工作目录) —— 回退不引入新的写权限依赖。
 */
object NativeCrashLogWriter {

    /** 崩溃日志文件名前缀, 供列表侧过滤 (与 JVM 侧 CrashLogWriter 同值)。 */
    const val FILE_PREFIX = "crash-"

    /** 保留天数, 与 app 端 CrashHandler / 桌面端 CrashLogWriter 同口径。 */
    private const val KEEP_DAYS = 7L

    private const val DAY_MILLIS = 86_400_000L

    /** 信号处理器落盘缓冲 (64KB 足够放下参数头; 信号上下文内不做任何分配)。 */
    private const val SIGNAL_BUFFER_SIZE = 64 * 1024

    /** 信号名在缓冲里的保留宽度; 表内所有名字都 ≤ 8 字符, 定宽覆写才不会挪动后续偏移。 */
    private const val SIGNAL_NAME_WIDTH = 8

    /** 文件创建权限 (0644), 与 okio 建文件的默认权限一致。 */
    private const val FILE_MODE = 0x1A4

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

    /** 单个崩溃日志绝对路径 (供分享用)。 */
    fun crashPath(name: String): String = "$crashDir/$name"

    // region 异常钩子落盘

    /**
     * 异常钩子落盘 (ObjC 未捕获异常 / Kotlin/Native 未捕获异常)。
     *
     * 任何一步失败都静默 —— 崩溃路径上不能再抛异常。返回写成功的文件名, 失败为 null。
     */
    fun writeException(kind: String, detail: String): String? = runCatching {
        val dir = File(crashDir)
        dir.mkdirs()
        cleanupExpired(dir)
        val name = fileName()
        File(dir, name).writeText(buildExceptionLog(kind, detail))
        name
    }.getOrNull()

    /** 文件名沿用全仓既有口径 (`crash-yy-MM-dd-HH-mm-ss-<millis>.log`)。 */
    fun fileName(now: Long = systemCurrentTimeMillis()): String =
        "$FILE_PREFIX${AppConst.fileNameFormat.format(now)}-$now.log"

    // endregion

    // region 崩溃日志读取 (供两端 CrashLogProvider 用)

    /** 列出崩溃日志文件名 (按修改时间倒序); 目录不存在返回空表。 */
    fun listCrashLogs(): List<String> {
        val dir = File(crashDir)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { it.isFile && it.name.startsWith(FILE_PREFIX) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.name }
            ?: emptyList()
    }

    /** 读取单个崩溃日志内容; 不存在或读取失败返回 null。 */
    fun readCrashLog(name: String): String? {
        val file = File(crashPath(name))
        return if (file.isFile) runCatching { file.readText() }.getOrNull() else null
    }

    /** 清空全部崩溃日志。 */
    fun clearCrashLogs() {
        val dir = File(crashDir)
        if (!dir.isDirectory) return
        dir.listFiles { it.isFile && it.name.startsWith(FILE_PREFIX) }
            ?.forEach { it.delete() }
    }

    /** 删除 7 天前的崩溃日志 (对齐 app 端 exceedMillis 清理)。 */
    private fun cleanupExpired(dir: File) {
        runCatching {
            val exceed = systemCurrentTimeMillis() - KEEP_DAYS * DAY_MILLIS
            dir.listFiles { it.isFile && it.name.startsWith(FILE_PREFIX) }
                ?.forEach { if (it.lastModified() < exceed) it.delete() }
        }
    }

    // endregion

    // region 信号处理器路径 (async-signal-safe)

    /**
     * 信号落盘缓冲。**安装期分配一次并写好全部内容**, 信号处理器内只覆写定宽的信号名
     * 再写盘 —— 不做任何 Kotlin 分配、不拼字符串。
     *
     * 用 ByteArray 而非 nativeHeap 指针: 写内容就是普通数组下标赋值, 交给 `write` 时用
     * `refTo` 取地址 (数组直接在 Kotlin 堆上, 不被移动), 避开了在信号上下文里做指针运算。
     * 唯一的代价是 `refTo` 在处理器里会构造一个 CValuesRef 包装 —— 崩溃场景下这点开销
     * 远低于它换来的正确性。
     */
    private var signalBuffer: ByteArray? = null

    /** 缓冲里正文 (含参数头) 的长度。 */
    private var signalBodyLength: Int = 0

    /** 信号名在缓冲里的固定偏移。 */
    private var signalNameOffset: Int = 0

    /** 各候选路径的 `\nlog=<path>\n` 片段在缓冲里的起止偏移。 */
    private var signalTailRanges: List<IntRange> = emptyList()

    /** 候选落盘路径 (安装期快照, 处理器内逐个试 open)。 */
    private var signalPaths: List<String> = emptyList()

    /** 上一次建缓冲时用的路径集合 (用于判断是否需要重建)。 */
    private var builtPaths: List<String> = emptyList()

    /**
     * 建/重建信号缓冲: 建目录、分配缓冲、写好整段正文与各候选路径的 `log=` 尾巴。
     *
     * **可重复调用**: 路径集合不变则直接返回; 变化则整个重建。这一点是必需的 ——
     * 崩溃捕获装在启动链首 (早于 `AppFilesDirs` 注册), 那一刻只能快照到回退路径;
     * 注册完成后由 [refreshNativeCrashLogPaths] 再调一次, 把真实沙盒路径补进来。
     *
     * 候选路径在安装时快照而非处理器内现算 —— 处理器内既不能拼字符串也不能做分配。
     */
    internal fun prepareSignalBuffer() {
        val paths = currentCrashPaths()
        if (signalBuffer != null && paths == builtPaths) return
        // 目录必须先存在: open 带 O_CREAT 只建文件, 不建父目录
        paths.forEach { runCatching { File(it).mkdirs() } }

        val buffer = ByteArray(SIGNAL_BUFFER_SIZE)
        var at = 0
        at = put(buffer, at, "\n=== native crash ===\nsignal=")
        signalNameOffset = at
        at = put(buffer, at, " ".repeat(SIGNAL_NAME_WIDTH))
        at = put(
            buffer, at,
            "\nsource=signal\nstack: unavailable in signal handler (async-signal-safe path)\n",
        )
        at = put(buffer, at, paramsText())
        signalBodyLength = at

        val ranges = ArrayList<IntRange>(paths.size)
        for (path in paths) {
            val from = at
            at = put(buffer, at, "\nlog=")
            at = put(buffer, at, path)
            at = put(buffer, at, "\n")
            ranges.add(from until at)
        }

        signalTailRanges = ranges
        signalPaths = paths
        builtPaths = paths
        signalBuffer = buffer
    }

    /** 当前候选落盘路径 (真实 filesDir 优先, 回退目录兜底; 去重保持稳定顺序)。 */
    private fun currentCrashPaths(): List<String> =
        runCatching { listOf(crashDir, fallbackDir).distinct() }
            .getOrDefault(listOf(fallbackDir))

    /**
     * 信号处理器落盘: 覆写定宽信号名, 逐候选路径 `open(O_WRONLY|O_CREAT|O_APPEND)` 直到成功。
     *
     * 只调用 async-signal-safe 函数 (`open`/`write`/`close`), 不加锁、不分配、不抛异常。
     * 失败静默 —— 崩溃路径上不能二次崩溃。
     */
    internal fun writeSignal(sig: Int) {
        val buffer = signalBuffer ?: return
        val name = signalName(sig)
        var i = 0
        while (i < SIGNAL_NAME_WIDTH) {
            buffer[signalNameOffset + i] = name[i].code.toByte()
            i++
        }
        var index = 0
        while (index < signalPaths.size) {
            val range = signalTailRanges.getOrNull(index)
            if (range != null) {
                val fd = open(signalPaths[index], O_WRONLY or O_CREAT or O_APPEND, FILE_MODE)
                if (fd >= 0) {
                    writeAll(fd, buffer, 0, signalBodyLength)
                    writeAll(fd, buffer, range.first, range.last - range.first + 1)
                    close(fd)
                    return
                }
            }
            index++
        }
    }

    /**
     * 把一段文本按 UTF-8 写进预分配缓冲, 返回新的写入位置 (安装期调用, 可以分配)。
     *
     * 按 UTF-8 而不是逐字符取低位字节: 参数头里有机型/设备名这类非 ASCII 内容, 逐字符
     * 截断会写成乱码。
     */
    private fun put(buffer: ByteArray, from: Int, text: String): Int {
        val bytes = text.encodeToByteArray()
        var p = from
        var i = 0
        while (i < bytes.size && p < SIGNAL_BUFFER_SIZE) {
            buffer[p] = bytes[i]
            p++
            i++
        }
        return p
    }

    /** 循环 write 直到写完 (短写或被信号打断都继续)。 */
    private fun writeAll(fd: Int, buffer: ByteArray, from: Int, length: Int) {
        var offset = 0
        while (offset < length) {
            val n = write(fd, buffer.refTo(from + offset), (length - offset).toULong())
            if (n <= 0L) return
            offset += n.toInt()
        }
    }

    /**
     * 信号名 (定宽 [SIGNAL_NAME_WIDTH], 不足补空格)。
     *
     * 表覆盖两端注册处理器的全部信号; 表外信号不会进处理器 (未注册即走系统默认处置),
     * 这里仍给出定宽兜底以免越界覆写。
     */
    private fun signalName(sig: Int): String = when (sig) {
        1 -> "SIGHUP  "
        4 -> "SIGILL  "
        5 -> "SIGTRAP "
        6 -> "SIGABRT "
        7 -> "SIGBUS  "
        8 -> "SIGFPE  "
        11 -> "SIGSEGV "
        else -> "SIG($sig)".padEnd(SIGNAL_NAME_WIDTH)
    }

    // endregion

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
 * `AppFilesDirs` 注册之后调一次: 把真实沙盒路径补进信号落盘候选。
 *
 * 崩溃捕获装在启动链首 (早于文件目录注册), 那时信号缓冲里只有回退路径;
 * 文件目录一就绪就重建一次, 保证信号日志落进与崩溃日志读取端同一个目录。
 */
fun refreshNativeCrashLogPaths() {
    NativeCrashLogWriter.prepareSignalBuffer()
}

/**
 * Kotlin/Native 未捕获异常钩子 (iOS/鸿蒙共用, 两端各调一次同一份实现)。
 *
 * 单独抽出是因为钩子是进程级单值, 两端各写一份容易漂移; 与信号处理器不同, 这个钩子
 * 跑在普通上下文里, 可以正常分配与调 okio。
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
