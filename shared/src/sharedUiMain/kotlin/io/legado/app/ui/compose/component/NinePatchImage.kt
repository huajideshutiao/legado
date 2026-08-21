package io.legado.app.ui.compose.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.image_cover_default
import org.jetbrains.compose.resources.imageResource
import kotlin.math.roundToInt

/**
 * .9 图解析结果。
 *
 * [stretchX]/[stretchY] 是内容区 (剔除四边各 1px 标记框后) 内的可拉伸区间 (含端点);
 * [padding] 为内容内边距 (由右/下边框黑段解析, 左/上无标记信息恒 0), 本批仅解析不消费。
 */
data class NinePatchInfo(
    val stretchX: IntRange,
    val stretchY: IntRange,
    val padding: IntRect? = null,
)

/**
 * 解析运行时导入的 .9.png (未经 aapt, 无 npTc chunk, 1px 标记边框仍是图像像素)。
 * Android .9 规范: 最外 1px 是标记框; 上/左边框上的不透明黑像素段为拉伸区,
 * 右/下边框黑段为内容内边距。简化: 多段拉伸区取首段起点到尾段终点合并为单段 (经典九宫格)。
 * 边框无任何黑段时判定不像标记框, 返回 null (调用方回落普通图绘制)。
 */
fun parseNinePatch(bitmap: ImageBitmap): NinePatchInfo? {
    val pm = bitmap.toPixelMap()
    val w = pm.width
    val h = pm.height
    // 至少 1px 边框 + 1px 内容, 否则无法解析
    if (w < 3 || h < 3) return null
    val xSegs = horizontalSegments(pm, 0, w) // 上边框黑段 = 横向拉伸区
    val ySegs = verticalSegments(pm, 0, h) // 左边框黑段 = 纵向拉伸区
    if (xSegs.isEmpty() && ySegs.isEmpty()) return null
    // 取首尾合并成单段; 某方向无黑段时该方向整段内容区可拉伸
    val stretchX = if (xSegs.isEmpty()) 0 until w - 2
    else xSegs.first().first..xSegs.last().last
    val stretchY = if (ySegs.isEmpty()) 0 until h - 2
    else ySegs.first().first..ySegs.last().last
    // 右/下边框黑段 → 内容内边距 (可选返回值, 本批不消费)
    val rightSegs = verticalSegments(pm, w - 1, h)
    val bottomSegs = horizontalSegments(pm, h - 1, w)
    val padding = if (rightSegs.isEmpty() && bottomSegs.isEmpty()) {
        null
    } else {
        IntRect(
            left = 0, top = 0,
            right = if (rightSegs.isEmpty()) 0 else (w - 1) - rightSegs.last().last,
            bottom = if (bottomSegs.isEmpty()) 0 else (h - 1) - bottomSegs.last().last,
        )
    }
    return NinePatchInfo(stretchX, stretchY, padding)
}

/**
 * 按九宫格拉伸渲染 .9 图 (剔除 1px 标记边框): 四角原尺寸、上下边横向拉伸、
 * 左右边纵向拉伸、中心双向拉伸, 用 [DrawScope.drawImage] 的 src/dst 参数逐块绘制。
 *
 * 若 [parseNinePatch] 返回 null (边框不像标记框) 则退化为整图按目标尺寸拉伸 (等价 FillBounds)。
 * [contentDescription] 经 semantics 承载 (Canvas 本身无无障碍语义)。
 */
@Composable
fun NinePatchImage(
    bitmap: ImageBitmap,
    modifier: Modifier,
    contentDescription: String? = null,
) {
    val info = remember(bitmap) { parseNinePatch(bitmap) }
    val semanticsModifier = if (contentDescription != null) {
        modifier.semantics { this.contentDescription = contentDescription }
    } else modifier
    Canvas(semanticsModifier) {
        val dw = size.width.roundToInt()
        val dh = size.height.roundToInt()
        if (dw <= 0 || dh <= 0) return@Canvas
        val np = info
        if (np == null) {
            drawImage(bitmap, dstOffset = IntOffset.Zero, dstSize = IntSize(dw, dh))
            return@Canvas
        }
        drawNinePatch(bitmap, np, dw, dh)
    }
}

