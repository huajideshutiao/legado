package io.legado.desktop

import androidx.compose.ui.graphics.toAwtImage
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.config.resolveImagePath
import io.legado.app.help.image.decodeBytesSampled
import io.legado.app.model.bakedImagePath
import io.legado.app.model.ensureBakedImage
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.swing.JWindow
import javax.swing.SwingUtilities
import kotlin.math.ceil

/**
 * 桌面端启动闪屏 (AWT JWindow, 无边框, 居中显示)。
 *
 * 对照 app 端 WelcomeActivity 的行为:
 * - enableWelcome 关闭时不显示闪屏, 直接进入主窗口
 * - welcomeShowTime 控制闪屏时长 (ms), 默认 600; 存什么用什么, 四端读取路径都不做区间钳制
 *   (设置界面的数值选择器只能限定"能从界面里选出哪些值", 不参与改写已有存储值);
 *   <=0 不显示 (与 app 端 WelcomeActivity 的 `if (delayMs > 0)` 同语义)
 * - 白天/夜间各有独立的 showText/showIcon 开关 (welcomeShowText/Dark, welcomeShowIcon/Dark)
 * - 白天/夜间各有独立的背景图 (welcomeImage/welcomeImageDark)
 * - 主题色 (accent) 从 DesktopThemeStoreProvider 读取
 *
 * 图形元素对照原版 activity_welcome.xml: 下方书本图标 @drawable/icon_read_book
 * (与 app 端共用同一源文件, 经 desktop sourceSets 挂载), 染 accent 色
 * (原版 WelcomeActivity: binding.ivBook.setColorFilter(accentColor))。
 *
 * 布局为左右并排 (用户拍板): 左半文字「阅读」+ 副标题, 右半书本图标。
 * 尺寸取屏幕宽高的一半 (低分辨率屏幕也能完整容纳), 内容按基准比例缩放。
 *
 * 闪屏在主窗口 [application] 的 Window 创建前显示; 主窗口放行可见之前由 [close] 先撤掉
 * (同一次交接里先撤后显, 两者不同框), 见 Main.kt 的 reveal。
 *
 * @param themeStore 主题色提供者 (已完成初始化); 偏好读取走 [PreferenceProviders] 单例
 *   (须已注册 DesktopPreferenceProvider)
 */
