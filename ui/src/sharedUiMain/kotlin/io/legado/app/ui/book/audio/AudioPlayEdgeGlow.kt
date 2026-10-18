package io.legado.app.ui.book.audio

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.legado.app.help.image.BookImageLoaders
import io.legado.app.model.AudioPlayShared
import io.legado.app.model.audio.AudioSpectrum
import io.legado.app.ui.compose.platform.LocalOverlayTopInset
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.LocalEInk
import io.legado.app.utils.ColorUtils
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

// ==================== 页面边缘光效 ====================

/** 波浪曲线沿边的采样点数 (越多越平滑) */
private const val SAMPLES = 32

/** 向内衰减的层数: 每层是同一波浪曲线向内缩进, 叠加出渐变 (层间是平行曲线, 不是台阶) */
private const val LAYERS = 6

/** 各层不透明度 (从贴边向内递减, 长度必须等于 [LAYERS]) */
private val LAYER_ALPHA = floatArrayOf(0.85f, 0.62f, 0.42f, 0.26f, 0.14f, 0.06f)

/** 整圈颜色渐变的色标数 (越多过渡越顺) */
private const val COLOR_STOPS = 24

/** 光带厚度基准 (各点在此基础上按频段起伏) */
private val GLOW_BASE_THICKNESS = 17.dp

/** 强度上限 (贴边处的不透明度) */
private const val GLOW_MAX_ALPHA = 0.85f

/** 各点厚度起伏: 频段 0 → 0.7 倍, 频段 1 → 1.3 倍 */
private const val THICKNESS_MIN_SCALE = 0.7f
private const val THICKNESS_MAX_SCALE = 1.3f

/** 颜色沿边缘流动一周的时间 (ms) */
private const val FLOW_PERIOD_MS = 16000f

/** 慢层 (段落响度) 的时间常数 (ms): 比歌词辉光的快层慢一个数量级 */
private const val SLOW_ATTACK_MS = 900f
private const val SLOW_RELEASE_MS = 1400f

/** 整体淡出时间常数 (ms): 比点亮慢, 但要在两秒内收干净 */
private const val FADE_OUT_MS = 380f

/** 整体淡入时间常数 (ms): 起播时快速亮起 */
private const val FADE_IN_MS = 120f

/** 淡出到这个值以下直接归零 (避免指数衰减留下永远擦不干净的薄光) */
private const val FADE_STOP_THRESHOLD = 0.02f

/** 低于此不透明度不画 (省掉无意义的绘制调用) */
private const val ALPHA_EPS = 0.004f

/** 色相接近到这个度数以内视为同一路颜色 */
private const val GLOW_SAME_HUE_DEGREES = 20f

private val glowClockStart = TimeSource.Monotonic.markNow()

/** 单调时钟 (ms): 与播放位置无关, 只用来判断能量管道是否还在供数 */
private fun glowNowMs(): Int = glowClockStart.elapsedNow().inWholeMilliseconds.toInt()

/**
 * 音频播放页的边缘光效: 四周一圈随频谱起伏、颜色沿边缘流动的光带。
 *
 * - 沿边缘各段的亮度与厚度由多频段幅度 ([AudioPlayShared.audioBands]) 分别决定 ——
 *   低频、人声、高频点亮不同位置, 所以边缘高度参差、不是整圈一起明暗。
 * - 整圈底亮度再叠一层慢平滑 (段落渐强), 时间常数比歌词辉光的快层慢一个数量级,
 *   两者不会同涨同落。
 * - 颜色按四路封面主色沿边缘环向插值, 相位随时间推进 → 整圈颜色缓慢流动 (约 16 秒一周)。
 * - 每段是一条从贴边向内淡出的连续渐变 (中段减半再归零), 没有层与层的接缝;
 *   段间高度与颜色都按频段/相位插值, 不出现柱子状的硬边。
 * - 四边同等对待 (不单独削弱顶边)。
 * - 绘制期只读运行态: 按 Compose 阶段约定, 绘制期读快照状态只失效重绘, 不触发重组。
 * - 静音段 (暂停/停止/缓冲断供) 显示值衰减到 0 后转低频轮询, 不空转帧循环。
 * - E-Ink 不绘制 (项目惯例: 墨水屏不做动效)。
 * - 顶部光带从 [LocalOverlayTopInset] 之下起画: 桌面窗口控制条是 native 层,
 *   恒压在 Compose 画布之上, 不让开会在顶部断一截。
 *
 * @param colors 四路封面主色 (契约: 至少四路, 由 [rememberAudioCoverPalette] 或
 *   [rememberFallbackGlowColors] 给出)
 */
