package io.legado.desktop.ui

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import io.legado.app.constant.AppLog
import io.legado.desktop.ui.DesktopWindowChromeNative.addMessageHandler
import io.legado.desktop.ui.DesktopWindowChromeNative.attach
import java.awt.Canvas
import java.awt.Component
import java.awt.Container
import java.awt.EventQueue
import java.awt.Window
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.Volatile

/**
 * Windows 窗口控制条 native 桥的 Kotlin 侧 (JNA 绑定 + 生命周期)。
 *
 * 实现在 desktop/src/main/cpp/wndchrome/wndchrome.c (MinGW 纯 C, 扁平导出非 JNI):
 * 双 HWND 子类化 (JFrame + skiko Canvas) + WS_EX_LAYERED 子窗口自绘控制条,
 * 命中判定全在 C 内完成, 只有我们自己的两个键 (深浅色 / ⋯) 才回调 JVM。
 *
 * 本文件只负责: 库加载 (熔断) + HWND 获取 + 位图/字符串编码 + 回调分派。
 * 主窗口的子类化由本桥独占 (三层收一层): 任务栏缩略图/thumbbar 需要的低频消息
 * 经 [addMessageHandler] 转发上来, 不再各自 SetWindowLongPtr。
 * 线程模型: 所有下行调用须在 EDT (子类化要求调用线程拥有窗口);
 * 上行回调在 native 窗口线程 (AWT-Windows, 非 EDT) 触发, 只做轻量分派。
 */
internal object DesktopWindowChromeNative {

    // 错误码 / 按钮标识 (对照 wndchrome.h)
    private const val LGC_OK = 0
    private const val LGC_BTN_NONE = 0
    private const val LGC_BTN_THEME = 4
    private const val LGC_BTN_MENU = 5

    /** C 桥导出函数 (扁平 C, 严格类型化; 见 wndchrome.h)。 */
    private interface LegadoWndChrome : Library {
        /** 挂载; 返回 LGC_OK=成功。hwndCanvas 传 null 表示不挂第二层 (标题栏拖拽会失效)。 */
        fun lgchrome_attach(hwndFrame: Pointer?, hwndCanvas: Pointer?): Int

        /** 卸载 (还原两层 subclass + 销毁控制条子窗口)。无状态时无害。 */
        fun lgchrome_detach()

        /** 注册回调 (null 清除); 须在 attach 之前调。 */
        fun lgchrome_set_callbacks(onAction: ActionCallback?, onMetrics: MetricsCallback?)

        /** 注册白名单窗口消息钩子 (null 清除); 与 attach 无先后要求。 */
        fun lgchrome_set_message_hook(cb: MessageCallback?)

        fun lgchrome_add_hook_message(msg: Int)

        /** 控制条高度 (物理像素, native 不做缩放)。 */
        fun lgchrome_set_caption_height(heightPx: Int)

        /** 应用名 (UTF-16 以 0 结尾; null 清空)。 */
        fun lgchrome_set_title(utf16Text: Pointer?)

        /** 图标 (预乘 alpha 的 BGRA, 行优先无 padding; null 清除该 slot)。 */
        fun lgchrome_set_bitmap(slot: Int, bgraPremultiplied: Pointer?, width: Int, height: Int)

        /** 配色 (0xAARRGGBB)。 */
        fun lgchrome_set_theme(bg: Int, fg: Int, dark: Int, inactiveAlpha: Int)

        /** 全屏挂起 (改窗口样式之前调 1, 还原样式之后调 0)。 */
        fun lgchrome_set_fullscreen(fullscreen: Int)

        /** 诊断: 最近一次失败的 LGC_* 错误码。 */
        fun lgchrome_last_error(): Int

        /** 诊断: 最近一次 Win32/HRESULT 原始错误值。 */
        fun lgchrome_last_os_error(): Int

        /** 我们自己的键被点击; x/y = 按钮左下角相对窗口客户区左上角的坐标 (物理像素)。 */
        interface ActionCallback : Callback {
            fun onAction(button: Int, x: Int, y: Int)
        }

        /** 几何/状态变化通知 (attach / DPI 变化 / 最大化)。 */
        interface MetricsCallback : Callback {
            fun onMetrics(dpi: Int, captionBtnW: Int, maximized: Int)
        }

