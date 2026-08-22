package io.legado.app.help.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Data

import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.impl.use

/**
 * [decodeAnimatedFrames] 的 Desktop (jvm) Skiko 实现, 仅桌面解码动图;
 * Android/iOS/OHOS 各走平台图片管线 (静态首帧退化, 见 expect 处的平台实现清单)。
 *
 * 解码策略 (对照 Skia `SkCodec` 多帧语义):
 * - 单个复用 [Bitmap] 逐帧 `readPixels(bitmap, i, priorFrame)` 递进解码: GIF 帧多为增量 (仅重绘
 *   局部矩形), 传 priorFrame 让 Skia 在上一帧画面上就地叠加, 省掉每帧全量重绘
 * - 每帧解完立刻 `makeFromBitmap` 快照成独立 [ImageBitmap] (makeFromBitmap 对可变 Bitmap 会拷贝
 *   像素, 故复用同一 Bitmap 不会污染已产出帧), 因此全程只分配 1 个解码 Bitmap 而非 N 个
 * - `requiredFrame`/`disposalMethod` 交由 Skia 内部处理: priorFrame 传上一帧索引即满足其契约
 *   (帧不可增量复用时 Skia 自行回退全量解码)
 *
 * 退化为 null (调用方转静态首帧) 的情形: 帧数 <= 1、像素预算超 [MAX_ANIMATED_PIXELS]、
 * 任一帧解码抛异常 (Codec 对残缺流会抛 IllegalArgumentException)。
 *
 * 资源释放: [Codec] 与解码用 [Bitmap] 在函数返回前 close, 产出帧已是独立快照, 不持有原生句柄。
 */
internal actual fun decodeAnimatedFrames(bytes: ByteArray): AnimatedFrames? = runCatching {
    if (bytes.isEmpty()) return null
    val data = Data.makeFromBytes(bytes)
    val codec = try {
        Codec.makeFromData(data)
    } finally {
        data.close()
    }
    codec.use { c ->
        val frameCount = c.frameCount
        if (frameCount <= 1) return null
        val info = c.imageInfo
        if (info.width <= 0 || info.height <= 0) return null
        // 像素预算: 超限直接放弃动画, 避免大尺寸长 GIF 预解码占满堆
        if (info.width.toLong() * info.height * frameCount > MAX_ANIMATED_PIXELS) return null

        // GIF 帧间需要透明叠加, 强制 PREMUL (Codec 报的 alphaType 对不透明首帧可能是 OPAQUE,
        // 直接沿用会让后续含透明的增量帧混合错位)
        val decodeInfo = ImageInfo.makeN32(info.width, info.height, ColorAlphaType.PREMUL)
        val frames = ArrayList<ImageBitmap>(frameCount)
        val durations = IntArray(frameCount)
        // 单个复用 Bitmap: 逐帧就地递进, 快照后即可覆写下一帧
        val bitmap = Bitmap()
        try {
            if (!bitmap.allocPixels(decodeInfo)) return null
            for (i in 0 until frameCount) {
                // priorFrame = i-1: 允许 Skia 在上一帧结果上增量叠加 (首帧 -1 表示无前置帧)
                c.readPixels(bitmap, i, i - 1)
                frames.add(org.jetbrains.skia.Image.makeFromBitmap(bitmap).use { it.toComposeImageBitmap() })
                // duration 为 0 的帧 (部分 GIF 未声明) 按浏览器惯例兜底 100ms, 否则会全速空转
                durations[i] = c.getFrameInfo(i).duration.takeIf { it > 0 } ?: DEFAULT_FRAME_DURATION_MS
            }
        } finally {
            bitmap.close()
        }
        AnimatedFrames(frames, durations, c.repetitionCount)
    }
}.getOrNull()

/** 未声明 duration 的帧兜底时长 (对齐浏览器对 0/过小延时的处理惯例)。 */
private const val DEFAULT_FRAME_DURATION_MS = 100
