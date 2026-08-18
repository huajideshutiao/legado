package io.legado.desktop.ui.tray

import com.sun.jna.platform.win32.GDI32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinGDI
import com.sun.jna.ptr.PointerByReference
import io.legado.app.constant.AppLog
import io.legado.app.constant.Status
import io.legado.app.model.AudioPlayShared
import io.legado.app.service.ReadAloudControllerShared.ReadAloudState
import io.legado.desktop.help.win.DwmApi
import io.legado.desktop.ui.DesktopWindowChromeNative
import io.legado.desktop.ui.hwndOrNull
import io.legado.desktop.ui.tray.DesktopTaskbarDwm.COVER_MAX_RETRY
import io.legado.desktop.ui.tray.DesktopTaskbarDwm.lastCoverUrl
import io.legado.desktop.ui.tray.DesktopTaskbarDwm.renderExecutor
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.Window
import java.awt.image.BufferedImage
import java.util.concurrent.Executors

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
 *   —— WH_GETMESSAGE 钩子只截队列消息, 拦不到 SendMessage, 必须子类化窗口过程;
 *   主窗口的子类化由 native 桥 (wndchrome) 独占, 这两条消息经
 *   [DesktopWindowChromeNative.addMessageHandler] 转发上来 (S3: 三层子类化收成一层)
 * - 响应 DwmSetIconicThumbnail(hwnd, hBitmap, 0) / DwmSetIconicLivePreviewBitmap(hwnd, hBitmap, null, 0),
 *   位图必须 32bpp; DWM 持拷贝, 可立即 DeleteObject
 * - 内容变化调 DwmInvalidateIconicBitmaps(hwnd) 触发系统重发请求 (勿频繁, 悬停才有开销)
 * - 响应可异步: 消息回调只记请求尺寸, 渲染放单线程执行器, 限时未供 DWM 用默认表示
 *
 * 非播放/朗读状态: 关闭 iconic (恢复窗口实时缩略图)。
 */
internal object DesktopTaskbarDwm {

    /**
     * 自绘 iconic 卡片总开关 (原版行为: 悬停显示封面+书名+章节+进度卡片)。
     * 关闭时任务栏走系统默认窗口缩略图。
     * 开启 = 向 DWM 承诺"缩略图由本进程提供", 故每个 WM_DWMSENDICONIC* 请求都必须应答
     * (renderCard 恒返位图 + 尺寸兜底 + 不丢请求), 否则索取方在 await 处抛异常。
     */
    private const val ENABLE_ICONIC_CARD = true

    // ==================== 常量 ====================

    // 消息 (WM_DWMSENDICONICTHUMBNAIL / WM_DWMSENDICONICLIVEPREVIEWBITMAP)
    /** 封面加载失败的最大重试次数 (每次 update 触发一次)。 */
    private const val COVER_MAX_RETRY = 5

    /** invalidate 节流间隔: 它们自己的注释也写着"勿频繁", 原实现每个进度事件都在调。 */
    private const val INVALIDATE_MIN_INTERVAL_MS = 400L

    private const val WM_DWMSENDICONICTHUMBNAIL = 0x0323
    private const val WM_DWMSENDICONICLIVEPREVIEWBITMAP = 0x0326

    // ==================== 状态 ====================

    @Volatile
    private var mainHwnd: WinDef.HWND? = null

    /** 消息处理器是否已挂到 native 桥 (挂不上则整套卡片功能退化)。 */
    @Volatile
    private var hooked = false

    /** iconic 是否已启用 (播放/朗读活跃时)。 */
    @Volatile
    private var iconicEnabled = false

    /** 卡片内容状态 (由 update 写入, 渲染线程读取); 封面另由封面线程写。 */
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

    /** 封面异步加载去重: 已处理的封面 url。**只在加载成功后提交** (见 onCoverLoadFailed)。 */
    @Volatile
    private var lastCoverUrl: String? = null

