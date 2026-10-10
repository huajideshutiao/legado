package io.legado.desktop.help.webview.gtk

import com.sun.jna.Pointer
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.desktop.help.webview.gtk.GtkLibs.AsyncReadyCallback
import io.legado.desktop.help.webview.gtk.GtkLibs.GErrorRef
import io.legado.desktop.help.webview.gtk.GtkLoop.await
import io.legado.desktop.help.webview.gtk.GtkLoop.post
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 专用 GTK 线程 + GLib 主循环 (模式同 Windows 的 [io.legado.desktop.help.webview.win.WebView2Loop])。
 *
 * WebKitGTK 的 GTK 组件有线程亲和: gtk_init 必须在目标线程首次调用, 之后所有 GTK/WebKit
 * 调用与回调都在该线程的事件循环上执行。本对象起一条守护线程独占 GTK 主循环, 刻意不复用
 * AWT EDT —— 与 AWT/Skiko 零交集, 避免两个 GUI 工具包抢主线程 (X11 下同进程双 toolkit
 * 会互相干扰焦点与剪贴板)。
 *
 * 跨线程投递用 [g_idle_add] (线程安全, 内部会 wakeup 主循环): 任意线程可 [post] 任意闭包到
 * GTK 线程执行; [await] 是 suspend 版, 阻塞等待执行结果 (带超时)。
 *
 * 线程结束 (初始化失败 / 主循环退出) 即没有线程迭代主上下文: 可用性按**线程是否还在跑**判定,
 * 线程已结束就整代重建 (见 [ensureStarted]); 待执行的任务挂在进程级默认主上下文里, 由新代抽取。
 *
 * 副作用与 WebView2Loop 相同: 可见窗口只能是**独立顶层窗口**而非嵌进 Compose 布局,
 * 登录/验证本就是弹窗语义, 可接受。
 */
internal object GtkLoop {

    /** 启动等待上限: 超过即按这一次不可用处理, 不让调用方永久挂起。 */
    private const val START_TIMEOUT_SECONDS = 10L

    /**
     * 一代 GTK 线程的启动结论: [ready] 是 gtk_init_check 出结论的闸门 (放开后 [failure] 与
     * [inited] 才是最终值), [failure] 非空表示本代初始化失败 (值为原因), [inited] 表示成功。
     */
    private class Generation {
        val ready = CountDownLatch(1)

        @Volatile
        var failure: String? = null

        @Volatile
        var inited = false
    }

    /** GTK 线程; 线程结束 (初始化失败 / 主循环退出) 即本代不可用 (没有线程迭代主循环)。 */
    @Volatile
    private var gtkThread: Thread? = null

    /** 当前代。 */
    @Volatile
    private var generation: Generation? = null

    /** 本代结论的等待闸门; 超时后置 null (之后直接按本代结论判定, 不再让调用方白等)。 */
    @Volatile
    private var waitGate: CountDownLatch? = null

    /** GTK 线程上的阻塞操作 (如等待异步 JS 回调) 的最长驱动时间。 */
    const val DRIVE_TIMEOUT_MS = AppConst.timeLimit

