package io.legado.app.utils

import com.github.weisj.jsvg.parser.LoaderContext
import com.github.weisj.jsvg.parser.SVGLoader
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * JVM 端 SVG 栅格化, 对照 Android 端 `io.legado.app.utils.SvgUtils` (androidsvg)。
 *
 * 用 jsvg (纯 Java Java2D 渲染器, 无 native, 比 Batik 轻) 把 SVG 字节渲染成 PNG,
 * 供只认栅格图的 ImageIO / Coil3 消费。
 */
object SvgRasterizer {

    /** 渲染长边上限, 防超大 viewBox 撑爆内存。 */
    private const val MAX_EDGE = 2048

    /** SVGLoader 非线程安全, 每次调用新建。 */
    private fun load(bytes: ByteArray) = runCatching {
        SVGLoader().load(ByteArrayInputStream(bytes), null, LoaderContext.createDefault())
    }.getOrNull()

    /**
     * SVG 字节 → PNG 字节; 非 SVG 或渲染失败返回 null。
     *
     * 目标尺寸取文档自身尺寸, 长边超 [MAX_EDGE] 时等比缩小
     * (对照 SvgUtils.createBitmap 只缩不放的语义)。
     */
    fun toPng(bytes: ByteArray, maxEdge: Int = MAX_EDGE): ByteArray? = runCatching {
        val document = load(bytes) ?: return null
        val size = document.size()
        val srcW = size.width.takeIf { it > 0f } ?: return null
        val srcH = size.height.takeIf { it > 0f } ?: return null
        val ratio = minOf(1f, maxEdge / maxOf(srcW, srcH))
        val w = (srcW * ratio).toInt().coerceAtLeast(1)
        val h = (srcH * ratio).toInt().coerceAtLeast(1)

        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g.scale((w / srcW).toDouble(), (h / srcH).toDouble())
            document.render(null, g)
        } finally {
            g.dispose()
        }
        ByteArrayOutputStream().use { out ->
            if (!ImageIO.write(image, "png", out)) return null
            out.toByteArray()
        }
    }.getOrNull()
}
