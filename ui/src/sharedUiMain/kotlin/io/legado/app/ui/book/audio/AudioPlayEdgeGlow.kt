package io.legado.app.ui.book.audio

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.legado.app.model.AudioPlayShared
import io.legado.app.model.audio.AudioSpectrum
import io.legado.app.ui.compose.platform.LocalOverlayTopInset
import io.legado.app.ui.compose.theme.LocalEInk
import io.legado.app.ui.root.rememberRouteVisibleState
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive

// ==================== 页面边缘光效 ====================

/**
 * 光带沿周长取多少个采样点 (等弧长)。每个采样点是一列顶点, 列与列之间铺三角形。
 *
 * 采样越密, 沿边缘的颜色/亮度台阶越小 (64 段时相邻列只差约 1/64 个调色周期)。
 * 列宽 = 周长 / 该值, 整圈一致。
 */
private const val GLOW_SEGMENTS = 64

/** 角点与均分网格点重合到这个精度内只留一个采样点 (否则出现零宽片) */
private const val CORNER_MERGE_EPS = 0.5f

/**
 * 贴边不透明度 (峰值)。向内按 [GLOW_FADE_STOPS] 的行一路收到透明。
 *
 * 亮度写在顶点色的透明道上, 厚度写在顶点位置上: 整圈一帧只发一次绘制, 不建渐变也不建路径。
 *
 * 叠上段落底色与逐点亮度后, 贴边有效不透明度约 0.22~0.34。
 */
private const val GLOW_PEAK_ALPHA = 0.49f

/** 向内衰减色标 (位置 → 相对峰值的不透明度): (1-d)^1.4 取四档 */
private val GLOW_FADE_STOPS = floatArrayOf(0f, 0.35f, 0.65f, 0.85f, 1f)
private val GLOW_FADE_ALPHA = floatArrayOf(1f, 0.547f, 0.230f, 0.070f, 0f)

/** 每条采样点上的顶点行数 (外沿 → 内沿), 与 [GLOW_FADE_STOPS] 一一对应 */
private val GLOW_ROWS = GLOW_FADE_STOPS.size

/** 光带厚度基准 (各点在此基础上按频段起伏) */
private val GLOW_BASE_THICKNESS = 26.dp

/**
 * 各点厚度起伏 (再乘段落底色 0.55~1.0): 频段 0 → 0.40 倍, 频段 1 → 1.30 倍。
 * 配合 26dp 基准: 静默约 6dp、常态约 12dp、鼓点约 34dp。
 *
 * 上限只给到 1.30: 再高就成一条占满半屏的色块, 低频重时反而盖住正文。
 */
private const val THICKNESS_MIN_SCALE = 0.40f
private const val THICKNESS_MAX_SCALE = 1.30f

/**
 * 某位置的亮度下限倍数: 该位置频段静音时的不透明度 = 峰值时的这个比例。
 * 亮度沿周长逐点取自己的频段, 所以亮斑与波峰同位置, 各段各按自己的节奏起伏。
 *
 * 下限取 0.25 而不是 0.35: 亮度是"响不响"的直接读数, 抬下限就等于把动静抹平
 * (0.35 配幂次 0.55 时, 常态与峰值只差 1.16 倍, 看着就是一条常亮的边)。
 */
private const val EDGE_BRIGHT_FLOOR = 0.25f

/** 亮度曲线的幂次: 0.8 略低于 1, 让中段比线性稍亮; 静默与峰值仍由下限 0.25 拉开四倍 */
private const val EDGE_BRIGHT_POWER = 0.8f

/** 颜色沿边缘流动一周的时间 (ms) */
private const val FLOW_PERIOD_MS = 16_000L

/** 慢层 (段落响度) 的时间常数 (ms): 比歌词辉光的快层慢一个数量级 */
private const val SLOW_ATTACK_MS = 900f
private const val SLOW_RELEASE_MS = 1400f

/** 段落底色 (慢层) 对亮度与厚度的调制下限: 0.55 给整圈一条随段落呼吸的慢线 */
private const val BED_MIN = 0.55f

/** 整体淡出时间常数 (ms): 比点亮慢, 但要在两秒内收干净 */
private const val FADE_OUT_MS = 380f

/** 整体淡入时间常数 (ms): 起播时快速亮起 */
private const val FADE_IN_MS = 120f

