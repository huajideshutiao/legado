package io.legado.app.ui.root

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize

/**
 * [ContainerSnapshot] 的 Android 实现: 直接用图层的 HWUI 合成层, 不落位图。
 *
 * 逐帧只改图层的 topLeft/scale/alpha (`GraphicsLayer` 属性 → `RenderNode` 属性), 显示列表一次
 * 录成不再重录。[CompositingStrategy.Offscreen] 让 HWUI 按节点自身尺寸分配离屏表面、绘入时不施
 * 加节点属性, 合成时才 concat 变换贴出 —— 字形只光栅一次, 逐帧一次纹理 blit。
 *
 * 缩放轴心必须显式定到左上角 (默认是节点中心) 才与 [draw] 的 dst 矩形语义一致: HWUI 绘制节点时
 * 先 translate(left, top) 再 concat(绕 pivot 的缩放), 轴心归零后内容 (0,0) 恰落到 dstOffset、
 * (w,h) 落到 dstOffset + dstSize。
 *
 * 合成层贴出时 HWUI 只有双线性采样 (`SkSamplingOptions(kLinear)`, 无 mipmap), 起手那几帧大幅
 * 缩小会比 skiko 端 (FilterQuality.Medium 带 mipmap) 略糙; 但那几帧内容不透明度本就接近 0
 * (见 [ContainerContentFadeOpenness]), 全不透明时缩放已回到约 0.5, 双线性足够。
 */
internal actual class ContainerSnapshot actual constructor() {

    private var size: IntSize = IntSize.Zero

    actual val capturedSize: IntSize get() = size

    actual fun capture(
        scope: DrawScope,
        layer: GraphicsLayer,
        pageSize: IntSize,
        record: () -> Unit,
    ) {
        // 先切离屏合成再录: 变换不参与离屏光栅, 字形按 1:1 只光栅一次
        layer.compositingStrategy = CompositingStrategy.Offscreen
        layer.pivotOffset = Offset.Zero
        record()
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
        if (size.width <= 0 || size.height <= 0) return
        layer.topLeft = dstOffset
        layer.scaleX = dstSize.width.toFloat() / size.width
        layer.scaleY = dstSize.height.toFloat() / size.height
        layer.alpha = alpha
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
            scope.clipPath(path) { drawLayer(layer) }
        } else {
            scope.drawLayer(layer)
        }
    }

    actual fun release(layer: GraphicsLayer) {
        // 图层由调用方 rememberGraphicsLayer 持有并跨段复用, 段结束须把属性归位:
        // 残留的 scale/topLeft 会让下一段的重放歪掉; 回 Auto 同时让 HWUI 不再维持离屏表面
        layer.compositingStrategy = CompositingStrategy.Auto
        layer.pivotOffset = Offset.Unspecified
        layer.topLeft = IntOffset.Zero
        layer.scaleX = 1f
        layer.scaleY = 1f
        layer.alpha = 1f
        size = IntSize.Zero
    }
}
