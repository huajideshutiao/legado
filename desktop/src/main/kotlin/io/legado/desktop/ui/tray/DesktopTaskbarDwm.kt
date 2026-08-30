package io.legado.desktop.ui.tray

import com.sun.jna.platform.win32.GDI32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinGDI
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.PointerByReference
import io.legado.app.constant.AppLog
import io.legado.app.constant.Status
import io.legado.app.model.AudioPlayCommanders
import io.legado.app.model.AudioPlayShared
import io.legado.app.service.ReadAloudControllerShared.ReadAloudState
import io.legado.desktop.help.win.DwmApi
import io.legado.desktop.ui.DesktopWindowChromeNative
import io.legado.desktop.ui.hwndOrNull
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.Path
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Typeface
import org.jetbrains.skia.impl.use
import java.awt.Window
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Windows 任务栏悬停媒体卡片 (DWM Iconic Live Preview, 基于 Skia/Skiko 现代高性能图形管线):
 *
 * 音频会话/朗读活跃时 hover 任务栏图标, 不再是窗口内容缩略图, 而是自绘卡片:
 * 封面 + 歌名 + 章节 + 播放状态 + 进度条, 下方叠加 ThumbBar 控制按钮 (DesktopTaskbarMedia)。
 *
 * # 现代 Skia 渲染管线升级
 * - 淘汰 AWT Graphics2D / BufferedImage，使用 Skia 离屏 Raster Surface (BGRA_8888 Premul)。
 * - 字体度量与渲染采用 DirectWrite / Skia 现代引擎，抗锯齿与排版性能大幅跃升。
 * - 像素通过 DMA 单次批量映射进 Win32 DIB Section，彻底消除逐像素 JVM 转换循环。
 *
 * # 机制 (MSDN: WM_DWMSENDICONICTHUMBNAIL / DwmSetIconicThumbnail)
 * - DwmSetWindowAttribute(DWMWA_FORCE_ICONIC_REPRESENTATION=7 + DWMWA_HAS_ICONIC_BITMAP=10)
 *   启用"自供位图"表示
 * - 主动预推机制: 缩略图生成时即在后台线程生成大图位图并主动向 dwm.exe 注册，根除 Aero Peek 白闪。
 */
internal object DesktopTaskbarDwm {

    /** 自绘 iconic 卡片总开关。 */
    private const val ENABLE_ICONIC_CARD = true

    // ==================== 常量 ====================

    private const val COVER_MAX_RETRY = 5
    private const val INVALIDATE_MIN_INTERVAL_MS = 400L

    private const val WM_DWMSENDICONICTHUMBNAIL = 0x0323
    private const val WM_DWMSENDICONICLIVEPREVIEWBITMAP = 0x0326

    // ==================== 字体缓存 ====================

    private val normalTypeface: Typeface? by lazy {
        runCatching {
            FontMgr.default.matchFamiliesStyle(
                arrayOf<String?>("Microsoft YaHei UI", "Segoe UI", "Arial"),
                FontStyle.NORMAL
            )
        }.getOrNull()
    }

    private val boldTypeface: Typeface? by lazy {
        runCatching {
            FontMgr.default.matchFamiliesStyle(
                arrayOf<String?>("Microsoft YaHei UI", "Segoe UI", "Arial"),
                FontStyle.BOLD
            )
        }.getOrNull()
    }

    // ==================== 不可变快照定义 ====================

    data class ThemeColors(
        val bg: Int = 0xFF242424.toInt(),
        val border: Int = 0x2EFFFFFF,
        val coverBorder: Int = 0x3DFFFFFF,
        val textPrimary: Int = 0xFFFFFFFF.toInt(),
        val textSecondary: Int = 0xBFFFFFFF.toInt(),
        val progressTrack: Int = 0x33FFFFFF,
        val progressFill: Int = 0xFF165DFF.toInt(),
        val isDark: Boolean = true,
    )

    private data class CardSnapshot(
        val title: String = "",
        val subtitle: String = "",
        val playing: Boolean = false,
        val progressMs: Int = 0,
        val durationMs: Int = 0,
        val cover: Image? = null,
    )

    private data class LivePreviewCache(
        val key: String,
        val bitmap: Bitmap,
    )

    // ==================== 状态 ====================

    private val windowStateLock = Any()

    @Volatile
    private var mainHwnd: WinDef.HWND? = null

    @Volatile
    private var windowGeneration = 0L

    @Volatile
    private var hooked = false

    @Volatile
    private var iconicEnabled = false

