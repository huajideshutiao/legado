package io.legado.app.ui.book.manga.render

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.util.fastForEach
import kotlin.math.abs

/**
 * 原 WebtoonFrame 触摸语义，在 Initial pass 先于列表观察/拦截：
 * 双指捏合缩放(焦点锚定+双指拖动)、放大态单指越 slop 后拦截平移、
 * pan 中加指不进缩放、捏合后剩单指不响应。消费事件即取消列表滚动与 tap 检测。
 */
internal suspend fun PointerInputScope.webtoonGestures(state: MangaRenderState) {
    awaitEachGesture {
        // 原语义：DOWN 不 cancel 动画——双击第二个 DOWN 时归位动画已由 tap 检测启动
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val touchSlop = viewConfiguration.touchSlop
        val velocityTracker = VelocityTracker()
        velocityTracker.addPointerInputChange(down)
        var lastPos = down.position // 未 pan 时存 DOWN 起点用于 slop 比较；pan 中存上一帧位置
        var panning = false
        var pinchActive = false
        var everPinched = false
        var prevPressedCount = 1
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) {
                if (pinchActive) state.endPinch()
                if (panning) state.finishPan(velocityTracker.calculateVelocity())
                break
            }
            if (pressed.size >= 2) {
                if (!panning) {
                    // pan 中加指不进缩放(原 onScaleBegin 拒绝)，只暂停 pan
                    if (!pinchActive) {
                        pinchActive = true
                        everPinched = true
                        state.beginPinch()
                    }
                    val zoom = event.calculateZoom()
                    val focus = event.calculateCentroid(useCurrent = true)
                    val prevFocus = event.calculateCentroid(useCurrent = false)
                    if (focus.isSpecified && prevFocus.isSpecified) {
                        state.pinchBy(prevFocus, focus, zoom)
                    }
                }
                event.changes.fastForEach { it.consume() }
            } else {
                val change = pressed[0]
                if (pinchActive) {
                    // 2→1：原 onScaleEnd(缩小过头橡皮筋弹回)；剩余单指不再响应
                    pinchActive = false
                    state.endPinch()
                }
                when {
                    panning -> {
                        if (prevPressedCount > 1) {
                            // 多指回到单指：pan 基准切到留下的那根，避免位移跳变
                            lastPos = change.position
                        } else {
                            val delta = change.position - lastPos
                            lastPos = change.position
                            velocityTracker.addPointerInputChange(change)
                            state.panBy(delta)
                        }
                        change.consume()
                    }

                    everPinched -> change.consume() // 捏合后剩单指：吞掉等全抬

                    else -> {
                        velocityTracker.addPointerInputChange(change)
                        // 已放大且单指拖动越过 touchSlop → 拦截做 pan
                        if (state.canPan() &&
                            (abs(change.position.x - lastPos.x) > touchSlop ||
                                abs(change.position.y - lastPos.y) > touchSlop)
                        ) {
                            panning = true
                            state.beginPan()
                            lastPos = change.position
                            change.consume()
                        }
                    }
                }
            }
            prevPressedCount = pressed.size
        }
    }
}