        /**
         * 白名单窗口消息 (0x0323 / 0x0326 / WM_COMMAND)。
         * 返回 1 = 已处理 (native 直接答 0 给 Windows), 0 = 未处理继续转发。
         */
        interface MessageCallback : Callback {
            fun invoke(msg: Int, wparam: Long, lparam: Long): Int
        }
    }

    // ==================== 状态 ====================

    private var bridge: LegadoWndChrome? = null

    @Volatile
    private var loadFailed = false

    @Volatile
    private var attached = false

    @Volatile
    private var themeToggleAction: ((Int, Int) -> Unit)? = null

    @Volatile
    private var menuAction: ((Int, Int) -> Unit)? = null

    /** native 报标题栏被按下/右键 ⇒ 关掉 Compose 侧浮层 (菜单收不到标题栏的鼠标事件)。 */
    private var captionPressAction: (() -> Unit)? = null

    /**
     * 传给 native 的堆外缓冲强引用: C 侧是否复制不由本文件保证,
     * 提前被 GC 释放就是野指针, 故按 slot 长期持有 (至多标题 + 2 张图标)。
     */
    private var titleBuffer: Memory? = null
    private val bitmapBuffers = arrayOfNulls<Memory>(2)

    /** C 回调对象强引用 (JNA Callback 被 GC 即失效)。 */
    private val actionCallback = object : LegadoWndChrome.ActionCallback {
        override fun onAction(button: Int, x: Int, y: Int) {
            // native 窗口线程直接调用: 只分派不做重活, 异常绝不能穿回 native 栈
            runCatching {
                when (button) {
                    LGC_BTN_THEME -> themeToggleAction?.invoke(x, y)
                    LGC_BTN_MENU -> menuAction?.invoke(x, y)
                    LGC_BTN_NONE -> captionPressAction?.invoke()
                }
            }.onFailure {
                AppLog.put("窗口控制条按钮回调失败 button=$button", it)
            }
        }
    }

    /** C 回调对象强引用; Kotlin 侧不依赖 metrics 做布局, 仅记诊断日志。 */
    private val metricsCallback = object : LegadoWndChrome.MetricsCallback {
        override fun onMetrics(dpi: Int, captionBtnW: Int, maximized: Int) {
            runCatching {
                AppLog.putDebug(
                    "窗口控制条 metrics: dpi=$dpi captionBtnW=$captionBtnW maximized=$maximized"
                )
            }
        }
    }

    /**
     * 白名单窗口消息处理器 (任务栏缩略图 / thumbbar 按钮)。
     * 写少读多且在 native 线程遍历, 用 COW 列表免加锁。
     */
    private val messageHandlers = CopyOnWriteArrayList<(Int, Long, Long) -> Boolean>()

    @Volatile
    private var messageHookArmed = false

    /** C 回调对象强引用 (JNA Callback 被 GC 即失效)。 */
    private val messageCallback = object : LegadoWndChrome.MessageCallback {
        override fun invoke(msg: Int, wparam: Long, lparam: Long): Int {
            // native 窗口线程直接调用: 逐个问处理器, 任一处理即返回 1;
            // 每个都单独 runCatching —— 异常穿回 native 栈会直接崩进程
            for (handler in messageHandlers) {
                val handled = runCatching { handler(msg, wparam, lparam) }.getOrElse {
                    AppLog.put("窗口消息处理器异常 msg=0x${Integer.toHexString(msg)}", it)
                    false
                }
                if (handled) return 1
            }
            return 0
        }
    }

    // ==================== 公开 API (幂等, 全 runCatching) ====================

    /** 是否可用 (Windows 平台且 native 库加载成功)。首次访问触发加载。 */
    val isAvailable: Boolean
        get() = lib() != null

