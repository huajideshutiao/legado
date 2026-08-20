package io.legado.app.ui.compose.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.lerp
import io.legado.app.ui.compose.theme.LocalEInk
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * KMP-pure 双指缩放/平移 Modifier，复刻 TouchImageView 交互语义：
 * 双指捏合以质心为锚缩放、拖拽平移、缩放态钳制到边界、双击在 1x/[doubleTapScale] 间循环。
 * 松手后按速度做 fling 衰减；E-Ink 下禁用 fling 与双击动画。[onLongPress] 供长按保存等场景使用。
 * [onTap] 供全屏看图单击关闭等场景使用，与双击互斥（超时判定后才回调）。
 */
@Composable
fun Modifier.zoomable(
    maxScale: Float = 4f,
    doubleTapScale: Float = 2f,
    contentAspectRatio: Float? = null,
    onLongPress: (() -> Unit)? = null,
    onTap: (() -> Unit)? = null,
): Modifier {
    val eInk = LocalEInk.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val decaySpec = remember(density) { splineBasedDecay<Float>(density) }
    // 两个 pointerInput(Unit) 的手势协程启动后不再重启, 经 rememberUpdatedState 读最新值:
    // 比例/上限/回调在组合中变化(如 PhotoDialog 静态图切 GIF 帧改宽高比)时, 手势内仍读到旧值
    val currentMaxScale by rememberUpdatedState(maxScale)
    val currentDoubleTapScale by rememberUpdatedState(doubleTapScale)
    val currentAspectRatio by rememberUpdatedState(contentAspectRatio)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnTap by rememberUpdatedState(onTap)

    var scale by remember { mutableFloatStateOf(1f) }
    // 平移量用普通状态: 手势块是受限挂起作用域 (awaitPointerEventScope), 双击动画回调是非挂起,
    // 两处都不能调 Animatable.snapTo; Animatable 只作 fling 衰减驱动, 每帧回写到这两个状态
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var flingJob by remember { mutableStateOf<Job?>(null) }

    // 钳制语义对齐 TouchImageView：以 Fit 拟合后的内容尺寸为准，放大超出容器的轴向
    // 边缘贴容器边缘，未超出的轴向保持居中——容器尺寸只在无宽高比信息时兜底
    fun maxOffsetX(s: Float): Float {
        val w = size.width.toFloat(); val h = size.height.toFloat()
        val ar = currentAspectRatio
        val cw = if (ar != null && ar > 0f && w > 0f && h > 0f)
            (if (ar > w / h) w else h * ar) else w
        return ((cw * s - w) / 2f).coerceAtLeast(0f)
    }
    fun maxOffsetY(s: Float): Float {
        val w = size.width.toFloat(); val h = size.height.toFloat()
        val ar = currentAspectRatio
        val ch = if (ar != null && ar > 0f && w > 0f && h > 0f)
            (if (ar > w / h) w / ar else h) else h
        return ((ch * s - h) / 2f).coerceAtLeast(0f)
    }
    fun clampX(o: Float, s: Float) = maxOffsetX(s).let { o.coerceIn(-it, it) }
    fun clampY(o: Float, s: Float) = maxOffsetY(s).let { o.coerceIn(-it, it) }
    fun clamp(o: Offset, s: Float) = Offset(clampX(o.x, s), clampY(o.y, s))

    // fling 的 Animatable 边界只在衰减期间有效，取消时清掉，避免旧 scale 的边界钳住后续手势
    fun cancelFling() {
        flingJob?.cancel(); flingJob = null
        offsetX.updateBounds(null, null)
        offsetY.updateBounds(null, null)
    }

    // 松手 fling：对齐 OverScroller 语义，x/y 各自 spline 衰减、触边即停；E-Ink 与未缩放态不启动
    fun startFling(vx: Float, vy: Float) {
        if (eInk) return
        if (scale <= 1f) return
        cancelFling()
        val s = scale
        flingJob = scope.launch {
            // 衰减前把当前平移量灌进 Animatable, 每帧经 block 回写
            offsetX.snapTo(panX)
            offsetY.snapTo(panY)
            maxOffsetX(s).let { offsetX.updateBounds(-it, it) }
            maxOffsetY(s).let { offsetY.updateBounds(-it, it) }
            coroutineScope {
                launch { offsetX.animateDecay(vx, decaySpec) { panX = value } }
                launch { offsetY.animateDecay(vy, decaySpec) { panY = value } }
            }
        }
    }

    return this
        .onSizeChanged { size = it }
        .pointerInput(Unit) {
            // 阈值对齐 GestureDetector：MINIMUM_FLING_VELOCITY 50dp/s、MAXIMUM 8000dp/s
            val minFlingVelocity = 50.dp.toPx()
            val maxFlingVelocity = 8000.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                cancelFling()
                val velocityTracker = VelocityTracker()
                velocityTracker.addPointerInputChange(down)
                var lastPos = down.position
                var panning = false
                var pinchActive = false
                var everPinched = false
                val touchSlop = viewConfiguration.touchSlop

                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) {
                        // 对齐 GestureDetector.onFling：末指速度过阈值即 fling，与是否 pinch 过无关
                        val v = velocityTracker.calculateVelocity()
                        if (abs(v.x) > minFlingVelocity || abs(v.y) > minFlingVelocity) {
                            startFling(
                                v.x.coerceIn(-maxFlingVelocity, maxFlingVelocity),
                                v.y.coerceIn(-maxFlingVelocity, maxFlingVelocity),
                            )
                        }
                        break
                    }
                    if (pressed.size >= 2) {
                        if (!pinchActive) {
                            pinchActive = true; everPinched = true
                        }
                        if (pinchActive) {
                            val zoom = event.calculateZoom()
                            val centroid = event.calculateCentroid()
                            val pan = event.calculatePan()
                            val s0 = scale
                            val s1 = (s0 * zoom).coerceIn(1f, currentMaxScale)
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val d = centroid - center
                            val k = s1 / s0
                            scale = s1
                            val curOffset = Offset(panX, panY)
                            val newOffset = clamp(curOffset * k + d * (1 - k) + pan, s1)
                            panX = newOffset.x
                            panY = newOffset.y
                            event.changes.fastForEach { it.consume() }
                        }
                    } else {
                        val change = pressed[0]
                        if (pinchActive) {
                            // 对齐原版 onScaleEnd→NONE：pinch 后单指本手势内不再平移；
                            // tracker 混入过双指坐标，重置后按当前指重新积累速度
                            pinchActive = false
                            panning = false
                            lastPos = change.position
                            velocityTracker.resetTracking()
                        }
                        when {
                            panning -> {
                                val delta = change.position - lastPos
                                lastPos = change.position
                                velocityTracker.addPointerInputChange(change)
                                val cur = Offset(panX, panY)
                                val newOffset = clamp(cur + delta, scale)
                                panX = newOffset.x
                                panY = newOffset.y
                                change.consume()
                            }
                            everPinched -> change.consume()
                            else -> {
                                velocityTracker.addPointerInputChange(change)
                                if (scale > 1f &&
                                    (abs(change.position.x - lastPos.x) > touchSlop ||
                                     abs(change.position.y - lastPos.y) > touchSlop)) {
                                    panning = true
                                    lastPos = change.position
                                }
                            }
                        }
                    }
                }
            }
        }
        .pointerInput(Unit) {
            detectTapGestures(
                onLongPress = currentOnLongPress?.let { { _: Offset -> it() } },
                onTap = currentOnTap?.let { { _: Offset -> it() } },
                onDoubleTap = { tap ->
                    cancelFling()
                    val s0 = scale
                    val s1 = if (s0 > 1f) 1f else currentDoubleTapScale
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val d = tap - center
                    val k = s1 / s0
                    val startOffset = Offset(panX, panY)
                    val targetOffset = clamp(startOffset * k + d * (1 - k), s1)
                    if (eInk) {
                        scale = s1
                        panX = targetOffset.x
                        panY = targetOffset.y
                    } else {
                        val startScale = s0
                        scope.launch {
                            animate(0f, 1f) { t, _ ->
                                scale = lerp(startScale, s1, t)
                                // animate 回调非挂起, 逐帧写状态而非 snapTo
                                panX = lerp(startOffset.x, targetOffset.x, t)
                                panY = lerp(startOffset.y, targetOffset.y, t)
                            }
                        }
                    }
                }
            )
        }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationX = panX
            translationY = panY
        }
}
