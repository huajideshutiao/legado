package io.legado.desktop.help

import io.legado.app.constant.AppLog
import io.legado.app.help.file.desktopAppRootDir
import io.legado.app.ui.association.LegadoDeepLink
import io.legado.app.ui.association.LegadoDeepLinkHandler
import io.legado.desktop.offerAssociationArgs
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
 * # 启动耗时 (2026-09 实测与本类为何不在主线程跑完整套)
 *
 * 同步跑完整套“探活 + bind + 写 lock”在主线程吃掉 110~166ms, 而这段与 Compose 开窗前的
 * AWT/Swing 初始化 (实测 189ms) 本来可以并行。所以入口改为 [startAsync] (后台线程) +
 * [awaitPrimaryDecision] (主线程在碰到任何配置/数据库之前的唯一一次等待), 等待只覆盖
 * “探活判定”, 不覆盖 bind/写 lock (那些跟本进程的启动无关)。
 *
 * 并发双起 (连击双击) 的竞态窗口不因此变宽: 今天同样是“探活后紧接着 bind+写 lock”之间那段
 * 窗口 (两进程都探到无实例 → 都当首实例), 本类只把这段从主线程搬到守卫线程, 时长不变。
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
 * 2. args 里的关联文件地址投递 [offerAssociationArgs] (文件关联双击, 与冷启动共用同一入口筛子);
 * 3. 窗口前置 ([bindWindow] 注册的 AWT 窗口, EDT 上取消最小化 + toFront + requestFocus)。
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
    private const val TAG = "single-instance"

    @Volatile
    private var serverSocket: ServerSocket? = null

    /** 守卫线程句柄 (仅 [startAsync] 写)。 */
    @Volatile
    private var guardThread: Thread? = null

    /** “探活判定已完成”门闩: 主线程在 [awaitPrimaryDecision] 上等它, 幂等。 */
    private val decided = CountDownLatch(1)

    /** 首实例的主窗口 (Main.kt 在 Window 内 bind), 供转发到达时前置; 窗口未组合完成时为 null。 */
    @Volatile
    private var mainWindow: java.awt.Window? = null

    /**
     * 入口 (后台线程版): 在 `main()` 里起守卫线程后立即返回, 主线程继续做与数据无关的初始化
     * (AWT/Swing 类加载、deep link/关联文件入队); “是不是第二个实例”的判定由
     * [awaitPrimaryDecision] 在**任何配置/数据库初始化之前** 收口。
     *
     * 调用前需保证 `legado.portable.root` 已设置 (Main.kt 的 initDesktopRuntimeEnvironment),
     * 因为 [desktopAppRootDir] 的解析结果进程内 lazy 缓存一次, 提前调用会把便携模式的数据根定位歪。
     */
    fun startAsync(args: Array<String>) {
        val thread = Thread({
            try {
                ensureSingleInstance(args)
            } finally {
                // 守卫线程抱异常也要放闸: 不能让主线程干等。判定没做成就继续启动, 与“bind
                // 失败降级为多实例”同一取向 —— 宁可丢单实例能力, 不可把启动卡死。
                decided.countDown()
            }
        }, "legado-single-instance-guard")
        // daemon: 本线程可能长期挂在 acceptLoop 上, 当用户线程会阻止 JVM 正常退出
        thread.isDaemon = true
        guardThread = thread
        thread.start()
    }

    /**
     * 等 [startAsync] 的探活判定 (已有实例存活时守卫线程直接 exitProcess, 本函数不会被走到)。
     *
     * 必须在首次触碰 java.util.prefs / Room 数据库 / 任何数据文件写入之前调用。
     * 默认上限 [DEFAULT_DECIDE_WAIT_MS] 覆盖探活内部两处超时 (connect 800ms + 读应答 1500ms),
     * 所以超时不是“正常慢”, 而是守卫线程真挂死 —— 那种情况选择继续启动并留痕,
     * 代价是可能与已有实例同时开同一库, 比把窗口永久卡在闪屏上可接受。
     *
     * @return true = 判定已完成 (本进程是首实例); false = 超时后放行
     */
    fun awaitPrimaryDecision(maxWaitMs: Long = DEFAULT_DECIDE_WAIT_MS): Boolean {
        if (guardThread == null) return true
        val ok = decided.await(maxWaitMs, TimeUnit.MILLISECONDS)
        if (!ok) {
            // 超时 = 守卫线程挂死 (探活内部自带 connect 800ms + 读应答 1500ms 上界, 正常走不到)。
            // 此刻主线程将往下走 java.util.prefs 与 Room, 所以必须先立旗再放行:
            // 否则守卫随后探到存活实例会 exitProcess(0), 把一个**已经开始写注册表/开库**的进程腰斩
            // (这是改异步后新增的窗口: 旧代码同步阻塞, 二次进程在 exitProcess 前不可能碰数据)。
            decisionAbandoned = true
            AppLog.put("单实例判定超时 ${maxWaitMs}ms, 放行启动 (可能与已有实例并存, 已禁止守卫事后退出)", tag = TAG)
        }
        return ok
    }

    /**
     * 主线程是否已"不等判定"放行。置位后守卫线程**不得再 exitProcess**:
     * 宁可退化成"两个进程同时开一个库"(SQLite WAL 有文件锁, 只会 SQLITE_BUSY),
     * 也不能在写注册表/写库中途被自己 kill。
     */
    @Volatile
    private var decisionAbandoned = false

    /** 探活内部超时总和 (connect 800 + 读应答 1500) 的宽容量, 见 [awaitPrimaryDecision]。 */
    private const val DEFAULT_DECIDE_WAIT_MS = 3_000L

    /**
     * 单实例主体: 探活 → (已有实例则转发 + `exitProcess(0)`, **不返回**) → 判定完成 → 接管为首实例。
     *
     * 由 [startAsync] 在守卫线程上调; 主线程必须经 [awaitPrimaryDecision] 等过判定才能碰数据。
     */
    private fun ensureSingleInstance(args: Array<String>) {
        val lockFile = lockFile() ?: return
        if (forwardToRunningInstance(lockFile, args)) {
            if (decisionAbandoned) {
                // 主线程已经越过判定点开始碰数据 —— 这里再 exitProcess 就是腰斩。
                // 参数已送达首实例, 本进程不接管 lock (写了会抢走真首实例的 lock), 就此静默做旁观者。
                AppLog.put("参数已转发, 但主线程已超时放行 → 本进程不退出也不再接管 lock", tag = TAG)
                decided.countDown()
                return
            }
            AppLog.put("已有实例接收本次启动参数, 当前进程退出", tag = TAG)
            exitProcess(0)
        }
        // 判定到此完成: 后面的 bind/写 lock 只影响“下一个进程能不能找到我们”, 与本进程启动无关
        decided.countDown()
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
            AppLog.put("转发到已有实例失败, 接管为首实例", it, tag = TAG)
            false
        }
    }

    // ==================== 首实例: 监听侧 ====================

    private fun becomePrimary(lockFile: File) {
        val token = newToken()
        val server = runCatching { ServerSocket(0, 16, loopback()) }.getOrElse {
            // 环回监听都开不了 (极端安全策略), 放弃单实例能力, 不阻断启动
            AppLog.put("单实例监听启动失败, 降级为多实例", it, tag = TAG)
            return
        }
        serverSocket = server
        if (!writeLock(lockFile, InstanceLock(server.localPort, token, currentPid()))) {
            runCatching { server.close() }
            serverSocket = null
            return
        }
        Runtime.getRuntime().addShutdownHook(Thread { releaseLock(lockFile, token) })
        Thread({ acceptLoop(server, token) }, "legado-single-instance").apply {
            isDaemon = true
            start()
        }
    }

    private fun acceptLoop(server: ServerSocket, token: String) {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }
                .onFailure {
                    // 静默 return 会让监听线程默默死掉而 lock 文件还在: 后续二次启动连到已死端口
                    // → 连接被拒 → 各自当首实例, 单实例能力静默失效
                    if (!server.isClosed) AppLog.put("单实例监听 accept 异常, 监听退出", it, tag = TAG)
                }
                .getOrElse { return }
            runCatching { handleConnection(socket, token) }
                .onFailure { AppLog.put("处理转发连接异常", it, tag = TAG) }
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
            AppLog.put("拒绝转发连接 (token 不匹配或报文非法)", tag = TAG)
            return
        }
        onForwardedArgs(message.args)
    }

    /** 首实例消费转发来的启动参数: 投递 deep link + 关联文件 + 前置窗口 (与 Main.kt 冷启动语义一致)。 */
    private fun onForwardedArgs(args: List<String>) {
        // 一次转发可能带多个 legado://, 旧实现只取首个
        args.filter { LegadoDeepLink.isDeepLink(it) }.forEach { url ->
            if (!LegadoDeepLinkHandler.handle(url)) {
                AppLog.put("转发的 deep link 解析失败 (缺 src 参数或 scheme/路径非法): $url", tag = TAG)
            }
        }
        // 必须走 Main.kt 的同一个入口筛子 offerAssociationArgs: 上一版这里自己内联了
        // `File(it).isFile`, 于是 file URI 形态 (Linux .desktop 的 %u、部分文件管理器) 在
        // 首实例已运行时被滤掉 —— 观感是"第一次双击能播, 第二次双击什也不会发生"
        offerAssociationArgs(args)
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
            AppLog.put("数据目录不可用, 跳过单实例", it, tag = TAG)
            null
        }

    /** 解析 lock; 文件缺失/损坏/端口非法一律返回 null (调用方按"无实例"处理)。 */
    private fun readLock(lockFile: File): InstanceLock? {
        if (!lockFile.isFile) return null
        val text = runCatching { lockFile.readText(StandardCharsets.UTF_8) }
            .onFailure { AppLog.put("lock 文件读取失败 (按无实例处理): ${lockFile.absolutePath}", it, tag = TAG) }
            .getOrNull()
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val lock = runCatching { json.decodeFromString<InstanceLock>(text) }.getOrElse {
            AppLog.put("lock 文件损坏, 按无实例处理: ${lockFile.absolutePath}", tag = TAG)
            return null
        }
        if (lock.port !in 1..65535 || lock.token.isEmpty()) {
            AppLog.put("lock 内容非法 (port=${lock.port}), 按无实例处理", tag = TAG)
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
        AppLog.put("写 lock 失败, 降级为多实例", it, tag = TAG)
        false
    }

    /** 退出清理: 关监听 + 删 lock, 且只删还属于自己的那份 (token 比对)。 */
    private fun releaseLock(lockFile: File, token: String) {
        runCatching { serverSocket?.close() }
        serverSocket = null
        if (readLock(lockFile)?.token == token) {
            runCatching { lockFile.delete() }
        }
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
}
