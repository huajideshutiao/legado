package io.legado.app.ui.compose.component

import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.PullRefreshState as MaterialPullRefreshState
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState as rememberMaterialPullRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import io.legado.app.ui.compose.theme.AppTheme

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun rememberPullToRefreshState(): PullToRefreshState {
    val state = remember { PullToRefreshState() }
    state.pullRefreshState = rememberMaterialPullRefreshState(
        refreshing = state.externalRefreshing,
        onRefresh = state::refresh,
    )
    return state
}

@OptIn(ExperimentalMaterialApi::class)
@Stable
class PullToRefreshState internal constructor() {
    internal lateinit var pullRefreshState: MaterialPullRefreshState
    internal var externalRefreshing by mutableStateOf(false)
    internal var enabled = true
    internal var onRefreshCallback: (() -> Unit)? = null

    internal fun refresh() {
        if (!enabled || externalRefreshing) return
        onRefreshCallback?.invoke()
    }
}

@OptIn(ExperimentalMaterialApi::class)
fun Modifier.pullToRefresh(
    isRefreshing: Boolean,
    state: PullToRefreshState,
    enabled: Boolean = true,
    onRefresh: () -> Unit,
): Modifier = composed {
    SideEffect {
        state.externalRefreshing = isRefreshing
        state.enabled = enabled
        state.onRefreshCallback = onRefresh
    }
    pullRefresh(
        state = state.pullRefreshState,
        enabled = enabled && !isRefreshing,
    )
}

object PullToRefreshDefaults {
    @OptIn(ExperimentalMaterialApi::class)
    @Composable
    fun Indicator(
        state: PullToRefreshState,
        isRefreshing: Boolean,
        modifier: Modifier = Modifier,
        color: Color = Color.Unspecified,
    ) {
        PullRefreshIndicator(
            refreshing = isRefreshing,
            state = state.pullRefreshState,
            modifier = modifier,
            backgroundColor = AppTheme.colors.bottomBackground,
            contentColor = color.takeIf { it != Color.Unspecified } ?: AppTheme.colors.accent,
        )
    }
}