/** 淡出到这个值以下直接归零, 也不再绘制 (避免指数衰减留下永远擦不干净的薄光) */
private const val FADE_STOP_THRESHOLD = 0.02f

/** 低于此不透明度或此频段值视为静默 (省掉无意义的绘制与空转帧循环) */
private const val SILENT_ALPHA = 0.004f
private const val SILENT_BAND = 0.01f

private val glowClockStart = TimeSource.Monotonic.markNow()

/** 单调时钟 (ms): 与播放位置无关, 只用来判断能量管道是否还在供数 */
private fun glowNowMs(): Long = glowClockStart.elapsedNow().inWholeMilliseconds

/** 一条直边: 起点、终点、向内法线 */
private class GlowEdge(
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
    val inx: Float,
    val iny: Float,
)

/**
 * 环形带的周长几何: 只与尺寸和顶部避让有关, 算一次就复用。
 *
 * 起算点取 3 点钟方向 (右边中点), 顺时针绕行; 每个采样点带"到起算点的周长距离占比",
 * 颜色与亮度全按这个周长种子算 (不需要角度)。
 */
private class GlowRing(
    val w: Float,
    val h: Float,
    val top: Float,
    /** 采样点在外沿上的位置 */
    val px: FloatArray,
    val py: FloatArray,
    /** 向内法线: 角点是相邻两边法线之和 → 45° 斜接, 斜向偏移量自动补回垂直厚度 */
    val nx: FloatArray,
    val ny: FloatArray,
    /** 该点的周长位置 0..1 (到起算点的距离 ÷ 周长) */
    val arc: FloatArray,
    /**
     * 该点的最大厚度 (px)。
     *
     * 转角的斜接内沿点会同时向内和向侧面各偏移一个厚度: 厚度超过到相邻采样点的距离时,
     * 这一列的内沿会翻过邻居, 与邻居叠着画两遍 (角内侧叠出一块更亮的斑)。把转角厚度卡在
     * 到相邻采样点的距离之内, 内沿就不会翻折, 也不需要额外的路径布尔运算。
     */
    val depthCap: FloatArray = FloatArray(px.size) { Float.MAX_VALUE },
)

/**
 * 一圈光带的顶点缓冲 (顶点色三角网)。
 *
 * 每条采样点上有 [GLOW_ROWS] 个顶点, 从外沿按 [GLOW_FADE_STOPS] 的深度一路排到内沿;
 * 相邻两条采样点之间铺 2×(行数-1) 个三角形。拓扑只跟采样点数有关, 建一次就复用,
 * 每帧只改 [Vertices.positions] 与 [Vertices.colors] 的内容: 两端都是绘制时读数组、
 * 录制时拷贝, 不持有数组引用, 所以原地改不会漏到上一帧。
 */
private class GlowMesh(val samples: Int) {

    val vertices: Vertices = run {
        val vertexCount = samples * GLOW_ROWS
        val indices = ArrayList<Int>(samples * (GLOW_ROWS - 1) * 6)
        for (i in 0 until samples) {
            val next = (i + 1) % samples
            for (row in 0 until GLOW_ROWS - 1) {
                val outer = i * GLOW_ROWS + row
                val outerNext = next * GLOW_ROWS + row
                val innerNext = next * GLOW_ROWS + row + 1
                val inner = i * GLOW_ROWS + row + 1
                indices += outer
                indices += outerNext
                indices += innerNext
                indices += outer
                indices += innerNext
                indices += inner
            }
        }
        Vertices(
            vertexMode = VertexMode.Triangles,
            positions = List(vertexCount) { Offset.Zero },
            textureCoordinates = List(vertexCount) { Offset.Zero },
            colors = List(vertexCount) { Color.Transparent },
            indices = indices,
        )
    }
}

/**
 * 按**等弧长**取样的环形几何: 整圈均分 [GLOW_SEGMENTS] 段, 再把四个屏幕转角强制补成采样点。
 *
 * 按"每条边各均分若干份"取样会让横边与竖边的段宽不同, 还会在角上插入零宽的段 ——
 * 颜色流动的速度与亮度台阶因此随边而变; 等弧长取样后每段宽度一致。
 *
 * 转角必须是采样点: 否则跨角那一段的外沿是一根直线, 把直角切掉一块 (拐角缺失),
 * 且两侧厚度方向不一致 (角内侧凹折)。补上后相邻两段共用"角点→斜接内沿点"这条斜线,
 * 每处只覆盖一次。
 *
 * 起算点仍是 3 点钟方向 (右边中点), 顺时针绕行。
 */
