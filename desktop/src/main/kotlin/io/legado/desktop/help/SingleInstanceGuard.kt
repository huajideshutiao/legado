package io.legado.desktop.help

import io.legado.app.help.file.desktopAppRootDir
import io.legado.app.ui.association.LegadoDeepLink
import io.legado.app.ui.association.LegadoDeepLinkHandler
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.Frame
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/** lock 文件内容 (首实例写, 二次启动读)。pid 仅供人工排查, 不参与判定。 */
@Serializable
private data class InstanceLock(val port: Int, val token: String, val pid: Long = -1L)

/** 转发报文: token 校验 + 原样启动参数。 */
@Serializable
private data class ForwardMessage(val token: String, val args: List<String>)

/**
 * 桌面端单实例守卫 (对照 app 端 AssociationActivity 的 singleTask: 二次启动复用已有实例)。
 *
 * 用户已运行 legado 时再次启动 (浏览器点 `legado://` 链接 / 双击 exe), 应把启动参数转发给
 * 已运行实例并前置其窗口, 而不是开第二个进程 (第二个进程会与首实例争抢同一个 SQLite 库)。
 *
 * # 协议
 *
 * - **lock 文件**: `{desktopAppRootDir()}/instance.lock`, 单行 JSON `{"port":N,"token":"hex","pid":N}`。
 *   首实例用 临时文件 + ATOMIC_MOVE 写入, 读方要么看到旧内容要么看到新内容, 不会读到半截。
 * - **监听**: 首实例在 `127.0.0.1` 随机端口 (bind port 0) 开 [ServerSocket], 只收环回连接。
 * - **转发**: 二次启动读 lock → connect → 发一行 JSON `{"token":"...","args":[...]}` (UTF-8, `\n` 结尾)
 *   → 读一行应答; 应答为 [REPLY_OK] 才算送达, 随即 `exitProcess(0)`。
 * - **应答**: token 匹配回 [REPLY_OK], 不匹配回 [REPLY_DENY] (端口撞车到别的进程时对方多半直接
 *   断开或超时, 见下方"边界")。
 *
 * # 首实例收到转发后的动作
 *
 * 1. args 里第一个 legado://`/`yuedu:// URL 投递 [LegadoDeepLinkHandler.handle]
 *    (与 Main.kt 冷启动 `handleDeepLinkArgs` 同一条链, 由 DeepLinkImportHost 弹导入框);
 * 2. 窗口前置 ([bindWindow] 注册的 AWT 窗口, EDT 上取消最小化 + toFront + requestFocus)。
 *
 * # 边界处理
 *
 * - **残留 lock** (上次进程被 kill, 文件没清): connect 抛异常 / 应答超时 → 判定为陈旧,
 *   本进程接管为首实例并覆盖 lock。
 * - **lock 文件损坏** (半截 JSON / 非 JSON / 端口越界): 解析失败当作无 lock, 直接接管。
 * - **端口撞车** (lock 记的端口被别的进程占了): token 不匹配对方不会回 [REPLY_OK];
 *   读应答加 [READ_TIMEOUT_MS] 超时, 对方不吭声也不会把本进程挂死, 超时后接管。
 * - **报文过长**: 读取限长 [MAX_LINE_CHARS], 超限直接断开, 防止畸形连接吃满内存。
 * - **退出清理**: shutdown hook 关监听并删 lock, 且只删 token 仍是自己的那份
 *   (避免删掉接管者刚写的新 lock)。
 */
object SingleInstanceGuard {

    private const val LOCK_FILE_NAME = "instance.lock"
    private const val REPLY_OK = "OK"
    private const val REPLY_DENY = "DENY"
    private const val CONNECT_TIMEOUT_MS = 800
    private const val READ_TIMEOUT_MS = 1500
    private const val MAX_LINE_CHARS = 64 * 1024

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val debug = System.getProperty("legado.desktop.debug")?.toBoolean() == true

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var ownToken: String? = null

    /** 首实例的主窗口 (Main.kt 在 Window 内 bind), 供转发到达时前置; 窗口未组合完成时为 null。 */
    @Volatile
    private var mainWindow: java.awt.Window? = null

