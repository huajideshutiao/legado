package io.legado.app.ui.main.home

import androidx.compose.runtime.Composable
import io.legado.app.data.entities.HomeSection
import io.legado.app.data.entities.HomeTab
import io.legado.app.data.entities.SearchBook

/**
 * iOS 端主页 tab 入口 (包装 shared/sharedUiMain 的 [HomeScreen])。
 *
 * 阻塞点: HomeUiState 数据源 (HomeTab/HomeSection/SearchBook 列表) 与 3 个 slot
 * (SectionBlock/InfiniteHeader/InfiniteGridCard 均依赖 Android View/Glide) 均未在 iOS 端实现,
 * 本入口仅做 stub 让编译通过, 后续 KP6+ 接入 iOS 平台数据源与 slot 实现。
 */
@Composable
fun IosHomeScreen() {
    // KP-iOS: 空 state, 全部 no-op actions
    val state = HomeUiState(
        tabs = emptyList<HomeTab>(),
        currentPage = 0,
        tabSections = emptyMap(),
        sectionBooks = emptyMap(),
        sectionLoading = emptyMap(),
        sectionError = emptyMap(),
        infiniteHasMore = emptyMap(),
    )
    val actions = object : HomeUiActions {
        override fun onPageVisible(tabTitle: String) {}
        override fun onPageChanged(page: Int) {}
        override fun openManageSection() {}
        override fun openManageTab() {}
        override fun refreshTab(tabTitle: String) {}
        override fun loadInfinite(tabTitle: String) {}
    }
    HomeScreen(
        state = state,
        actions = actions,
        // KP-iOS: 3 个 slot 均依赖 Android View/Glide, stub 空实现
        sectionBlockSlot = { _: String, _: HomeSection -> },
        infiniteHeaderSlot = { _: String, _: HomeSection -> },
        infiniteGridCardSlot = { _: String, _: HomeSection, _: SearchBook -> },
    )
}
