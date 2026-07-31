package io.legado.desktop.help.video

import io.legado.app.constant.AppLog
import io.legado.app.ui.compose.platform.jvmGetString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * mpv 外部进程播放器 (desktop 视频 all-in mpv 方案的核心)。
 *
 * # 架构
 *
 * - **一个实例 = 一次播放**: 每个 URL (章节/分辨率) 起一个 mpv 进程, 播完 (--keep-open=no)
 *   或切章时进程退出; 不用 --idle+loadfile 常驻模式, 避免 loadfile 第 3/4 参在
 *   mpv 0.38 前后位置不同的兼容坑, 也让 IPC 保持"纯增强"定位 (IPC 挂了播放不受影响)
 * - **嵌入**: `--wid=<AWT Canvas 句柄>` 把 mpv 挂为子窗口 (Windows HWND / X11 XID);
 *   wid=null 时开独立窗口 (macOS 拿不到 NSView 句柄时的降级)
 * - **控制层**: mpv 内建 OSC (--osc=yes, 播放/暂停/进度/音量), 不再叠 Compose 控制层
 *   (SwingPanel 是重量级 AWT, Compose 无法叠加其上)
 * - **IPC 桥** (--input-ipc-server, Windows 命名管道 / Unix domain socket):
 *   观察 time-pos/duration 供进度回写, 监听 end-file(eof) 自动切下一章,
 *   转发 Compose 侧键盘快捷键 (空格/方向键) 为 mpv 命令; 连接失败仅丢增强能力
 * - **防盗链**: header 全量透传含 Cookie —— UA/Referer 走专属选项 (达 HLS 分片请求),
 *   其余逐条 `--http-header-fields-append` (避开 --http-header-fields 逗号转义)
 *
 * # 进度落存语义
 *
 * 进程退出时回调 [onProcessExit] (最后观测的 time-pos/duration):
 * - 仅在收到过播放进度 (hadPlayback) 且未被 [quit] `discardProgress=true` 时触发,
 *   防止"启动失败/未播先退"把 0 写回冲掉已存进度
 * - 章节切换按原语义重置为 0 (由 VM saveVideoProgress(0) 负责), 旧进程 discard 退出
 *
 * @param mpvPath mpv 可执行 (来自 [MpvDetector.detect])
 * @param onEof 自然播完回调 (end-file reason=eof; 调用方切下一章)
 * @param onPlayError 播放失败回调 (end-file reason=error 或进程非零退出; 每实例至多一次)
 * @param onProcessExit 进程退出回调 (lastPosMs, durationMs), 供进度落存
 */