    /**
     * 单实例入口: **必须在 main() 最前调用** (在 handleDeepLinkArgs / 任何数据库或 provider 初始化之前),
     * 否则二次启动进程会先碰 SQLite 再退出。
     *
     * 调用前需保证 `legado.portable.root` 已设置 (Main.kt 的 initDesktopRuntimeEnvironment),
     * 因为 [desktopAppRootDir] 的解析结果进程内 lazy 缓存一次, 提前调用会把便携模式的数据根定位歪。
     *
     * 已有实例存活: 转发 args 后 `exitProcess(0)`, **本函数不返回**。
     * 无实例 / 残留 lock: 接管为首实例 (开监听 + 写 lock + 注册 shutdown hook) 并正常返回。
     */
    fun ensureSingleInstance(args: Array<String>) {
        val lockFile = lockFile() ?: return
        if (forwardToRunningInstance(lockFile, args)) {
            debugLog("已有实例接收本次启动参数, 当前进程退出")
            exitProcess(0)
        }
        becomePrimary(lockFile)
    }

    /**
     * 绑定主窗口 (Main.kt 在 `Window { }` 内用 FrameWindowScope 的 `window` 调用, dispose 时传 null)。
     * 未绑定时转发仍会投递 deep link, 只是不做窗口前置。
     */
    fun bindWindow(window: java.awt.Window?) {
        mainWindow = window
    }

    // ==================== 二次启动: 转发侧 ====================