private fun buildGlowRing(w: Float, h: Float, top: Float): GlowRing {
    val cy = (top + h) / 2f
    // 顺时针五段 (右边被起算点切成两段); 全部是轴平行的直边
    val edges = arrayOf(
        GlowEdge(w, cy, w, h, -1f, 0f),
        GlowEdge(w, h, 0f, h, 0f, -1f),
        GlowEdge(0f, h, 0f, top, 1f, 0f),
        GlowEdge(0f, top, w, top, 0f, 1f),
        GlowEdge(w, top, w, cy, -1f, 0f),
    )
    val lengths = FloatArray(edges.size) { i ->
        abs(edges[i].x1 - edges[i].x0) + abs(edges[i].y1 - edges[i].y0)
    }
    val perim = lengths.sum()
    // 四个转角: 坐标、周长距离、斜接法线 (相邻两边法线之和, 90° 角下分量均为 ±1)
    val cornerCount = 4
    val cornerX = FloatArray(cornerCount)
    val cornerY = FloatArray(cornerCount)
    val cornerDist = FloatArray(cornerCount)
    val cornerNX = FloatArray(cornerCount)
    val cornerNY = FloatArray(cornerCount)
    run {
        var walked = 0f
        for (i in 0 until cornerCount) {
            walked += lengths[i]
            cornerDist[i] = walked
            cornerX[i] = edges[i].x1
            cornerY[i] = edges[i].y1
            cornerNX[i] = edges[i].inx + edges[i + 1].inx
            cornerNY[i] = edges[i].iny + edges[i + 1].iny
        }
    }
    // 均分网格与角点归并 (角点与网格点重合时只留角点, 否则出现零宽片)
    val total = GLOW_SEGMENTS + cornerCount
    val dist = FloatArray(total)
    val cornerIdx = IntArray(total) { -1 }
    var grid = 0
    var ci = 0
    var m = 0
    while (grid < GLOW_SEGMENTS || ci < cornerCount) {
        val gd = if (grid < GLOW_SEGMENTS) perim * grid / GLOW_SEGMENTS else Float.MAX_VALUE
        val cd = if (ci < cornerCount) cornerDist[ci] else Float.MAX_VALUE
        when {
            cd < gd - CORNER_MERGE_EPS -> {
                dist[m] = cd; cornerIdx[m] = ci; ci++
            }
            cd <= gd + CORNER_MERGE_EPS -> {
                dist[m] = cd; cornerIdx[m] = ci; ci++; grid++
            }
            else -> {
                dist[m] = gd; grid++
            }
        }
        m++
    }
    val count = m
    val px = FloatArray(count)
    val py = FloatArray(count)
    val nx = FloatArray(count)
    val ny = FloatArray(count)
    val arc = FloatArray(count)
    val cornerSlot = IntArray(cornerCount) { -1 }
    for (i in 0 until count) {
        val distance = dist[i]
        val k = cornerIdx[i]
        if (k >= 0) {
            // 角点直接取精确坐标与斜接法线: 按边定位会把它归到下一条边的起点,
            // 相邻边就取错了 (斜接线不共边 → 角内侧重叠或留缝)
            px[i] = cornerX[k]
            py[i] = cornerY[k]
            nx[i] = cornerNX[k]
            ny[i] = cornerNY[k]
            arc[i] = (distance / perim).coerceIn(0f, 1f)
            cornerSlot[k] = i
            continue
        }
        // 定位该弧长落在哪条边上
        var walked = 0f
        var edgeIndex = 0
        while (edgeIndex < edges.size - 1 && walked + lengths[edgeIndex] <= distance) {
            walked += lengths[edgeIndex]
            edgeIndex++
        }
        val edge = edges[edgeIndex]
        val len = lengths[edgeIndex].coerceAtLeast(1e-4f)
        val t = ((distance - walked) / len).coerceIn(0f, 1f)
        px[i] = edge.x0 + (edge.x1 - edge.x0) * t
        py[i] = edge.y0 + (edge.y1 - edge.y0) * t
        arc[i] = (distance / perim).coerceIn(0f, 1f)
        nx[i] = edge.inx
        ny[i] = edge.iny
    }
    // 转角厚度上限 = 到相邻采样点的距离 (见 GlowRing.depthCap)
    val depthCap = FloatArray(count) { Float.MAX_VALUE }
    for (k in 0 until cornerCount) {
        val c = cornerSlot[k]
        if (c >= 0) {
            val prevGap = if (c == 0) perim - dist[count - 1] else dist[c] - dist[c - 1]
            val nextGap = if (c == count - 1) perim - dist[c] else dist[c + 1] - dist[c]
            depthCap[c] = min(prevGap, nextGap)
        }
    }
    return GlowRing(w, h, top, px, py, nx, ny, arc, depthCap)
}

