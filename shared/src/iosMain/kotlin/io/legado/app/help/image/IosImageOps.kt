@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.help.image

import io.legado.app.utils.Base64Lenient
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.NSLog
import platform.Foundation.create
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext

/**
 * iOS 端 [ImageOps] 真实实现 (KP4)。
 *
 * # 存在意义
 * [io.legado.app.model.script.JsBindings] 构造时访问 [io.legado.app.model.script.JsBindingInjector.image],
 * 未注册会 checkNotNull 失败, 导致任何 `JsEngine.eval` 都跑不了。
 * iOS 端 JS 引擎 ([io.legado.app.model.script.IosJsEngine]) 解阻塞必须先注册一个 [ImageOps] 实现。
 *
 * # 当前实现 (KP4 真实化, 基于 UIKit `UIImage` + `UIGraphics` 图形上下文)
 *
 * 内部句柄 [IosImageRef] 持有 [UIImage] (替代原 P0 的 ByteArray 占位),
 * 所有像素操作走 UIKit/UIKit Graphics API:
 *
 * - [decode(bytes)]: `UIImage(data:)` 解码 JPEG/PNG/WebP/HEIC 等格式 (iOS 系统解码能力);
 *   失败抛 [IllegalArgumentException] (与 [ImageOps.decode] 契约一致: "失败抛异常")
 * - [decode(base64)]: 复用 commonMain [Base64Lenient.decode] (容忍 `data:image/...;base64,` 前缀)
 *   后走 [decode] (ByteArray)
 * - [encode]: `UIImagePNGRepresentation` (PNG 无损) / `UIImageJPEGRepresentation(quality/100.0)`
 *   真实编码; webp iOS 不支持编码, 抛异常 (与 desktop DesktopImageOps 一致, JDK ImageIO 也不支持 webp 编码)
 * - [split]: 按行优先切分, 每个 cell 用 [renderSubImage] 裁剪; 余数并入最后一行/列
 *   (算法与 desktop DesktopImageOps.split 一致)
 * - [stitch]: 创建大尺寸 `UIGraphics` 图形上下文, 逐个 `UIImage.drawInRect` 平铺
 *   (对照 desktop BufferedImage + Graphics.drawImage)
 * - [crop]: [renderSubImage] 裁剪 (x,y) 起点的 w×h 区域
 * - [size]: 读 `UIImage.size` (decode 来的图 scale=1.0, size 即像素尺寸)
 *
 * # UIGraphics 裁剪技巧 (renderSubImage)
 * [renderSubImage] 用 `UIGraphicsBeginImageContextWithOptions(CGSizeMake(w,h), false, 1.0)`
 * 创建 w×h 的位图上下文, 然后把原图按原尺寸画到偏移 (-x,-y) 的位置:
 * ```
 *   UIGraphicsBeginImageContextWithOptions(CGSizeMake(w, h), false, 1.0)
 *   image.drawInRect(CGRectMake(-x, -y, fullW, fullH))  // 平移, 不缩放
 *   val sub = UIGraphicsGetImageFromCurrentImageContext()
 *   UIGraphicsEndImageContext()
 * ```
 * 上下文只截取 (0,0,w,h) 区域, 等价裁剪原图 (x,y,w,h); 比直接调
 * `CGImageCreateWithImageInRect` 更高层 (不依赖 CGImageRef C 指针, 规避 cinterop 类型不确定性)。
 *
 * # 影响
 * - 书源 JS 中 `image.decode/encode/split/stitch/crop/size` 在 iOS 上全部真实工作,
 *   真实像素操作 (切分/裁剪/格式转换) 可用 (替代原 P0 降级占位)
 * - 与 desktop DesktopImageOps 行为对齐 (算法/契约一致, 仅平台 API 不同)
 *
 * # 替代原 P0 降级占位 (KP3)
 * 原 P0 占位 [encode]/[split]/[stitch]/[crop]/[size] 返回原图/空 map (不抛异常让 JS 调用链不崩),
 * 本次 KP4 替换为真实 UIKit 实现。
 *
 * 参考: desktop [io.legado.desktop.image.DesktopImageOps] (BufferedImage + ImageIO, 算法参考);
 * base64 解码复用 commonMain [Base64Lenient] (iosMain actual 已实现);
 * UIKit 桥接模式参考 [io.legado.app.ui.bookshelf.IosBookCover] (UIImage + UIImagePNGRepresentation + NSData.create)。
 *
 * # macOS 编译验证 (Windows 无法编译 iOS target)
 * ```
 * ./gradlew :shared:compileKotlinIosArm64
 * ./gradlew :shared:compileKotlinIosSimulatorArm64
 * ```
 */