class DesktopSplashScreen(
    private val themeStore: DesktopThemeStoreProvider,
) {
    private val prefs get() = PreferenceProviders.get()
    private var splashWindow: JWindow? = null
    private var showDurationMs: Long = 0L

    /** 闪屏实际置为可见的时刻 (未显示为 0), 供调用方算"已驻留多久"决定主窗口的放行时机。 */
    var shownAtMs: Long = 0L
        private set

    /** 自闪屏显示起算的毫秒数; 尚未显示时返 [Long.MAX_VALUE] (语义: 已远超任何驻留时长)。 */
    fun elapsedSinceShow(): Long =
        if (shownAtMs == 0L) Long.MAX_VALUE else System.currentTimeMillis() - shownAtMs

    /** 基准画布 (内容布局/字号/图标尺寸的参照系), 实际窗口按屏幕比例缩放。 */
    companion object {
        const val BASE_WIDTH = 800
        const val BASE_HEIGHT = 480

        /**
         * 闪屏窗口尺寸: 屏幕宽高一半 (≥400x300, 低分辨率屏如 1024x768 也能完整显示)。
         *
         * 入参是屏幕尺寸而不是自己读屏幕: 屏幕尺寸要走 `Toolkit.getDefaultToolkit().screenSize`
         * (一次原生显示设备枚举 + 显示模式/DPI 查询, 不是纳秒级), 而闪屏显示路径必须"读一次,
         * 窗口尺寸与居中都用它" —— 所以纯计算单独成函数, 不必为了拿尺寸再碰一次 Toolkit。
         * 分两次读还有个正确性问题: 多块屏分辨率不同的机器上, 尺寸与居中可能落在不同屏的几何上。
         */
        fun splashSizeFor(screen: Dimension): Dimension = Dimension(
            (screen.width / 2).coerceAtLeast(400),
            (screen.height / 2).coerceAtLeast(300),
        )
    }

    /**
     * 显示闪屏。由启动主线程 (CMP `application` 首组合) 同步调用。
     *
     * 本方法内部不做 invokeAndWait/invokeLater, 所以它不是"排队等 EDT": 耗时全部落在调用线程上;
     * 调用线程到底是不是 EDT 由段内归因打点在线判定 (见方法末尾那条 mark)。
     * 返回闪屏持续时间 (ms); 0 = 不显示 (配置关闭或时长为 0)。
     */
    fun show(): Long {
        // enableWelcome 开关 (默认 true)
        if (!prefs.getBoolean(PreferKey.enableWelcome, true)) return 0
        // 闪屏时长 (默认 600ms): 原样取存储值, 不钳制 —— 主窗口的出现时机也直接挂在这个数上
        // (见 Main.kt 的 revealDelay), 所以它大了就是真的等那么久, 不再有上界兜底。
        val timeMs = prefs.getInt(PreferKey.welcomeShowTime, 600)
        if (timeMs <= 0) return 0
        showDurationMs = timeMs.toLong()

        val isDark = themeStore.isDark
        val accent = themeStore.accentColor
        val bgColor = themeStore.backgroundColor
        val showText = prefs.getBoolean(
            if (isDark) PreferKey.welcomeShowTextDark else PreferKey.welcomeShowText, true
        )
        val showIcon = prefs.getBoolean(
            if (isDark) PreferKey.welcomeShowIconDark else PreferKey.welcomeShowIcon, true
        )
        val bgImagePath = resolveImagePath(
            prefs.getString(
                if (isDark) PreferKey.welcomeImageDark else PreferKey.welcomeImage
            )
        )?.takeUnless { it.isBlank() }

        val accentRgb = Color(
            (accent.red * 255).toInt(),
            (accent.green * 255).toInt(),
            (accent.blue * 255).toInt(),
        )
        val bgRgb = Color(
            (bgColor.red * 255).toInt(),
            (bgColor.green * 255).toInt(),
            (bgColor.blue * 255).toInt(),
        )

        // 屏幕尺寸只读这一次: 下面窗口尺寸、居中、缺产物时的补烘焙目标尺寸全复用它。
        // 原实现 splashSize() 调两次 + 居中一次 + 补烘焙一次, 每次都是一轮原生显示设备枚举
        // 与显示模式/DPI 查询。
        val screen = java.awt.Toolkit.getDefaultToolkit().screenSize
        // 尺寸规则见 [splashSizeFor]
        val size = splashSizeFor(screen)
        val width = size.width
        val height = size.height
        val scale = minOf(
            width.toFloat() / BASE_WIDTH,
            height.toFloat() / BASE_HEIGHT,
        ).coerceAtMost(1f)

        val window = JWindow()
        // 无控制栏不可拖动 (JWindow 本身无边框)
        window.size = size
        window.minimumSize = size
        window.maximumSize = size  // 锁定尺寸, 防止任何意外 resize
        window.background = bgRgb
        window.contentPane.background = bgRgb

        // 背景图: 产物优先 (选图时已烘焙); 缺失时不卡 EDT 硬解原图 (对齐 Android 端 WelcomeActivity
        // upBackgroundImage), 先展示主题纯色闪屏, 由后台线程现场重烘焙+解码后回填重绘
        var bgImage: BufferedImage? = null
        val bakedPath = bgImagePath?.let { bakedImagePath(it) }
        val bakedFile = bakedPath?.let { java.io.File(it) }
        val bakedExists = bakedFile?.exists() == true

        if (bakedExists && bakedFile != null) {
            // 常态: 产物在, 直接采样解码 (与 Android 端一致, 快速)
            bgImage = runCatching {
                decodeBytesSampled(bakedFile.readBytes(), 0)?.toAwtImage()
            }.getOrNull()
        }

        window.contentPane.layout = null
        val content = SplashContent(
            showText = showText,
            showIcon = showIcon,
            accentColor = accentRgb,
            backgroundColor = bgRgb,
            bgImage = bgImage,
            width = width,
            height = height,
            scale = scale,
        )
        window.contentPane.add(content)
        content.bounds = java.awt.Rectangle(0, 0, width, height)

        if (bgImagePath != null && !bakedExists) {
            // 冷路径 (缓存被清/跨端同步首启): 后台重烘焙 + 解码, 不卡首帧
            Thread({
                runCatching {
                    val baked = ensureBakedImage(bgImagePath, screen.width, screen.height) ?: bgImagePath
                    val file = java.io.File(baked).takeIf { it.exists() } ?: return@runCatching
                    val image = decodeBytesSampled(file.readBytes(), 0)?.toAwtImage() ?: return@runCatching
                    SwingUtilities.invokeLater {
                        if (splashWindow === window) {
                            content.bgImage = image
                            content.repaint()
                        }
                    }
                }
            }, "splash-bake-bg").apply {
                isDaemon = true
                start()
            }
        }

        // 居中 (screen 用上方那一次读取, 不再二次枚举显示设备)
        window.setLocation(
            (screen.width - width) / 2,
            (screen.height - height) / 2,
        )
        window.isAlwaysOnTop = true
        window.isVisible = true
        shownAtMs = System.currentTimeMillis()
        splashWindow = window
        return showDurationMs
    }

    /**
     * 关闭闪屏。必须在 EDT 调用。幂等。
     */
    fun close() {
        splashWindow?.let { w ->
            w.isVisible = false
            w.dispose()
        }
        splashWindow = null
    }

    /** 闪屏内容绘制 (自定义 JPanel, 用 paintComponent 画文字/图标/背景图)。 */
    private class SplashContent(
        private val showText: Boolean,
        private val showIcon: Boolean,
        private val accentColor: Color,
        private val backgroundColor: Color,
        @Volatile var bgImage: BufferedImage?,
        private val width: Int,
        private val height: Int,
        private val scale: Float,
    ) : javax.swing.JPanel() {

        init {
            isOpaque = true
        }

        /**
         * 染色后的书本图标 (首绘时解码一次, 之后复用)。
         *
         * 为什么要缓存: 原实现把"读 classpath 里的 PNG → Skia 解码 → 新建一张 ARGB 位图染色"
         * 放在 paintComponent 里, 而 paintComponent 跑在图形绘制线程上; 闪屏驻留 600~3000ms,
         * 期间任何一次重绘 (被其他窗口遮蔽后 expose、系统刷新主题等) 都会把这三步重做一遍,
         * 既拖后"真正出现像素"的时间, 也在启动期反复分配大对象。染色结果与本次绘制的 iconSize
         * 无关 (缩放在 drawImage 里做), 所以按实例算一次即可。
         * 保留原有"取不到/解不了返 null 则不画图标"的行为 (未改动异常处理口径)。
         */
        private val tintedIcon: BufferedImage? by lazy {
            runCatching {
                // 与 app 端共用同一份 icon_read_book.png (desktop sourceSets 挂载 drawable-nodpi)
                val decoded = Thread.currentThread().contextClassLoader
                    ?.getResourceAsStream("icon_read_book.png")
                    ?.use { decodeBytesSampled(it.readBytes(), 0) }?.toAwtImage()
                decoded?.let { tintImage(it, accentColor) }
            }.getOrNull()
        }

        override fun paintComponent(g: Graphics) {
            val g2d = g.create() as Graphics2D
            try {
                g2d.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
                )
                g2d.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON
                )
                // 背景图或纯色 (背景图可能被烘焙线程回填, 先取本地引用再判空, 避免并发改动使智能转换失效)
                val bg = bgImage
                if (bg != null) {
                    // 有意偏离原版：原版拉伸铺满会变形，改为中心裁切保持宽高比
                    val imgW = bg.width.coerceAtLeast(1)
                    val imgH = bg.height.coerceAtLeast(1)
                    val coverScale = maxOf(
                        width.toDouble() / imgW,
                        height.toDouble() / imgH,
                    )
                    // 目标尺寸向上取整保证完全覆盖窗口（不留发丝缝），
                    // 左上角居中偏移可为负，出界部分由绘制裁剪自动丢弃
                    val drawW = ceil(imgW * coverScale).toInt()
                    val drawH = ceil(imgH * coverScale).toInt()
                    val drawX = (width - drawW) / 2
                    val drawY = (height - drawH) / 2
                    g2d.drawImage(bg, drawX, drawY, drawW, drawH, null)
                } else {
                    g2d.color = backgroundColor
                    g2d.fillRect(0, 0, width, height)
                }

                // 左右排布 (用户拍板): 左半部分文字, 右半部分图标
                val halfWidth = width / 2

                // ============ 左侧: 文字「阅读」+ 副标题 ============
                if (showText) {
                    val fontSize = (64 * scale).toInt().coerceAtLeast(24)
                    g2d.color = accentColor
                    g2d.font = Font(Font.SANS_SERIF, Font.BOLD, fontSize)
                    val fontMetrics = g2d.fontMetrics
                    // 横排「阅读」居中于左半区域
                    val text = "阅读"
                    val textW = fontMetrics.stringWidth(text)
                    val textX = (halfWidth - textW) / 2
                    val textY = height / 2 - fontMetrics.height / 2 + fontMetrics.ascent
                    g2d.drawString(text, textX, textY)

                    // 副标题「享受美好时光」横排, 位于「阅读」下方
                    val subFontSize = (18 * scale).toInt().coerceAtLeast(10)
                    g2d.font = Font(Font.SANS_SERIF, Font.PLAIN, subFontSize)
                    val subFm = g2d.fontMetrics
                    val subText = "享受美好时光"
                    val subW = subFm.stringWidth(subText)
                    val subX = (halfWidth - subW) / 2
                    val subY = textY + (40 * scale).toInt()
                    g2d.drawString(subText, subX, subY)

                    // 左侧竖线装饰 (「阅读」左侧)
                    val lineX = textX - (16 * scale).toInt()
                    val lineHeight = fontMetrics.height
                    g2d.fillRect(
                        lineX,
                        textY - fontMetrics.ascent + (4 * scale).toInt(),
                        (6 * scale).toInt().coerceAtLeast(3),
                        (lineHeight - (8 * scale).toInt()).coerceAtLeast(8),
                    )
                }

                // ============ 右侧: 书本图标 (对照原版 @drawable/icon_read_book, 染 accent 色) ============
                if (showIcon) {
                    val iconSize = (140 * scale).toInt().coerceAtLeast(64)
                    val tinted = tintedIcon
                    if (tinted != null) {
                        val iconX = halfWidth + (halfWidth - iconSize) / 2
                        val iconY = (height - iconSize) / 2
                        g2d.drawImage(tinted, iconX, iconY, iconSize, iconSize, null)
                    }
                }
            } finally {
                g2d.dispose()
            }
        }

        /** 将图片染为目标颜色 (原版 WelcomeActivity setColorFilter(accentColor) 同语义)。 */
        private fun tintImage(image: BufferedImage, color: Color): BufferedImage {
            val result = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
            val g = result.createGraphics()
            try {
                g.drawImage(image, 0, 0, null)
                g.composite = java.awt.AlphaComposite.SrcIn
                g.color = color
                g.fillRect(0, 0, result.width, result.height)
            } finally {
                g.dispose()
            }
            return result
        }
    }
}
