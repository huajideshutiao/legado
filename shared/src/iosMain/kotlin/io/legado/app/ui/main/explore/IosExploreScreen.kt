package io.legado.app.ui.main.explore

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.PinnedExplore
import io.legado.app.data.entities.rule.ExploreKind

/**
 * iOS 端发现 tab 入口 (包装 shared/sharedUiMain 的 [ExploreScreen])。
 *
 * 阻塞点: ExploreUiState 数据源 (BookSourcePart/PinnedExplore 列表) 与
 * ExploreUiActions (DB 写入/Activity 跳转/JS 执行) 均未在 iOS 端实现,
 * 本入口仅做 stub 让编译通过, 后续 KP6+ 接入 iOS 平台数据源与 actions 实现。
 */
@Composable
fun IosExploreScreen() {
    val listState = rememberLazyListState()
    // KP-iOS: 空 state, 全部 no-op actions
    val state = ExploreUiState(
        sources = emptyList(),
        pinned = emptyList(),
        groups = emptyList(),
        searchKey = "",
        expandedUrl = null,
        expandedKinds = emptyMap(),
        expandedLoading = emptySet(),
        listState = listState,
    )
    val actions = object : ExploreUiActions {
        override fun onSearch(query: String) {}
        override fun onGroup(group: String) {}
        override fun onToggleExpand(item: BookSourcePart) {}
        override fun onOpenPinned(pin: PinnedExplore) {}
        override fun onRemovePinned(pin: PinnedExplore) {}
        override fun onOpenExplore(source: BookSource, title: String, exploreUrl: String?) {}
        override fun onShowKindError(kind: ExploreKind) {}
        override fun onRunKindJs(source: BookSource, js: String) {}
        override fun onEditSource(sourceUrl: String) {}
        override fun onToTop(source: BookSourcePart) {}
        override fun onLogin(source: BookSourcePart) {}
        override fun onSearchBook(source: BookSourcePart) {}
        override fun onRefreshSource(source: BookSourcePart) {}
        override fun onDeleteSource(source: BookSourcePart) {}
    }
    ExploreScreen(state = state, actions = actions)
}
