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
 * 副作用与 WebView2Loop 相同: 可见窗口只能是**独立顶层窗口**而非嵌进 Compose 布局,
 * 登录/验证本就是弹窗语义, 可接受。
 */
internal object GtkLoop {

    @Volatile
    private var started = false

    @Volatile
    private var startFailed = false

    /** GTK 线程上的阻塞操作 (如等待异步 JS 回调) 的最长驱动时间。 */
    const val DRIVE_TIMEOUT_MS = AppConst.timeLimit

    /** 启动 GTK 线程 (幂等); 返回是否可用。无显示环境 gtk_init_check 失败即不可用。 */
    @Synchronized
    fun ensureStarted(): Boolean {
        if (started) return true
        if (startFailed) return false
        val ready = CountDownLatch(1)
        val failed = AtomicReference<String?>(null)
        val thread = Thread({
            runCatching {
                // gtk_init_check 只在 GTK 线程第一次调用; 传入 null 即可 (JNA 无 argc/argv)
                val ok = GtkLibs.gtk.gtk_init_check(null, null) != 0
                if (ok == false) {
                    failed.set("gtk_init_check 失败 (无显示环境?)")
                    ready.countDown()
                    return@Thread
                }
            }.onFailure { e ->
                failed.set("gtk_init_check 异常: ${e.message}")
                ready.countDown()
                return@Thread
            }
            started = true
            ready.countDown()
            // GTK 主循环: 阻塞迭代直到线程中断 (守护线程随进程退出)
            while (!Thread.currentThread().isInterrupted) {
                runCatching {
                    GtkLibs.glib.g_main_context_iteration(null, 1 /* TRUE 阻塞 */)
                }.onFailure { e ->
                    AppLog.put("GTK 主循环异常", e)
                    break
                }
            }
        }, "legado-gtk")
        thread.isDaemon = true
        thread.start()
        try {
            if (!ready.await(10, TimeUnit.SECONDS)) {
                startFailed = true
                AppLog.put("GTK 线程启动超时, 按不可用处理")
                return false
            }
        } catch (e: InterruptedException) {
            startFailed = true
            return false
        }
        failed.get()?.let {
            startFailed = true
            AppLog.put("GTK 初始化失败: $it")
            return false
        }
        if (!started) {
            startFailed = true
            return false
        }
        return true
    }

    /** 投递任务到 GTK 线程 (任意线程可调, 线程安全)。 */
    fun post(block: () -> Unit) {
        if (!started) throw IllegalStateException("GTK 线程未启动")
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
            }
        }
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