    /**
     * 挂载到 AWT 窗口。必须在窗口 realize 之后、setVisible 之前调用, 且必须在 EDT。
     *
     * 两个回调在 native 窗口线程 (AWT-Windows, 不是 EDT) 触发, 要动 Compose 状态请自行投递。
     *
     * @param window ComposeWindow (JFrame)
     * @param onThemeToggle 深浅色键被点击; 参数为按钮左下角相对窗口客户区左上角的坐标
     *                      (物理像素, 非屏幕坐标; 原样透传自 native, 未除 density)
     * @param onMenu ⋯ 键被点击 (同上)
     * @return 是否挂载成功
     */
    fun attach(
        window: Window,
        onThemeToggle: (Int, Int) -> Unit,
        onMenu: (Int, Int) -> Unit,
        onCaptionPress: (() -> Unit)? = null,
    ): Boolean {
        val lib = lib() ?: return false
        // 回调随时可替换 (重复 attach 时指向新 lambda)
        themeToggleAction = onThemeToggle
        menuAction = onMenu
        captionPressAction = onCaptionPress
        if (attached) return true
        if (!EventQueue.isDispatchThread()) {
            // SetWindowSubclass 要求调用线程拥有窗口; 非 EDT 时子类化可能静默失效
            AppLog.putDebug("窗口控制条: attach 不在 EDT 调用")
        }
        if (!window.isDisplayable) {
            AppLog.put("窗口控制条: 窗口未 realize, 无法取 HWND")
            return false
        }
        return runCatching {
            val hwndFrame = componentHandle(window)
            if (hwndFrame == 0L) {
                AppLog.put("窗口控制条: 取窗口 HWND 失败")
                return false
            }
            val hwndCanvas = canvasHandle(window)
            lib.lgchrome_set_callbacks(actionCallback, metricsCallback)
            val rc = lib.lgchrome_attach(
                Pointer.createConstant(hwndFrame),
                if (hwndCanvas == 0L) null else Pointer.createConstant(hwndCanvas),
            )
            if (rc != LGC_OK) {
                AppLog.put("窗口控制条挂载失败 rc=$rc ${diagnostics(lib)}")
                return false
            }
            attached = true
            true
        }.getOrElse {
            AppLog.put("窗口控制条挂载异常", it)
            false
        }
    }

    /** 卸载 (幂等; 卸载后可再 [attach])。 */
    fun detach() {
        val lib = bridge ?: return
        runCatching {
            lib.lgchrome_detach()
            lib.lgchrome_set_callbacks(null, null)
        }.onFailure {
            AppLog.put("窗口控制条卸载失败", it)
        }
        attached = false
        themeToggleAction = null
        menuAction = null
        captionPressAction = null
    }

    /** 控制条高度 (物理像素; 调用方用 40dp * density 换算)。 */
    fun setCaptionHeightPx(px: Int) {
        val lib = lib() ?: return
        runCatching { lib.lgchrome_set_caption_height(px) }
            .onFailure { AppLog.put("窗口控制条设置高度失败", it) }
    }

    /** 应用名。 */
    fun setTitle(text: String) {
        val lib = lib() ?: return
        runCatching {
            // 手工编码 UTF-16LE + 结尾 0: 契约是 `const unsigned short*`, 用 Memory 明确按
            // 16 位单元编码 (WString 会走 wchar_t, 宽度随平台变), 且能自己控制缓冲生命周期
            val buffer = utf16z(text)
            titleBuffer = buffer
            lib.lgchrome_set_title(buffer)
        }.onFailure {
            AppLog.put("窗口控制条设置标题失败", it)
        }
    }

    /** slot 0=应用图标, 1=深浅色切换图标; image 传 null 清除。 */
    fun setBitmap(slot: Int, image: BufferedImage?) {
        val lib = lib() ?: return
        if (slot !in bitmapBuffers.indices) {
            AppLog.putDebug("窗口控制条: 未知位图 slot=$slot")
            return
        }
        runCatching {
            if (image == null || image.width <= 0 || image.height <= 0) {
                bitmapBuffers[slot] = null
                lib.lgchrome_set_bitmap(slot, null, 0, 0)
                return@runCatching
            }
            val buffer = premultipliedBgra(image)
            bitmapBuffers[slot] = buffer
            lib.lgchrome_set_bitmap(slot, buffer, image.width, image.height)
        }.onFailure {
            AppLog.put("窗口控制条设置图标失败 slot=$slot", it)
        }
    }

    /**
     * 配色。颜色为 0xAARRGGBB; dark=底色偏深 (决定 hover 叠色方向);
     * inactiveAlpha=失焦时文字/glyph 的 alpha (Windows 惯例 153)。
     */
    fun setTheme(bg: Int, fg: Int, dark: Boolean, inactiveAlpha: Int = 153) {
        val lib = lib() ?: return
        runCatching {
            lib.lgchrome_set_theme(bg, fg, if (dark) 1 else 0, inactiveAlpha)
        }.onFailure {
            AppLog.put("窗口控制条设置配色失败", it)
        }
    }