/**
 * 音频播放页的边缘光效: 四周一圈随频谱起伏、颜色沿边缘流动的光带。
 *
 * - 沿每一处圆周的亮度与厚度由**该处所属频段**的幅度 ([AudioPlayShared.audioVisual] 的
 *   [AudioPlayShared.AudioVisualFrame.bands]) 决定: 低频、人声、高频各占一段圆周,
 *   所以是"这段跳、那段不跳", 不是整圈一起明暗。
 * - 位置一律按**周长**度量 (一圈均分 12 段频段): 四边长短不同不影响每边能看到几段,
 *   也不随窗口比例搬家。
 * - 整圈底亮度再叠一层慢平滑 (段落渐强), 时间常数比歌词辉光的快层慢一个数量级。
 * - 颜色按四路封面主色沿圆周插值, 相位随时间推进 → 整圈颜色缓慢流动 (约 16 秒一周)。
 * - 整圈是一条顶点色三角网 (外沿贴屏、内沿为波浪), 逐行向内收到透明; 角落只覆盖一次。
 * - 淡出只压亮度、不改形状: 暂停时形状保留最后一帧, 由淡出曲线慢慢压下去。
 * - 绘制期只读运行态: 按 Compose 阶段约定, 绘制期读快照状态只失效重绘, 不触发重组。
 * - 静音段 (暂停/停止/缓冲断供) 显示值衰减到 0 后挂起等下一帧有声的采样, 不轮询也不空转帧循环。
 * - E-Ink 不绘制 (项目惯例: 墨水屏不做动效)。
 * - 顶部光带从 [LocalOverlayTopInset] 之下起画: 桌面窗口控制条是 native 层,
 *   恒压在 Compose 画布之上, 不让开会在顶部断一截。
 *
 * 只有整体电平、拿不到频谱的端 (桌面/鸿蒙) 会把 12 段填成同一个值, 此时整圈一起起伏 ——
 * 这是如实退化, 不是本函数的缺陷 (修它要给播放引擎补真实 PCM 取数)。
 *
 * @param colors 四路封面主色 (契约: 至少四路, 由 [rememberAudioCoverPalette] 或
 *   [rememberFallbackGlowColors] 给出)
 */
