package io.legado.app.ui.compose.component

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.lerp
import io.legado.app.ui.compose.theme.LocalEInk
import kotlinx.coroutines.launch

/**
 * KMP-pure 双指缩放/平移 Modifier，复刻 TouchImageView 交互语义：
 * 双指捏合以质心为锚缩放、拖拽平移、缩放态钳制到边界、双击在 1x/[doubleTapScale] 间循环。
 * E-Ink 下双击不走动画。[onLongPress] 供长按保存等场景使用。
 */
@Composable
fun Modifier.zoomable(
    maxScale: Float = 4f,
    doubleTapScale: Float = 2f,
    contentAspectRatio: Float? = null,
    onLongPress: (() -> Unit)? = null,
): Modifier {
    val eInk = LocalEInk.current
    val scope = rememberCoroutineScope()
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    // 钳制语义对齐 TouchImageView：以 Fit 拟合后的内容尺寸为准，放大超出容器的轴向
    // 边缘贴容器边缘，未超出的轴向保持居中——容器尺寸只在无宽高比信息时兜底
    fun clamp(o: Offset, s: Float): Offset {
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        val ar = contentAspectRatio
        val (cw, ch) = if (ar != null && ar > 0f && w > 0f && h > 0f) {
            if (ar > w / h) w to w / ar else h * ar to h
        } else w to h
        val maxX = ((cw * s - w) / 2f).coerceAtLeast(0f)
        val maxY = ((ch * s - h) / 2f).coerceAtLeast(0f)
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    return this
        .onSizeChanged { size = it }
        .pointerInput(Unit) {
            detectTransformGestures { centroid, pan, zoom, _ ->
                val s0 = scale
                val s1 = (s0 * zoom).coerceIn(1f, maxScale)
                val center = Offset(size.width / 2f, size.height / 2f)
                val d = centroid - center
                val k = s1 / s0
                // graphicsLayer 以中心为原点缩放，保持质心下的内容点不动再叠加平移
                scale = s1
                offset = clamp(offset * k + d * (1 - k) + pan, s1)
            }
        }
        .pointerInput(Unit) {
            detectTapGestures(
                onLongPress = onLongPress?.let { { _: Offset -> it() } },
                onDoubleTap = { tap ->
                    val s0 = scale
                    val s1 = if (s0 > 1f) 1f else doubleTapScale
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val d = tap - center
                    val k = s1 / s0
                    val targetOffset = clamp(offset * k + d * (1 - k), s1)
                    if (eInk) {
                        scale = s1
                        offset = targetOffset
                    } else {
                        val startScale = s0
                        val startOffset = offset
                        scope.launch {
                            animate(0f, 1f) { t, _ ->
                                scale = lerp(startScale, s1, t)
                                offset = lerp(startOffset, targetOffset, t)
                            }
                        }
                    }
                }
            )
        }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationX = offset.x
            translationY = offset.y
        }
}
