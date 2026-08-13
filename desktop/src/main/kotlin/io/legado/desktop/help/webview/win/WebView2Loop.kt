package io.legado.desktop.help.webview.win

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import io.legado.app.constant.AppLog
import io.legado.desktop.help.webview.win.WebView2Loop.WM_RUN_TASK
import io.legado.desktop.help.webview.win.WebView2Loop.ensureStarted
import io.legado.desktop.help.webview.win.WebView2Loop.tasks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Toolkit
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    /** 每个 HWND 的消息处理钩子 (WM_SIZE/WM_CLOSE/工具栏绘制与鼠标), 由窗口创建方注册。 */
    private val windowHooks = HashMap<Pointer, (Int, WinDef.WPARAM, WinDef.LPARAM) -> Boolean>()

    // 强引用: WNDCLASS 里的 lpfnWndProc 是 JNA 回调, 被 GC 后窗口消息即崩
    private val windowProc = WinUser.WindowProc { hwnd, msg, wParam, lParam ->
        try {
            when {
                msg == WM_RUN_TASK -> {
                    drainTasks()
                    WinDef.LRESULT(0)
                }

                windowHooks[hwnd.pointer]?.invoke(msg, wParam, lParam) == true -> WinDef.LRESULT(0)

                else -> User32.INSTANCE.DefWindowProc(hwnd, msg, wParam, lParam)
            }
        } catch (e: Throwable) {
            // JNA 回调内异常默认只打 stderr 静默吞掉; 工具栏曾因 paint 内
            // UnsatisfiedLinkError 永久不重绘 (白条) 而无任何日志, 这里必须显式记录
            AppLog.put("WebView2 窗口消息处理异常 (msg=${msg})", e)
            WinDef.LRESULT(0)
        }
    }

    /** gdi32 补充: CreateSolidBrush (窗口背景画刷)。 */
    private interface WebView2Gdi32Ex : com.sun.jna.win32.StdCallLibrary {
        fun CreateSolidBrush(crColor: Int): Pointer
    }

    private val webview2Gdi32Ex: WebView2Gdi32Ex by lazy {
        Native.load(
            "gdi32",
            WebView2Gdi32Ex::class.java,
            com.sun.jna.win32.W32APIOptions.DEFAULT_OPTIONS
        )
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
        // 超时不再等: 建窗口卡住时按启动失败处理, 上层降级到无 WebView 路径而不是永久挂起
        if (!ready.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            AppLog.put("WebView2 消息泵启动超时 (${START_TIMEOUT_SECONDS}s), 按不可用处理")
            startFailed = true
            return false
        }
        if (pumpWindow == null) startFailed = true
        return pumpWindow != null
    }

    /**
     * 把 [block] 排到 WebView2 线程执行 (不等待完成)。
     *
     * 必须先 [ensureStarted]: 首次调用 (如书源 `java.startBrowser` 直接开窗) 时消息泵
     * 线程尚未启动, 只入队不 PostMessage 会导致任务永远不被消费, 环境/窗口创建 20s
     * 静默超时 (曾表现为"WebView2 窗口创建失败"后无任何其他日志)。
     */
    fun post(block: () -> Unit) {
        if (!ensureStarted()) return
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
        // 工具栏区背景由窗口背景画刷承担 (深色主题下默认白底会露馅);
        // 主题背景色跟随创建时主题 (短生命周期窗口, 不追动态切换)
        wndClass.hbrBackground = WinDef.HBRUSH(
            webview2Gdi32Ex.CreateSolidBrush(WebView2WindowTheme.themeBgColorRef())
        )
        // 重复注册返回 0 (ERROR_CLASS_ALREADY_EXISTS), 无害
        User32.INSTANCE.RegisterClassEx(wndClass)
    }

    /**
     * 建窗口。[visible] = false 时不给 WS_VISIBLE 且摆到屏幕外:
     * 窗口全程不显示 (无头), 但仍是真 HWND, 消息与 WebView2 渲染都正常。
     */
    fun createWindow(
        visible: Boolean,
        title: String,
        bounds: WindowBounds = WindowBounds()
    ): WinDef.HWND {
        val style = if (visible) WinUser.WS_OVERLAPPEDWINDOW else WinUser.WS_POPUP
        // 无头窗口必须摆到屏幕外 (bounds 是给可见窗口用的)
        val posX = if (visible) bounds.x else OFFSCREEN
        val posY = if (visible) bounds.y else OFFSCREEN
        val hwnd = User32.INSTANCE.CreateWindowEx(
            0,
            WINDOW_CLASS,
            title.ifBlank { "legado" },
            style,
            posX, posY, bounds.width, bounds.height,
            null, null,
            Kernel32.INSTANCE.GetModuleHandle(null),
            null,
        ) ?: error("CreateWindowEx 失败 (err=${Native.getLastError()})")
        if (visible) {
            User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_SHOW)
            // 弹窗语义: 新窗口置前显示。曾出现 WebView2 窗口启动在主窗口后面
            // (ShowWindow 不改变 Z 序, 主窗口保持激活)。HWND_TOP 提升到 Z 序顶部
            // (非 TOPMOST 置顶), 再请求前台激活 (同进程前台时 SetForegroundWindow 有效)
            User32.INSTANCE.SetWindowPos(
                hwnd,
                null, // HWND_TOP = NULL 指针 (置顶 Z 序, 非 TOPMOST)
                0, 0, 0, 0,
                WinUser.SWP_NOMOVE or WinUser.SWP_NOSIZE or WinUser.SWP_SHOWWINDOW
            )
            User32.INSTANCE.SetForegroundWindow(hwnd)
            // 原生标题栏跟随应用主题 (与主窗口原生控制栏观感统一, 用户拍板 2026-08)
            WebView2WindowTheme.apply(hwnd)
        }
        return hwnd
    }

    fun hookWindow(
        hwnd: WinDef.HWND,
        handler: (Int, WinDef.WPARAM, WinDef.LPARAM) -> Boolean,
    ) {
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

    /**
     * 屏幕居中窗口矩形 (弹窗语义): 独立浏览器窗口默认居中打开, 尺寸自适应屏幕。
     * 置底半屏 (bottomSheet) 由调用方显式传 [WindowBounds]。
     */
    fun centeredBounds(): WindowBounds {
        val screen = Toolkit.getDefaultToolkit().screenSize
        val width = kotlin.math.min(DEFAULT_WIDTH, (screen.width * 0.8).toInt().coerceAtLeast(400))
        val height =
            kotlin.math.min(DEFAULT_HEIGHT, (screen.height * 0.8).toInt().coerceAtLeast(300))
        return WindowBounds(
            x = ((screen.width - width) / 2).coerceAtLeast(0),
            y = ((screen.height - height) / 2).coerceAtLeast(0),
            width = width,
            height = height,
        )
    }

    /** 屏幕外坐标 (Win32 惯用值, 保证任何显示器布局下都不可见)。 */
    private const val OFFSCREEN = -32000

    private const val DEFAULT_WIDTH = 1100
    private const val DEFAULT_HEIGHT = 800

    /** CreateWindowEx 窗口矩形参数 (默认 = 系统居中 + 原默认尺寸)。 */
    class WindowBounds(
        val x: Int = CW_USEDEFAULT,
        val y: Int = CW_USEDEFAULT,
        val width: Int = DEFAULT_WIDTH,
        val height: Int = DEFAULT_HEIGHT,
    )

    /** 消息泵启动等待上限。 */
    private const val START_TIMEOUT_SECONDS = 30L
}
