package io.legado.desktop.help.video

import io.legado.app.constant.AppLog
import io.legado.app.help.config.PreferenceProviders
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * mpv 可执行文件检测 (desktop 视频播放 all-in mpv 的入口探测)。
 *
 * 检测顺序:
 * 1. 设置项自定义路径 ([PREF_KEY_MPV_PATH], java.util.prefs)
 * 2. PATH 上 `mpv --version` 试探
 * 3. 平台常见安装路径扫描 (Windows: 官方包/winget/scoop/chocolatey; Unix: /usr/bin 等)
 *
 * 每个候选都跑 `--version` 验证可执行 (5s 超时), 结果进程级缓存;
 * [detect] `forceRefresh = true` 供"重新检测"按钮在用户装完 mpv 后刷新。
 */
object MpvDetector {

    /** 设置项 key: 自定义 mpv 可执行文件完整路径 (跨 build 稳定的字符串 key) */
    const val PREF_KEY_MPV_PATH = "mpvPath"

    private val osName = System.getProperty("os.name", "").lowercase()

    val isWindows: Boolean = osName.contains("win")

    /** macOS 上 AWT Canvas 拿不到可供 --wid 嵌入的 NSView 句柄, 走独立窗口模式 */
    val isMac: Boolean = osName.contains("mac") || osName.contains("darwin")

    @Volatile
    private var cached: String? = null

    @Volatile
    private var probed = false

    /**
     * 检测 mpv, 返回可直接传给 ProcessBuilder 的命令或完整路径; null = 未找到。
     *
     * @param forceRefresh 忽略缓存重新探测 (用户装完 mpv 后点"重新检测")
     */
    fun detect(forceRefresh: Boolean = false): String? {
        if (probed && !forceRefresh) return cached
        synchronized(this) {
            if (probed && !forceRefresh) return cached
            cached = doDetect()
            probed = true
            return cached
        }
    }

    private fun doDetect(): String? {
        // 1. 设置项自定义路径 (优先级最高; 失效时打日志继续向后探测, 不阻断)
        val custom = runCatching {
            PreferenceProviders.get().getString(PREF_KEY_MPV_PATH, "")
        }.getOrDefault("")
        if (custom.isNotBlank()) {
            if (File(custom).isFile && probe(custom)) return custom
            AppLog.put("设置项 $PREF_KEY_MPV_PATH=$custom 不可用, 回退自动探测")
        }
        // 2. PATH 试探
        if (probe("mpv")) return "mpv"
        // 3. 常见安装路径扫描
        for (candidate in commonInstallPaths()) {
            if (File(candidate).isFile && probe(candidate)) return candidate
        }
        return null
    }

    /** 平台常见安装路径 (Windows: 环境变量拼接; Unix: 固定绝对路径) */
    private fun commonInstallPaths(): List<String> = if (isWindows) {
        buildList {
            System.getenv("ProgramFiles")?.let { add("$it\\mpv\\mpv.exe") }
            System.getenv("ProgramFiles(x86)")?.let { add("$it\\mpv\\mpv.exe") }
            System.getenv("LOCALAPPDATA")?.let {
                add("$it\\Programs\\mpv\\mpv.exe")
                // winget 的可执行软链目录
                add("$it\\Microsoft\\WinGet\\Links\\mpv.exe")
            }
            // scoop shim / chocolatey bin
            System.getenv("USERPROFILE")?.let { add("$it\\scoop\\shims\\mpv.exe") }
            System.getenv("ProgramData")?.let { add("$it\\chocolatey\\bin\\mpv.exe") }
        }
    } else {
        listOf(
            "/usr/bin/mpv",
            "/usr/local/bin/mpv",
            "/opt/homebrew/bin/mpv",
            "/snap/bin/mpv",
            "/Applications/mpv.app/Contents/MacOS/mpv",
        )
    }

    /** `<cmd> --version` 试探可执行性 (5s 超时; 退出码 0 视为可用) */
    private fun probe(cmd: String): Boolean = runCatching {
        val process = ProcessBuilder(cmd, "--version")
            .redirectErrorStream(true)
            .start()
        // drain 输出防缓冲塞满 (mpv --version 输出很短, 但保持惯例)
        Thread({ runCatching { process.inputStream.readAllBytes() } }, "mpv-probe-drain")
            .apply { isDaemon = true }
            .start()
        val done = process.waitFor(5, TimeUnit.SECONDS)
        if (!done) {
            process.destroyForcibly()
            return@runCatching false
        }
        process.exitValue() == 0
    }.getOrDefault(false)
}
