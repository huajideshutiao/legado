package io.legado.app.ui.book.manga.render

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import kotlin.math.abs

/**
 * 桌面端鼠标拖拽翻页手势：鼠标左键按住拖动时滚动漫画列表（横向 LazyRow / 纵向 LazyColumn）。
 *
 * # 背景
 * Compose Desktop 的 Lazy 列表默认不响应鼠标拖拽（只有滚轮能滚动），而 [webtoonGestures]
 * 只处理缩放/平移（scale>1 才消费），scale==1 时鼠标拖动事件无人消费 → 桌面端
 * "按住图片拖动翻页"完全无响应。本处理器补齐这一缺口。
 *
 * # 语义
 * - 仅鼠标（[PointerType.Mouse]）主键生效；触摸/触控板手势不受影响（仍走 Lazy 列表原生拖拽）
 * - 仅未缩放（scale<=1）时接管：放大态交给 [webtoonGestures] 的 pan，不争抢事件
 * - 越过 touchSlop 后才消费事件并滚动列表（与 Lazy 列表触摸拖拽一致，小位移不吞点击，
 *   detectTapGestures 的单击/双击/长按不受影响）
 * - 松手时把速度喂给 [MangaRenderState.flingAfterMouseDrag]：横向走单页 snap 吸附归位，
 *   纵向走普通衰减惯性，与触摸翻页手感一致
 */
internal suspend fun PointerInputScope.mangaMouseDragGestures(state: MangaRenderState) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        // 仅鼠标左键拖拽；触摸仍由 Lazy 列表原生处理 (CMP 1.9.2 的 PointerInputChange 无
        // button 属性, 主键判断放循环内用 PointerEvent.buttons.isPrimaryPressed)
        if (down.type != PointerType.Mouse) {
            return@awaitEachGesture
        }
        // 放大态由 webtoonGestures 的 pan 接管（与触摸语义一致），不抢事件
        if (state.canPan()) {
            return@awaitEachGesture
        }
        val touchSlop = viewConfiguration.touchSlop
        var lastPos = down.position
        var dragging = false
        val velocityTracker = VelocityTracker()
        velocityTracker.addPointerInputChange(down)
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                // 松手：有实际拖动才给惯性/吸附（单击不消费，tap 检测照常触发）
                if (dragging) {
                    val velocity = velocityTracker.calculateVelocity()
                    state.flingAfterMouseDrag(
                        if (state.horizontal) velocity.x else velocity.y
                    )
                }
                break
            }
            // 非左键(中/右键)按下时结束拖拽, 不消费 (点击/右键菜单不受影响)
            if (!event.buttons.isPrimaryPressed) {
                break
            }
            if (change.isConsumed) {
                // 其他手势层（webtoonGestures pan）已接管本次拖动
                break
            }
            if (!dragging) {
                val moved = abs(change.position.x - lastPos.x) > touchSlop ||
                    abs(change.position.y - lastPos.y) > touchSlop
                if (!moved) continue
                dragging = true
                // 越过 slop 的第一帧以当前位置为基准（吞掉 slop 前置位移，与列表触摸拖拽一致）
                lastPos = change.position
            }
            val delta = change.position - lastPos
            lastPos = change.position
            velocityTracker.addPointerInputChange(change)
            // 拖动方向与列表滚动方向相反：内容下拉 → 向前一页（对照 panBy 的 -d/scale 约定）
            state.listState.dispatchRawDelta(
                if (state.horizontal) -delta.x else -delta.y
            )
            change.consume()
        }
    }
}
