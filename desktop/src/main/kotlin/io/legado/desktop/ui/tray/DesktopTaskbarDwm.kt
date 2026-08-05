package io.legado.desktop.ui.tray

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.GDI32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinGDI
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.PointerByReference
import io.legado.app.constant.AppLog
import io.legado.app.constant.Status
import io.legado.app.model.AudioPlayShared
import io.legado.app.service.ReadAloudControllerShared.ReadAloudState
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.RenderingHints
import java.awt.Window
import java.awt.image.BufferedImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Windows 任务栏悬停媒体卡片 (DWM Iconic Live Preview, 对照调研结论):
 *
 * 播放/朗读时 hover 任务栏图标, 不再是窗口内容缩略图, 而是自绘卡片:
 * 封面 + 歌名 + 章节 + 播放状态 + 进度条, 下方叠加 ThumbBar 控制按钮 (DesktopTaskbarMedia)。
 *
 * # 机制 (MSDN: WM_DWMSENDICONICTHUMBNAIL / DwmSetIconicThumbnail)
 * - DwmSetWindowAttribute(DWMWA_FORCE_ICONIC_REPRESENTATION=7 + DWMWA_HAS_ICONIC_BITMAP=10)
 *   启用"自供位图"表示 (只设 10 不设 7 时非最小化悬停不触发, 常见坑)
 * - dwm.exe 跨进程 SendMessage 直达窗口 WndProc 发 WM_DWMSENDICONICTHUMBNAIL (0x0323,
 *   lParam: HIWORD=宽 LOWORD=高) / WM_DWMSENDICONICLIVEPREVIEWBITMAP (0x0326)
 *   —— WH_GETMESSAGE 钩子只截队列消息, 拦不到 SendMessage, 必须 SetWindowLongPtr
 *   GWLP_WNDPROC 子类化 AWT 窗口 + CallWindowProc 转发 (C# 参考实现同法)
 * - 响应 DwmSetIconicThumbnail(hwnd, hBitmap, 0) / DwmSetIconicLivePreviewBitmap(hwnd, hBitmap, null, 0),
 *   位图必须 32bpp; DWM 持拷贝, 可立即 DeleteObject
 * - 内容变化调 DwmInvalidateIconicBitmaps(hwnd) 触发系统重发请求 (勿频繁, 悬停才有开销)
 * - 响应可异步: 子类化回调只记请求尺寸, 渲染放单线程执行器, 限时未供 DWM 用默认表示
 *
 * 非播放/朗读状态: 关闭 iconic (恢复窗口实时缩略图)。
 */
internal object DesktopTaskbarDwm {

    // ==================== 常量 ====================

    // DWMWINDOWATTRIBUTE
    private const val DWMWA_FORCE_ICONIC_REPRESENTATION = 7
    private const val DWMWA_HAS_ICONIC_BITMAP = 10

    // 消息 (WM_DWMSENDICONICTHUMBNAIL / WM_DWMSENDICONICLIVEPREVIEWBITMAP)
    private const val WM_DWMSENDICONICTHUMBNAIL = 0x0323
    private const val WM_DWMSENDICONICLIVEPREVIEWBITMAP = 0x0326

    // ==================== 状态 ====================

    @Volatile
    private var mainHwnd: WinDef.HWND? = null

    /** 原窗口过程 (子类化转发用, Long 形式)。 */
    @Volatile
    private var oldWndProc: Long = 0L

    /** iconic 是否已启用 (播放/朗读活跃时)。 */
    @Volatile
    private var iconicEnabled = false

    /** 卡片内容状态 (由 update 写入, 渲染线程读取)。 */
    @Volatile
    private var coverImage: BufferedImage? = null

    @Volatile
    private var cardTitle: String = ""

    @Volatile
    private var cardSubtitle: String = ""

    @Volatile
    private var cardPlaying = false

    @Volatile
    private var cardProgressMs: Int = 0

    @Volatile
    private var cardDurationMs: Int = 0

    /** 上次请求的缩略图尺寸 (0x0323 lParam: HIWORD=宽 LOWORD=高)。 */
    @Volatile
    private var requestedThumbSize: Pair<Int, Int>? = null

    /** 封面异步加载去重。 */
    @Volatile
    private var lastCoverUrl: String? = null

    private val renderExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "legado-dwm-card").apply { isDaemon = true }
    }

    private val subclassed = AtomicBoolean(false)

    // ==================== 生命周期 ====================

    /** 主窗口可用时注入 (Main.kt 在 Window 组装后调用): 子类化收 DWM 消息。 */
    fun attach(window: Window) {
        if (!com.sun.jna.Platform.isWindows()) return
        val hwnd = hwndOf(window) ?: return
        if (hwnd.pointer == mainHwnd?.pointer) return
        mainHwnd = hwnd
        subclassWindow(hwnd)
    }

    fun uninstall() {
        mainHwnd = null
        iconicEnabled = false
        requestedThumbSize = null
        lastCoverUrl = null
        coverImage = null
        restoreWindowProc()
    }

    /**
     * 状态变化时刷新卡片 (由 DesktopMediaTray.updateTaskbar 驱动, 与任务栏按钮同源)。
     * 活跃 → 启用 iconic + 刷状态 + Invalidate; 空闲 → 关闭 iconic (恢复实时缩略图)。
     */
    fun update(
        audioStatus: Int,
        aloud: ReadAloudTrayBinding?,
        progressMs: Int,
        durationMs: Int,
    ) {
        val audioActive = audioStatus != Status.STOP
        val aloudState = aloud?.controller?.state?.value
        val aloudActive =
            aloudState == ReadAloudState.PLAYING || aloudState == ReadAloudState.PAUSED
        val active = audioActive || aloudActive
        val hwnd = mainHwnd ?: return
        if (!subclassed.get()) return

        // 卡片状态快照 (对照原版通知标题: 状态: 书名 / 章节)
        cardPlaying = when {
            audioActive -> audioStatus == Status.PLAY
            aloudActive -> aloudState == ReadAloudState.PLAYING
            else -> false
        }
        cardTitle = when {
            audioActive -> AudioPlayShared.book?.name ?: ""
            aloudActive -> aloud?.bookName() ?: ""
            else -> ""
        }
        cardSubtitle = when {
            audioActive -> AudioPlayShared.durChapter?.title ?: ""
            aloudActive -> aloud?.chapterTitle() ?: ""
            else -> ""
        }
        cardProgressMs = progressMs
        cardDurationMs = durationMs

        // iconic 开关 (幂等; 空闲时关闭恢复实时缩略图)
        if (active != iconicEnabled) {
            setIconicMode(hwnd, active)
            iconicEnabled = active
        }
        if (!active) return

        // 封面异步加载 (音频: durCoverUrl; 朗读: 无封面)
        val coverUrl = if (audioActive) AudioPlayShared.durCoverUrl else null
        if (coverUrl != lastCoverUrl) {
            lastCoverUrl = coverUrl
            if (coverUrl.isNullOrBlank()) {
                coverImage = null
            } else {
                renderExecutor.execute { loadCover(coverUrl) }
            }
        }
        // 内容变化 → 请求重绘 (悬停时才真正触发 0x0323, 无悬停无开销)
        invalidateIconicBitmaps(hwnd)
    }

    // ==================== 窗口子类化 (收 dwm.exe SendMessage) ====================

    /** 子类化回调强引用 (SetWindowLongPtr 存函数指针, 回调对象被 GC 即崩)。 */
    private val wndProc = object : WinUser.WindowProc {
        override fun callback(
            hwnd: WinDef.HWND,
            msg: Int,
            wParam: WinDef.WPARAM,
            lParam: WinDef.LPARAM,
        ): WinDef.LRESULT {
            try {
                when (msg) {
                    // 缩略图请求: lParam HIWORD=宽 LOWORD=高 (MSDN 与常规相反)
                    WM_DWMSENDICONICTHUMBNAIL -> {
                        val w = (lParam.toLong() ushr 16).toInt()
                        val h = (lParam.toLong() and 0xFFFF).toInt()
                        if (w > 0 && h > 0) requestedThumbSize = w to h
                        requestRender(live = false)
                        return WinDef.LRESULT(0)
                    }

                    // Peek 放大预览请求: lParam 无用, 尺寸 = 客户区
                    WM_DWMSENDICONICLIVEPREVIEWBITMAP -> {
                        requestRender(live = true)
                        return WinDef.LRESULT(0)
                    }
                }
            } catch (e: Throwable) {
                AppLog.put("DWM 卡片窗口过程异常 (msg=$msg)", e)
            }
            return callOldProc(hwnd, msg, wParam, lParam)
        }
    }

    private fun subclassWindow(hwnd: WinDef.HWND) {
        if (!subclassed.compareAndSet(false, true)) return
        runCatching {
            val old = SubclassWin32.INSTANCE.GetWindowLongPtrW(hwnd, WinUser.GWL_WNDPROC) ?: return
            oldWndProc = Pointer.nativeValue(old)
            val fnPtr = com.sun.jna.CallbackReference.getFunctionPointer(wndProc)
            SubclassWin32.INSTANCE.SetWindowLongPtrW(
                hwnd,
                WinUser.GWL_WNDPROC,
                Pointer(Pointer.nativeValue(fnPtr)),
            )
        }.onFailure {
            AppLog.put("DWM 卡片窗口子类化失败", it)
            oldWndProc = 0L
            subclassed.set(false)
        }
    }

    private fun restoreWindowProc() {
        val hwnd = mainHwnd ?: return
        val old = oldWndProc
        if (old == 0L || !subclassed.get()) return
        runCatching {
            SubclassWin32.INSTANCE.SetWindowLongPtrW(
                hwnd,
                WinUser.GWL_WNDPROC,
                Pointer(old),
            )
        }.onFailure { AppLog.put("DWM 卡片窗口过程还原失败", it) }
        oldWndProc = 0L
        subclassed.set(false)
    }

    private fun callOldProc(
        hwnd: WinDef.HWND,
        msg: Int,
        wParam: WinDef.WPARAM,
        lParam: WinDef.LPARAM,
    ): WinDef.LRESULT {
        val old = oldWndProc
        return if (old != 0L) {
            runCatching {
                SubclassWin32.INSTANCE.CallWindowProcW(
                    Pointer(old),
                    hwnd, msg, wParam, lParam,
                )
            }.getOrDefault(WinDef.LRESULT(0))
        } else {
            SubclassWin32.INSTANCE.DefWindowProcW(hwnd, msg, wParam, lParam)
        }
    }

    // ==================== 渲染 (单线程执行器, 不阻塞 EDT) ====================

    /** 合并渲染请求: 正在渲染时只记 pending, 完成后重查。 */
    private val rendering = AtomicBoolean(false)

    private fun requestRender(live: Boolean) {
        renderExecutor.execute {
            if (!rendering.compareAndSet(false, true)) return@execute
            try {
                doRender(live)
            } finally {
                rendering.set(false)
            }
        }
    }

    private fun doRender(live: Boolean) {
        val hwnd = mainHwnd ?: return
        if (!iconicEnabled) return
        val size = if (live) {
            val rect = WinDef.RECT()
            if (!User32.INSTANCE.GetClientRect(hwnd, rect)) return
            rect.right.coerceAtLeast(1) to rect.bottom.coerceAtLeast(1)
        } else {
            requestedThumbSize ?: return
        }
        val (w, h) = size
        if (w <= 0 || h <= 0 || w > 4000 || h > 4000) return
        val bitmap = renderCard(w, h) ?: return
        val hBitmap = toHBitmap(bitmap) ?: return
        try {
            if (live) {
                dwmapi("DwmSetIconicLivePreviewBitmap")
                    .invokeInt(arrayOf(hwnd, hBitmap, null, 0))
            } else {
                dwmapi("DwmSetIconicThumbnail").invokeInt(arrayOf(hwnd, hBitmap, 0))
            }
        } catch (e: Throwable) {
            AppLog.put("DWM 卡片位图回传失败", e)
        } finally {
            runCatching { GDI32.INSTANCE.DeleteObject(hBitmap) }
        }
    }

    /**
     * 绘制卡片: 深色底 + 封面(左) + 歌名/章节(右) + 状态图标 + 底部进度条。
     * 字号随卡片高度缩放 (缩略图 ~120px 用小字, Peek 大预览用大字)。
     */
    private fun renderCard(w: Int, h: Int): BufferedImage? {
        if (cardTitle.isBlank() && cardSubtitle.isBlank()) return null
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON
            )
            // 背景 (深色, 对照原版媒体卡观感)
            g.color = Color(0x24, 0x24, 0x24)
            g.fillRect(0, 0, w, h)
            g.color = Color(0x3A, 0x3A, 0x3A)
            g.drawRect(0, 0, w - 1, h - 1)

            val margin = (w * 0.05f).toInt().coerceAtLeast(6)
            val titleSize = (h * 0.14f).toInt().coerceIn(11, 26)
            val subSize = (h * 0.10f).toInt().coerceIn(9, 18)

            // 封面 (左侧, 仅音频; 有封面才画)
            coverImage?.let { cover ->
                val coverSize = (h * 0.72f).toInt().coerceIn(40, 180)
                val coverX = margin
                val coverY = (h - coverSize) / 2
                g.drawImage(cover, coverX, coverY, coverSize, coverSize, null)
                // 圆角描边
                g.color = Color(0x55FFFFFF)
                g.drawRoundRect(coverX, coverY, coverSize - 1, coverSize - 1, 6, 6)
            }

            val textX = if (coverImage != null) margin * 2 + (h * 0.72f).toInt().coerceIn(40, 180)
            else margin
            val textW = (w - textX - margin).coerceAtLeast(40)

            // 状态图标 (▶/⏸/…)
            val iconX = textX
            val iconY = (h * 0.22f).toInt()
            drawStatusIcon(g, iconX, iconY, (titleSize * 1.2f).toInt())

            // 歌名 (状态图标右侧, 单行截断)
            g.font = Font("Microsoft YaHei UI", Font.BOLD, titleSize)
            g.color = Color.WHITE
            val titleX = iconX + (titleSize * 1.6f).toInt()
            drawTruncated(
                g,
                cardTitle,
                titleX,
                iconY + titleSize,
                textW - (titleSize * 1.6f).toInt()
            )

            // 章节 (副标题, 单行截断)
            g.font = Font("Microsoft YaHei UI", Font.PLAIN, subSize)
            g.color = Color(0xBFFFFFFF.toInt())
            drawTruncated(
                g,
                cardSubtitle,
                textX,
                iconY + titleSize + subSize + (h * 0.06f).toInt(),
                textW
            )

            // 进度条 (底部; 朗读无进度概念, 音频且时长>0 才画)
            if (cardDurationMs > 0) {
                val barY = h - (h * 0.12f).toInt().coerceAtLeast(10)
                val barH = 3
                val barX = textX
                val barW = (w - textX - margin).coerceAtLeast(20)
                g.color = Color(0x66FFFFFF)
                g.fillRoundRect(barX, barY, barW, barH, 2, 2)
                val frac = (cardProgressMs.toFloat() / cardDurationMs).coerceIn(0f, 1f)
                g.color = Color(0xFF4D94FF.toInt())
                g.fillRoundRect(
                    barX,
                    barY,
                    (barW * frac).toInt().coerceAtLeast(if (frac > 0) 2 else 0),
                    barH,
                    2,
                    2
                )
            }
        } finally {
            g.dispose()
        }
        return img
    }

    /** 播放/暂停/加载状态图标 (自绘, 避免 Unicode 字形跨字体缺失)。 */
    private fun drawStatusIcon(g: java.awt.Graphics2D, x: Int, y: Int, size: Int) {
        val s = size
        g.color = Color.WHITE
        when {
            cardPlaying -> {
                // 播放: 实心三角形
                val xs = intArrayOf(x, x + s, x)
                val ys = intArrayOf(y, y + s / 2, y + s)
                g.fillPolygon(xs, ys, 3)
            }

            else -> {
                // 暂停/加载: 双竖条
                val barW = (s * 0.28f).toInt().coerceAtLeast(2)
                val gap = (s * 0.12f).toInt().coerceAtLeast(2)
                g.fillRoundRect(x, y, barW, s, barW, barW)
                g.fillRoundRect(x + barW + gap, y, barW, s, barW, barW)
            }
        }
    }

    private fun drawTruncated(g: java.awt.Graphics2D, text: String, x: Int, y: Int, maxW: Int) {
        if (text.isEmpty() || maxW <= 0) return
        var t = text
        while (g.fontMetrics.stringWidth(t) > maxW && t.length > 1) {
            t = t.dropLast(1)
        }
        if (t != text) t = t.dropLast(1) + "…"
        g.drawString(t, x, y)
    }

    // ==================== 位图 (CreateDIBSection 32bpp → HBITMAP) ====================

    private fun toHBitmap(img: BufferedImage): WinDef.HBITMAP? {
        val w = img.width
        val h = img.height
        // BITMAPINFOHEADER: biSize 40 + biWidth + biHeight(负=自顶向下) + planes + bitcount + compression
        val header = WinGDI.BITMAPINFOHEADER()
        header.biSize = 40
        header.biWidth = w
        header.biHeight = -h // 自顶向下, 首行在内存开头
        header.biPlanes = 1
        header.biBitCount = 32
        header.biCompression = WinGDI.BI_RGB
        val bmi = WinGDI.BITMAPINFO()
        bmi.bmiHeader = header
        val bits = PointerByReference()
        val hbm = GDI32.INSTANCE.CreateDIBSection(
            null, bmi, WinGDI.DIB_RGB_COLORS, bits, null, 0
        )
        if (hbm == null || bits.value == null) return null
        // ARGB int → BGRA 字节序 (DIB 32bpp 内存序 B,G,R,A)
        val pixels = img.getRGB(0, 0, w, h, null, 0, w)
        val dst = bits.value
        for (i in pixels.indices) {
            val argb = pixels[i]
            val a = (argb ushr 24) and 0xFF
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF
            dst.setInt(i * 4L, (a shl 24) or (r shl 16) or (g shl 8) or b)
        }
        return hbm
    }

    // ==================== 封面加载 ====================

    /** 封面 ImageBitmap → BufferedImage (ImageBitmap.readPixels 返回 ARGB, 与平台解耦)。 */
    private fun loadCover(url: String) {
        val loader = io.legado.app.help.image.BookImageLoaders.getOrNull() ?: return
        val bitmap = kotlinx.coroutines.runBlocking {
            loader.loadCoverOrNull(url, AudioPlayShared.book?.origin)
        } ?: return
        runCatching {
            val w = bitmap.width
            val h = bitmap.height
            if (w <= 0 || h <= 0) return@runCatching
            val pixels = IntArray(w * h)
            bitmap.readPixels(pixels, 0, 0, w, h, 0, w)
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            for (i in pixels.indices) {
                img.setRGB(i % w, i / w, pixels[i])
            }
            coverImage = img
        }.onFailure {
            AppLog.put("DWM 卡片封面转换失败", it)
        }
    }

    // ==================== DWM API (dwmapi.dll 手写绑定) ====================

    private fun dwmapi(name: String): Function =
        Function.getFunction("dwmapi", name, Function.ALT_CONVENTION)

    private fun setIconicMode(hwnd: WinDef.HWND, enable: Boolean) {
        runCatching {
            val mem = Memory(4)
            mem.setInt(0, if (enable) 1 else 0)
            dwmapi("DwmSetWindowAttribute")
                .invokeInt(arrayOf(hwnd, DWMWA_FORCE_ICONIC_REPRESENTATION, mem, 4))
            dwmapi("DwmSetWindowAttribute")
                .invokeInt(arrayOf(hwnd, DWMWA_HAS_ICONIC_BITMAP, mem, 4))
        }.onFailure {
            AppLog.put("DWM iconic 开关失败", it)
        }
    }

    private fun invalidateIconicBitmaps(hwnd: WinDef.HWND) {
        runCatching {
            dwmapi("DwmInvalidateIconicBitmaps").invokeInt(arrayOf(hwnd))
        }.onFailure { /* 悬停外调用无副作用, 忽略 */ }
    }

    // ==================== HWND 获取 ====================

    /** AWT Window → 原生 HWND (反射 peer.getHWnd, 同 DesktopTaskbarMedia)。 */
    private fun hwndOf(window: Window): WinDef.HWND? {
        return runCatching {
            val peerField = Component::class.java.getDeclaredField("peer")
            peerField.isAccessible = true
            val peer = peerField.get(window) ?: return null
            val method = peer.javaClass.methods.firstOrNull { it.name == "getHWnd" }
                ?: peer.javaClass.declaredMethods.firstOrNull { it.name == "getHWnd" }
                ?: return null
            method.isAccessible = true
            val value = method.invoke(peer) as? Long ?: return null
            if (value == 0L) null else WinDef.HWND(Pointer(value))
        }.getOrNull()
    }

    // ==================== 子类化 API 最小绑定 ====================

    /**
     * 项目所用 JNA 版本的 User32.GetWindowLongPtr 返回 BaseTSD.LONG_PTR,
     * 无法直接取得指针地址值; 这里以 Pointer 重声明这三个函数, 与 jna-platform 版本解耦。
     * 注意: user32 导出名是 GetWindowLongPtrW/SetWindowLongPtrW (宏按 UNICODE 展开),
     * 裸 GetWindowLongPtr 无导出符号 (UnsatisfiedLinkError)。
     */
    private interface SubclassWin32 : com.sun.jna.Library {
        fun GetWindowLongPtrW(hWnd: WinDef.HWND, nIndex: Int): Pointer
        fun SetWindowLongPtrW(hWnd: WinDef.HWND, nIndex: Int, dwNewLong: Pointer): Pointer
        fun CallWindowProcW(
            lpPrevWndFunc: Pointer,
            hWnd: WinDef.HWND,
            msg: Int,
            wParam: WinDef.WPARAM,
            lParam: WinDef.LPARAM,
        ): WinDef.LRESULT

        fun DefWindowProcW(
            hWnd: WinDef.HWND,
            msg: Int,
            wParam: WinDef.WPARAM,
            lParam: WinDef.LPARAM,
        ): WinDef.LRESULT

        companion object {
            val INSTANCE: SubclassWin32 = Native.load("user32", SubclassWin32::class.java)
        }
    }
}