    /**
     * 启动 (线程已结束就整代重建) GTK 线程; 返回是否可用。
     *
     * 可用性按**线程是否还在跑**判定: 主循环由这条线程独占迭代, 线程结束后没有线程抽取默认主
     * 上下文里的任务, 投进去的任务没人执行; 按一次性的"已启动"标记判会让此后每次调用都以为泵
     * 还在, 任务静默丢弃、只能重启应用。线程已结束就整代重建; 建不起来的那一代只是这一次不可用
     * (线程随之结束), 下一次调用继续重试。
     *
     * 换代只作废随线程失效的东西: GLib 默认主上下文与 GTK/GDK 初始化都是进程级的 (显示连接由
     * gdk_display_open_default 复用已设的默认显示), 线程只是迭代者, 故这里不清理本地状态 ——
     * 待执行的 idle 任务 (见 [post]) 与既有会话留给新代接着用。重建以旧线程已结束为前提, 不会
     * 有两条线程同时碰 GTK。
     *
     * 换代把迭代交给一条新线程 —— GTK3 文档要求 GTK/GDK 只在调用 gtk_init 的那条线程上使用,
     * WebKitGTK 也按首个碰它的线程判定"主线程", 故这条恢复路径的真实行为需在 Linux 实机上验证。
     *
     * 幂等: 线程还在 (含正在启动的那一代) 即不新建; 全程持锁, 并发调用者等锁后看到新线程已在
     * 跑, 不会起出两份。
     */
    @Synchronized
    fun ensureStarted(): Boolean {
        if (gtkThread?.isAlive != true) {
            val gen = Generation()
            val thread = Thread({ runLoop(gen) }, "legado-gtk")
            thread.isDaemon = true
            gtkThread = thread
            generation = gen
            waitGate = gen.ready
            thread.start()
        }
        val gen = generation ?: return false
        val gate = waitGate
        if (gate != null && gate.count > 0L) {
            try {
                if (!gate.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    AppLog.put("GTK 线程启动超时, 按不可用处理")
                    // 卡住的这一代不再让后续调用白等: 之后就绪与否按本代结论判定
                    waitGate = null
                    return false
                }
            } catch (e: InterruptedException) {
                return false
            }
        }
        gen.failure?.let {
            AppLog.put("GTK 初始化失败: $it")
            return false
        }
        return gen.inited && gtkThread?.isAlive == true
    }

    /**
     * 一代 GTK 线程的主体: 初始化 → 放闸门 → 独占迭代默认主上下文。
     *
     * 对已初始化过的进程, `gtk_init_check` 是幂等的: GTK/GDK 用 pre_initialized /
     * gtk_initialized 守护跳过全局初始化, 只重新取默认显示 (已设则直接返回)。
     */
    private fun runLoop(gen: Generation) {
        runCatching {
            // gtk_init_check 只在 GTK 线程调用; 传入 null 即可 (JNA 无 argc/argv)
            if (GtkLibs.gtk.gtk_init_check(null, null) == 0) {
                gen.failure = "gtk_init_check 失败 (无显示环境?)"
            }
        }.onFailure { e ->
            gen.failure = "gtk_init_check 异常: ${e.message}"
        }
        val inited = gen.failure == null
        // 先写结论再放闸门: 等闸门的调用者看到的必然是写完的结论
        gen.inited = inited
        gen.ready.countDown()
        if (!inited) return
        // GTK 主循环: 阻塞迭代直到线程中断 (守护线程随进程退出)
        while (!Thread.currentThread().isInterrupted) {
            runCatching {
                GtkLibs.glib.g_main_context_iteration(null, 1 /* TRUE 阻塞 */)
            }.onFailure { e ->
                AppLog.put("GTK 主循环异常", e)
                break
            }
        }
    }

    /**
     * 投递任务到 GTK 线程 (任意线程可调, 线程安全)。
     *
     * 任务经 g_idle_add 挂到**进程级默认主上下文** (GLib 文档: 回调由正在迭代该上下文的那条
     * 线程执行): 队列不随线程失效, 换代后由新代抽取。故先把任务交给队列、再 [ensureStarted]
     * 确保有线程抽取 —— 泵这一刻起不来只是暂时没人抽, 任务留在队列里等下一次重建, 不由本方法
     * 丢弃 (重建失败的提示由 [ensureStarted] 给)。
     * GTK 库不可用 (这台机器没有 WebKitGTK) 时保持原有的拒绝语义。
     */
    fun post(block: () -> Unit) {
        if (!GtkLibs.ensureLoaded()) throw IllegalStateException("GTK 线程未启动")
        val task = object : GtkLibs.GSourceFunc {
            override fun invoke(userData: Pointer?): Int {
                // JNA 回调被 GC 后 GTK 调用即崩, 执行完才允许释放引用
                synchronized(pendingIdles) { pendingIdles.remove(this) }
                runCatching { block() }
                    .onFailure { AppLog.put("GTK 任务执行异常", it) }
                return 0 // G_SOURCE_REMOVE: 一次性
            }
        }
        // g_idle_add 是线程安全的 (内部 wakeup 主循环), 回调执行前必须持有 JNA 回调引用
        synchronized(pendingIdles) { pendingIdles.add(task) }
        GtkLibs.glib.g_idle_add(task, null)
        // 入队后再确保有线程抽取: 新代 (或首次启动) 的迭代会取走刚入队的任务
        ensureStarted()
    }

