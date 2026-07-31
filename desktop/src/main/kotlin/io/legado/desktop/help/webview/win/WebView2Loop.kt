package io.legado.desktop.help.webview.win

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import io.legado.app.constant.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch

/**
 * WebView2 专用 STA 线程 + Win32 消息泵。
 *
 * WebView2 的所有 COM 调用与回调都必须在**创建它的那个跑消息泵的 STA 线程**上, 因此本对象
 * 起一条独立守护线程 (CoInitializeEx APARTMENTTHREADED + GetMessage 循环) 独占所有窗口与
 * WebView 实例。刻意不复用 AWT EDT: Compose Desktop 走 Skiko, EDT 的消息泵归 AWT 工具线程,
 * 跨线程持有 HWND 会把焦点/输入队列搅在一起 (同仓 mpv 走独立进程 `--wid` 才没这问题)。
 *
 * 副作用是可见窗口只能是**独立顶层窗口**而非嵌进 Compose 布局 —— 登录/验证本就是弹窗语义,
 * 可接受。
 */
internal object WebView2Loop {

    /** 自定义消息: 唤醒消息循环去消费 [tasks]。 */
    private const val WM_RUN_TASK = WinUser.WM_USER + 1

    private const val WINDOW_CLASS = "LegadoWebView2Host"

    private val tasks = ConcurrentLinkedQueue<() -> Unit>()

    /** 消息泵窗口: 仅用于收 [WM_RUN_TASK], 不承载 WebView。 */
    @Volatile
    private var pumpWindow: WinDef.HWND? = null

    @Volatile
    private var startFailed = false

    /** 每个 HWND 的消息处理钩子 (WM_SIZE/WM_CLOSE), 由 [WebView2Window] 注册。 */
    private val windowHooks = HashMap<Pointer, (Int) -> Boolean>()

    // 强引用: WNDCLASS 里的 lpfnWndProc 是 JNA 回调, 被 GC 后窗口消息即崩
    private val windowProc = WinUser.WindowProc { hwnd, msg, wParam, lParam ->
        when {
            msg == WM_RUN_TASK -> {
                drainTasks()
                WinDef.LRESULT(0)
            }

            windowHooks[hwnd.pointer]?.invoke(msg) == true -> WinDef.LRESULT(0)

            else -> User32.INSTANCE.DefWindowProc(hwnd, msg, wParam, lParam)
        }
    }

    /** 启动线程 (幂等); 返回是否可用。 */
    @Synchronized
    fun ensureStarted(): Boolean {
        if (pumpWindow != null) return true
        if (startFailed) return false
        val ready = CountDownLatch(1)
        val thread = Thread({ runLoop(ready) }, "legado-webview2")
        thread.isDaemon = true
        thread.start()
        ready.await()
        if (pumpWindow == null) startFailed = true
        return pumpWindow != null
    }

    /** 把 [block] 排到 WebView2 线程执行 (不等待完成)。 */
    fun post(block: () -> Unit) {
        tasks += block
        val hwnd = pumpWindow ?: return
        User32.INSTANCE.PostMessage(hwnd, WM_RUN_TASK, WinDef.WPARAM(0), WinDef.LPARAM(0))
    }

    /** 在 WebView2 线程执行并等结果 (COM 对象只能在本线程碰)。超时/异常返回 null。 */
    suspend fun <T : Any> runOnLoop(timeoutMs: Long = 10_000L, block: () -> T?): T? {
        if (!ensureStarted()) return null
        val deferred = CompletableDeferred<T?>()
        post { deferred.complete(runCatching(block).getOrNull()) }
        return withTimeoutOrNull(timeoutMs) { deferred.await() }
    }

    private fun runLoop(ready: CountDownLatch) {
        val created = runCatching {
            Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_APARTMENTTHREADED)
            registerWindowClass()
            createWindow(visible = false, title = WINDOW_CLASS).also { pumpWindow = it }
        }.onFailure {
            AppLog.put("WebView2 消息泵启动失败", it)
        }.isSuccess
        ready.countDown()
        if (!created) {
            runCatching { Ole32.INSTANCE.CoUninitialize() }
            return
        }
        val msg = WinUser.MSG()
        while (User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0) {
            User32.INSTANCE.TranslateMessage(msg)
            User32.INSTANCE.DispatchMessage(msg)
            // PostThreadMessage 之外的路径也顺手清一次, 避免任务卡到下一条消息
            drainTasks()
        }
        runCatching { Ole32.INSTANCE.CoUninitialize() }
    }

    private fun drainTasks() {
        while (true) {
            val task = tasks.poll() ?: return
            runCatching { task() }.onFailure { AppLog.put("WebView2 任务执行失败", it) }
        }
    }

    private fun registerWindowClass() {
        val wndClass = WinUser.WNDCLASSEX()
        wndClass.cbSize = wndClass.size()
        wndClass.lpszClassName = WINDOW_CLASS
        wndClass.lpfnWndProc = windowProc
        // HMODULE 继承自 HINSTANCE, 直接给
        wndClass.hInstance = Kernel32.INSTANCE.GetModuleHandle(null)
        // 重复注册返回 0 (ERROR_CLASS_ALREADY_EXISTS), 无害
        User32.INSTANCE.RegisterClassEx(wndClass)
    }

    /**
     * 建窗口。[visible] = false 时不给 WS_VISIBLE 且摆到屏幕外:
     * 窗口全程不显示 (无头), 但仍是真 HWND, 消息与 WebView2 渲染都正常。
     */
    fun createWindow(visible: Boolean, title: String): WinDef.HWND {
        val style = if (visible) WinUser.WS_OVERLAPPEDWINDOW else WinUser.WS_POPUP
        val position = if (visible) CW_USEDEFAULT else OFFSCREEN
        val hwnd = User32.INSTANCE.CreateWindowEx(
            0,
            WINDOW_CLASS,
            title.ifBlank { "legado" },
            style,
            position, position, DEFAULT_WIDTH, DEFAULT_HEIGHT,
            null, null,
            Kernel32.INSTANCE.GetModuleHandle(null),
            null,
        ) ?: error("CreateWindowEx 失败 (err=${Native.getLastError()})")
        if (visible) User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_SHOW)
        return hwnd
    }

    fun hookWindow(hwnd: WinDef.HWND, handler: (Int) -> Boolean) {
        windowHooks[hwnd.pointer] = handler
    }

    fun unhookWindow(hwnd: WinDef.HWND) {
        windowHooks.remove(hwnd.pointer)
    }

    fun clientRect(hwnd: WinDef.HWND): RectValue {
        val rect = WinDef.RECT()
        User32.INSTANCE.GetClientRect(hwnd, rect)
        return RectValue().apply {
            left = rect.left
            top = rect.top
            right = rect.right
            bottom = rect.bottom
        }
    }

    private const val CW_USEDEFAULT = 0x80000000.toInt()

    /** 屏幕外坐标 (Win32 惯用值, 保证任何显示器布局下都不可见)。 */
    private const val OFFSCREEN = -32000

    private const val DEFAULT_WIDTH = 1100
    private const val DEFAULT_HEIGHT = 800
}
