package io.legado.app.ui.compose.component

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.dp
import io.legado.app.ui.compose.theme.LocalEInk
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