@Composable
internal fun AudioPlayEdgeGlow(colors: List<Color>, modifier: Modifier = Modifier) {
    val eInk = LocalEInk.current
    if (eInk) return
    // 快层: 与歌词辉光同一套曲线 (整体亮度); 慢层: 段落渐强
    val fast = remember { AudioEnergyEnvelope() }
    val slow = remember {
        AudioEnergyEnvelope(attackMs = SLOW_ATTACK_MS, releaseMs = SLOW_RELEASE_MS)
    }
    // 多频段驱动: 非状态数组, 靠 frameTick 建立绘制依赖
    val bands = remember { FloatArray(AudioSpectrum.BAND_COUNT) }
    var frameTick by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val baseThicknessPx = with(density) { GLOW_BASE_THICKNESS.toPx() }
    val topInsetPx = with(density) { LocalOverlayTopInset.current.toPx() }

    // 观察播放侧单值能量: 只写运行态, 不参与组合
    LaunchedEffect(Unit) {
        AudioPlayShared.audioEnergy.collect {
            fast.energy = it
            fast.energyAtMs = glowNowMs()
        }
    }
    // 整体淡入淡出: 暂停时播放侧会把频谱清零, 只靠它收会"啪"一下消失,
    // 所以形状保留最后一帧, 由这条曲线把整圈慢慢压下去
    var fade by remember { mutableFloatStateOf(1f) }
    // 观察多频段幅度: 只在有真实频谱时覆盖 —— 暂停清零时保留最后一帧的形状
    LaunchedEffect(Unit) {
        AudioPlayShared.audioBands.collect { values ->
            // 全零 = 暂停/停止时播放侧的清零通知, 保留上一帧形状交给 fade 收
            if (values.none { it > 0f }) return@collect
            for (i in bands.indices) {
                bands[i] = values.getOrElse(i) { 0f }
            }
        }
    }

    // 帧循环: 有信号才逐帧推进; 完全淡出后转低频轮询 (省电)
    LaunchedEffect(Unit) {
        var lastFrameNs = 0L
        while (isActive) {
            val idle = fast.energy <= AudioEnergyEnvelope.SILENT_THRESHOLD &&
                fast.smoothEnergy <= AudioEnergyEnvelope.SILENT_THRESHOLD
            if (idle && fade <= 0.01f) {
                delay(AudioEnergyEnvelope.IDLE_POLL_MS)
                continue
            }
            withFrameNanos { frameNs ->
                val dtMs = if (lastFrameNs == 0L) 16f
                else ((frameNs - lastFrameNs) / 1_000_000f).coerceIn(1f, 100f)
                lastFrameNs = frameNs
                val target = fast.decayedEnergy(glowNowMs())
                fast.advance(dtMs, target)
                slow.advance(dtMs, target)
                // 起播快速亮起, 暂停/停止按慢时间常数收回
                val fadeTarget = if (idle) 0f else 1f
                val tau = if (fadeTarget > fade) FADE_IN_MS else FADE_OUT_MS
                fade += (fadeTarget - fade) * (1f - exp(-dtMs / tau))
                // 指数衰减永远逼近而不等于 0: 过阈值直接归零, 否则会一直留一层极薄的光
                if (fadeTarget <= 0f && fade < FADE_STOP_THRESHOLD) fade = 0f
                frameTick++
            }
        }
    }

    Spacer(
        modifier.drawBehind {
            val slowEnergy = slow.smoothEnergy.coerceIn(0f, 1f)
            // 读帧计数只为建立绘制依赖: 颜色流动与频谱起伏都要求逐帧重绘
            val tick = frameTick
            val visible = fade.coerceIn(0f, 1f)
            if (tick < 0 || visible <= 0.01f) return@drawBehind
            drawEdgeGlow(
                colors = colors,
                bands = bands,
                slowEnergy = slowEnergy,
                baseThicknessPx = baseThicknessPx,
                topInsetPx = topInsetPx,
                phase = (glowNowMs() % FLOW_PERIOD_MS) / FLOW_PERIOD_MS,
                fade = visible,
            )
        }
    )
}

/**
 * 边缘光带本体 (纯绘制, 无状态)。
 *
 * @param bands 各频段 0~1 驱动值, 沿边缘环向排布
 * @param slowEnergy 慢层能量 (段落渐强, 影响亮度与厚度)
 * @param phase 颜色流动相位 0~1
 */
