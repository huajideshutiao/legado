package io.legado.app.ui.compose.component

import androidx.compose.animation.core.animate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 下拉触发阈值 (对照 MD3 PullToRefresh 默认 64dp)
private val PullToRefreshThreshold = 64.dp
// 下拉阻尼系数: 实际偏移 = 滑动距离 * 0.5
private const val DragMultiplier = 0.5f
// 调用方不反馈 isRefreshing, 内部 refreshing 触发后延时重置 (ms)
private const val RefreshingDisplayMs = 1500L

@Composable
fun rememberPullToRefreshState(): PullToRefreshState {
    val thresholdPx = with(LocalDensity.current) { PullToRefreshThreshold.toPx() }
    return remember(thresholdPx) { PullToRefreshState(thresholdPx) }
}

@Stable
class PullToRefreshState internal constructor(
    internal val thresholdPx: Float,
) {
    var offsetPx by mutableStateOf(0f)
        private set
    var refreshing by mutableStateOf(false)
        internal set
    internal var enabled: Boolean = true
    internal var onRefreshCallback: (() -> Unit)? = null

    val nestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (!enabled || refreshing) return Offset.Zero
            // 已有下拉偏移时, 上滑先收回 offset
            if (available.y < 0f && offsetPx > 0f) {
                val consumed = maxOf(available.y, -offsetPx)
                offsetPx += consumed
                return Offset(0f, consumed)
            }
            return Offset.Zero
        }

        override fun onPostScroll(
            consumed: Offset, available: Offset, source: NestedScrollSource,
        ): Offset {
            if (!enabled || refreshing) return Offset.Zero
            // 内容已滚到顶, 继续下拉时累加 offset (阻尼 0.5)
            if (available.y > 0f) {
                offsetPx = (offsetPx + available.y * DragMultiplier)
                    .coerceAtMost(thresholdPx * 2f)
                return Offset(0f, available.y)
            }
            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (!enabled) return Velocity.Zero
            // 释放时超过阈值则触发刷新
            if (offsetPx >= thresholdPx) {
                refreshing = true
                onRefreshCallback?.invoke()
            }
            return Velocity.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            // 释放后回弹到 0
            if (offsetPx > 0f) {
                animate(initialValue = offsetPx, targetValue = 0f) { value, _ ->
                    offsetPx = value
                }
            }
            return Velocity.Zero
        }
    }
}

fun Modifier.pullToRefresh(
    isRefreshing: Boolean,
    state: PullToRefreshState,
    enabled: Boolean = true,
    onRefresh: () -> Unit,
): Modifier = composed {
    val scope = rememberCoroutineScope()
    SideEffect {
        state.enabled = enabled
        state.onRefreshCallback = {
            onRefresh()
            // 调用方固定传 isRefreshing=false, 内部 refreshing 自管理: 触发后延时重置
            scope.launch {
                delay(RefreshingDisplayMs)
                state.refreshing = false
            }
        }
    }
    nestedScroll(state.nestedScrollConnection)
}

object PullToRefreshDefaults {
    @Composable
    fun Indicator(
        state: PullToRefreshState,
        isRefreshing: Boolean,
        modifier: Modifier = Modifier,
        color: Color = Color.Unspecified,
    ) {
    val progress = (state.offsetPx / state.thresholdPx).coerceIn(0f, 1f)
    val refreshingNow = isRefreshing || state.refreshing
    if (!refreshingNow && state.offsetPx <= 0f) return
    Box(
        modifier = modifier
            .size(40.dp)
            .graphicsLayer {
                translationY = state.offsetPx
                alpha = if (refreshingNow) 1f else progress
                // 下拉中按 progress 旋转固定角度 (非无限旋转); 刷新中不旋转 (转圈自带动画)
                rotationZ = if (refreshingNow) 0f else progress * 180f
            },
        contentAlignment = Alignment.Center,
    ) {
        if (refreshingNow) {
            // 刷新中: 不确定性转圈
            CircularProgressIndicator(
                modifier = Modifier.size(40.dp),
                color = color,
                strokeWidth = 2.dp,
            )
        } else {
            // 下拉中: 确定性弧长随 progress 增长
            CircularProgressIndicator(
                progress = progress,
                modifier = Modifier.size(40.dp),
                color = color,
                strokeWidth = 2.dp,
            )
        }
    }
    }
}