    private val pendingIdles =
        java.util.Collections.synchronizedList(ArrayList<GtkLibs.GSourceFunc>())

    /**
     * 在 GTK 线程执行 [block] 并取回结果 (suspend 版)。
     * 供引擎在协程上下文跨线程调用 GTK/WebKit API。
     */
    suspend fun <T> await(block: () -> T): T {
        if (Thread.currentThread().name == "legado-gtk") return block()
        val future = CompletableFuture<T>()
        post {
            try {
                future.complete(block())
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
        return withTimeout(AppConst.timeLimit) { future.await() }
    }

    // ==================== GTK 线程内同步等待异步回调的驱动工具 ====================

    /**
     * 在 GTK 线程上迭代主循环直到 [until] 为 true 或超时。
     * 用于把 WebKit 的异步 GAsync 回调 (run_javascript / cookie manager) 在 GTK 线程内
     * 转成同步调用 —— 回调本身由同一个主循环派发, 迭代即驱动。
     */
    fun driveLoopUntil(timeoutMs: Long = DRIVE_TIMEOUT_MS, until: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!until() && System.currentTimeMillis() < deadline) {
            GtkLibs.glib.g_main_context_iteration(null, 1 /* TRUE */)
        }
    }

    /** 尚未触发的 GAsync 回调强引用 (回调触发时自摘, 超时的留到真正触发为止)。 */
    private val pendingAsync: MutableSet<AsyncReadyCallback> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    /**
     * 驱动一个 GAsync 调用直到回调完成, 返回回调产物。
     * [start] 在 GTK 线程发起异步调用并注册 [produce] (回调里调用), [produce] 返回的结果
     * 作为本函数返回值。
     */
    fun <T> driveAsync(
        timeoutMs: Long = DRIVE_TIMEOUT_MS,
        start: (callback: AsyncReadyCallback) -> Unit,
        produce: (res: Pointer) -> T?,
    ): T? {
        val done = AtomicBoolean(false)
        val result = AtomicReference<T?>(null)
        val error = AtomicReference<Throwable?>(null)
        val cb = object : AsyncReadyCallback {
            override fun invoke(source: Pointer?, res: Pointer, userData: Pointer?) {
                try {
                    result.set(produce(res))
                } catch (t: Throwable) {
                    error.set(t)
                }
                done.set(true)
                pendingAsync.remove(this)
            }
        }
        // 超时返回后 GAsync 调用仍可能触发: 局部变量松手后 JNA 蹦床被 GC free, GTK 打进
        // 已释放内存即随机崩溃 (堆坏)。故存活期交给回调自己结束, 不由本函数作用域决定
        pendingAsync.add(cb)
        start(cb)
        driveLoopUntil(timeoutMs) { done.get() }
        error.get()?.let { throw it }
        return result.get()
    }

    /** GAsync 调用的统一错误读取: 读 GError message 并释放。 */
    fun errorMessage(error: GErrorRef?): String? {
        val msg = error?.message()
        error?.errorPointer()?.let { runCatching { GtkLibs.glib.g_error_free(it) } }
        return msg
    }
}
