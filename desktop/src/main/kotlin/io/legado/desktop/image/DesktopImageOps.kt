package io.legado.desktop.image

import io.legado.app.help.image.ImageOps
import io.legado.app.help.image.ImageRef
import java.awt.Graphics
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * 桌面 JVM 端 [ImageOps] 实现 (KP1.1)。
 *
 * # 为什么需要
 * [io.legado.app.model.script.JsBindings] 构造时统一注入 `platform` / `image`,
 * `image` 走 [io.legado.app.model.script.JsBindingInjector.registerImageOps] 注入。
 * JsBindings init 调 `JsBindingInjector.image` getter, 未注册时 `checkNotNull` 抛异常,
 * 导致任何 `JsEngine.eval(js, bindingsConfig)` 都无法执行 (因为内部 new JsBindings())。
 *
 * 桌面端冒烟测试至少要能跑 JS, 故必须注册一个 [ImageOps] 实现, 哪怕 KP1 阶段不实际用 image 解密。
 *
 * # 实现
 * 内持 `java.awt.image.BufferedImage`, 用 `javax.imageio.ImageIO` 编解码 (JDK 内置, 无外部依赖)。
 * 对应 app 端 `BitmapImageOps` 内持 `android.graphics.Bitmap`。
 *
 * # 覆盖范围
 * KP1 仅做基础解码/编码/split/stitch/crop/size, 满足 JS `image.*` API 契约即可。
 * 复杂场景 (如 base64 含 data:image/...;base64, 前缀) 已处理, 与 BitmapImageOps 行为对齐。
 */
object DesktopImageOps : ImageOps {

    private class BufferedImageRef(val image: BufferedImage) : ImageRef

    override fun decode(bytes: ByteArray): ImageRef {
        val image = ImageIO.read(ByteArrayInputStream(bytes))
            ?: throw IllegalArgumentException("image.decode: 无法解码图片字节(${bytes.size} bytes)")
        return BufferedImageRef(image)
    }

    override fun decode(base64: String): ImageRef {
        // 容忍 `data:image/...;base64,` 前缀, 与 BitmapImageOps 行为一致
        val payload = base64.substringAfter("base64,", base64)
        return decode(Base64.getDecoder().decode(payload))
    }

    override fun encode(img: ImageRef, format: String, quality: Int): ByteArray {
        val formatName = when (format.lowercase()) {
            "png" -> "png"
            "jpg", "jpeg" -> "jpg"
            "webp" -> "webp" // JDK 内置 ImageIO 不支持 webp 编码, 会抛 IIOException; 桌面端暂不支持
            else -> throw IllegalArgumentException("image.encode: 不支持的格式 $format，仅 png/jpg/webp")
        }
        val out = ByteArrayOutputStream()
        val image = bufferedImageOf(img)
        // 注意: javax.imageio 不直接支持 quality 参数 (需 ImageWriter + ImageWriteParam),
        // 桌面端冒烟阶段用默认质量, 后续 KP2 若有需要再补 quality 控制
        if (!ImageIO.write(image, formatName, out)) {
            throw IllegalStateException("image.encode: ImageIO.write 失败 format=$formatName")
        }
        return out.toByteArray()
    }

    override fun split(img: ImageRef, rows: Int, cols: Int): List<ImageRef> {
        val image = bufferedImageOf(img)
        require(rows > 0 && cols > 0) { "image.split: rows/cols 必须为正数($rows,$cols)" }
        require(cols <= image.width && rows <= image.height) {
            "image.split: 切块数超过像素尺寸(${image.width}x${image.height} / ${rows}行${cols}列)"
        }
        val cellW = image.width / cols
        val cellH = image.height / rows
        val out = ArrayList<ImageRef>(rows * cols)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val w = if (c == cols - 1) image.width - cellW * c else cellW
                val h = if (r == rows - 1) image.height - cellH * r else cellH
                out.add(BufferedImageRef(image.getSubimage(cellW * c, cellH * r, w, h)))
            }
        }
        return out
    }

    override fun stitch(imgs: List<ImageRef>, direction: String): ImageRef {
        val images = imgs.map { bufferedImageOf(it) }
        require(images.isNotEmpty()) { "image.stitch: imgs 为空" }
        val horizontal = when (direction.lowercase()) {
            "h" -> true
            "v" -> false
            else -> throw IllegalArgumentException("image.stitch: direction 仅支持 h/v，收到 $direction")
        }
        val width = if (horizontal) images.sumOf { it.width } else images.maxOf { it.width }
        val height = if (horizontal) images.maxOf { it.height } else images.sumOf { it.height }
        val result = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g: Graphics = result.graphics
        try {
            var offset = 0
            for (b in images) {
                if (horizontal) {
                    g.drawImage(b, offset, 0, null)
                    offset += b.width
                } else {
                    g.drawImage(b, 0, offset, null)
                    offset += b.height
                }
            }
        } finally {
            g.dispose()
        }
        return BufferedImageRef(result)
    }

    override fun crop(img: ImageRef, x: Int, y: Int, w: Int, h: Int): ImageRef {
        val image = bufferedImageOf(img)
        return BufferedImageRef(image.getSubimage(x, y, w, h))
    }

    override fun size(img: ImageRef): Map<String, Int> {
        val image = bufferedImageOf(img)
        return mapOf("w" to image.width, "h" to image.height)
    }

    private fun bufferedImageOf(ref: Any?): BufferedImage {
        return (ref as? BufferedImageRef)?.image
            ?: throw IllegalArgumentException(
                "image: 参数不是 image.decode/split/stitch/crop 返回的 ImageRef(${ref?.javaClass?.name})"
            )
    }
}
