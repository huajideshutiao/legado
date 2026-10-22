package io.legado.app.ui.book.audio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import io.legado.app.help.image.BookImageLoaders
import io.legado.app.model.AudioPlayShared
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.ColorUtils
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 色相接近到这个度数以内视为同一路颜色 */
private const val GLOW_SAME_HUE_DEGREES = 20f

/** 无封面 (取色未就绪/加载失败) 时的光效配色: 由主题强调色延伸, 不出现无光的空档 */
@Composable
internal fun rememberFallbackGlowColors(): List<Color> {
    val accent = AppTheme.colors.accent
    return remember(accent) { glowColorsOf(emptyList(), accent) }
}

// ==================== 封面取色 (复刻原版 getRepresentativeColor + updateLrcColor) ====================

/** 色相分桶数 (每桶 22.5°): 关键色按色相聚簇, 再按像素占比取前几路 */
private const val KEY_COLOR_BUCKETS = 16

/** 光效最多取几路封面关键色 (四边各一路) */
private const val MAX_KEY_COLORS = 4

/** 色簇至少占总有效像素的比例, 低于此视为噪点 */
private const val KEY_COLOR_MIN_SHARE = 0.04f

/**
 * 边缘光效配色与歌词配色 (同源, 一次采样算齐)。
 *
 * @param lrcPrimary 歌词主色 (已唱/高亮), 同时是 SeekBar 已播层颜色
 * @param lrcSecondary 歌词次色 (未唱)
 * @param glowColors 边缘光效四路颜色 (左/上/右/下)
 */
internal class AudioCoverPalette(
    val lrcPrimary: Color,
    val lrcSecondary: Color,
    val glowColors: List<Color>,
)

/** 封面取色统计: 平均代表色 + 主要色簇 */
private class CoverColorStats(val mean: Color, val keyColors: List<Color>)

/**
 * 封面取色 (复刻原版 `Bitmap.getRepresentativeColor`, BitmapUtils.kt):
 * - 缩放到 64px 最长边 (加载时已按 64px 请求, 这里的采样步长兜住更大的图)
 * - 过滤: alpha < 128 跳过; HSL 饱和度 <0.1 或 亮度 <0.1 或 >0.9 跳过
 * - 平均 RGB 作代表色; 同时按色相分桶累计, 取占比最大的几桶作关键色
 *
 * 一次性整块读回 (逐行 readPixels 在安卓端对 HARDWARE 位图每次都要整图拷贝), 通道走位运算,
 * HSL 复用同一个 FloatArray —— 与原版逐行等价且采样过程零分配。
 */
private fun ImageBitmap.coverColorStats(): CoverColorStats {
    val pixels = IntArray(width * height)
    readPixels(pixels)
    val step = max(1, (max(width, height) / 64f).roundToInt())
    val hsl = FloatArray(3)
    var rSum = 0L
    var gSum = 0L
    var bSum = 0L
    var count = 0
    val bucketR = LongArray(KEY_COLOR_BUCKETS)
    val bucketG = LongArray(KEY_COLOR_BUCKETS)
    val bucketB = LongArray(KEY_COLOR_BUCKETS)
    val bucketCount = IntArray(KEY_COLOR_BUCKETS)
    var y = 0
    while (y < height) {
        val rowStart = y * width
        var x = 0
        while (x < width) {
            val pixel = pixels[rowStart + x]
            if ((pixel ushr 24) >= 128) { // 原版 (pixel shr 24) and 0xFF >= 128
                val r = ColorUtils.red(pixel)
                val g = ColorUtils.green(pixel)
                val b = ColorUtils.blue(pixel)
                ColorUtils.RGBToHSL(r, g, b, hsl)
                if (hsl[1] >= 0.1f && hsl[2] >= 0.1f && hsl[2] <= 0.9f) {
                    rSum += r
                    gSum += g
                    bSum += b
                    count++
                    val bucket = (hsl[0] * KEY_COLOR_BUCKETS / 360f).toInt()
                        .coerceIn(0, KEY_COLOR_BUCKETS - 1)
                    bucketR[bucket] += r
                    bucketG[bucket] += g
                    bucketB[bucket] += b
                    bucketCount[bucket]++
                }
            }
            x += step
        }
        y += step
    }
    // 全是黑白/透明时取中心像素 (复刻原版 pixels[size / 2])
    if (count == 0) {
        val center = Color(pixels[pixels.size / 2])
        return CoverColorStats(center, listOf(center))
    }
    val mean = Color(
        ColorUtils.argb((rSum / count).toInt(), (gSum / count).toInt(), (bSum / count).toInt())
    )
    val minShare = count * KEY_COLOR_MIN_SHARE
    val keyColors = (0 until KEY_COLOR_BUCKETS)
        .filter { bucketCount[it] >= minShare }
        .sortedByDescending { bucketCount[it] }
        .take(MAX_KEY_COLORS)
        .map { i ->
            Color(
                ColorUtils.argb(
                    (bucketR[i] / bucketCount[i]).toInt(),
                    (bucketG[i] / bucketCount[i]).toInt(),
                    (bucketB[i] / bucketCount[i]).toInt(),
                )
            )
        }
    return CoverColorStats(mean, keyColors)
}