    /** 全屏挂起 (改窗口样式之前置 true, 还原样式之后置 false)。 */
    fun setFullscreen(enabled: Boolean) {
        val lib = lib() ?: return
        runCatching { lib.lgchrome_set_fullscreen(if (enabled) 1 else 0) }
            .onFailure { AppLog.put("窗口控制条切换全屏失败", it) }
    }

    // ==================== 白名单窗口消息 (本桥是唯一子类化持有者) ====================

    /**
     * 注册主窗口消息处理器 (返回 true = 已处理, native 不再沿子类化链转发)。
     *
     * 只有白名单低频消息会送来: WM_DWMSENDICONICTHUMBNAIL(0x0323) /
     * WM_DWMSENDICONICLIVEPREVIEWBITMAP(0x0326) / WM_COMMAND(0x0111)。
     *
     * **处理器跑在 native 窗口线程 (AWT-Windows), 不是 EDT**: 禁止阻塞 (取图/网络/等锁),
     * 也不要直接动 Compose/Swing 状态, 请自行投递。
     *
     * 幂等 (同一实例重复注册只留一份); 注册顺序即询问顺序, 但白名单三条消息互不重叠, 顺序无关。
     *
     * @return 是否注册成功 (native 桥不可用时 false, 调用方功能需优雅退化)
     */
    fun addMessageHandler(handler: (Int, Long, Long) -> Boolean): Boolean {
        val lib = lib()
        if (lib == null) {
            AppLog.put(
                "窗口消息钩子不可用: native 桥未加载 " +
                    "(非 Windows 或 legado_wndchrome.dll 缺失), 任务栏缩略图/按钮将失效"
            )
            return false
        }
        messageHandlers.addIfAbsent(handler)
        if (messageHookArmed) return true
        return runCatching {
            lib.lgchrome_set_message_hook(messageCallback)
            messageHookArmed = true
            true
        }.getOrElse {
            AppLog.put("窗口消息钩子注册失败", it)
            false
        }
    }

    /** 注销处理器; 全部注销后摘掉 native 钩子 (回到零 upcall)。 */
    fun removeMessageHandler(handler: (Int, Long, Long) -> Boolean) {
        messageHandlers.remove(handler)
        if (messageHandlers.isNotEmpty() || !messageHookArmed) return
        val lib = bridge ?: return
        runCatching {
            lib.lgchrome_set_message_hook(null)
            messageHookArmed = false
        }.onFailure { AppLog.put("窗口消息钩子摘除失败", it) }
    }

    /**
     * 把一个运行期才知道号的消息追加进 native 侧白名单 (如 RegisterWindowMessage 取到的
     * TaskbarButtonCreated)。只准加低频消息 —— 白名单里的消息每次都会 upcall 到 JVM。
     */
    fun addHookMessage(msg: Int): Boolean {
        val lib = lib() ?: return false
        return runCatching { lib.lgchrome_add_hook_message(msg); true }.getOrElse {
            AppLog.put("追加消息白名单失败 msg=0x${msg.toString(16)}", it)
            false
        }
    }

    // ==================== HWND ====================

    /** Native.getComponentID: JNA 官方 API 取 HWND, 不需要反射与 --add-opens。 */
    private fun componentHandle(component: Component): Long =
        runCatching { Native.getComponentID(component) }.getOrDefault(0L)

    /**
     * skiko 渲染层 (HardwareLayer, 继承 java.awt.Canvas) 的 HWND。
     * 它是独立子窗口且吃掉客户区全部鼠标消息, 拿不到就只能挂单层 —— 标题栏拖拽会失效,
     * 属于必须能查到的故障, 所以每种失败都记日志。
     */
    private fun canvasHandle(window: Window): Long {
        val canvas = findCanvas(window)
        if (canvas == null) {
            AppLog.put("窗口控制条: 未找到 skiko Canvas, 标题栏拖拽将失效")
            return 0L
        }
        if (!canvas.isDisplayable) {
            AppLog.put("窗口控制条: skiko Canvas 未 realize, 标题栏拖拽将失效")
            return 0L
        }
        val hwnd = componentHandle(canvas)
        if (hwnd == 0L) {
            AppLog.put("窗口控制条: 取 skiko Canvas HWND 失败 (${canvas.javaClass.name})")
        }
        return hwnd
    }

