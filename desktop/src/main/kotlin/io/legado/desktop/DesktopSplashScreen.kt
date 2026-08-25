package io.legado.desktop

import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.config.resolveImagePath
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
import javax.imageio.ImageIO
import javax.swing.JWindow

/**
 * 桌面端启动闪屏 (AWT JWindow, 无边框, 居中显示)。
 *
 * 对照 app 端 WelcomeActivity 的行为:
 * - enableWelcome 关闭时不显示闪屏, 直接进入主窗口
 * - welcomeShowTime 控制闪屏时长 (ms), 默认 600 (设置对话框范围 600..3000);
 *   <=0 不显示 (兼容历史遗留值, 旧版曾允许存 0)
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
 * 闪屏在主窗口 [application] 的 Window 创建前显示, 主窗口可见后由 [close] 关闭。
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

    /** 基准画布 (内容布局/字号/图标尺寸的参照系), 实际窗口按屏幕比例缩放。 */
    companion object {
        const val BASE_WIDTH = 800
        const val BASE_HEIGHT = 480

        /** 闪屏窗口尺寸: 屏幕宽高一半 (≥400x300, 低分辨率屏如 1024x768 也能完整显示)。 */
        fun splashSize(): java.awt.Dimension {
            val screen = java.awt.Toolkit.getDefaultToolkit().screenSize
            return java.awt.Dimension(
                (screen.width / 2).coerceAtLeast(400),
                (screen.height / 2).coerceAtLeast(300),
            )
        }
    }

    /**
     * 显示闪屏。在 EDT 调用。
     * 返回闪屏持续时间 (ms); 0 = 不显示 (配置关闭或时长为 0)。
     */
    fun show(): Long {
        // enableWelcome 开关 (默认 true)
        if (!prefs.getBoolean(PreferKey.enableWelcome, true)) return 0
        // 闪屏时长 (默认 600ms, 设置对话框限定 600..3000)
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

        // 尺寸规则见 [splashSize] (选图裁剪共用)
        val width = splashSize().width
        val height = splashSize().height
        val scale = minOf(
            width.toFloat() / BASE_WIDTH,
            height.toFloat() / BASE_HEIGHT,
        ).coerceAtMost(1f)

        val window = JWindow()
        // 无控制栏不可拖动 (JWindow 本身无边框)
        val size = Dimension(width, height)
        window.size = size
        window.minimumSize = size
        window.maximumSize = size  // 锁定尺寸, 防止任何意外 resize
        window.background = bgRgb
        window.contentPane.background = bgRgb

        // 背景图: 产物优先 (选图时已烘焙); 缺失直解原图保显示 (EDT 不做烘焙),
        // 同时后台补烘焙落盘供下次秒读 —— 与壁纸产物/启动图渲染端冷路径同构
        var bgImage: BufferedImage? = null
        if (bgImagePath != null) {
            val baked = bakedImagePath(bgImagePath)
            val displayPath = if (java.io.File(baked).exists()) baked else {
                val screen = java.awt.Toolkit.getDefaultToolkit().screenSize
                Thread {
                    // 补烘焙复用全端冷路径 ensureBakedImage (屏幕尺寸, WEBP q80, 不放大)
                    runCatching { ensureBakedImage(bgImagePath, screen.width, screen.height) }
                }.apply { isDaemon = true }.start()
                bgImagePath
            }
            bgImage = runCatching {
                // 产物是 WEBP 字节 (ImageIO 直读, TwelveMonkeys imageio-webp SPI)
                java.io.File(displayPath).takeIf { it.exists() }?.let { ImageIO.read(it) }
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

        // 居中
        val screen = java.awt.Toolkit.getDefaultToolkit().screenSize
        window.setLocation(
            (screen.width - width) / 2,
            (screen.height - height) / 2,
        )
        window.isAlwaysOnTop = true
        window.isVisible = true
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
        private val bgImage: BufferedImage?,
        private val width: Int,
        private val height: Int,
        private val scale: Float,
    ) : javax.swing.JPanel() {

        init {
            isOpaque = true
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
                // 背景图或纯色
                if (bgImage != null) {
                    // 有意偏离原版：原版拉伸铺满会变形，改为中心裁切保持宽高比
                    val imgW = bgImage.width.coerceAtLeast(1)
                    val imgH = bgImage.height.coerceAtLeast(1)
                    val coverScale = maxOf(
                        width.toDouble() / imgW,
                        height.toDouble() / imgH,
                    )
                    // 目标尺寸向上取整保证完全覆盖窗口（不留发丝缝），
                    // 左上角居中偏移可为负，出界部分由绘制裁剪自动丢弃
                    val drawW = Math.ceil(imgW * coverScale).toInt()
                    val drawH = Math.ceil(imgH * coverScale).toInt()
                    val drawX = (width - drawW) / 2
                    val drawY = (height - drawH) / 2
                    g2d.drawImage(bgImage, drawX, drawY, drawW, drawH, null)
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
                    val icon = runCatching {
                        // 与 app 端共用同一份 icon_read_book.png (desktop sourceSets 挂载 drawable-nodpi)
                        Thread.currentThread().contextClassLoader
                            ?.getResourceAsStream("icon_read_book.png")?.use { ImageIO.read(it) }
                    }.getOrNull()
                    if (icon != null) {
                        val iconX = halfWidth + (halfWidth - iconSize) / 2
                        val iconY = (height - iconSize) / 2
                        val tinted = tintImage(icon, accentColor)
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