object IosImageOps : ImageOps {

    /**
     * iOS 端 [ImageRef] 真实实现: 持有 [UIImage]。
     *
     * [UIImage] 是 iOS 系统级图片对象 (封装 CGImage + 解码缓存),
     * split/crop/stitch/encode 全部基于 UIImage 完成, encode 才输出字节。
     * 由 ARC 管理生命周期 (Kotlin/Native 对 ObjC 对象引用计数自动管理)。
     */
    private class IosImageRef(val image: UIImage) : ImageRef

    override fun decode(bytes: ByteArray): ImageRef {
        // UIImage(data:) 解码 JPEG/PNG/WebP/HEIC 等 (iOS 系统解码能力, 与 IosBookCover 验证图片有效性一致)
        // 失败返回 nil -> 抛异常 (与 ImageOps.decode 契约一致: "失败抛异常")
        val nsData = bytes.toNSData()
        val image = UIImage(data = nsData)
            ?: throw IllegalArgumentException("image.decode: 无法解码图片字节(${bytes.size} bytes)")
        return IosImageRef(image)
    }

    override fun decode(base64: String): ImageRef {
        // 用 Base64Lenient.decode 解码 (容忍 data:image/...;base64, 前缀, 与 ImageOps.decode 契约一致)
        // Base64Lenient: commonMain expect + iosMain actual 已实现, 对齐 jvmAndAndroid Base64.getMimeDecoder
        val bytes = Base64Lenient.decode(base64)
        return decode(bytes)
    }

    override fun encode(img: ImageRef, format: String, quality: Int): ByteArray {
        // 真实编码: UIImagePNGRepresentation (PNG 无损) / UIImageJPEGRepresentation (quality 0-1.0)
        // webp iOS 不支持编码 (与 desktop DesktopImageOps 注释一致, JDK ImageIO 也不支持 webp 编码)
        val image = uiImageOf(img)
        val q = quality.coerceIn(0, 100) / 100.0
        val nsData: NSData? = when (format.lowercase()) {
            "png" -> UIImagePNGRepresentation(image)
            "jpg", "jpeg" -> UIImageJPEGRepresentation(image, q)
            "webp" -> throw IllegalArgumentException("image.encode: iOS 暂不支持 webp 编码, 仅 png/jpg")
            else -> throw IllegalArgumentException("image.encode: 不支持的格式 $format, 仅 png/jpg/jpeg/webp")
        }
        return nsData?.toByteArray()
            ?: throw IllegalStateException("image.encode: 编码失败 format=$format")
    }

