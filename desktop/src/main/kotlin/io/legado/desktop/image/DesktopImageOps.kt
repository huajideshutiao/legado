package io.legado.desktop.image

import io.legado.app.help.image.ImageOps
import io.legado.app.help.image.ImageRef
import io.legado.app.ui.compose.platform.jvmGetString
import java.awt.Color
import java.awt.Graphics
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * 桌面 JVM 端 [ImageOps] 实现。
 *
 * # 为什么需要
 * [io.legado.app.model.script.JsBindings] 构造时统一注入 `platform` / `image`,
 * `image` 走 [io.legado.app.model.script.JsBindingInjector.registerImageOps] 注入。
 * JsBindings init 调 `JsBindingInjector.image` getter, 未注册时 `checkNotNull` 抛异常,
 * 导致任何 `JsEngine.eval(js, bindingsConfig)` 都无法执行 (因为内部 new JsBindings())。
 *
 * 桌面端冒烟测试至少要能跑 JS, 故必须注册一个 [ImageOps] 实现。
 *
 * # 实现
 * 内持 `java.awt.image.BufferedImage`, 用 `javax.imageio.ImageIO` 编解码。
 * webp 编解码靠 `com.github.gotson:webp-imageio` 的 ImageIO SPI (classpath 即生效)。
 * 对应 app 端 `BitmapImageOps` 内持 `android.graphics.Bitmap`。
 * jpg 编码时透明区铺黑底 (与 app 端 Bitmap.compress(JPEG) 的透明转黑行为一致)。
 *
 * # 覆盖范围
 * 仅做基础解码/编码/split/stitch/crop/size, 满足 JS `image.*` API 契约即可。
 * 复杂场景 (如 base64 含 data:image/...;base64, 前缀) 已处理, 与 BitmapImageOps 行为对齐。
 */
object DesktopImageOps : ImageOps {

    private class BufferedImageRef(val image: BufferedImage) : ImageRef

