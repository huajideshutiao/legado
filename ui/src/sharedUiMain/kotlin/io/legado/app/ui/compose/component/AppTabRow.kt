package io.legado.app.ui.compose.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.dp
import io.legado.app.ui.compose.theme.LocalEInk
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private data class TabPos(val x: Float = 0f, val w: Float = 0f)

/**
 * 一格滚轮位移换算成横向像素: 视口宽 / 20 为一行高, 每格滚轮走 3 行
 * (对齐 WindowsWinUIConfig 每格 3 行惯例)。
 */
internal fun wheelScrollPixels(delta: Float, viewportWidthPx: Float): Float =
    delta * viewportWidthPx / 20f * 3f

/**
 * 为横向滚动组件附加鼠标滚轮支持 (复用 AppScrollTabRow / 书架 tab / 首页封面行统一逻辑)。
 *
 * 鼠标滚轮向下 (delta.y > 0) = 向右滚动 (scrollBy 正值); 滚轮向右 (delta.x > 0) = 向右滚动;
 * 到头不消费, 事件继续冒泡给外层垂直滚动。
 */
@Composable
fun Modifier.horizontalMouseWheel(scrollState: ScrollState): Modifier =
    verticalWheelToHorizontalScroll(scrollState) { scrollState.viewportSize.toFloat() }

/**
 * [horizontalMouseWheel] 的 LazyRow 版: 倍率与"到头不吞事件"语义与 ScrollState 版一致。
 * 惰性横向列表无法用 horizontalScroll, 滚轮平移走 LazyListState 自己的 scrollBy。
 */
@Composable
fun Modifier.horizontalMouseWheel(listState: LazyListState): Modifier =
    verticalWheelToHorizontalScroll(listState) { listState.layoutInfo.viewportSize.width.toFloat() }

/**
 * 竖向滚轮转横向滚动 (ScrollState 与 LazyListState 共用这一份手势与滚动调度)。
 *
 * 只转未消费的竖向分量: 框架自己的滚轮处理按角度判轴, 横向可滚动组件收到纯竖向滚轮位移时
 * 判定结果是 0 (Scrollable.toSingleAxisDeltaFromAngle), 不会消费; 横向分量与框架能消费的
 * 事件一律交给框架, 这边看到已消费就直接放行, 一格滚轮不会滚两次。
 *
 * 滚动走 [MutatePriority.UserInput]: 这是用户输入, 同优先级的程序滚动 (吸附/选中项居中)
 * 由 MutatorMutex 的仲裁终止, 不会出现事件已消费但滚动被低优先级程序滚动挡住的丢步。
 * 每次滚动单独起 Job: 抢占只取消这一次滚动, 收事件的循环与通道继续为后续滚轮服务。
 */
@Composable
private fun Modifier.verticalWheelToHorizontalScroll(
    state: ScrollableState,
    viewportWidth: () -> Float,
): Modifier {
    val wheelDeltas = remember(state) { Channel<Float>(Channel.UNLIMITED) }
    LaunchedEffect(wheelDeltas) {
        for (first in wheelDeltas) {
            var delta = first
            // 连续滚轮合并成一次滚动 (与框架 MouseWheelScrollingLogic 合并待处理位移同口径),
            // 避免逐格重启滚动而丢掉位移
            while (true) {
                val pending = wheelDeltas.tryReceive().getOrNull() ?: break
                delta += pending
            }
            launch {
                state.scroll(MutatePriority.UserInput) {
                    scrollBy(delta)
                }
            }
        }
    }
    return this.pointerInput(state) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type != PointerEventType.Scroll) continue
                val change = event.changes.firstOrNull() ?: continue
                if (change.isConsumed) continue
                // 只接竖向分量: 纯横向滚轮交给框架自己的滚轮处理
                val delta = change.scrollDelta.y
                if (delta == 0f) continue
                // 到头不吞事件, 放行给父级 (嵌在可竖滚页面里滚轮继续滚页面)
                if (delta > 0f && !state.canScrollForward) continue
                if (delta < 0f && !state.canScrollBackward) continue
                wheelDeltas.trySend(wheelScrollPixels(delta, viewportWidth()))
                change.consume()
            }
        }
    }
}

/**
 * 复刻 TabLayout MODE_SCROLLABLE(view_tab_layout_min)：tab 宽度随内容
 * (material 的 ScrollableTabRow — M2/M3 同样 — 硬编码 90dp 最小 tab 宽，会拉大 tab 间距，不可用)。
 * 2dp 指示条画在行底随选中平移，选中项滚至可视区居中；E-Ink 均不动画。
 */
@Composable
fun AppScrollTabRow(
    tabCount: Int,
    selectedIndex: Int,
    indicatorColor: Color,
    modifier: Modifier = Modifier,
    tab: @Composable (Int) -> Unit,
) {
    val eInk = LocalEInk.current
    val scrollState = rememberScrollState()
    // 每 tab 的 x/宽(px)，onPlaced 回填
    val positions = remember(tabCount) { mutableStateListOf(*Array(tabCount) { TabPos() }) }
    val target = positions.getOrNull(selectedIndex) ?: TabPos()
    val animX by animateFloatAsState(target.x, label = "tabIndicatorX")
    val animW by animateFloatAsState(target.w, label = "tabIndicatorW")
    // 选中变化时滚入可视区(对照 TabLayout 自动滚动)；等 onPlaced 回填后只滚一次。
    // key 补 tabCount: positions 以 tabCount 重建, 否则 effect 挂在旧实例的 flow 上永远等不到回填
    LaunchedEffect(selectedIndex, tabCount) {
        val pos = snapshotFlow { positions.getOrNull(selectedIndex) }
            .filterNotNull()
            .first { it.w > 0f }
        if (scrollState.viewportSize <= 0) return@LaunchedEffect
        val to = (pos.x + pos.w / 2 - scrollState.viewportSize / 2)
            .roundToInt().coerceIn(0, scrollState.maxValue)
        if (eInk) scrollState.scrollTo(to) else scrollState.animateScrollTo(to)
    }
    Row(
        modifier
            .horizontalScroll(scrollState)
            .horizontalMouseWheel(scrollState)
            .drawBehind {
                val x = if (eInk) target.x else animX
                val w = if (eInk) target.w else animW
                if (w <= 0f) return@drawBehind
                val h = 2.dp.toPx() // TabLayout 默认 tabIndicatorHeight
                drawRect(indicatorColor, Offset(x, size.height - h), Size(w, h))
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(tabCount) { i ->
            Box(
                Modifier.onPlaced { c ->
                    val p = TabPos(c.positionInParent().x, c.size.width.toFloat())
                    if (positions.getOrNull(i) != p) positions[i] = p
                },
            ) { tab(i) }
        }
    }
}