    /** 深度优先找第一个 Canvas 子组件 (skiko 把 HardwareLayer 嵌在 SkiaLayer 面板里)。 */
    private fun findCanvas(root: Container): Canvas? {
        for (child in root.components) {
            if (child is Canvas) return child
            if (child is Container) findCanvas(child)?.let { return it }
        }
        return null
    }

    // ==================== 编码 ====================

    /** UTF-16LE + 结尾 0 (Memory 由 malloc 得来不保证清零, 终止符显式写)。 */
    private fun utf16z(text: String): Memory {
        val bytes = text.toByteArray(Charsets.UTF_16LE)
        val memory = Memory(bytes.size + 2L)
        if (bytes.isNotEmpty()) memory.write(0, bytes, 0, bytes.size)
        memory.setShort(bytes.size.toLong(), 0.toShort())
        return memory
    }

    /**
     * BufferedImage → 预乘 alpha 的 BGRA (行优先无 padding)。
     * getRGB 统一把任意图片类型转成非预乘 sRGB ARGB (省掉逐类型 raster 分支),
     * 图标推送是低频操作, 这点开销无所谓。
     */
    private fun premultipliedBgra(image: BufferedImage): Memory {
        val width = image.width
        val height = image.height
        val argb = IntArray(width * height)
        image.getRGB(0, 0, width, height, argb, 0, width)
        val bytes = ByteArray(width * height * 4)
        var i = 0
        for (pixel in argb) {
            val a = (pixel ushr 24) and 0xFF
            val r = (pixel ushr 16) and 0xFF
            val g = (pixel ushr 8) and 0xFF
            val b = pixel and 0xFF
            bytes[i++] = ((b * a + 127) / 255).toByte()
            bytes[i++] = ((g * a + 127) / 255).toByte()
            bytes[i++] = ((r * a + 127) / 255).toByte()
            bytes[i++] = a.toByte()
        }
        val memory = Memory(bytes.size.toLong())
        memory.write(0, bytes, 0, bytes.size)
        return memory
    }

    // ==================== native 库加载 (照 DesktopSmtc 搜索链) ====================

    /** 幂等取库; 加载失败即熔断不再重试。 */
    private fun lib(): LegadoWndChrome? {
        if (!Platform.isWindows() || loadFailed) return null
        bridge?.let { return it }
        return runCatching {
            val path = findNativeLibrary() ?: error("legado_wndchrome native library not found")
            Native.load(path, LegadoWndChrome::class.java).also { bridge = it }
        }.onFailure {
            loadFailed = true
            AppLog.put("窗口控制条 native 桥加载失败", it)
        }.getOrNull()
    }

    /** 搜索链: 系统属性 → 环境变量 → 构建产物 (工作目录向上递归) → 打包资源目录。 */
    private fun findNativeLibrary(): String? {
        val name = "legado_wndchrome.dll"
        System.getProperty("legado.wndchrome.lib")?.takeIf { File(it).exists() }?.let { return it }
        System.getenv("LEGADO_WNDCHROME_LIB")?.takeIf { File(it).exists() }?.let { return it }
        // 构建产物: 工作目录可能是模块目录也可能是仓库根, 两种相对位置都试
        runCatching {
            val candidates = listOf(
                "build/libs/wndchrome/native",
                "desktop/build/libs/wndchrome/native",
            )
            var dir = File(System.getProperty("user.dir") ?: "")
            while (dir.parentFile != null) {
                for (relative in candidates) {
                    val file = File(dir, "$relative/$name")
                    if (file.exists()) return file.absolutePath
                }
                dir = dir.parentFile
            }
        }
        // 打包资源目录 (jpackage app/{packageName}/ 下, Main.kt 注入的 resources.dir)
        runCatching {
            val resDir = System.getProperty("compose.application.resources.dir")
            if (!resDir.isNullOrBlank()) {
                val file = File(resDir, name)
                if (file.exists()) return file.absolutePath
            }
        }
        return null
    }

    /** 诊断串: native 侧错误码 + Win32 原始错误。 */
    private fun diagnostics(lib: LegadoWndChrome): String {
        val err = runCatching { lib.lgchrome_last_error() }.getOrDefault(0)
        val os = runCatching { lib.lgchrome_last_os_error() }.getOrDefault(0)
        return "lastError=$err osError=0x${Integer.toHexString(os)}"
    }
}