class MpvPlayer(
    private val mpvPath: String,
    private val onEof: () -> Unit = {},
    private val onPlayError: (String) -> Unit = {},
    private val onProcessExit: (lastPosMs: Long, durationMs: Long) -> Unit = { _, _ -> },
) {

    /** 最后观测的播放位置 (毫秒, IPC time-pos 事件持续刷新) */
    @Volatile
    var lastPosMs: Long = 0L
        private set

    /** 最后观测的媒体总时长 (毫秒) */
    @Volatile
    var durationMs: Long = 0L
        private set

    private var process: Process? = null
    private var connection: IpcConnection? = null

    private val started = AtomicBoolean(false)
    private val quitRequested = AtomicBoolean(false)
    private val discardProgress = AtomicBoolean(false)
    private val errorOnce = AtomicBoolean(false)

    /** 是否收到过播放进度 (未播先退时不落存, 防冲掉已存进度) */
    @Volatile
    private var hadPlayback = false

    /** end-file(eof) 已见标记 (waiter 据此区分自然播完与异常退出) */
    @Volatile
    private var eofSeen = false

    @Volatile
    private var ipcReady = false

    private val writeLock = Any()

    /** IPC 未就绪时的命令暂存队列 (连接建立后按序补发) */
    private val pending = ArrayDeque<String>()

    /** mpv 错误输出尾部 (最近 20 行), 启动失败时随原因上报, 避免"白屏无因" */
    private val outputTail = ArrayDeque<String>()

    @Volatile
    private var drainThread: Thread? = null

    /** IPC 端点: Windows 命名管道 / Unix domain socket 文件 */
    private val ipcPath: String = if (MpvDetector.isWindows) {
        "\\\\.\\pipe\\legado-mpv-${ProcessHandle.current().pid()}-${SEQ.incrementAndGet()}"
    } else {
        File(
            System.getProperty("java.io.tmpdir"),
            "legado-mpv-${ProcessHandle.current().pid()}-${SEQ.incrementAndGet()}.sock",
        ).absolutePath
    }

    /**
     * 启动 mpv 进程 (每实例仅一次)。
     *
     * @param playPath 播放目标 (http(s) 直链或本地文件路径)
     * @param headers HTTP 请求头全量 (含 Cookie; 本地文件场景传空 map)
     * @param startMs 起播位置 (毫秒, >0 时 --start 恢复进度)
     * @param wid 嵌入的原生窗口句柄 (null = 独立窗口)
     * @param windowTitle 窗口标题 (书名)
     * @param mediaTitle OSC 显示标题 (书名 + 章节)
     * @param unsafeLocalPlaylist 本地 m3u8 引用网络分片 (内存 m3u8 落地临时文件场景),
     *   需放行 mpv 播放列表安全拦截 + ffmpeg 协议白名单
     */
    fun start(
        playPath: String,
        headers: Map<String, String>,
        startMs: Long,
        wid: Long?,
        windowTitle: String,
        mediaTitle: String,
        unsafeLocalPlaylist: Boolean = false,
    ) {
        check(started.compareAndSet(false, true)) { "MpvPlayer 每个实例只能 start 一次" }
        val cmd = buildList {
            add(mpvPath)
            // 嵌入: mpv 以子窗口挂到 AWT Canvas 原生句柄 (Windows HWND / X11 XID)
            if (wid != null) add("--wid=$wid")
            // IPC 桥: 进度观察 / eof 联动 / 快捷键转发 / 退出控制
            add("--input-ipc-server=$ipcPath")
            add("--force-window=yes") // 网络慢/无视频轨时也先建窗口 (黑底占位)
            add("--osc=yes") // mpv 内建 OSC 控制层
            add("--input-default-bindings=yes") // mpv 原生快捷键 (点击视频区获焦后可用)
            // 嵌入子窗口不该自己全屏 (用户 mpv.conf 的 fullscreen=yes 会盖住整个界面)
            add("--fullscreen=no")
            add("--keep-open=no") // 播完即 end-file(eof) → 进程退出 → 调用方切下一章
            add("--input-terminal=no") // 不读 stdin (ProcessBuilder 给的管道不会关, 避免误判按键/阻塞)
            add("--msg-level=all=error") // 只留错误行, 供 outputTail 捕获后随失败原因上报
            add("--title=$windowTitle")
            add("--force-media-title=$mediaTitle")
            if (startMs > 0) add("--start=${startMs / 1000.0}")
            // header 全量透传 (含 Cookie): UA/Referer 走专属选项 (mpv 会传导给 ffmpeg,
            // 达 HLS 分片请求), 其余逐条 -append 追加, 避开 --http-header-fields 逗号转义
            headers.forEach { (key, value) ->
                when {
                    key.equals("User-Agent", true) -> add("--user-agent=$value")
                    key.equals("Referer", true) || key.equals("Referrer", true) ->
                        add("--referrer=$value")

                    else -> add("--http-header-fields-append=$key: $value")
                }
            }
            if (unsafeLocalPlaylist) {
                // 本地播放列表引用网络分片默认被 mpv 安全策略拒载, ffmpeg 同样要求协议白名单
                add("--load-unsafe-playlists=yes")
                add("--demuxer-lavf-o-append=protocol_whitelist=file,http,https,tcp,tls,crypto,data")
            }
            add("--") // 防 playPath 以 - 开头被误当选项
            add(playPath)
        }
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        process = p
        // 收集 mpv 错误输出尾部 (顺带 drain 防缓冲塞满), 失败时随原因一起上报
        drainThread = Thread({ drainOutput(p) }, "legado-mpv-drain")
            .apply { isDaemon = true; start() }
        // IPC 连接 + 事件读循环
        Thread({ runIpc(p) }, "legado-mpv-ipc").apply { isDaemon = true }.start()
        // 进程退出监视: 落存进度 + 异常退出上报
        Thread({ waitExit(p) }, "legado-mpv-waiter").apply { isDaemon = true }.start()
    }

    /**
     * 发送 mpv JSON IPC 命令 (线程安全; IPC 未就绪时入队, 连接后按序补发)。
     *
     * 例: `command("cycle", "pause")` / `command("seek", 10.0, "relative")` /
     * `command("set_property", "speed", 2.0)`
     */
    fun command(vararg args: Any) {
        val json = buildJsonObject {
            put("command", buildJsonArray {
                args.forEach { arg ->
                    when (arg) {
                        is JsonElement -> add(arg)
                        is Boolean -> add(JsonPrimitive(arg))
                        is Number -> add(JsonPrimitive(arg))
                        else -> add(JsonPrimitive(arg.toString()))
                    }
                }
            })
        }.toString()
        send(json)
    }

    /**
     * 退出 mpv: IPC quit → 2s 等待 → destroy → 1s → destroyForcibly。
     *
     * @param discardProgress true = 本次退出不落存进度 (章节/分辨率切换清场;
     *   进度重置语义由调用方负责), false = 退出时按 [onProcessExit] 落存
     */
    fun quit(discardProgress: Boolean) {
        if (discardProgress) this.discardProgress.set(true)
        if (!quitRequested.compareAndSet(false, true)) return
        val p = process ?: return
        if (!p.isAlive) return
        command("quit")
        Thread({
            runCatching {
                if (!p.waitFor(2, TimeUnit.SECONDS)) {
                    p.destroy()
                    if (!p.waitFor(1, TimeUnit.SECONDS)) p.destroyForcibly()
                }
            }
        }, "legado-mpv-quit").apply { isDaemon = true }.start()
    }

    // ---- 内部: 进程输出 ----

    private fun drainOutput(p: Process) {
        runCatching {
            p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                synchronized(outputTail) {
                    if (outputTail.size >= 20) outputTail.removeFirst()
                    outputTail.addLast(line)
                }
            }
        }
    }

    /** 错误原因拼上 mpv 输出尾部 (有则附加), 供 UI 展示 */
    private fun withOutputTail(reason: String): String {
        val tail = synchronized(outputTail) { outputTail.joinToString("\n") }
        return if (tail.isBlank()) reason else "$reason\n$tail"
    }

    // ---- 内部: IPC ----

    private fun send(json: String) {
        synchronized(writeLock) {
            val conn = connection
            if (!ipcReady || conn == null) {
                if (pending.size < 64) pending.addLast(json)
                return
            }
            runCatching { conn.writeLine(json) }.onFailure {
                AppLog.putDebug("mpv IPC 写入失败: ${it.message}")
            }
        }
    }

    private fun runIpc(p: Process) {
        val conn = connectWithRetry(p)
        if (conn == null) {
            if (p.isAlive && !quitRequested.get()) {
                AppLog.put(jvmGetString("video_mpv_ipc_error_log", ipcPath))
            }
            return
        }
        synchronized(writeLock) {
            connection = conn
            ipcReady = true
            runCatching {
                // id 1/2 与 handleEvent 对应
                conn.writeLine("""{"command":["observe_property",1,"time-pos"]}""")
                conn.writeLine("""{"command":["observe_property",2,"duration"]}""")
                while (pending.isNotEmpty()) conn.writeLine(pending.removeFirst())
            }
        }
        try {
            while (true) {
                val line = conn.readLine() ?: break
                if (line.isNotBlank()) {
                    runCatching { handleEvent(line) }
                }
            }
        } catch (e: Exception) {
            if (!quitRequested.get() && p.isAlive) {
                AppLog.putDebug("mpv IPC 事件流中断: ${e.message}")
            }
        } finally {
            synchronized(writeLock) { ipcReady = false }
            runCatching { conn.close() }
        }
    }

    /** mpv 启动后异步创建 IPC 端点, 250ms 间隔重试至多 10s; 失败返回 null (纯增强, 不影响播放) */
    private fun connectWithRetry(p: Process): IpcConnection? {
        repeat(40) {
            if (!p.isAlive || quitRequested.get()) return null
            runCatching { openConnection() }.getOrNull()?.let { return it }
            runCatching { Thread.sleep(250) }
        }
        return null
    }

    private fun openConnection(): IpcConnection = if (MpvDetector.isWindows) {
        PipeConnection(ipcPath)
    } else {
        SocketConnection(ipcPath)
    }

    private fun handleEvent(line: String) {
        val obj = Json.parseToJsonElement(line).jsonObject
        when (obj["event"]?.jsonPrimitive?.contentOrNull) {
            "property-change" -> {
                val data = (obj["data"] as? JsonPrimitive)?.doubleOrNull ?: return
                when (obj["id"]?.jsonPrimitive?.intOrNull) {
                    1 -> {
                        lastPosMs = (data * 1000).toLong().coerceAtLeast(0L)
                        if (lastPosMs > 0) hadPlayback = true
                    }

                    2 -> durationMs = (data * 1000).toLong().coerceAtLeast(0L)
                }
            }

            "end-file" -> when (obj["reason"]?.jsonPrimitive?.contentOrNull) {
                "eof" -> {
                    eofSeen = true
                    onEof()
                }

                "error" -> {
                    if (errorOnce.compareAndSet(false, true)) {
                        onPlayError(
                            withOutputTail(
                                obj["file_error"]?.jsonPrimitive?.contentOrNull ?: "end-file error"
                            )
                        )
                    }
                }
            }
        }
    }

    private fun waitExit(p: Process) {
        val exit = runCatching { p.waitFor() }.getOrDefault(-1)
        // 等 drain 收完尾部输出再报错, 否则错误原因可能是空的
        runCatching { drainThread?.join(500) }
        synchronized(writeLock) { ipcReady = false }
        runCatching { connection?.close() }
        if (!MpvDetector.isWindows) runCatching { File(ipcPath).delete() }
        val discarded = discardProgress.get()
        if (!discarded && hadPlayback) {
            onProcessExit(lastPosMs, durationMs)
        }
        // 非零退出且非主动 quit/自然播完 → 播放失败 (启动参数错/流不可用/mpv 崩溃)
        if (exit != 0 && !quitRequested.get() && !eofSeen && !discarded &&
            errorOnce.compareAndSet(false, true)
        ) {
            onPlayError(withOutputTail("mpv 退出码 $exit"))
        }
    }

    // ---- IPC 连接抽象 (Windows 命名管道 / Unix domain socket) ----

    private interface IpcConnection {
        fun readLine(): String?
        fun writeLine(line: String)
        fun close()
    }

    /**
     * Windows 命名管道: RandomAccessFile 可直接开 `\\.\pipe\xxx`。
     *
     * 读线程阻塞在 [readLine] 时写入同句柄可能被内核短暂串行化, 但 observe_property
     * 的 time-pos 事件流 (~1s/条) 会持续解锁, 写延迟上限约一个事件间隔, 可接受。
     */
    private class PipeConnection(path: String) : IpcConnection {
        private val raf = RandomAccessFile(path, "rw")
        override fun readLine(): String? = raf.readLine()
        override fun writeLine(line: String) {
            raf.write((line + "\n").toByteArray(Charsets.UTF_8))
        }

        override fun close() {
            runCatching { raf.close() }
        }
    }

    /** Unix domain socket (JDK16+ SocketChannel; 项目 jvmTarget 17 满足) */
    private class SocketConnection(path: String) : IpcConnection {
        private val channel = SocketChannel.open(StandardProtocolFamily.UNIX).also {
            it.connect(UnixDomainSocketAddress.of(path))
        }
        private val reader = Channels.newInputStream(channel).bufferedReader()
        private val output = Channels.newOutputStream(channel)

        override fun readLine(): String? = reader.readLine()

        override fun writeLine(line: String) {
            output.write((line + "\n").toByteArray(Charsets.UTF_8))
            output.flush()
        }

        override fun close() {
            runCatching { channel.close() }
        }
    }

    private companion object {
        /** IPC 端点名去重序号 (同进程多次进出视频页) */
        private val SEQ = AtomicInteger(0)
    }
}