    /** 动态主题配色 (原子更新，跟随全局应用主题色) */
    private val currentTheme = AtomicReference(ThemeColors())

    /** 核心卡片状态不可变快照 (原子更新，读者无锁一致性读取) */
    private val currentSnapshot = AtomicReference(CardSnapshot())

    /** 实时预览大图缓存 (原子引用) */
    private val liveCache = AtomicReference<LivePreviewCache?>(null)

    /** 封面 URL、快照中的 Image 及失败计数的复合更新锁。 */
    private val coverStateLock = Any()

    /** 封面异步加载去重: 当前请求的封面 URL。 */
    @Volatile
    private var lastCoverUrl: String? = null

    /** 封面加载失败的 url 与次数: 给有限重试, 避免死链在每个进度事件上重复拉取。 */
    @Volatile
    private var coverFailUrl: String? = null

    @Volatile
    private var coverFailCount = 0

    /** 上次 invalidate 的时间戳 (节流)。 */
    @Volatile
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
        synchronized(windowStateLock) {
            if (hwnd.pointer == mainHwnd?.pointer) return
            mainHwnd = hwnd
            windowGeneration++
            iconicEnabled = false
        }
        // 处理器与窗口无关 (native 桥自己跟着窗口重挂), 幂等注册即可
        if (!hooked) hooked = DesktopWindowChromeNative.addMessageHandler(messageHandler)
    }

    fun uninstall() {
        val hwnd = synchronized(windowStateLock) {
            val current = mainHwnd
            mainHwnd = null
            windowGeneration++
            iconicEnabled = false
            current
        }
        clearCardContent()
        if (hwnd != null) {
            setIconicMode(hwnd, false)
            invalidateIconicBitmaps(hwnd)
        }
        if (hooked) {
            DesktopWindowChromeNative.removeMessageHandler(messageHandler)
            hooked = false
        }
    }

    /** 会话终结时清空卡片内容 (封面/文案/进度与加载去重状态), 下次会话从干净状态开始。 */
    private fun clearCardContent() {
        val previousSnapshot = synchronized(coverStateLock) {
            lastCoverUrl = null
            coverFailUrl = null
            coverFailCount = 0
            currentSnapshot.getAndSet(CardSnapshot())
        }
        // 缓存可能正被已有渲染任务改写，必须到执行器中再取出并释放。
        renderExecutor.execute {
            liveCache.getAndSet(null)?.bitmap?.close()
            previousSnapshot.cover?.close()
        }
    }

    /**
     * 状态变化时刷新卡片 (由 DesktopMediaTray.updateTaskbar 驱动, 与任务栏按钮同源)。
     * 会话活跃 → 启用 iconic + 刷状态 + Invalidate; 会话终结 → 关闭 iconic (恢复实时缩略图)。
     */
    fun update(
        audioStatus: Int,
        aloud: ReadAloudTrayBinding?,
        progressMs: Int,
        durationMs: Int,
    ) {
        if (!ENABLE_ICONIC_CARD) return
        // 卡片寿命 = 媒体会话寿命 (对照原版 Android 前台服务, 桌面镜像即 provider running /
        // AudioPlayCommanders.isServiceRunning): 首次 play 建立, 只有 stop / 致命错误终结;
        // AudioPlayService 里 stop → stopSelf, 而 stopPlay (切章节) 只停播放器不停服务。
        val audioSession = AudioPlayCommanders.getOrNull()?.isServiceRunning == true
        val aloudState = aloud?.controller?.state?.value
        val aloudActive =
            aloudState == ReadAloudState.PLAYING || aloudState == ReadAloudState.PAUSED
        val active = audioSession || aloudActive
        val hwnd = mainHwnd ?: return
        if (!hooked) return

        // iconic 开关 (幂等; 会话终结时关闭恢复实时窗口缩略图, 并清空卡片内容)
        if (active != iconicEnabled) {
            synchronized(windowStateLock) {
                if (mainHwnd?.pointer != hwnd.pointer) return
                setIconicMode(hwnd, active)
                iconicEnabled = active
            }
            if (!active) {
                clearCardContent()
                invalidateIconicBitmaps(hwnd)
                return
            }
        }
        if (!active) return

        // 卡片状态快照 (不可变对象原子更新，避免跨线程 Torn Read)
        val playing = if (audioSession) audioStatus == Status.PLAY else aloudState == ReadAloudState.PLAYING
        val title = if (audioSession) AudioPlayShared.book?.name.orEmpty() else aloud?.bookName().orEmpty()
        val subtitle = if (audioSession) AudioPlayShared.durChapter?.title.orEmpty() else aloud?.chapterTitle().orEmpty()

        currentSnapshot.updateAndGet { snapshot ->
            snapshot.copy(
                title = title,
                subtitle = subtitle,
                playing = playing,
                progressMs = progressMs,
                durationMs = durationMs,
            )
        }

        // 封面异步加载 (音频: durCoverUrl; 朗读: 无封面)
        val coverUrl = if (audioSession) AudioPlayShared.durCoverUrl else null
        var previousCover: Image? = null
        var pendingCoverUrl: String? = null
        synchronized(coverStateLock) {
            if (coverUrl != lastCoverUrl) {
                lastCoverUrl = coverUrl
                if (coverUrl.isNullOrBlank()) {
                    previousCover = currentSnapshot.getAndUpdate { it.copy(cover = null) }.cover
                } else {
                    pendingCoverUrl = coverUrl
                }
            }
        }
        previousCover?.let { cover ->
            // 已开始的渲染任务可能仍持有旧快照，不能在调用线程立即释放。
            renderExecutor.execute { cover.close() }
        }
        pendingCoverUrl?.let { url -> coverExecutor.execute { loadCover(url, hwnd) } }
        // 内容变化 → 请求重绘 (悬停时才真正触发 0x0323, 无悬停无开销)
        invalidateIconicBitmaps(hwnd)
    }

    // ==================== 主题同步 ====================

    fun setTheme(
        bg: Int,
        textPrimary: Int,
        textSecondary: Int,
        accent: Int,
        isDark: Boolean,
    ) {
        val theme = ThemeColors(
            bg = bg,
            border = if (isDark) 0x2EFFFFFF else 0x1A000000,
            coverBorder = if (isDark) 0x3DFFFFFF else 0x22000000,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            progressTrack = if (isDark) 0x33FFFFFF else 0x1F000000,
            progressFill = accent,
            isDark = isDark,
        )

        if (currentTheme.getAndSet(theme) != theme) {
            val hwnd = mainHwnd
            renderExecutor.execute {
                // 执行时再取缓存，才能覆盖主题变更前已经在队列中的渲染结果。
                liveCache.getAndSet(null)?.bitmap?.close()
                if (hwnd != null && mainHwnd?.pointer == hwnd.pointer && iconicEnabled) {
                    runCatching { doRender(live = true) }
                        .onFailure { AppLog.put("DWM 实时预览渲染失败", it) }
                }
            }
            if (hwnd != null && iconicEnabled) invalidateIconicBitmaps(hwnd)
        }
    }

    // ==================== DWM 消息 ====================

    private val messageHandler: (Int, Long, Long) -> Boolean = ::handleWindowMessage

    private fun handleWindowMessage(msg: Int, wparam: Long, lparam: Long): Boolean {
        when (msg) {
            WM_DWMSENDICONICTHUMBNAIL -> {
                if (!iconicEnabled) return false
                val w = ((lparam ushr 16) and 0xFFFF).toInt()
                val h = (lparam and 0xFFFF).toInt()
                if (w <= 0 || h <= 0) return false
                enqueueRender(live = false, thumbnailSize = w to h)
                return true
            }

            WM_DWMSENDICONICLIVEPREVIEWBITMAP -> {
                if (mainHwnd == null || !iconicEnabled) return false
                enqueueRender(live = true)
                return true
            }
        }
        return false
    }

    private fun enqueueRender(live: Boolean, thumbnailSize: Pair<Int, Int>? = null) {
        renderExecutor.execute {
            runCatching { doRender(live, thumbnailSize) }.onFailure {
                AppLog.put(
                    if (live) "DWM 实时预览渲染失败" else "DWM 缩略图渲染失败",
                    it,
                )
            }
        }
    }

    // ==================== 渲染 (Skia 引擎) ====================

    private fun getWindowPreviewSize(hwnd: WinDef.HWND): Pair<Int, Int> {
        // Live Preview 位图不得超过客户区；正常窗口优先使用当前客户区的真实尺寸。
        val rect = WinDef.RECT()
        if (User32.INSTANCE.GetClientRect(hwnd, rect)) {
            val w = rect.right - rect.left
            val h = rect.bottom - rect.top
            if (w > 0 && h > 0) return w.coerceAtMost(8192) to h.coerceAtMost(8192)
        }

        // 最小化时 GetClientRect 可能没有有效尺寸，JNA User32 未声明 IsIconic，
        // 因此直接读取还原位置；GetWindowPlacement 的实际返回类型是 WinDef.BOOL。
        val placement = WinUser.WINDOWPLACEMENT()
        placement.length = placement.size()
        if (User32.INSTANCE.GetWindowPlacement(hwnd, placement).booleanValue()) {
            val r = placement.rcNormalPosition
            return (r.right - r.left).coerceIn(400, 8192) to
                (r.bottom - r.top).coerceIn(300, 8192)
        }
        return 1280 to 720
    }

    private fun doRender(live: Boolean, thumbnailSize: Pair<Int, Int>? = null) {
        val (hwnd, generation) = synchronized(windowStateLock) {
            val current = mainHwnd ?: return
            if (!iconicEnabled) return
            current to windowGeneration
        }

        if (live) {
            renderAndSubmitLivePreview(hwnd, generation)
        } else {
            val (w, h) = thumbnailSize ?: (200 to 120)
            val snapshot = currentSnapshot.get()
            val theme = currentTheme.get()
            val thumb = renderThumbnailCard(w, h, snapshot, theme)
            thumb.use { thumb ->
                sendBitmapToDwm(hwnd, generation, thumb, live = false, w, h)
            }
            renderAndSubmitLivePreview(hwnd, generation)
        }
    }

    private fun sendBitmapToDwm(
        hwnd: WinDef.HWND,
        generation: Long,
        bitmap: Bitmap,
        live: Boolean,
        w: Int,
        h: Int,
    ) {
        val hBitmap = toHBitmap(bitmap) ?: run {
            AppLog.put("DWM 位图转换失败 (toHBitmap 返回 null)")
            return
        }
        try {
            synchronized(windowStateLock) {
                if (
                    generation != windowGeneration ||
                    mainHwnd?.pointer != hwnd.pointer ||
                    !iconicEnabled
                ) return
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
            }
        } catch (e: Throwable) {
            AppLog.put("DWM 卡片位图回传失败", e)
        } finally {
            runCatching { GDI32.INSTANCE.DeleteObject(hBitmap) }
        }
    }

    private fun liveCacheKey(w: Int, h: Int, s: CardSnapshot, theme: ThemeColors): String {
        return "$w|$h|${s.title}|${s.subtitle}|${s.playing}|${s.progressMs}|${s.durationMs}|${s.cover?.hashCode()}|${theme.hashCode()}"
    }

    private fun renderAndSubmitLivePreview(hwnd: WinDef.HWND, generation: Long) {
        val (w, h) = getWindowPreviewSize(hwnd)
        val snapshot = currentSnapshot.get()
        val theme = currentTheme.get()
        val key = liveCacheKey(w, h, snapshot, theme)
        val cached = liveCache.get()
        val bmp = if (cached != null && cached.key == key && !cached.bitmap.isClosed) {
            cached.bitmap
        } else {
            val newBmp = renderLivePreview(w, h, snapshot, theme)
            val old = liveCache.getAndSet(LivePreviewCache(key, newBmp))
            if (old?.bitmap !== newBmp) old?.bitmap?.close()
            newBmp
        }
        sendBitmapToDwm(hwnd, generation, bmp, live = true, w, h)
    }

    /**
     * 小缩略图卡片绘制 (WM_DWMSENDICONICTHUMBNAIL, 基于 Skia 高性能管线):
     * - 书名与章节名均允许最多两行
     * - 文字与图标轻微上移
     * - 进度条上移且不低于封面图片的下边缘
     * - 颜色严格跟随应用全局主题
     */
    private fun renderThumbnailCard(w: Int, h: Int, s: CardSnapshot, theme: ThemeColors): Bitmap {
        val bitmap = allocateBitmap(w, h, "缩略图")
        val canvas = Canvas(bitmap)

        val fillPaint = Paint().apply { isAntiAlias = true }
        val strokePaint = Paint().apply {
            isAntiAlias = true
            mode = PaintMode.STROKE
            strokeWidth = 1f
        }
        val fonts = ArrayList<Font>(3)

        try {
            fillPaint.color = theme.bg
            canvas.drawRect(Rect(0f, 0f, w.toFloat(), h.toFloat()), fillPaint)

            strokePaint.color = theme.border
            canvas.drawRect(Rect(0.5f, 0.5f, w - 0.5f, h - 0.5f), strokePaint)

            val margin = (w * 0.05f).toInt().coerceAtLeast(6)
            val titleSize = (h * 0.14f).toInt().coerceIn(11, 26)
            val subSize = (h * 0.10f).toInt().coerceIn(9, 18)

            val coverSize = (h * 0.72f).toInt().coerceIn(40, 180)
            val coverY = (h - coverSize) / 2
            val coverBottom = coverY + coverSize

            s.cover?.let { coverImg ->
                val src = Rect(0f, 0f, coverImg.width.toFloat(), coverImg.height.toFloat())
                val dst = Rect(margin.toFloat(), coverY.toFloat(), (margin + coverSize).toFloat(), coverBottom.toFloat())
                canvas.drawImageRect(coverImg, src, dst, SamplingMode.MITCHELL, null, true)
                strokePaint.color = theme.coverBorder
                canvas.drawRRect(RRect.makeXYWH(margin.toFloat(), coverY.toFloat(), coverSize.toFloat(), coverSize.toFloat(), 6f, 6f), strokePaint)
            }

            val textX = (if (s.cover != null) margin * 2 + coverSize else margin).toFloat()
            val textW = (w - textX - margin).coerceAtLeast(20f)

            val iconY = (h * 0.16f)
            val titleFont = createFont(boldTypeface, titleSize.toFloat(), fonts)
            val subFont = createFont(normalTypeface, subSize.toFloat(), fonts)

            fillPaint.color = theme.textPrimary
            drawStatusIcon(canvas, textX, iconY, titleSize * 1.2f, s.playing, fillPaint)

            val titleX = textX + titleSize * 1.1f
            val titleFirstBaselineY = iconY + titleSize
            val titleLines = drawWrapped(
                canvas,
                s.title,
                titleX,
                titleFirstBaselineY,
                (textW - titleSize * 1.1f).coerceAtLeast(10f),
                titleSize * 1.18f,
                titleFont,
                fillPaint,
                maxLines = 2,
            )

            val subStartY = iconY + titleSize + (titleLines.coerceAtLeast(1) - 1) * (titleSize * 1.18f) +
                subSize + (h * 0.04f)
            fillPaint.color = theme.textSecondary
            drawWrapped(
                canvas,
                s.subtitle,
                textX,
                subStartY,
                textW,
                subSize * 1.18f,
                subFont,
                fillPaint,
                maxLines = 2,
            )

            if (s.durationMs > 0) {
                val timeSize = (h * 0.09f).toInt().coerceIn(8, 14)
                val timeFont = createFont(normalTypeface, timeSize.toFloat(), fonts)
                val curText = formatTime(s.progressMs)
                val durText = formatTime(s.durationMs)
                val curW = measureTextWidth(timeFont, curText)
                val durW = measureTextWidth(timeFont, durText)
                val gap = (timeSize * 0.6f).coerceAtLeast(6f)
                val barH = 3f

                val barY = if (s.cover != null) {
                    (coverBottom - barH)
                } else {
                    (h - (h * 0.12f).toInt().coerceAtLeast(12)).toFloat()
                }

                val barX = textX + curW + gap
                val barW = (w - textX - margin - curW - durW - gap * 2).coerceAtLeast(10f)
                val textBaseY = barY + barH / 2 - timeFont.metrics.ascent / 2 - 1f

                fillPaint.color = theme.textSecondary
                canvas.drawString(curText, textX, textBaseY, timeFont, fillPaint)
                canvas.drawString(durText, barX + barW + gap, textBaseY, timeFont, fillPaint)

                fillPaint.color = theme.progressTrack
                canvas.drawRRect(RRect.makeXYWH(barX, barY, barW, barH, 2f, 2f), fillPaint)
                val frac = (s.progressMs.toFloat() / s.durationMs).coerceIn(0f, 1f)
                fillPaint.color = theme.progressFill
                canvas.drawRRect(
                    RRect.makeXYWH(
                        barX,
                        barY,
                        (barW * frac).coerceAtLeast(if (frac > 0f) 2f else 0f),
                        barH,
                        2f,
                        2f,
                    ),
                    fillPaint,
                )
            }
        } catch (t: Throwable) {
            canvas.close()
            bitmap.close()
            throw t
        } finally {
            fonts.asReversed().forEach { it.close() }
            fillPaint.close()
            strokePaint.close()
            if (!canvas.isClosed) canvas.close()
        }

        return bitmap
    }

    /**
     * 大窗口 Aero Peek 实时全屏预览绘制 (WM_DWMSENDICONICLIVEPREVIEWBITMAP, 方案 A 居中左右大卡片, 基于 Skia 高性能管线):
     * 屏幕中央黄金居中容器 + 大专辑封面 (45%~50% 屏幕高) + 右侧贴顶信息组 + 贴底粗条进度条 + 动态主题背景色。
     */
    private fun renderLivePreview(w: Int, h: Int, s: CardSnapshot, theme: ThemeColors): Bitmap {
        val bitmap = allocateBitmap(w, h, "实时预览")
        val canvas = Canvas(bitmap)

        val fillPaint = Paint().apply { isAntiAlias = true }
        val strokePaint = Paint().apply {
            isAntiAlias = true
            mode = PaintMode.STROKE
            strokeWidth = 1f
        }
        val fonts = ArrayList<Font>(3)

        try {
            fillPaint.color = theme.bg
            canvas.drawRect(Rect(0f, 0f, w.toFloat(), h.toFloat()), fillPaint)

            strokePaint.color = theme.border
            canvas.drawRect(Rect(0.5f, 0.5f, w - 0.5f, h - 0.5f), strokePaint)

            val maxCoverSize = minOf(600, w / 2, (h - 32).coerceAtLeast(1)).coerceAtLeast(1)
            val coverSize = (h * 0.46f).toInt().coerceIn(minOf(120, maxCoverSize), maxCoverSize)
            val coverRadius = 16f
            val gap = (w * 0.04f).toInt().coerceIn(16, 64)
            val infoW = (w * 0.38f).toInt().coerceIn(200, 720)

            val totalContentW = if (s.cover != null) coverSize + gap + infoW else infoW
            val startX = ((w - totalContentW) / 2).coerceAtLeast(16)
            val startY = ((h - coverSize) / 2).coerceAtLeast(16)

            if (s.cover != null) {
                val src = Rect(0f, 0f, s.cover.width.toFloat(), s.cover.height.toFloat())
                val dst = Rect(startX.toFloat(), startY.toFloat(), (startX + coverSize).toFloat(), (startY + coverSize).toFloat())
                canvas.drawImageRect(s.cover, src, dst, SamplingMode.MITCHELL, null, true)
                strokePaint.color = theme.coverBorder
                canvas.drawRRect(RRect.makeXYWH(startX.toFloat(), startY.toFloat(), coverSize.toFloat(), coverSize.toFloat(), coverRadius, coverRadius), strokePaint)
            }

            val textX = (if (s.cover != null) startX + coverSize + gap else startX).toFloat()
            val textStartY = (if (s.cover != null) startY + 4 else (h * 0.28f).toInt()).toFloat()

            val titleSize = (h * 0.042f).toInt().coerceIn(20, 48)
            val titleLineH = (titleSize * 1.25f)
            val subSize = (titleSize * 0.58f).toInt().coerceIn(14, 26)
            val subLineH = (subSize * 1.25f)
            val timeSize = (titleSize * 0.40f).toInt().coerceIn(12, 20)
            val barH = (h * 0.007f).coerceIn(4f, 8f)

            val titleFont = createFont(boldTypeface, titleSize.toFloat(), fonts)
            val subFont = createFont(normalTypeface, subSize.toFloat(), fonts)

            val iconBarSize = titleSize * 1.15f
            fillPaint.color = theme.textPrimary
            drawStatusIcon(canvas, textX, textStartY, iconBarSize, s.playing, fillPaint)

            val titleX = textX + titleSize * 1.2f
            val titleFirstBaselineY = textStartY + titleSize
            val titleLines = drawWrapped(
                canvas,
                s.title,
                titleX,
                titleFirstBaselineY,
                (infoW - titleSize * 1.2f).coerceAtLeast(20f),
                titleLineH,
                titleFont,
                fillPaint,
                maxLines = 2,
            )

            val subGap = (titleSize * 0.45f).coerceAtLeast(8f)
            val subY = textStartY + titleSize + (titleLines.coerceAtLeast(1) - 1) * titleLineH + subGap + subSize
            fillPaint.color = theme.textSecondary
            val subLines = drawWrapped(
                canvas,
                s.subtitle,
                textX,
                subY,
                infoW.toFloat(),
                subLineH,
                subFont,
                fillPaint,
                maxLines = 2,
            )

            if (s.durationMs > 0) {
                val barY = if (s.cover != null) {
                    (startY + coverSize - barH - 4)
                } else {
                    subY + (subLines.coerceAtLeast(1) - 1) * subLineH + (titleSize * 0.8f)
                }
                val timeFont = createFont(normalTypeface, timeSize.toFloat(), fonts)
                val curText = formatTime(s.progressMs)
                val durText = formatTime(s.durationMs)
                val curW = measureTextWidth(timeFont, curText)
                val durW = measureTextWidth(timeFont, durText)
                val timeGap = (timeSize * 0.8f).coerceAtLeast(8f)
                val barW = (infoW - curW - durW - timeGap * 2).coerceAtLeast(20f)
                val barX = textX + curW + timeGap
                val textBaseY = barY + barH / 2 - timeFont.metrics.ascent / 2 - 1f

                fillPaint.color = theme.textSecondary
                canvas.drawString(curText, textX, textBaseY, timeFont, fillPaint)
                canvas.drawString(durText, barX + barW + timeGap, textBaseY, timeFont, fillPaint)

                fillPaint.color = theme.progressTrack
                canvas.drawRRect(RRect.makeXYWH(barX, barY, barW, barH, barH / 2, barH / 2), fillPaint)
                val frac = (s.progressMs.toFloat() / s.durationMs).coerceIn(0f, 1f)
                fillPaint.color = theme.progressFill
                canvas.drawRRect(
                    RRect.makeXYWH(
                        barX,
                        barY,
                        (barW * frac).coerceAtLeast(if (frac > 0f) barH else 0f),
                        barH,
                        barH / 2,
                        barH / 2,
                    ),
                    fillPaint,
                )
            }
        } catch (t: Throwable) {
            canvas.close()
            bitmap.close()
            throw t
        } finally {
            fonts.asReversed().forEach { it.close() }
            fillPaint.close()
            strokePaint.close()
            if (!canvas.isClosed) canvas.close()
        }

        return bitmap
    }

    private fun createFont(typeface: Typeface?, size: Float, ownedFonts: MutableList<Font>): Font {
        return Font(typeface, size).apply {
            isSubpixel = true
            ownedFonts.add(this)
        }
    }

    private fun drawStatusIcon(
        canvas: Canvas,
        x: Float,
        y: Float,
        barSize: Float,
        playing: Boolean,
        paint: Paint,
    ) {
        if (playing) {
            val barW = (barSize * 0.28f).coerceAtLeast(2f)
            val gap = (barSize * 0.12f).coerceAtLeast(2f)
            canvas.drawRRect(RRect.makeXYWH(x, y, barW, barSize, barW / 2, barW / 2), paint)
            canvas.drawRRect(RRect.makeXYWH(x + barW + gap, y, barW, barSize, barW / 2, barW / 2), paint)
        } else {
            val s = barSize * 0.8f
            val top = y + (barSize - s) / 2
            val path = Path.makeFromSVGString("M $x $top L ${x + s} ${top + s / 2f} L $x ${top + s} Z")
            path.use { p -> canvas.drawPath(p, paint) }
        }
    }

    private fun measureTextWidth(font: Font, text: String): Float {
        return if (text.isEmpty()) 0f else font.measureTextWidth(text)
    }

    private fun drawWrapped(
        canvas: Canvas,
        text: String,
        x: Float,
        firstBaselineY: Float,
        maxWidth: Float,
        lineHeight: Float,
        font: Font,
        paint: Paint,
        maxLines: Int = 2,
    ): Int {
        if (text.isEmpty() || maxWidth <= 0f) return 0
        var line = 0
        var start = 0
        while (start < text.length && line < maxLines) {
            var end = start + 1
            while (end <= text.length && measureTextWidth(font, text.substring(start, end)) <= maxWidth) {
                end++
            }
            if (end > text.length) {
                // 剩余文字全部放得下时，探测游标会越过末尾一位，须收回到合法边界。
                end = text.length
            } else {
                end--
                if (end == start) end = start + 1
            }
            val isLast = line == maxLines - 1
            val hasMore = end < text.length
            val lineStr = if (isLast && hasMore) {
                var s = text.substring(start, end)
                while (s.isNotEmpty() && measureTextWidth(font, "$s…") > maxWidth) {
                    s = s.dropLast(1)
                }
                "$s…"
            } else {
                text.substring(start, end)
            }
            val y = firstBaselineY + line * lineHeight
            canvas.drawString(lineStr, x, y, font, paint)
            line++
            start = end
            if (isLast) break
        }
        return line
    }

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

    // ==================== 位图转换 (Skia Bitmap → Win32 32bpp DIB Section) ====================

    private fun allocateBitmap(w: Int, h: Int, kind: String): Bitmap {
        val bitmap = Bitmap()
        if (!bitmap.allocPixels(ImageInfo.makeN32Premul(w, h))) {
            bitmap.close()
            error("无法分配 DWM $kind 位图 ${w}x$h")
        }
        return bitmap
    }

    private fun toHBitmap(bitmap: Bitmap): WinDef.HBITMAP? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null

        val bmi = WinGDI.BITMAPINFO().apply {
            bmiHeader.biSize = 40
            bmiHeader.biWidth = w
            bmiHeader.biHeight = -h // 自顶向下
            bmiHeader.biPlanes = 1
            bmiHeader.biBitCount = 32
            bmiHeader.biCompression = WinGDI.BI_RGB
        }
        val ppvBits = PointerByReference()
        val hbm = GDI32.INSTANCE.CreateDIBSection(
            null,
            bmi,
            WinGDI.DIB_RGB_COLORS,
            ppvBits,
            null,
            0,
        ) ?: return null

        val ptr = ppvBits.value ?: run {
            GDI32.INSTANCE.DeleteObject(hbm)
            return null
        }

        return try {
            bitmap.peekPixels()?.use { pixmap ->
                val expectedBytes = w * h * 4
                check(pixmap.rowBytes == w * 4 && pixmap.computeByteSize() == expectedBytes) {
                    "Skia 位图布局不连续: rowBytes=${pixmap.rowBytes}, " +
                        "bytes=${pixmap.computeByteSize()}, expected=$expectedBytes"
                }
                pixmap.buffer.use { data ->
                    val bytes = data.bytes
                    check(bytes.size == expectedBytes) {
                        "Skia 位图字节数错误: ${bytes.size}, expected=$expectedBytes"
                    }
                    ptr.write(0L, bytes, 0, bytes.size)
                }
            } ?: run {
                GDI32.INSTANCE.DeleteObject(hbm)
                return null
            }
            hbm
        } catch (t: Throwable) {
            GDI32.INSTANCE.DeleteObject(hbm)
            AppLog.put("CreateDIBSection 像素写入失败", t)
            null
        }
    }

    // ==================== 封面加载 ====================

    private fun loadCover(url: String, hwnd: WinDef.HWND) {
        val loader = io.legado.app.help.image.BookImageLoaders.getOrNull()
            ?: return onCoverLoadFailed(url)
        val imageBitmap = kotlinx.coroutines.runBlocking {
            loader.loadCoverOrNull(url, AudioPlayShared.book?.origin)
        } ?: return onCoverLoadFailed(url)

        runCatching {
            val skiaImage = Image.makeFromBitmap(imageBitmap.asSkiaBitmap())
            var previousCover: Image? = null
            val accepted = synchronized(coverStateLock) {
                // 网络加载可能晚于切歌或会话结束完成，旧请求不得覆盖当前卡片。
                if (lastCoverUrl != url || mainHwnd?.pointer != hwnd.pointer || !iconicEnabled) {
                    false
                } else {
                    previousCover = currentSnapshot.getAndUpdate { it.copy(cover = skiaImage) }.cover
                    coverFailUrl = null
                    coverFailCount = 0
                    true
                }
            }
            if (!accepted) {
                skiaImage.close()
                return@runCatching
            }
            renderExecutor.execute {
                previousCover?.close()
                if (lastCoverUrl == url && mainHwnd?.pointer == hwnd.pointer && iconicEnabled) {
                    runCatching { doRender(live = true) }
                        .onFailure { AppLog.put("DWM 实时预览渲染失败", it) }
                }
            }
            invalidateIconicBitmaps(hwnd)
        }.onFailure {
            AppLog.put("DWM 卡片封面转换失败", it)
        }
    }

    private fun onCoverLoadFailed(url: String) {
        synchronized(coverStateLock) {
            if (coverFailUrl != url) {
                coverFailUrl = url
                coverFailCount = 0
            }
            coverFailCount++
            if (coverFailCount <= COVER_MAX_RETRY && lastCoverUrl == url) lastCoverUrl = null
        }
    }

    // ==================== DWM API ====================

    private fun setIconicMode(hwnd: WinDef.HWND, enable: Boolean) {
        runCatching {
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
        val now = System.currentTimeMillis()
        if (now - lastInvalidateAt < INVALIDATE_MIN_INTERVAL_MS) return
        lastInvalidateAt = now
        runCatching {
            val hr = DwmApi.dwmapi?.DwmInvalidateIconicBitmaps(hwnd) ?: return@runCatching
            if (hr != 0) {
                AppLog.put("DWM 缓存失效返回失败 hr=0x${hr.toUInt().toString(16)}")
            }
        }.onFailure { AppLog.put("DWM 缓存失效失败", it) }
    }
}
