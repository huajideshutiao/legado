package io.legado.app.ui.root

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize

/**
 * [ContainerSnapshot] 的 skiko 实现 (desktop/iOS/鸿蒙): 把显示列表光栅成一张位图, 逐帧贴图。
 *
 * 本端没有"离屏按未缩放尺寸分配"的开关 (见 [ContainerSnapshot] 的说明), 只能落到真位图。
 * 光栅目标是 raster SkCanvas, `drawInto` 把显示列表重放进去即可, 不存在 Android 那种
 * "软件画布画不了硬件位图"的限制。
 *
 * **不用 `GraphicsLayer.toImageBitmap()`**: 它带 `suspend`, draw 阶段调不了 (skiko 实现体
 * 倒是同步的, 但不能依赖这个细节)。改用全公开 API 自己拉: 调用方 `record` 录显示列表 →
 * [CanvasDrawScope] 把它画进 `Canvas(ImageBitmap)`。
 */
internal actual class ContainerSnapshot actual constructor() {

    private var bitmap: ImageBitmap? = null
    private var size: IntSize = IntSize.Zero

    actual val capturedSize: IntSize get() = size

    actual fun capture(
        scope: DrawScope,
        layer: GraphicsLayer,
        pageSize: IntSize,
        record: () -> Unit,
    ) {
        record()
        val bmp = ImageBitmap(pageSize.width, pageSize.height)
        CanvasDrawScope().draw(
            density = Density(scope.density, scope.fontScale),
            layoutDirection = scope.layoutDirection,
            canvas = Canvas(bmp),
            size = pageSize.toSize(),
        ) { drawLayer(layer) }
        // 画完即标不可变: 本端 drawImage 每帧都要经 Image.makeFromBitmap 包一次, 而该入口对
        // 可变位图会整幅复制像素并换新 pixelRef, Ganesh 又按 pixelRef 世代号做纹理缓存键 ——
        // 不标的话每帧一次全幅拷贝 + 全幅上传, 上一帧的纹理还随临时 pixelRef 析构被作废。
        // 标记必须在建过画布之后: Canvas(ImageBitmap) 只接受可变位图
        bmp.asSkiaBitmap().setImmutable()
        bitmap = bmp
        size = pageSize
    }

    actual fun draw(
        scope: DrawScope,
        layer: GraphicsLayer,
        dstOffset: IntOffset,
        dstSize: IntSize,
        alpha: Float,
        cornerRadiusPx: Float,
    ) {
        val bmp = bitmap ?: return
        val drawBitmap = {
            scope.drawImage(
                image = bmp,
                dstOffset = dstOffset,
                dstSize = dstSize,
                alpha = alpha,
                // Medium = 双线性 + mipmap: 起手帧是大幅缩小 (全屏→卡片), 默认 Low 无 mipmap 会起锯齿
                filterQuality = FilterQuality.Medium,
            )
        }
        val radius = cornerRadiusPx.coerceAtLeast(0f)
        if (radius > 0f) {
            val path = Path().apply {
                addRoundRect(
                    RoundRect(
                        rect = Rect(
                            offset = Offset(dstOffset.x.toFloat(), dstOffset.y.toFloat()),
                            size = dstSize.toSize(),
                        ),
                        cornerRadius = CornerRadius(radius, radius),
                    )
                )
            }
            scope.clipPath(path) { drawBitmap() }
        } else {
            drawBitmap()
        }
    }

    actual fun release(layer: GraphicsLayer) {
        bitmap = null
        size = IntSize.Zero
    }
}