    override fun decode(bytes: ByteArray): ImageRef {
        val image = ImageIO.read(ByteArrayInputStream(bytes))
            ?: throw IllegalArgumentException(jvmGetString("image_decode_failed_bytes", bytes.size))
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
            // webp 编码由 com.github.gotson:webp-imageio 的 ImageIO SPI 提供 (jar 内置各平台 native)
            "webp" -> "webp"
            else -> throw IllegalArgumentException(jvmGetString("image_encode_unsupported_format", format))
        }
        val out = ByteArrayOutputStream()
        // webp native writer 只认 INT_RGB/INT_ARGB 光栅, jpg 不支持 alpha, 均需先归一化
        val image = normalizeForWrite(bufferedImageOf(img), formatName)
        // quality 映射: jpg 用 ImageWriter + JPEG ImageWriteParam 压缩比 (0..1),
        // 与 app 端 Bitmap.compress(JPEG, quality) 语义一致; png/webp 无质量概念忽略
        // (app 端 PNG 同样忽略 quality), webp writer 不支持压缩参数时走默认质量
        if (!writeWithQuality(image, formatName, quality, out)) {
            throw IllegalStateException(jvmGetString("image_encode_write_failed", formatName))
        }
        return out.toByteArray()
    }

    /**
     * ImageIO.write 不支持 quality 参数, 这里改用 ImageWriter + ImageWriteParam:
     * jpg 设 MODE_EXPLICIT + compressionQuality = quality/100; 其余格式用默认参数。
     */
    private fun writeWithQuality(
        image: BufferedImage,
        formatName: String,
        quality: Int,
        out: ByteArrayOutputStream,
    ): Boolean {
        val writers = ImageIO.getImageWritersByFormatName(formatName)
        if (!writers.hasNext()) return false
        val writer = writers.next()
        try {
            val param = writer.defaultWriteParam
            if (formatName == "jpg" && param.canWriteCompressed()) {
                param.compressionMode = ImageWriteParam.MODE_EXPLICIT
                param.compressionQuality = quality.coerceIn(0, 100) / 100f
            }
            writer.output = ImageIO.createImageOutputStream(out)
            writer.write(null, IIOImage(image, null, null), param)
        } finally {
            writer.dispose()
        }
        return true
    }

    /** 按目标格式把图转成 writer 能接受的 raster 类型; 已匹配时原样返回。 */
    private fun normalizeForWrite(image: BufferedImage, formatName: String): BufferedImage {
        val target = when (formatName) {
            "jpg" -> BufferedImage.TYPE_INT_RGB
            "webp" -> BufferedImage.TYPE_INT_ARGB
            else -> return image // png writer 通吃, 不必转换
        }
        if (image.type == target) return image
        val copy = BufferedImage(image.width, image.height, target)
        val g = copy.createGraphics()
        try {
            if (target == BufferedImage.TYPE_INT_RGB) {
                // jpg 无 alpha 通道, 透明区先铺黑底再绘制
                // (与 app 端 Bitmap.compress(JPEG) 的透明转黑行为、漫画阅读界面黑底一致)
                g.color = Color.BLACK
                g.fillRect(0, 0, image.width, image.height)
            }
            g.drawImage(image, 0, 0, null)
        } finally {
            g.dispose()
        }
        return copy
    }

    override fun split(img: ImageRef, rows: Int, cols: Int): List<ImageRef> {
        val image = bufferedImageOf(img)
        require(rows > 0 && cols > 0) { jvmGetString("image_split_rows_cols_positive", rows, cols) }
        require(cols <= image.width && rows <= image.height) {
            jvmGetString("image_split_exceeds_size", image.width, image.height, rows, cols)
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
        require(images.isNotEmpty()) { jvmGetString("image_stitch_imgs_empty") }
        val horizontal = when (direction.lowercase()) {
            "h" -> true
            "v" -> false
            else -> throw IllegalArgumentException(jvmGetString("image_stitch_direction_invalid", direction))
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

    override fun rotate(img: ImageRef, deg: Int): ImageRef {
        val image = bufferedImageOf(img)
        val angle = deg % 360
        if (angle == 0) return BufferedImageRef(image)
        // 旋转后外接矩形尺寸 (任意角度; 90/180/270 时精确)
        val rad = angle * PI / 180.0
        val cos = abs(cos(rad))
        val sin = abs(sin(rad))
        val w = ceil(image.width * cos + image.height * sin).toInt()
        val h = ceil(image.width * sin + image.height * cos).toInt()
        val out = BufferedImage(w, h, image.type)
        val g = out.createGraphics()
        try {
            // Graphics2D 正值顺时针 (y 向下, 与 app 端 Matrix / ios UIKit 视觉一致):
            // 平移至新画布中心 → 旋转 → 用 AffineTransform 平移到原图左上角 → 绘制
            g.translate(w / 2.0, h / 2.0)
            g.rotate(rad)
            g.drawImage(
                image,
                AffineTransform.getTranslateInstance(-image.width / 2.0, -image.height / 2.0),
                null,
            )
        } finally {
            g.dispose()
        }
        return BufferedImageRef(out)
    }

    override fun flip(img: ImageRef, direction: String): ImageRef {
        val image = bufferedImageOf(img)
        val out = BufferedImage(image.width, image.height, image.type)
        val g = out.createGraphics()
        try {
            // 以中心为轴镜像: 平移到中心 → 负缩放 → 平移回左上角 → 绘制
            g.translate(image.width / 2.0, image.height / 2.0)
            when (direction.lowercase()) {
                "h" -> g.scale(-1.0, 1.0)
                "v" -> g.scale(1.0, -1.0)
                else -> throw IllegalArgumentException("image.flip: direction 仅支持 h/v，收到 $direction")
            }
            g.translate(-image.width / 2.0, -image.height / 2.0)
            g.drawImage(image, 0, 0, null)
        } finally {
            g.dispose()
        }
        return BufferedImageRef(out)
    }

    override fun size(img: ImageRef): Map<String, Int> {
        val image = bufferedImageOf(img)
        return mapOf("w" to image.width, "h" to image.height)
    }

    private fun bufferedImageOf(ref: Any?): BufferedImage {
        return (ref as? BufferedImageRef)?.image
            ?: throw IllegalArgumentException(
                jvmGetString("image_ref_type_invalid", ref?.javaClass?.name)
            )
    }
}