private fun DrawScope.drawEdgeGlow(
    colors: List<Color>,
    bands: FloatArray,
    slowEnergy: Float,
    baseThicknessPx: Float,
    topInsetPx: Float,
    phase: Float,
    fade: Float,
) {
    val w = size.width
    val h = size.height
    val slowGain = 0.55f + 0.45f * slowEnergy
    // 亮度只跟慢层走: 快层在暂停瞬间会被播放侧清零, 直接拿它调制亮度会当场掉一半 (跳一下)
    val energyGain = 0.62f + 0.38f * slowEnergy

    fun colorAt(pos: Float): Color {
        // 沿环插值四路色, 相位让颜色持续流动
        val t = (((pos + phase) % 1f + 1f) % 1f) * colors.size
        val index = t.toInt().coerceIn(0, colors.size - 1)
        val frac = t - index
        return lerpColor(colors[index], colors[(index + 1) % colors.size], frac)
    }

    fun bandAt(ring: Float): Float {
        // 环位置 → 频段值 (相邻段线性插值, 曲线连续)
        val t = ((ring * bands.size) % bands.size + bands.size) % bands.size
        val i = t.toInt()
        val frac = t - i
        val a = bands[i % bands.size]
        val b = bands[(i + 1) % bands.size]
        return a + (b - a) * frac
    }

    // 淡出线性作用在厚度上 (不经过幂曲线)
    fun depthAt(ring: Float): Float = baseThicknessPx *
        (THICKNESS_MIN_SCALE + (THICKNESS_MAX_SCALE - THICKNESS_MIN_SCALE) * bandAt(ring)) *
        slowGain * fade

    // 颜色: 整圈一个扫描渐变, 相位让颜色沿边缘流动 (四边共用同一渐变, 角落自然衔接)
    val colorStops = List(COLOR_STOPS) { i -> colorAt(i / COLOR_STOPS.toFloat()) }
    val brush = Brush.sweepGradient(colorStops, center = Offset(w / 2f, h / 2f))

    /**
     * 一条边的波浪带: 外沿贴边, 内沿按频段起伏成连续曲线, 填充整圈颜色渐变。
     *
     * @param vertical true=左右两边 (沿 y 采样), false=上下两边 (沿 x 采样)
     * @param atZero true=贴 0 侧 (左/上), false=贴远侧 (右/下)
     * @param ringStart 该边在环上的起点 (0=左中, 0.25=上中, 0.5=右中, 0.75=下中)
     */
    fun wavyEdge(
        vertical: Boolean,
        atZero: Boolean,
        fixed: Float,
        from: Float,
        to: Float,
        ringStart: Float,
        ratio: Float,
        alpha: Float,
    ) {
        if (alpha <= ALPHA_EPS) return
        val span = to - from
        val path = Path()
        for (i in 0..SAMPLES) {
            val t = i / SAMPLES.toFloat()
            val along = from + span * t
            val depth = depthAt(ringStart + t * 0.25f) * ratio
            val x = if (vertical) (if (atZero) depth else fixed - depth) else along
            val y = if (vertical) along else (if (atZero) fixed + depth else fixed - depth)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        // 回到贴边闭合
        if (vertical) {
            path.lineTo(fixed, to)
            path.lineTo(fixed, from)
        } else {
            path.lineTo(to, fixed)
            path.lineTo(from, fixed)
        }
        path.close()
        drawPath(path, brush, alpha = alpha)
    }

    // 从最外层 (最浅) 到最内层 (最亮) 依次覆盖: 叠加出向内衰减, 层间是平行曲线不是台阶
    for (layer in LAYERS - 1 downTo 0) {
        val ratio = (layer + 1f) / LAYERS
        // 淡出线性乘在亮度上: 不能并进 band 再走 0.6 次幂, 那样小数值会被放大
        // (0.02 的淡出值开 0.6 次幂后还剩约 0.096, 看起来永远擦不干净)
        val alpha = GLOW_MAX_ALPHA * LAYER_ALPHA[layer] * energyGain * fade
        if (alpha <= ALPHA_EPS) continue
        // 左 / 右
        wavyEdge(true, atZero = true, fixed = 0f, from = topInsetPx, to = h, ringStart = 0.75f, ratio, alpha)
        wavyEdge(true, atZero = false, fixed = w, from = topInsetPx, to = h, ringStart = 0.25f, ratio, alpha)
        // 上 / 下
        wavyEdge(false, atZero = true, fixed = topInsetPx, from = 0f, to = w, ringStart = 0f, ratio, alpha)
        wavyEdge(false, atZero = false, fixed = h, from = 0f, to = w, ringStart = 0.5f, ratio, alpha)
    }
}

/** 线性插值两个颜色 */
private fun lerpColor(from: Color, to: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = from.red + (to.red - from.red) * f,
        green = from.green + (to.green - from.green) * f,
        blue = from.blue + (to.blue - from.blue) * f,
        alpha = from.alpha + (to.alpha - from.alpha) * f,
    )
}

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
    hsl[2] = hsl[2].coerceIn(0.45f, 0.75f)
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
    LaunchedEffect(coverUrl, sourceOrigin) {
        val bitmap = if (coverUrl.isNullOrBlank()) null else BookImageLoaders.getOrNull()
            ?.loadCoverOrNull(coverUrl, sourceOrigin, widthPx = 64, heightPx = 64)
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
