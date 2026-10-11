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
 * 字节缓冲 + 安装期打开的 fd ([writeSignal])。
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

    /** 信号处理器落盘缓冲 (64KB 足够放下参数头; 信号上下文内不做任何分配)。 */
    private const val SIGNAL_BUFFER_SIZE = 64 * 1024

    /** 信号名在缓冲里的保留宽度; 表内所有名字都 ≤ 9 字符, 定宽覆写才不会挪动后续偏移。 */
    private const val SIGNAL_NAME_WIDTH = 9

    /** 文件创建权限 (0644), 与 okio 建文件的默认权限一致。 */
    private const val FILE_MODE = 0x1A4

    /** 异常退出标记文件名 (与崩溃日志同在 crash 目录)。 */
    private const val MARKER_FILE_NAME = CrashMarkerFile.FILE_NAME

    /**
     * 信号路径的崩溃日志文件名 (固定名 + 追加写)。
     *
     * 不用时间戳拼名: 信号上下文内拼字符串要分配, 而预分配缓冲装不下可变长度的文件名;
     * 固定名追加写让每次崩溃的现场都留在同一文件里 (每段以 `=== native crash ===` 开头),
     * 具体崩溃时刻看文件修改时间。
     */
    private const val SIGNAL_FILE_NAME = "crash-signal.log"

    /** 标记文件内容 (固定 ASCII, 安装期分配一次)。 */
    private val MARKER_TEXT = "1\n".encodeToByteArray()

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
        // 长度为 0 的条目过滤掉: 信号日志文件在应用启动时就由安装期 fd 创建 (见 [openSignalTargets]),
        // 尚未崩溃时它只是个空文件
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
        // 删完重开: 否则信号崩溃会写进已删除的 inode (界面上看不到的"幽灵文件")
        openSignalTargets()
    }

    /** 删除 7 天前的崩溃日志 (对齐 app 端 exceedMillis 清理); 覆盖全部候选目录。 */
    private fun cleanupExpired() {
        runCatching {
            val exceed = systemCurrentTimeMillis() - KEEP_DAYS * DAY_MILLIS
            crashCandidateDirs().forEach { path ->
                File(path).listFiles { it.isFile && it.name.startsWith(FILE_PREFIX) }
                    ?.forEach { if (it.lastModified() < exceed) it.delete() }
            }
            // 过期清理可能删掉信号日志文件: 重开 fd, 避免之后写进已删除的 inode
            openSignalTargets()
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

    /** 候选崩溃日志**文件**路径 (安装期快照, 处理器内逐个试 open)。 */
    private var signalPaths: List<String> = emptyList()

    /**
     * 各候选目录下的异常退出标记文件路径 (与 [signalPaths] 同序)。
     *
     * 同序是必需的: 日志在哪个候选目录写成功, 标记就必须写同一目录 —— 启动期读取端按目录
     * 逐个查, 落错目录就读不到。
     */
    private var markerPaths: List<String> = emptyList()

    /** 上一次建缓冲时用的路径集合 (用于判断是否需要重建)。 */
    private var builtPaths: List<String> = emptyList()

    /**
     * 安装期打开的日志 fd (与 [signalPaths] 同序; -1 = 该候选打不开)。
     *
     * 不在处理器内 open: `open(path: String)` 在 K/N 上要经 `cstr` 把路径转成 UTF-8 字节数组
     * (Kotlin 堆分配 + native 堆拷贝), 而信号处理器里做 Kotlin 堆分配可能撞上被信号打断的
     * 分配器/GC 状态而死锁。安装期打开并持有, 处理器内只 write。
     */
    private var logFds: IntArray = IntArray(0)

    /**
     * 安装期打开的标记文件 fd (与 [markerPaths] 同序; -1 = 打不开)。
     *
     * 不用 `O_TRUNC` 打开: 那会在安装期就把"上次异常退出"这一位抹掉。内容只需"存在",
     * 多次崩溃只会让偏移前进, 不影响读取侧判存在。
     */
    private var markerFds: IntArray = IntArray(0)

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

        // 候选是**文件**路径: open 必须落在文件上。此前逐候选 open 的是目录路径,
        // 而 O_WRONLY 对目录恒 EISDIR —— 信号崩溃因此从未落过盘 (既有缺陷, 本次修掉)
        val logPaths = paths.map { it.trimEnd('/', '\\') + "/" + SIGNAL_FILE_NAME }
        val ranges = ArrayList<IntRange>(logPaths.size)
        for (path in logPaths) {
            val from = at
            at = put(buffer, at, "\nlog=")
            at = put(buffer, at, path)
            at = put(buffer, at, "\n")
            ranges.add(from until at)
        }

        signalTailRanges = ranges
        signalPaths = logPaths
        markerPaths = paths.map { it.trimEnd('/', '\\') + "/" + MARKER_FILE_NAME }
        builtPaths = paths
        signalBuffer = buffer
        openSignalTargets()
    }

    /**
     * 安装期打开日志与标记文件并持有 fd, 并关掉上一轮的 fd。
     *
     * 日志文件在应用启动时就会被创建 (空文件), 读取侧用长度过滤掉它。
     */
    private fun openSignalTargets() {
        closeSignalTargets()
        val logs = IntArray(signalPaths.size) { -1 }
        for (i in signalPaths.indices) {
            logs[i] = open(signalPaths[i], O_WRONLY or O_CREAT or O_APPEND, FILE_MODE)
        }
        val markers = IntArray(markerPaths.size) { -1 }
        for (i in markerPaths.indices) {
            markers[i] = open(markerPaths[i], O_WRONLY or O_CREAT, FILE_MODE)
        }
        logFds = logs
        markerFds = markers
    }

    /**
     * 关掉并释放安装期打开的 fd。
     *
     * 日志/标记文件被删除后必须重开 —— 否则 fd 指向已删除的 inode, 信号崩溃会写进一个
     * 界面上看不到、也占着磁盘空间的"幽灵文件"。
     */
    private fun closeSignalTargets() {
        logFds.forEach { if (it >= 0) close(it) }
        markerFds.forEach { if (it >= 0) close(it) }
        logFds = IntArray(0)
        markerFds = IntArray(0)
    }

    /**
     * 候选崩溃目录 (真实 filesDir 优先, 回退目录兜底; 去重保持稳定顺序)。
     *
     * 供"异常退出"标记读取侧复用: 标记与崩溃日志同目录, 读取必须查同一组候选。
     */
    fun crashCandidateDirs(): List<String> = currentCrashPaths()

    /** 当前候选落盘路径 (真实 filesDir 优先, 回退目录兜底; 去重保持稳定顺序)。 */
    private fun currentCrashPaths(): List<String> =
        runCatching { listOf(crashDir, fallbackDir).distinct() }
            .getOrDefault(listOf(fallbackDir))

    /**
     * 信号处理器落盘: 覆写定宽信号名, 往安装期打开的 fd 里写 (逐候选直到成功)。
     *
     * 处理器内只调 async-signal-safe 的 `write` —— 不做任何 Kotlin 分配、不拼字符串、不取锁。
     * 路径的 String→C 字符串转换已在安装期完成 (见 [openSignalTargets])。
     * 失败静默 —— 崩溃路径上不能二次崩溃。
     */
    internal fun writeSignal(label: String) {
        val buffer = signalBuffer ?: return
        // label 由各端在**安装期**按自己的平台常量备好: 信号编号在 iOS 与 Linux 内核上并不
        // 相同 (如 SIGSYS), 共用实现里用字面量会把名字映射错。这里只做定宽覆写, 不分配
        var i = 0
        while (i < SIGNAL_NAME_WIDTH) {
            buffer[signalNameOffset + i] = label[i].code.toByte()
            i++
        }
        var index = 0
        while (index < logFds.size) {
            val range = signalTailRanges.getOrNull(index)
            val fd = logFds[index]
            if (range != null && fd >= 0) {
                // 日志写成功后, 在**同一目录**置异常退出标记 (启动期提示读它;
                // 偏好存储在信号上下文内不可用, 只能碰文件)
                val markerFd = markerFds.getOrNull(index) ?: -1
                if (markerFd >= 0) {
                    writeAll(markerFd, MARKER_TEXT, 0, MARKER_TEXT.size)
                }
                writeAll(fd, buffer, 0, signalBodyLength)
                writeAll(fd, buffer, range.first, range.last - range.first + 1)
                return
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
     * 信号名 (定宽 9 字符, 不足补空格)。
     *
     * 已由各端在安装期按自己的平台常量备好并传给 [writeSignal]: 信号编号在 iOS 与 Linux
     * 内核上并不相同 (如 SIGSYS), 共用实现里用字面量会把名字映射错。此处不再自行映射。
     */

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