/**
 * 按 ninePatch 标记选渲染路径: true → [NinePatchImage] 九宫格拉伸, false → 普通 [Image]。
 * 调用方负责解码 (各消费点均已持有 [ImageBitmap]); 普通图默认 [ContentScale.Crop] 对齐
 * 封面槽裁剪语义, 需要其它缩放行为时传 [contentScale]。
 */
@Composable
fun NinePatchImageOrImage(
    bitmap: ImageBitmap,
    isNinePatch: Boolean,
    modifier: Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
) {
    if (isNinePatch) {
        NinePatchImage(bitmap = bitmap, modifier = modifier, contentDescription = contentDescription)
    } else {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
    }
}

/**
 * 内置默认封面 (`image_cover_default.jpg`, 已预先裁成 3:4) 按九宫格拉伸绘制。
 *
 * 资源入库前已裁好, 运行期不做任何裁剪; 拉伸区取自历史 `image_cover_default.9.png` 的标记框
 * (内容区 300×400 上的 x95..146 / y55..331, 已逐字节复核过该 .9 资源正是本 jpg 同一裁剪),
 * 按比例映射到实际像素尺寸, 故换图只要仍是同一构图即可, 不必带标记边框。
 */
@Composable
fun DefaultCoverNineImage(
    modifier: Modifier,
    contentDescription: String? = null,
) {
    val bitmap = imageResource(Res.drawable.image_cover_default)
    val semanticsModifier = if (contentDescription != null) {
        modifier.semantics { this.contentDescription = contentDescription }
    } else modifier
    Canvas(semanticsModifier) {
        val dw = size.width.roundToInt()
        val dh = size.height.roundToInt()
        if (dw <= 0 || dh <= 0) return@Canvas
        val bw = bitmap.width
        val bh = bitmap.height
        if (bw <= 0 || bh <= 0) return@Canvas
        val stretchX = (DEFAULT_COVER_STRETCH_X.first * bw).roundToInt()..
            (DEFAULT_COVER_STRETCH_X.second * bw).roundToInt()
        val stretchY = (DEFAULT_COVER_STRETCH_Y.first * bh).roundToInt()..
            (DEFAULT_COVER_STRETCH_Y.second * bh).roundToInt()
        drawNineSlice(
            bitmap = bitmap,
            srcLeft = 0,
            srcTop = 0,
            srcW = bw,
            srcH = bh,
            stretchX = stretchX,
            stretchY = stretchY,
            dw = dw,
            dh = dh,
        )
    }
}

/** 默认封面横向可拉伸区占图宽的比例 (历史 .9 标记框 x95..146 / 内容宽 300)。 */
private val DEFAULT_COVER_STRETCH_X = 95f / 300f to 146f / 300f

/** 默认封面纵向可拉伸区占图高的比例 (历史 .9 标记框 y55..331 / 内容高 400)。 */
private val DEFAULT_COVER_STRETCH_Y = 55f / 400f to 331f / 400f

/** 是否不透明黑 (alpha=255 且 RGB=0, Android .9 标记像素判定)。 */
private fun isBlack(c: Color): Boolean =
    c.alpha == 1f && c.red == 0f && c.green == 0f && c.blue == 0f

/** 沿水平线 y 扫描连续黑段 (第 0 行/第 h-1 行)。 */
private fun horizontalSegments(pm: PixelMap, y: Int, w: Int): List<IntRange> {
    val segs = mutableListOf<IntRange>()
    var start = -1
    for (x in 0 until w) {
        if (isBlack(pm[x, y])) {
            if (start < 0) start = x
        } else if (start >= 0) {
            segs += start..x - 1
            start = -1
        }
    }
    if (start >= 0) segs += start..w - 1
    return segs
}

/** 沿垂直线 x 扫描连续黑段 (第 0 列/第 w-1 列)。 */
private fun verticalSegments(pm: PixelMap, x: Int, h: Int): List<IntRange> {
    val segs = mutableListOf<IntRange>()
    var start = -1
    for (y in 0 until h) {
        if (isBlack(pm[x, y])) {
            if (start < 0) start = y
        } else if (start >= 0) {
            segs += start..y - 1
            start = -1
        }
    }
    if (start >= 0) segs += start..h - 1
    return segs
}

