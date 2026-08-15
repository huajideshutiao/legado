package io.legado.app.ui.compose.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
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
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

private data class TabPos(val x: Float = 0f, val w: Float = 0f)

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
    // 滚轮 → 横向滚动的事件通道 (CMP 1.10+ 的 pointerInput block 无 CoroutineScope,
    // 官方 ScrollableNode 同款模式: 事件层 trySend(非 suspend), 消费协程在普通 scope):
    val scrollChannel = remember { Channel<Float>(Channel.UNLIMITED) }
    LaunchedEffect(scrollChannel) {
        for (delta in scrollChannel) {
            scrollState.scrollBy(delta)
        }
    }
    // 每 tab 的 x/宽(px)，onPlaced 回填
    val positions = remember(tabCount) { mutableStateListOf(*Array(tabCount) { TabPos() }) }
    val target = positions.getOrNull(selectedIndex) ?: TabPos()
    val animX by animateFloatAsState(target.x, label = "tabIndicatorX")
    val animW by animateFloatAsState(target.w, label = "tabIndicatorW")
    // 选中变化时滚入可视区(对照 TabLayout 自动滚动)；等 onPlaced 回填后只滚一次
    LaunchedEffect(selectedIndex) {
        val pos = snapshotFlow { positions.getOrNull(selectedIndex) }
            .first { it != null && it.w > 0f } ?: return@LaunchedEffect
        if (scrollState.viewportSize <= 0) return@LaunchedEffect
        val to = (pos.x + pos.w / 2 - scrollState.viewportSize / 2)
            .roundToInt().coerceIn(0, scrollState.maxValue)
        if (eInk) scrollState.scrollTo(to) else scrollState.animateScrollTo(to)
    }
    Row(
        modifier
            .horizontalScroll(scrollState)
            .drawBehind {
                val x = if (eInk) target.x else animX
                val w = if (eInk) target.w else animW
                if (w <= 0f) return@drawBehind
                val h = 2.dp.toPx() // TabLayout 默认 tabIndicatorHeight
                drawRect(indicatorColor, Offset(x, size.height - h), Size(w, h))
            }
            // 鼠标滚轮 → 横向滚动标签 (对照阅读器滚轮处理惯例, 见 ReaderRoute):
            // CMP 的 scrollDelta 是格数 (preciseWheelRotation 透传, Windows 一格 = 1.0, 非像素),
            // 高精度滚轮 (小数 delta) 自然细分;
            // 方向按垂直语义旋转 90°: 滚轮向下 (delta > 0) = 查看右侧标签 (内容左移 = scrollBy 正);
            // 倍率对照官方 WindowsWinUIConfig: 一格 = 视口宽/20 × 系统每格行数 (默认 3);
            // 置于 horizontalScroll 之后 (命中链更内层) 先消费, 官方 mouseWheel 见已消费即跳过,
            // 且不会误触外层 HorizontalPager (滚轮不再切分组)。
            .pointerInput(scrollState) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Scroll) continue
                        val change = event.changes.firstOrNull() ?: continue
                        val delta = change.scrollDelta.y
                        if (delta == 0f) continue
                        val viewportW = size.width.toFloat()
                        if (viewportW > 0f) {
                            scrollChannel.trySend(delta * viewportW / 20f * 3f)
                        }
                        change.consume()
                    }
                }
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