    /** 读 lock → 连 → 发 → 等应答; 返回 true 表示已送达存活实例 (调用方应退出)。 */
    private fun forwardToRunningInstance(lockFile: File, args: Array<String>): Boolean {
        val lock = readLock(lockFile) ?: return false
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(loopback(), lock.port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS
                val payload = json.encodeToString(ForwardMessage(lock.token, args.toList()))
                val writer = socket.getOutputStream().writer(StandardCharsets.UTF_8)
                writer.write(payload)
                writer.write("\n")
                writer.flush()
                val reply = readLineLimited(socket.getInputStream().reader(StandardCharsets.UTF_8))
                reply == REPLY_OK
            }
        }.getOrElse {
            // connect 被拒 (残留 lock) / 读应答超时 (端口撞车到闷声不响的进程) → 当作无实例
            debugLog("转发到已有实例失败, 接管为首实例: ${it.javaClass.simpleName}: ${it.message}")
            false
        }
    }

    // ==================== 首实例: 监听侧 ====================

    private fun becomePrimary(lockFile: File) {
        val token = newToken()
        val server = runCatching { ServerSocket(0, 16, loopback()) }.getOrElse {
            // 环回监听都开不了 (极端安全策略), 放弃单实例能力, 不阻断启动
            debugLog("单实例监听启动失败, 降级为多实例: ${it.message}")
            return
        }
        serverSocket = server
        ownToken = token
        if (!writeLock(lockFile, InstanceLock(server.localPort, token, currentPid()))) {
            runCatching { server.close() }
            serverSocket = null
            ownToken = null
            return
        }
        Runtime.getRuntime().addShutdownHook(Thread { releaseLock(lockFile, token) })
        Thread({ acceptLoop(server, token) }, "legado-single-instance").apply {
            isDaemon = true
            start()
        }
        debugLog("首实例监听 127.0.0.1:${server.localPort}, lock=${lockFile.absolutePath}")
    }

    private fun acceptLoop(server: ServerSocket, token: String) {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrElse { return }
            runCatching { handleConnection(socket, token) }
                .onFailure { debugLog("处理转发连接异常: ${it.message}") }
            runCatching { socket.close() }
        }
    }

    private fun handleConnection(socket: Socket, token: String) {
        socket.soTimeout = READ_TIMEOUT_MS
        val line = readLineLimited(socket.getInputStream().reader(StandardCharsets.UTF_8))
        val message = line?.let { runCatching { json.decodeFromString<ForwardMessage>(it) }.getOrNull() }
        val accepted = message != null && message.token == token
        val writer = socket.getOutputStream().writer(StandardCharsets.UTF_8)
        writer.write(if (accepted) REPLY_OK else REPLY_DENY)
        writer.write("\n")
        writer.flush()
        if (!accepted) {
            debugLog("拒绝转发连接 (token 不匹配或报文非法)")
            return
        }
        onForwardedArgs(message.args)
    }

    /** 首实例消费转发来的启动参数: 投递 deep link + 前置窗口 (与 Main.kt 冷启动语义一致)。 */
    private fun onForwardedArgs(args: List<String>) {
        args.firstOrNull { LegadoDeepLink.isDeepLink(it) }?.let { url ->
            if (!LegadoDeepLinkHandler.handle(url)) {
                debugLog("转发的 deep link 解析失败 (缺 src 参数): $url")
            }
        }
        activateWindow()
    }

    /**
     * 窗口前置 (EDT 上执行): 取消最小化 → 临时置顶抬到最前 → 请求焦点 → 还原置顶。
     * Windows 上单纯 toFront 常被前台窗口锁拒绝, 借一次 alwaysOnTop 翻转把窗口抬出来, 随后立刻还原。
     */
    private fun activateWindow() {
        val window = mainWindow ?: return
        SwingUtilities.invokeLater {
            runCatching {
                (window as? Frame)?.let { frame ->
                    if (frame.extendedState and Frame.ICONIFIED != 0) {
                        frame.extendedState = frame.extendedState and Frame.ICONIFIED.inv()
                    }
                }
                window.isVisible = true
                val wasAlwaysOnTop = window.isAlwaysOnTop
                if (!wasAlwaysOnTop && window.isAlwaysOnTopSupported) {
                    window.isAlwaysOnTop = true
                    window.toFront()
                    window.isAlwaysOnTop = false
                } else {
                    window.toFront()
                }
                window.requestFocus()
            }
        }
    }

    // ==================== lock 文件读写 ====================

    private fun lockFile(): File? = runCatching { File(desktopAppRootDir(), LOCK_FILE_NAME) }
        .getOrElse {
            debugLog("数据目录不可用, 跳过单实例: ${it.message}")
            null
        }

    /** 解析 lock; 文件缺失/损坏/端口非法一律返回 null (调用方按"无实例"处理)。 */
    private fun readLock(lockFile: File): InstanceLock? {
        if (!lockFile.isFile) return null
        val text = runCatching { lockFile.readText(StandardCharsets.UTF_8) }.getOrNull()
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val lock = runCatching { json.decodeFromString<InstanceLock>(text) }.getOrElse {
            debugLog("lock 文件损坏, 按无实例处理: ${lockFile.absolutePath}")
            return null
        }
        if (lock.port !in 1..65535 || lock.token.isEmpty()) {
            debugLog("lock 内容非法 (port=${lock.port}), 按无实例处理")
            return null
        }
        return lock
    }

    /** 临时文件 + ATOMIC_MOVE 落盘, 避免并发读到半截内容。 */
    private fun writeLock(lockFile: File, lock: InstanceLock): Boolean = runCatching {
        lockFile.parentFile?.mkdirs()
        val tmp = File.createTempFile("instance", ".lock.tmp", lockFile.parentFile)
        tmp.writeText(json.encodeToString(lock), StandardCharsets.UTF_8)
        runCatching {
            Files.move(
                tmp.toPath(), lockFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
            )
        }.getOrElse {
            // 个别文件系统不支持原子移动, 退化为普通替换
            Files.move(tmp.toPath(), lockFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        true
    }.getOrElse {
        debugLog("写 lock 失败, 降级为多实例: ${it.message}")
        false
    }

    /** 退出清理: 关监听 + 删 lock, 且只删还属于自己的那份 (token 比对)。 */
    private fun releaseLock(lockFile: File, token: String) {
        runCatching { serverSocket?.close() }
        serverSocket = null
        if (readLock(lockFile)?.token == token) {
            runCatching { lockFile.delete() }
        }
        ownToken = null
    }

    // ==================== 工具 ====================

    private fun loopback(): InetAddress = InetAddress.getLoopbackAddress()

    private fun newToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun currentPid(): Long = runCatching { ProcessHandle.current().pid() }.getOrDefault(-1L)

    /** 读一行 (以 `\n` 结束), 超过 [MAX_LINE_CHARS] 返回 null (畸形连接防护)。 */
    private fun readLineLimited(reader: java.io.Reader): String? {
        val sb = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c < 0) return sb.takeIf { it.isNotEmpty() }?.toString()?.trim()
            if (c == '\n'.code) return sb.toString().trim()
            sb.append(c.toChar())
            if (sb.length > MAX_LINE_CHARS) return null
        }
    }

    private fun debugLog(msg: String) {
        if (debug) println("[legado-desktop][single-instance] $msg")
    }
}