@Composable
internal fun AudioPlayEdgeGlow(
    colors: List<Color>,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val eInk = LocalEInk.current
    if (eInk) return
    // 快层: 与歌词辉光同一套曲线 (整圈底亮度); 慢层: 段落渐强
    val fast = remember { AudioEnergyEnvelope() }
    val slow = remember {
        AudioEnergyEnvelope(attackMs = SLOW_ATTACK_MS, releaseMs = SLOW_RELEASE_MS)
    }
    // 多频段驱动: 整组换数组 (派发侧本来就每帧给新数组), 读它即建立逐帧重绘依赖
    var bands by remember { mutableStateOf(FloatArray(AudioSpectrum.BAND_COUNT)) }
    // 周长几何缓存: 尺寸或顶部避让变化才重建
    val ringCache = remember { arrayOfNulls<GlowRing>(1) }
    // 顶点缓冲: 采样点数变了才重建; 每帧只改数组内容, 不新建对象
    val meshCache = remember { arrayOfNulls<GlowMesh>(1) }
    val paint = remember { Paint().apply { color = Color.White } }
    // 帧时钟 (ns→ms): 只在动画推进时更新, 绘制期读它即建立逐帧重绘依赖, 也用作颜色流动相位
    var frameClockMs by remember { mutableLongStateOf(0L) }
    val density = LocalDensity.current
    val baseThicknessPx = with(density) { GLOW_BASE_THICKNESS.toPx() }
    val topInsetPx = with(density) { LocalOverlayTopInset.current.toPx() }

    // 本页不可见 (被压栈/退后台) 时视觉整条管道停摆: 不收采样、不请求帧;
    // 音频本身与业务状态照旧, 回到前台由 effect 重启并按当下重新校准
    val routeVisible = rememberRouteVisibleState().value

    // 观察音频可视帧: 播放中一律照单收下 (各段同时为 0 是真实静默, 形状该跟着落);
    // 暂停/停止时不再收, 保留最后一帧形状交给 fade 收尾 (播放侧那时会发清零帧)。
    // drop(1): 订阅一开始状态流会回放缓存帧 (暂停前那一帧), 它不是新样本 —— 当成新样本会把
    // 采样时刻打成当下, 恢复播放瞬间亮度直接跳到暂停前水平, 而不是随新音频淡入。
    LaunchedEffect(isPlaying, routeVisible) {
        if (!isPlaying || !routeVisible) return@LaunchedEffect
        AudioPlayShared.audioVisual.drop(1).collect { frame ->
            fast.energy = frame.energy
            fast.energyAtMs = glowNowMs()
            bands = frame.bands
        }
    }
    // 整体淡入淡出: 形状保留最后一帧, 由这条曲线把整圈慢慢压下去
    var fade by remember { mutableFloatStateOf(0f) }

    // 帧循环: 有信号才推进; 完全淡出后转低频轮询 (播放中) 或直接停帧 (暂停)
    LaunchedEffect(isPlaying, routeVisible) {
        if (!routeVisible) {
            // 整圈收干净并归零相位: 回到前台随新音频重新亮起, 不残留离开时的形状
            fast.resetDisplay()
            slow.resetDisplay()
            fade = 0f
            return@LaunchedEffect
        }
        var lastFrameNs = 0L
        while (isActive) {
            // 暂停时目标恒为 0: 显示包络自行收干净, 不依赖播放侧清零
            val target = if (isPlaying) fast.decayedEnergy(glowNowMs()) else 0f
            // 暂停时频段值已冻结 (采集已停), 不能拿它当"还有信号"的依据:
            // 只看显示侧是否收干净, 否则循环永远不会退出
            val settled = target <= AudioEnergyEnvelope.SILENT_THRESHOLD &&
                fast.smoothEnergy <= AudioEnergyEnvelope.SILENT_THRESHOLD &&
                fade <= FADE_STOP_THRESHOLD
            if (!isPlaying) {
                if (settled) return@LaunchedEffect
            } else if (settled && bands.all { it <= SILENT_BAND }) {
                // 播放中的静音段: 挂起等到下一帧真的有声的采样 (不按定时醒来查);
                // 静默期的同值帧不会让光带有任何变化, 叫醒只会白白走一帧
                lastFrameNs = 0L
                AudioPlayShared.audioVisual.drop(1).first { frame ->
                    frame.energy > SILENT_BAND || frame.bands.any { it > SILENT_BAND }
                }
                continue
            }
            withFrameNanos { frameNs ->
                val dtMs = if (lastFrameNs == 0L) 16f
                else ((frameNs - lastFrameNs) / 1_000_000f).coerceIn(1f, 100f)
                lastFrameNs = frameNs
                fast.advance(dtMs, target)
                slow.advance(dtMs, target)
                // 起播快速亮起, 暂停/停止按慢时间常数收回
                val fadeTarget = if (target > AudioEnergyEnvelope.SILENT_THRESHOLD) 1f else 0f
                val tau = if (fadeTarget > fade) FADE_IN_MS else FADE_OUT_MS
                fade += (fadeTarget - fade) * (1f - exp(-dtMs / tau))
                // 指数衰减永远逼近而不等于 0: 过阈值直接归零, 否则会一直留一层极薄的光
                if (fadeTarget <= 0f && fade < FADE_STOP_THRESHOLD) fade = 0f
                frameClockMs = frameNs / 1_000_000L
            }
        }
    }

    Spacer(
        modifier.drawBehind {
            val slowEnergy = slow.smoothEnergy.coerceIn(0f, 1f)
            // 读帧时钟与频段数组只为建立绘制依赖 (颜色流动与频谱起伏都要求逐帧重绘)
            val clockMs = frameClockMs
            val values = bands
            val visible = fade.coerceIn(0f, 1f)
            if (visible <= FADE_STOP_THRESHOLD) return@drawBehind
            val viewW = size.width
            val viewH = size.height
            var ring = ringCache[0]
            if (ring == null || ring.w != viewW || ring.h != viewH || ring.top != topInsetPx) {
                ring = buildGlowRing(viewW, viewH, topInsetPx)
                ringCache[0] = ring
            }
            val samples = ring.px.size
            var mesh = meshCache[0]
            if (mesh == null || mesh.samples != samples) {
                mesh = GlowMesh(samples)
                meshCache[0] = mesh
            }
            drawEdgeGlow(
                colors = colors,
                bands = values,
                ring = ring,
                mesh = mesh,
                slowEnergy = slowEnergy,
                baseThicknessPx = baseThicknessPx,
                phase = (((clockMs % FLOW_PERIOD_MS) + FLOW_PERIOD_MS) % FLOW_PERIOD_MS) /
                    FLOW_PERIOD_MS.toFloat(),
                fade = visible,
                paint = paint,
            )
        }
    )
}