/**
 * 复刻原版 `AudioPlayActivity.updateLrcColor`:
 * - meanColor → HSL; isLight = L > 0.6
 * - secondary: light → L-0.45 (下限 0.3); dark → L+0.45 (上限 0.7)
 * - primary: 基于 secondary 的 L 再 ±0.35 (下限 0.2 / 上限 0.8)
 * - 输出不透明色 (HSLToColor), 供歌词 setColors + SeekBar tint
 */
private fun adjustLrcColors(meanColor: Color): Pair<Color, Color> {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(meanColor.toArgb(), hsl)
    val l = hsl[2]
    val isLight = l > 0.6f
    val secondaryL = if (isLight) (l - 0.45f).coerceAtLeast(0.3f)
    else (l + 0.45f).coerceAtMost(0.7f)
    hsl[2] = secondaryL
    val secondary = Color(ColorUtils.HSLToColor(hsl))
    hsl[2] = if (isLight) (secondaryL - 0.35f).coerceAtLeast(0.2f)
    else (secondaryL + 0.35f).coerceAtMost(0.8f)
    val primary = Color(ColorUtils.HSLToColor(hsl))
    return primary to secondary
}

/** 把任意封面色规范成适合叠在暗背景上发光、又不至于糊成一片的色调 */
private fun Color.glowTone(): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), hsl)
    hsl[1] = hsl[1].coerceIn(0.35f, 0.95f)
    hsl[2] = hsl[2].coerceIn(0.50f, 0.82f)
    return Color(ColorUtils.HSLToColor(hsl))
}

/** 沿色相环偏移 (度): 由主歌词色延伸出相邻色 */
private fun Color.hueShifted(degrees: Float): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), hsl)
    hsl[0] = (hsl[0] + degrees + 360f) % 360f
    return Color(ColorUtils.HSLToColor(hsl))
}

/** 两个颜色的色相距离 (度), 取环上较近一侧 */
private fun Color.hueDistance(other: Color): Float {
    val a = FloatArray(3)
    val b = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), a)
    ColorUtils.colorToHSL(other.toArgb(), b)
    val diff = abs(a[0] - b[0])
    return if (diff > 180f) 360f - diff else diff
}

/**
 * 四路光效配色: 优先取封面关键色 (按像素占比排序); 不足四路时由主歌词色沿色相环
 * 依次延伸补齐, 单色封面也能让整圈转起来时看得出变化。
 */
private fun glowColorsOf(keyColors: List<Color>, primary: Color): List<Color> {
    val tones = ArrayList<Color>(MAX_KEY_COLORS)
    fun add(color: Color) {
        if (tones.size >= MAX_KEY_COLORS) return
        val tone = color.glowTone()
        if (tones.none { it.hueDistance(tone) < GLOW_SAME_HUE_DEGREES }) tones += tone
    }
    keyColors.forEach(::add)
    // 不足四路时由主歌词色沿色相环等距补足 (不再去重: 本就是从同一主色延伸出的相邻色)
    var shift = 30f
    while (tones.size < MAX_KEY_COLORS) {
        tones += primary.hueShifted(shift).glowTone()
        shift += 30f
    }
    return tones
}

/**
 * 封面 → 歌词配色 + 边缘光效配色 (原版 updateCover → updateLrcColor 链路, 全端共享)。
 *
 * 封面 URL 变化时经 [BookImageLoaders] 加载封面 (只请求 64px 级别的小图, 磁盘缓存键只按 url,
 * 不会多下载一次), 一次采样同时算出平均代表色与关键色。
 * 未注册 loader / 加载失败 / 无封面 → null (调用方回落原版默认色)。
 *
 * keep-previous: 加载期间保留旧配色, 新色算出才换 (原版 updateLrcColor 无中间态, 不会先闪回默认色);
 * 但换到无封面/加载失败的书要回落默认色, 不能沿用上一本的配色 (同 SharedAudioCoverSlot 的语义)。
 */
@Composable
internal fun rememberAudioCoverPalette(
    coverUrl: String?,
    sourceOrigin: String? = null,
): AudioCoverPalette? {
    var palette by remember { mutableStateOf<AudioCoverPalette?>(null) }
    // 书源来源不是快照状态, 不拿它当 effect 键 (换书时不会更新); 进入效果时读当前值
    LaunchedEffect(coverUrl) {
        val origin = sourceOrigin ?: AudioPlayShared.book?.origin
        val bitmap = if (coverUrl.isNullOrBlank()) null else BookImageLoaders.getOrNull()
            ?.loadCoverOrNull(coverUrl, origin, widthPx = 64, heightPx = 64)
        palette = bitmap?.let { bmp ->
            withContext(Dispatchers.Default) {
                val stats = bmp.coverColorStats()
                val (primary, secondary) = adjustLrcColors(stats.mean)
                AudioCoverPalette(
                    lrcPrimary = primary,
                    lrcSecondary = secondary,
                    glowColors = glowColorsOf(stats.keyColors, primary),
                )
            }
        }
    }
    return palette
}