    /** 封面加载失败的 url 与次数: 给有限重试, 避免死链在每个进度事件上重复拉取。 */
    private var coverFailUrl: String? = null
    private var coverFailCount = 0

    /** 上次 invalidate 的时间戳 (节流)。 */
    private var lastInvalidateAt = 0L

    private val renderExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "legado-dwm-card").apply { isDaemon = true }
    }

    /**
     * 封面加载专用线程 —— **绝不能和 [renderExecutor] 共用**。
     *
     * loadCover 里是 runBlocking 的阻塞取图 (网络/磁盘), 一旦和 DWM 应答共线程, 切歌时它会先入队
     * 并阻塞数秒, 紧随其后的 WM_DWMSENDICONICTHUMBNAIL 只能排队 ⇒ DWM 等不到就画自己的
     * 渐变+图标默认占位, 且此后不再询问 (用户实测: 鼠标停在任务栏弹窗上点下一张必中)。
     */
    private val coverExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "legado-dwm-cover").apply { isDaemon = true }
    }

    // ==================== 生命周期 ====================

    /** 主窗口可用时注入 (Main.kt 在 Window 组装后调用): 经 native 桥收 DWM 消息。 */
    fun attach(window: Window) {
        if (!com.sun.jna.Platform.isWindows()) return
        if (!ENABLE_ICONIC_CARD) return
        val hwnd = window.hwndOrNull() ?: return
        if (hwnd.pointer == mainHwnd?.pointer) return
        mainHwnd = hwnd
        iconicEnabled = false
        // 处理器与窗口无关 (native 桥自己跟着窗口重挂), 幂等注册即可
        if (!hooked) hooked = DesktopWindowChromeNative.addMessageHandler(messageHandler)
    }

    fun uninstall() {
        mainHwnd = null
        iconicEnabled = false
        requestedThumbSize = null
        lastCoverUrl = null
        coverImage = null
        if (hooked) {
            DesktopWindowChromeNative.removeMessageHandler(messageHandler)
            hooked = false
        }
    }

    /**
     * 状态变化时刷新卡片 (由 DesktopMediaTray.updateTaskbar 驱动, 与任务栏按钮同源)。
     * 活跃 → 启用 iconic + 刷状态 + Invalidate; 空闲 → 关闭 iconic (恢复实时缩略图)。
     *
     * iconic 自绘卡片暂时关闭 (ENABLE_ICONIC_CARD=false): 悬停崩溃排查期间只保留系统默认缩略图。
     */
    fun update(
        audioStatus: Int,
        aloud: ReadAloudTrayBinding?,
        progressMs: Int,
        durationMs: Int,
    ) {
        if (!ENABLE_ICONIC_CARD) return
        // iconic 的归属是"有没有音频会话", 不是"此刻在不在播":
        // audioStatus 会在切章节 / 进入播放页时瞬时回到 STOP (AudioPlayShared.resetData
        // 里显式 status = Status.STOP)。用它当判据会让我们把 DWMWA_FORCE_ICONIC_REPRESENTATION
        // 关掉, DWM 随即丢弃位图并回落自己的渐变+图标默认表示, 且只在下次悬停才重新索取
        // ⇒ 用户实测"切章节 / 进音频播放页后缩略图掉成默认图, 移开鼠标重新悬停才恢复"。
        // 会话存在与否是确定状态, 不需要靠计时器猜。
        val audioActive = AudioPlayShared.book != null
        val aloudState = aloud?.controller?.state?.value
        val aloudActive =
            aloudState == ReadAloudState.PLAYING || aloudState == ReadAloudState.PAUSED
        val active = audioActive || aloudActive
        val hwnd = mainHwnd ?: return
        if (!hooked) return

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
                coverExecutor.execute { loadCover(coverUrl, hwnd) }
            }
        }
        // 内容变化 → 请求重绘 (悬停时才真正触发 0x0323, 无悬停无开销)
        invalidateIconicBitmaps(hwnd)
    }

    // ==================== DWM 消息 (由 native 桥转发, 收 dwm.exe 的 SendMessage) ====================

    /** 处理器实例 (注册/注销必须同一个对象)。 */
    private val messageHandler: (Int, Long, Long) -> Boolean = ::handleWindowMessage

    /**
     * 白名单消息处理 (跑在 native 窗口线程 AWT-Windows, 非 EDT): 只记尺寸 + 派渲染, 不阻塞。
     * 返回 true = 已处理 (native 直接答 0 给 Windows)。
     */
    private fun handleWindowMessage(msg: Int, wparam: Long, lparam: Long): Boolean {
        when (msg) {
            // 缩略图请求: lParam HIWORD=宽 LOWORD=高 (MSDN 与常规相反)
            WM_DWMSENDICONICTHUMBNAIL -> {
                val w = (lparam ushr 16).toInt()
                val h = (lparam and 0xFFFF).toInt()
                if (w > 0 && h > 0) requestedThumbSize = w to h
                requestRender(live = false)
                return true
            }

            // Peek 放大预览请求: lParam 无用, 尺寸 = 客户区
            WM_DWMSENDICONICLIVEPREVIEWBITMAP -> {
                requestRender(live = true)
                return true
            }
        }
        return false
    }

    // ==================== 渲染 (单线程执行器, 不阻塞 EDT) ====================

    /**
     * 渲染请求: executor 已是单线程 (天然串行), 不做丢弃 —— 每个 DWM 请求都必须有应答。
     * **这条线程上只准做 GDI 绘制**: 任何阻塞 (取封面/网络) 都会让 DWM 等超时并回落默认占位。
     */
    private fun requestRender(live: Boolean) {
        renderExecutor.execute { runCatching { doRender(live) } }
    }

    private fun doRender(live: Boolean) {
        val hwnd = mainHwnd ?: return
        if (!iconicEnabled) return
        val size = if (live) {
            val rect = WinDef.RECT()
            if (!User32.INSTANCE.GetClientRect(hwnd, rect)) return
            rect.right.coerceAtLeast(1) to rect.bottom.coerceAtLeast(1)
        } else {
            // 尺寸缺失也要应答 (DWM 已被告知本窗口自供位图), 用任务栏缩略图常规尺寸兜底
            requestedThumbSize ?: (200 to 120)
        }
        val (w, h) = size
        if (w <= 0 || h <= 0 || w > 4000 || h > 4000) return
        val hBitmap = toHBitmap(renderCard(w, h)) ?: return
        try {
            // HRESULT 必须看: 位图超过请求上限等情形返回 E_INVALIDARG(0x80070057),
            // 表现就是"我们以为答了、DWM 却回落默认渐变图"
            val dwm = DwmApi.dwmapi ?: error("dwmapi.dll 加载失败")
            val hr = if (live) {
                dwm.DwmSetIconicLivePreviewBitmap(hwnd, hBitmap, null, 0)
            } else {
                dwm.DwmSetIconicThumbnail(hwnd, hBitmap, 0)
            }
            if (hr != 0) {
                AppLog.put(
                    "DWM 位图回传返回失败 hr=0x${hr.toUInt().toString(16)} " +
                        (if (live) "livePreview" else "thumbnail") + " ${w}x$h"
                )
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
     * 无文案时也返回位图 (只有底色+状态图标): DWM 请求必须有应答。
     */
    private fun renderCard(w: Int, h: Int): BufferedImage {
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

            // 歌名 (状态图标右侧, 最多双行换行)
            g.font = Font("Microsoft YaHei UI", Font.BOLD, titleSize)
            g.color = Color.WHITE
            val titleX = iconX + (titleSize * 1.6f).toInt()
            val titleLines = drawWrapped(
                g,
                cardTitle,
                titleX,
                iconY + titleSize,
                textW - (titleSize * 1.6f).toInt(),
                (titleSize * 1.18f).toInt(),
                2,
            )

            // 章节 (副标题, 标题区之后; 单行截断)
            g.font = Font("Microsoft YaHei UI", Font.PLAIN, subSize)
            g.color = Color(0xBFFFFFFF.toInt())
            drawTruncated(
                g,
                cardSubtitle,
                textX,
                iconY + titleSize + (titleLines - 1) * (titleSize * 1.18f).toInt() +
                    subSize + (h * 0.06f).toInt(),
                textW,
            )

            // 进度条 + 时间文本 (底部; 朗读无进度概念, 音频且时长>0 才画)
            // 布局: 当前时间最左 / 进度条居中 / 总时长最右, 三者与条中线垂直对齐
            if (cardDurationMs > 0) {
                val timeSize = (h * 0.09f).toInt().coerceIn(8, 14)
                g.font = Font("Microsoft YaHei UI", Font.PLAIN, timeSize)
                val curText = formatTime(cardProgressMs)
                val durText = formatTime(cardDurationMs)
                val curW = g.fontMetrics.stringWidth(curText)
                val durW = g.fontMetrics.stringWidth(durText)
                val gap = (timeSize * 0.6f).toInt().coerceAtLeast(6)
                val barY = h - (h * 0.12f).toInt().coerceAtLeast(12)
                val barH = 3
                val barX = textX + curW + gap
                val barW =
                    (w - textX - margin - curW - durW - gap * 2).coerceAtLeast(20)
                // 文本基线: 条中线 + 半个字高 (文本垂直居中于条)
                val textBaseY = barY + barH / 2 + g.fontMetrics.ascent / 2 - 1
                g.color = Color(0xBFFFFFFF.toInt())
                g.drawString(curText, textX, textBaseY)
                g.drawString(durText, barX + barW + gap, textBaseY)
                // 条 (文本之间)
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
                    2,
                )
            }
        } finally {
            g.dispose()
        }
        return img
    }

    /** 播放/暂停/加载状态图标 (自绘, 避免 Unicode 字形跨字体缺失)。
     * 操作语义 (对照 ThumbBar toggle 按钮): 播放中显示“暂停”双竖杠 (点击即暂停),
     * 暂停/停止显示“播放”三角 (点击即播放)。 */
    private fun drawStatusIcon(g: java.awt.Graphics2D, x: Int, y: Int, size: Int) {
        val s = size
        g.color = Color.WHITE
        when {
            cardPlaying -> {
                // 播放中: 双竖条 (操作语义: 点击即暂停)
                val barW = (s * 0.28f).toInt().coerceAtLeast(2)
                val gap = (s * 0.12f).toInt().coerceAtLeast(2)
                g.fillRoundRect(x, y, barW, s, barW, barW)
                g.fillRoundRect(x + barW + gap, y, barW, s, barW, barW)
            }

            else -> {
                // 暂停/停止: 实心三角形 (操作语义: 点击即播放)
                val xs = intArrayOf(x, x + s, x)
                val ys = intArrayOf(y, y + s / 2, y + s)
                g.fillPolygon(xs, ys, 3)
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

    /** 最多 maxLines 行换行绘制 (超长末行省略号), 返回实际行数。 */
    private fun drawWrapped(
        g: java.awt.Graphics2D,
        text: String,
        x: Int,
        baselineY: Int,
        maxWidth: Int,
        lineHeight: Int,
        maxLines: Int,
    ): Int {
        if (text.isEmpty() || maxWidth <= 0 || maxLines <= 0) return 0
        val fm = g.fontMetrics
        var line = 0
        var start = 0
        while (start < text.length && line < maxLines) {
            var end = start + 1
            while (end <= text.length && fm.stringWidth(text.substring(start, end)) <= maxWidth) {
                end++
            }
            if (end > text.length) {
                end = text.length
            } else if (end > start + 1) {
                // end 停在第一个超宽位置, 回退到最后放得下的字符, 否则每行末尾多取一字被画布裁掉
                end--
            }
            var display = text.substring(start, end)
            val truncated = line == maxLines - 1 && end < text.length
            if (truncated) {
                while (display.length > 1 &&
                    fm.stringWidth(display + "…") > maxWidth
                ) {
                    display = display.dropLast(1)
                }
                display += "…"
            }
            g.drawString(display, x, baselineY + line * lineHeight)
            line++
            start = end
            if (truncated) break
        }
        return line
    }

    /** ms → mm:ss (超过 1 小时显示 h:mm:ss)。 */
    private fun formatTime(ms: Int): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            "$h:${"%02d".format(m)}:${"%02d".format(s)}"
        } else {
            "${m}:${"%02d".format(s)}"
        }
    }

    // ==================== 位图 (CreateDIBSection 32bpp → HBITMAP) ====================

    /** BufferedImage → HBITMAP (32bpp DIB; DWM 卡片与任务栏按钮图标共用)。 */
    internal fun toHBitmap(img: BufferedImage): WinDef.HBITMAP? {
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
    private fun loadCover(url: String, hwnd: WinDef.HWND) {
        val loader = io.legado.app.help.image.BookImageLoaders.getOrNull()
            ?: return onCoverLoadFailed(url)
        val bitmap = kotlinx.coroutines.runBlocking {
            loader.loadCoverOrNull(url, AudioPlayShared.book?.origin)
        } ?: return onCoverLoadFailed(url)
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
            coverFailUrl = null
            coverFailCount = 0
            // 封面就绪后必须补发缓存失效: update() 里的 invalidate 发生在新封面就绪之前,
            // 不补发则 DWM 保留旧封面直到悬停重入 (换歌缩略图不自动更新的根因)
            if (mainHwnd?.pointer == hwnd.pointer) invalidateIconicBitmaps(hwnd)
        }.onFailure {
            AppLog.put("DWM 卡片封面转换失败", it)
        }
    }

    /**
     * 封面加载失败的收尾。
     *
     * 关键: **不能把 url 当作"已处理"留在 [lastCoverUrl] 里** —— update() 是在加载之前就提交 url 的,
     * 若失败还保留, 后续同 url 的 update 会一律跳过重试, 缩略图永远停在空白占位, 章节加载好也不恢复
     * (用户实测: 从任务栏 thumbbar 切歌时, 新曲目封面常常还没就绪)。
     * 置空即可让下一次进度事件重试; 但给 [COVER_MAX_RETRY] 上限, 避免死链每秒重拉。
     */
    private fun onCoverLoadFailed(url: String) {
        if (coverFailUrl != url) {
            coverFailUrl = url
            coverFailCount = 0
        }
        coverFailCount++
        if (coverFailCount <= COVER_MAX_RETRY && lastCoverUrl == url) lastCoverUrl = null
    }

    // ==================== DWM API (绑定统一收口在 help/win/DwmApi) ====================

    private fun setIconicMode(hwnd: WinDef.HWND, enable: Boolean) {
        runCatching {
            // 加载失败对齐旧 Function.getFunction 直调的抛错行为 (走 onFailure 日志)
            requireNotNull(DwmApi.dwmapi) { "dwmapi.dll 加载失败" }
            DwmApi.setAttribute(
                hwnd,
                DwmApi.DWMWA_FORCE_ICONIC_REPRESENTATION,
                if (enable) 1 else 0
            )
            DwmApi.setAttribute(hwnd, DwmApi.DWMWA_HAS_ICONIC_BITMAP, if (enable) 1 else 0)
        }.onFailure {
            AppLog.put("DWM iconic 开关失败", it)
        }
    }

    private fun invalidateIconicBitmaps(hwnd: WinDef.HWND) {
        // 节流: 原实现每个进度事件都调, 而 MSDN 与本文件自己的注释都说"勿频繁"
        val now = System.currentTimeMillis()
        if (now - lastInvalidateAt < INVALIDATE_MIN_INTERVAL_MS) return
        lastInvalidateAt = now
        runCatching {
            DwmApi.dwmapi?.DwmInvalidateIconicBitmaps(hwnd)
        }.onFailure { /* 悬停外调用无副作用, 忽略 */ }
    }

    // ==================== HWND 获取 ====================

}