/**
 * 边缘光带本体 (纯绘制, 无状态)。
 *
 * 整圈光带是一张顶点色三角网: 每条采样点上按 [GLOW_FADE_STOPS] 排若干行顶点, 外沿顶点带
 * 该处的颜色与亮度, 向内逐行收到透明; 厚度决定每行顶点落在哪里。亮度与厚度按同一个
 * "周长位置"取同一段频段: 该处频段上来时同时变亮变厚, 静下去时同时变暗变薄,
 * 亮斑不会落到矮波上。
 *
 * 顶点色在两端都是绘制时读数组、录制时拷贝, 所以同一个缓冲可以逐帧原地改。
 *
 * @param slowEnergy 慢层能量 (段落渐强): 给出整圈的底亮度与厚度底色
 * @param phase 颜色流动相位 0~1
 */
private fun DrawScope.drawEdgeGlow(
    colors: List<Color>,
    bands: FloatArray,
    ring: GlowRing,
    mesh: GlowMesh,
    slowEnergy: Float,
    baseThicknessPx: Float,
    phase: Float,
    fade: Float,
    paint: Paint,
) {
    val bed = BED_MIN + (1f - BED_MIN) * slowEnergy
    val bandCount = bands.size

    fun colorAt(arc: Float): Color {
        // 沿周长插值四路色, 相位让颜色持续流动
        val t = ((arc + phase) % 1f) * colors.size
        val index = t.toInt()
        return lerpColor(colors[index], colors[(index + 1) % colors.size], t - index)
    }

    fun bandAt(arc: Float): Float {
        // 周长位置 → 频段值 (相邻段线性插值, 曲线连续)
        val t = (arc % 1f) * bandCount
        val i = t.toInt()
        return bands[i] + (bands[(i + 1) % bandCount] - bands[i]) * (t - i)
    }

    val peakAlpha = GLOW_PEAK_ALPHA * bed * fade
    if (peakAlpha <= SILENT_ALPHA) return
    val positions = mesh.vertices.positions
    val vertexColors = mesh.vertices.colors
    for (i in 0 until mesh.samples) {
        val arc = ring.arc[i]
        val band = bandAt(arc)
        val base = colorAt(arc)
        // 该处亮度: 频段走幂曲线, 低能量留一层底不让它全黑
        val brightness =
            EDGE_BRIGHT_FLOOR + (1f - EDGE_BRIGHT_FLOOR) * band.pow(EDGE_BRIGHT_POWER)
        // 该处厚度: 转角点卡在到相邻采样点的距离之内, 内沿不会翻折
        val depth = min(
            baseThicknessPx *
                (THICKNESS_MIN_SCALE + (THICKNESS_MAX_SCALE - THICKNESS_MIN_SCALE) * band) * bed,
            ring.depthCap[i],
        )
        val alpha = base.alpha * peakAlpha * brightness
        val outerX = ring.px[i]
        val outerY = ring.py[i]
        val normalX = ring.nx[i]
        val normalY = ring.ny[i]
        val first = i * GLOW_ROWS
        for (row in 0 until GLOW_ROWS) {
            val offset = depth * GLOW_FADE_STOPS[row]
            val at = (first + row) * 2
            positions[at] = outerX + normalX * offset
            positions[at + 1] = outerY + normalY * offset
            vertexColors[first + row] = base.copy(alpha = alpha * GLOW_FADE_ALPHA[row]).toArgb()
        }
    }
    drawIntoCanvas { canvas ->
        canvas.drawVertices(mesh.vertices, BlendMode.SrcOver, paint)
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