    override fun split(img: ImageRef, rows: Int, cols: Int): List<ImageRef> {
        // 行优先切分 (先左→右再上→下), 余数并入最后一行/列 (与 desktop DesktopImageOps.split 算法一致)
        val image = uiImageOf(img)
        require(rows > 0 && cols > 0) { "image.split: rows/cols 必须为正数($rows,$cols)" }
        val (width, height) = image.size.useContents { width.toInt() to height.toInt() }
        require(cols <= width && rows <= height) {
            "image.split: 切块数超过像素尺寸(${width}x${height} / ${rows}行${cols}列)"
        }
        val cellW = width / cols
        val cellH = height / rows
        val out = ArrayList<ImageRef>(rows * cols)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                // 最后一个 cell 并入余数 (与 desktop 一致, 避免丢像素)
                val w = if (c == cols - 1) width - cellW * c else cellW
                val h = if (r == rows - 1) height - cellH * r else cellH
                val sub = renderSubImage(
                    image,
                    (c * cellW).toDouble(),
                    (r * cellH).toDouble(),
                    w.toDouble(),
                    h.toDouble(),
                )
                out.add(IosImageRef(sub))
            }
        }
        return out
    }

    override fun stitch(imgs: List<ImageRef>, direction: String): ImageRef {
        // 创建大尺寸 UIGraphics 上下文 + 逐个 drawInRect 平铺 (对照 desktop BufferedImage + Graphics.drawImage)
        require(imgs.isNotEmpty()) { "image.stitch: imgs 为空" }
        val horizontal = when (direction.lowercase()) {
            "h" -> true
            "v" -> false
            else -> throw IllegalArgumentException("image.stitch: direction 仅支持 h/v, 收到 $direction")
        }
        val images = imgs.map { uiImageOf(it) }
        val sizes = images.map { it.size.useContents { width.toInt() to height.toInt() } }
        val width = if (horizontal) sizes.sumOf { it.first } else sizes.maxOf { it.first }
        val height = if (horizontal) sizes.maxOf { it.second } else sizes.sumOf { it.second }
        UIGraphicsBeginImageContextWithOptions(CGSizeMake(width.toDouble(), height.toDouble()), false, 1.0)
        try {
            var offset = 0
            for ((img, sz) in images.zip(sizes)) {
                val (w, h) = sz
                // 按 direction 平铺: h 水平左→右累加 width, v 垂直上→下累加 height
                val rect = if (horizontal) {
                    CGRectMake(offset.toDouble(), 0.0, w.toDouble(), h.toDouble())
                } else {
                    CGRectMake(0.0, offset.toDouble(), w.toDouble(), h.toDouble())
                }
                img.drawInRect(rect)
                offset += if (horizontal) w else h
            }
            val result = UIGraphicsGetImageFromCurrentImageContext()
                ?: throw IllegalStateException("image.stitch: UIGraphicsGetImageFromCurrentImageContext 返回 nil")
            return IosImageRef(result)
        } finally {
            UIGraphicsEndImageContext()
        }
    }

    override fun crop(img: ImageRef, x: Int, y: Int, w: Int, h: Int): ImageRef {
        // 用 renderSubImage 裁剪 (x,y) 起点的 w×h 区域
        val image = uiImageOf(img)
        return IosImageRef(renderSubImage(image, x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble()))
    }

    override fun size(img: ImageRef): Map<String, Int> {
        // UIImage.size 返回 points; decode 来的图 scale=1.0, size 即像素尺寸
        // (与 desktop DesktopImageOps.size 返回 mapOf("w","h") 的 key 一致)
        val image = uiImageOf(img)
        return image.size.useContents {
            mapOf("w" to width.toInt(), "h" to height.toInt())
        }
    }

    // ===== 内部辅助 =====

    /**
     * 渲染原图 [image] 的 (x,y,w,h) 区域为独立 [UIImage] (用于 [split]/[crop])。
     *
     * 用 `UIGraphicsBeginImageContextWithOptions(CGSizeMake(w,h), false, 1.0)` 创建 w×h 位图上下文,
     * 把原图按原尺寸画到偏移 (-x,-y) (平移不缩放), 上下文只截取 (0,0,w,h) -> 等价裁剪原图 (x,y,w,h)。
     *
     * 比直接调 `CGImageCreateWithImageInRect` 更高层 (不依赖 CGImageRef C 指针, 规避 cinterop 类型不确定性)。
     * scale=1.0 确保输出 UIImage 的 size 即像素尺寸 (无 retina 缩放)。
     */
    private fun renderSubImage(image: UIImage, x: Double, y: Double, w: Double, h: Double): UIImage {
        val fullW = image.size.useContents { width }
        val fullH = image.size.useContents { height }
        UIGraphicsBeginImageContextWithOptions(CGSizeMake(w, h), false, 1.0)
        return try {
            // 原图按原尺寸画到偏移 (-x,-y): 上下文 (0,0,w,h) 截取原图 (x,y,w,h) 区域
            image.drawInRect(CGRectMake(-x, -y, fullW, fullH))
            UIGraphicsGetImageFromCurrentImageContext()
                ?: throw IllegalStateException("image: UIGraphicsGetImageFromCurrentImageContext 返回 nil")
        } finally {
            UIGraphicsEndImageContext()
        }
    }

    /** 取出 [ImageRef] 内持的 [UIImage], 非本类创建的抛异常 (与 desktop bufferedImageOf 一致)。 */
    private fun uiImageOf(ref: Any?): UIImage {
        return (ref as? IosImageRef)?.image
            ?: throw IllegalArgumentException(
                "image: 参数不是 image.decode/split/stitch/crop 返回的 ImageRef(${ref?.toString()})"
            )
    }

    /**
     * NSData → ByteArray 转换 (Kotlin/Native 无标准库扩展, 自行实现)。
     *
     * 用 [NSData.bytes] (CPointer) + [kotlinx.cinterop.readBytes] 拷贝到 ByteArray,
     * 与 [io.legado.app.ui.bookshelf.IosBookCover] 的 NSData.toByteArray 实现一致。
     */
    private fun NSData.toByteArray(): ByteArray {
        val size = this.length.toInt()
        if (size == 0) return ByteArray(0)
        return this.bytes?.readBytes(size) ?: ByteArray(0)
    }

    /** ByteArray → NSData (`NSData.create(bytes:length:)` 只收 CPointer, 需先 pin)。 */
    private fun ByteArray.toNSData(): NSData {
        if (isEmpty()) return NSData()
        return usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
        }
    }
}