/**
 * 按 [np] 把源内容区 (剔除边框) 分成 9 块绘制到目标尺寸。
 * 目标尺寸不足四角之和时两端按比例压缩, 中心区可为 0 (不重叠)。
 */
private fun DrawScope.drawNinePatch(
    bitmap: ImageBitmap,
    np: NinePatchInfo,
    dw: Int,
    dh: Int,
) {
    // 剔除 1px 标记边框后即内容区, 拉伸区已是内容区坐标
    drawNineSlice(
        bitmap = bitmap,
        srcLeft = 1,
        srcTop = 1,
        srcW = bitmap.width - 2,
        srcH = bitmap.height - 2,
        stretchX = np.stretchX,
        stretchY = np.stretchY,
        dw = dw,
        dh = dh,
    )
}

/**
 * 九宫格绘制核心: 把 [bitmap] 的 ([srcLeft], [srcTop], [srcW]×[srcH]) 源区按
 * [stretchX]/[stretchY] (源区内坐标, 含端点) 切九块画到 [dw]×[dh]。
 *
 * 与标记框无关, 故同时服务"带 1px 标记框的 .9 图"与"运行期裁剪 + 写死拉伸区的普通图"。
 */
private fun DrawScope.drawNineSlice(
    bitmap: ImageBitmap,
    srcLeft: Int,
    srcTop: Int,
    srcW: Int,
    srcH: Int,
    stretchX: IntRange,
    stretchY: IntRange,
    dw: Int,
    dh: Int,
) {
    if (srcW <= 0 || srcH <= 0) return
    val xStart = stretchX.first
    val xEnd = stretchX.last
    val yStart = stretchY.first
    val yEnd = stretchY.last
    val leftW = xStart
    val rightW = (srcW - 1) - xEnd
    val topH = yStart
    val bottomH = (srcH - 1) - yEnd
    val (dl, dc, dr) = distribute(dw, leftW, rightW)
    val (dt, dm, db) = distribute(dh, topH, bottomH)
    // 源区三列起点 (源区内坐标 + 源区左上偏移)
    val sx = intArrayOf(srcLeft, srcLeft + xStart, srcLeft + xEnd + 1)
    val sy = intArrayOf(srcTop, srcTop + yStart, srcTop + yEnd + 1)
    // 源区三段宽高
    val sw = intArrayOf(leftW, xEnd - xStart + 1, rightW)
    val sh = intArrayOf(topH, yEnd - yStart + 1, bottomH)
    // 目标区三列起点与宽高
    val dx = intArrayOf(0, dl, dl + dc)
    val dy = intArrayOf(0, dt, dt + dm)
    val dww = intArrayOf(dl, dc, dr)
    val dhh = intArrayOf(dt, dm, db)
    for (i in 0..2) {
        if (sw[i] <= 0 || dww[i] <= 0) continue
        for (j in 0..2) {
            if (sh[j] <= 0 || dhh[j] <= 0) continue
            drawImage(
                image = bitmap,
                srcOffset = IntOffset(sx[i], sy[j]),
                srcSize = IntSize(sw[i], sh[j]),
                dstOffset = IntOffset(dx[i], dy[j]),
                dstSize = IntSize(dww[i], dhh[j]),
                filterQuality = FilterQuality.Low,
            )
        }
    }
}

/** 把目标长度分给 (固定端1, 可拉伸中心, 固定端2); 目标过短时两端按比例压缩, 保证不重叠。 */
private fun distribute(dst: Int, fix1: Int, fix2: Int): Triple<Int, Int, Int> {
    val fix = fix1 + fix2
    if (fix <= 0) return Triple(0, dst, 0)
    if (dst >= fix) return Triple(fix1, dst - fix, fix2)
    val a = (fix1.toFloat() * dst / fix).roundToInt().coerceIn(0, dst)
    return Triple(a, 0, dst - a)
}
