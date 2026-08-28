package io.legado.app.help.image

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 已解码的动图帧表 (逐帧 [ImageBitmap] + 每帧展示时长)。
 *
 * 帧全部预解码为独立快照, 构造完成后底层解码器已释放, 故本类无需 dispose。
 *
 * @param frames 按序帧位图 (已完成 GIF 帧间混合, 每帧都是可直接绘制的完整画面)
 * @param durationsMs 各帧展示毫秒数, 长度与 [frames] 一致
 * @param repetitionCount 循环次数 (-1 无限循环; 0 只播一轮; n>0 播 n+1 轮, 对齐 GIF 89a 语义)
 */
class AnimatedFrames(
    val frames: List<ImageBitmap>,
    val durationsMs: IntArray,
    val repetitionCount: Int,
) {
    val frameCount: Int get() = frames.size
}

/**
 * 解码动图字节为帧表; 非动图 (单帧) / 解码失败 / 超出内存预算时返回 null (调用方退化为静态图)。
 *
 * 平台实现:
 * - skikoUiMain (desktop/iOS/鸿蒙): [org.jetbrains.skia.Codec] 逐帧解码 GIF 和 WebP
 * - androidMain: 返回 null (Android 走 coil3-gif, 消费点自带动图能力, 不经本路径)
 */
internal expect fun decodeAnimatedFrames(bytes: ByteArray): AnimatedFrames?

/**
 * 动图帧解码的像素预算 (帧数 x 宽 x 高)。超预算退化静态首帧, 防超大 GIF 预解码打爆内存。
 * 16M 像素 ≈ 64MB (N32 每像素 4 字节), 足够覆盖表情/插图级 GIF。
 */
internal const val MAX_ANIMATED_PIXELS = 16_000_000L

/**
 * 判定字节流是否为 GIF (魔数 `GIF87a` / `GIF89a` 的公共前缀 "GIF8")。
 *
 * 只认 GIF: 其余格式即便 Skia 能多帧解码 (如动画 WebP) 也不在本批范围, 避免静态图白跑一趟 Codec。
 */
internal fun isGifBytes(bytes: ByteArray?): Boolean {
    if (bytes == null || bytes.size < 4) return false
    return bytes[0] == 'G'.code.toByte() &&
        bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte() &&
        bytes[3] == '8'.code.toByte()
}

/** GIF/WebP 编码头候选，静态图进入 Codec 后会自然退化为普通位图。 */
internal fun isAnimatedImageBytes(bytes: ByteArray?): Boolean {
    if (bytes == null) return false
    val gif = bytes.size >= 4 && bytes[0] == 'G'.code.toByte() &&
        bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() &&
        bytes[3] == '8'.code.toByte()
    val webp = bytes.size >= 12 && bytes[0] == 'R'.code.toByte() &&
        bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() &&
        bytes[3] == 'F'.code.toByte() && bytes[8] == 'W'.code.toByte() &&
        bytes[9] == 'E'.code.toByte() && bytes[10] == 'B'.code.toByte() &&
        bytes[11] == 'P'.code.toByte()
    return gif || webp
}
/**
 * 动图字节 → 随时间自动推进的当前帧 [ImageBitmap]; 非动图 / 解码失败返回 null。
 *
 * 解码在 [Dispatchers.Default] 完成 (CPU 密集, 不占主线程), 推进循环按各帧自身 duration 挂起,
 * 离开组合时随 [LaunchedEffect] 一并取消。返回值随帧推进触发重组, 调用方直接当普通位图画。
 *
 * @param bytes 原始图片字节 (null 或非 GIF/WebP 直接返回 null)
 */
@Composable
fun rememberAnimatedImageBitmap(bytes: ByteArray?): ImageBitmap? {
    val imageBytes = if (isAnimatedImageBytes(bytes)) bytes else null
    val frames by produceState<AnimatedFrames?>(null, imageBytes) {
        value = imageBytes?.let { withContext(Dispatchers.Default) { decodeAnimatedFrames(it) } }
    }
    val animated = frames ?: return null
    if (animated.frameCount <= 1) return animated.frames.firstOrNull()

    var index by remember(animated) { mutableIntStateOf(0) }
    LaunchedEffect(animated) {
        var i = 0
        var loop = 0
        while (true) {
            delay(animated.durationsMs[i].toLong().coerceAtLeast(1L))
            i++
            if (i >= animated.frameCount) {
                i = 0
                loop++
                // repetitionCount: -1 无限; n>=0 表示首轮之外再播 n 轮, 播完停在末帧
                if (animated.repetitionCount >= 0 && loop > animated.repetitionCount) {
                    index = animated.frameCount - 1
                    break
                }
            }
            index = i
        }
    }
    return animated.frames.getOrNull(index) ?: animated.frames.firstOrNull()
}
