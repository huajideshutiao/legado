package io.legado.app.ui.main.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.HomeSection
import io.legado.app.data.entities.HomeTab
import io.legado.app.data.entities.SearchBook
import io.legado.app.model.webBook.ExploreOption
import io.legado.app.ui.compose.component.AppScrollTabRow
import io.legado.app.ui.compose.component.PullToRefreshDefaults
import io.legado.app.ui.compose.component.pullToRefresh
import io.legado.app.ui.compose.component.rememberPullToRefreshState
import io.legado.app.ui.compose.component.rememberResponsiveColumns
import io.legado.app.ui.compose.platform.transitionStatusBarPadding
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.LocalEInk
import kotlinx.coroutines.launch
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.bottom_line
import legado.shared.generated.resources.home
import legado.shared.generated.resources.home_manage
import legado.shared.generated.resources.home_tab_empty
import legado.shared.generated.resources.home_tab_manage
import legado.shared.generated.resources.ic_cfg_other
import legado.shared.generated.resources.ic_groups
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/*
 * 下沉所需资源 key 清单 (供 ResourceProvider 各平台 actual 补全)
 *
 * Painter key (drawable):
 *   - ic_cfg_other     主页"管理展示项"图标
 *   - ic_groups        主页"管理分组"图标 (ResourceProvider 已支持)
 *
 * String key (string):
 *   - home              主页标题 (tab 数<=1 时显示)
 *   - home_manage       管理展示项 contentDescription
 *   - home_tab_manage   管理分组 contentDescription
 *   - home_tab_empty    tab 空态文案
 *   - bottom_line       无更多数据文案
 *
 * 平台专属 slot (原 app 端 L3, 现 shared 端纯 Compose 实现兜底):
 *   - SectionBlock (原 HomeSectionComposables.kt): 内含封面 +
 *     ItemExploreVideoBinding (视频卡), 全 L3; 参数 chip 行已下沉为
 *     [io.legado.app.ui.compose.component.ExploreOptionsRow] (纯 Compose)
 *     → sectionBlockSlot: @Composable (tabTitle: String, section: HomeSection) -> Unit
 *   - InfiniteHeader (原 HomeSectionComposables.kt): 同 SectionBlock 标题行+参数 chip 行
 *     → infiniteHeaderSlot: @Composable (tabTitle: String, section: HomeSection) -> Unit
 *   - InfiniteGridCard (原 HomeSectionComposables.kt): 封面/视频卡单元
 *     → infiniteGridCardSlot: @Composable (tabTitle: String, section: HomeSection,
 *       book: SearchBook) -> Unit
 *
 * 原不下沉的相关文件 (全 L3; app 端 HomeSectionComposables.kt 已随 View 版封面组件移除,
 * 现 shared 端纯 Compose 实现):
 *   - HomeSectionComposables.kt: SectionBlock/InfiniteHeader/InfiniteGridCard + 私有
 *     CoverRow/RankColumn/FourRow/NovelCoverCard/RankItem/VideoCardView/SectionOptions
 *     全部依赖 AndroidView/封面组件/ItemExploreVideoBinding/colorResource/stringResource
 *   - HomeSectionEditDialog.kt / HomeSectionManageDialog.kt / HomeTabManageDialog.kt /
 *     HomeTabEditDialog.kt: 已下沉本包 (纯 Compose + HomeTabHelpShared + FlowBus),
 *     弹窗态由 HomeScreenModel 持有, MainRoute 渲染
 *   - HomeViewModel.kt / HomeTabState: 依赖 appDb + HomeTabHelp + LiveData + activity, L3
 *     (HomeTabState 实现 HomeUiActions 桥接); HomeEvents.kt 已下沉 commonMain
 *   - SectionTitleRow: 留在 app HomeSectionComposables.kt (仅该文件使用, 含
 *     R.drawable.ic_arrow_right + R.string.home_more)
 */

/**
 * 主页 UI 展示状态。
 *
 * app 端 [io.legado.app.ui.main.home.HomeTabState] 将自身的 mutableState 字段及
 * SnapshotStateMap 引用打包为本数据类传入; 桌面端/iOS 端可同样构造本类复用 [HomeScreen]。
 *
 * 字段语义对照原 HomeTabState 同名字段/方法:
 * - [tabs]: 当前 tab 列表 (HomeTab 数据实体, commonMain 共享)
 * - [currentPage]: Pager 当前页索引 (仅初始化读; 翻页变化由 [HomeUiActions.onPageChanged] 派发)
 * - [tabSections]: tabTitle -> 该 tab 下的展示项列表 (SnapshotStateMap 引用, 按需订阅)
 * - [sectionBooks]: sectionKey(tabTitle + " " + sectionId) -> 该展示项的书籍列表
 * - [sectionLoading]: 同 key -> 该展示项是否加载中
 * - [sectionError]: 同 key -> 该展示项是否加载出错
 * - [sectionOptions]: 同 key -> 该展示项发现地址的可选参数 (可变 ExploreOption 实例, 点 chip 就地改)
 * - [sectionOptionsVersion]: options 结构变化信号 (ExploreOption 就地可变, 单靠 map 相等性检测不到重解析)
 * - [infiniteHasMore]: tabTitle -> 该 tab 的无限流是否还有更多
 *
 * 注: [sectionBooks] / [sectionLoading] / [sectionError] 的 key 由 [homeSectionKey]
 * 生成, 与 app 端 HomeTabState 内部 key 一致。
 */
data class HomeUiState(
    val tabs: List<HomeTab>,
    val currentPage: Int,
    val tabSections: Map<String, List<HomeSection>>,
    val sectionBooks: Map<String, List<SearchBook>>,
    val sectionLoading: Map<String, Boolean>,
    val sectionError: Map<String, Boolean>,
    val sectionOptions: Map<String, List<ExploreOption>>,
    val sectionOptionsVersion: Int,
    val infiniteHasMore: Map<String, Boolean>,
)

/** 组合 sectionBooks/sectionLoading/sectionError 的 map key (对照 HomeTabState.key) */
fun homeSectionKey(tabTitle: String, sectionId: String): String = "$tabTitle $sectionId"

/**
 * 主页用户交互回调。
 *
 * app 端 [io.legado.app.ui.main.home.HomeTabState] 实现本接口 (已有同名方法直接
 * `override`); 桌面端/iOS 端可自行实现本接口接入各自平台行为。
 *
 * 设计: 返回式回调, 无 Android Context 依赖。所有需要 Android 专属 API 的动作
 * (如 [openManageSection] 调 showDialogFragment、[refreshTab] 调 vm.refreshTab)
 * 由 app 端实现内部桥接到 Activity。
 */
interface HomeUiActions {
    /** 页可见 (对照 HomeTabFragment.onResume→initTab): 首次拉取该 tab 全部展示项 */
    fun onPageVisible(tabTitle: String)
    /** 页变化 (由 Pager 派发, 用于持久化当前页) */
    fun onPageChanged(page: Int)
    /** 打开"管理展示项"对话框 (当前 tab) */
    fun openManageSection()
    /** 打开"管理分组"对话框 */
    fun openManageTab()
    /** 下拉刷新该 tab */
    fun refreshTab(tabTitle: String)
    /** 触底加载更多 (对照原 onScrolled 距末 4 项) */
    fun loadInfinite(tabTitle: String)

    /** 用户切换了某个展示项的参数 chip (对照 HomeSectionAdapter.Callback.onOptionSelected) */
    fun onSectionOptionSelected(tabTitle: String, section: HomeSection)
}

/**
 * 主页内容 (Compose)。无 Fragment 壳, 状态托管在 [HomeUiState], 装配见 app 端
 * `HomeTab()`。顶栏 (分组 tab + 管理入口) + HorizontalPager (替 ViewPager2), 各 tab
 * 页共享 slots。
 *
 * 下沉自 app 端原 `HomeScreen(state: HomeTabState)`, 将 HomeTabState 直接依赖拆为
 * [state] (展示状态) + [actions] (交互回调) + 三个 slot 注入, 去除 Android Context 依赖。
 *
 * 视觉/布局/动画/手势/状态管理完全与 app 端原版一致 (宽高/边距/颜色/层级)。
 *
 * @param sectionBlockSlot 非无限流展示项区块 (含封面 + 视频卡)
 *   - 调用方传入 tabTitle + section, slot 内部自行从 state 取书籍/参数/状态/回调
 * @param infiniteHeaderSlot 无限流头部 (标题行 + 参数 chip 行)
 *   - 调用方传入 tabTitle + section, slot 内部自行从 state 取参数/回调
 * @param infiniteGridCardSlot 无限流网格单元 (含封面/视频卡)
 *   - 调用方传入 tabTitle + section + book, slot 内部自行处理渲染
 *
 * @param active 本 tab 是否为主界面当前页 (对照原版 BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT:
 *   主界面预组合时保持组合但不加载, 真正翻到本 tab 才触发分组加载)
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    actions: HomeUiActions,
    active: Boolean,
    sectionBlockSlot: @Composable (tabTitle: String, section: HomeSection) -> Unit,
    infiniteHeaderSlot: @Composable (tabTitle: String, section: HomeSection) -> Unit,
    infiniteGridCardSlot: @Composable (tabTitle: String, section: HomeSection, book: SearchBook) -> Unit,
) {
    val colors = AppTheme.colors
    val eInk = LocalEInk.current
    val tabs = state.tabs
    val scope = rememberCoroutineScope()
    // 每 tab 的网格滚动状态, 组合存活期间保留分页位置 (对照原 HomeTabState.gridState)
    val gridStates = remember { mutableMapOf<String, LazyGridState>() }
    // 稳定化透传到条目层的 slot: 上层 (MainRoute) 重组会新建 slot lambda 实例,
    // 直接透传时每次上层重组 → 本屏/各 tab 页重组 → LazyVerticalGrid content lambda 换新实例
    // (LazyGridItemProvider 按引用比较) → 全部可见条目全量重组。
    // rememberUpdatedState 桥接: 透传引用恒定, 调用时读最新实现, 行为语义不变。
    val currentSectionBlockSlot = rememberUpdatedState(sectionBlockSlot)
    val stableSectionBlockSlot: @Composable (String, HomeSection) -> Unit = remember {
        { tabTitle, section -> currentSectionBlockSlot.value(tabTitle, section) }
    }
    val currentInfiniteHeaderSlot = rememberUpdatedState(infiniteHeaderSlot)
    val stableInfiniteHeaderSlot: @Composable (String, HomeSection) -> Unit = remember {
        { tabTitle, section -> currentInfiniteHeaderSlot.value(tabTitle, section) }
    }
    val currentInfiniteGridCardSlot = rememberUpdatedState(infiniteGridCardSlot)
    val stableInfiniteGridCardSlot: @Composable (String, HomeSection, SearchBook) -> Unit = remember {
        { tabTitle, section, book -> currentInfiniteGridCardSlot.value(tabTitle, section, book) }
    }
    Column(Modifier.fillMaxSize().background(colors.background)) {
        val pagerState = rememberPagerState(
            initialPage = state.currentPage.coerceAtLeast(0),
            pageCount = { tabs.size },
        )
        // 页停稳 → 记录当前页; 仅当本 tab 为主界面当前页(active)才触发该分组加载 (网络请求)。
        // 对照原版 HomeTabFragment.onResume→initTab: 相邻分组 fragment 预创建但不 RESUMED,
        // 只有真正翻到才 initTab。用 settledPage: 拖拽回弹/滑动动画中不变, "翻到那里"才加载;
        // initTab 内部有 initialized 标记, 翻来翻去不重复发请求。
        LaunchedEffect(pagerState, tabs, active) {
            snapshotFlow { pagerState.settledPage }.collect { page ->
                actions.onPageChanged(page)
                if (active) {
                    tabs.getOrNull(page)?.let { actions.onPageVisible(it.title) }
                }
            }
        }
        HomeTopBar(actions, tabs, pagerState, eInk) { index ->
            scope.launch {
                if (eInk) pagerState.scrollToPage(index)
                else pagerState.animateScrollToPage(index)
            }
        }
        if (tabs.isNotEmpty()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1, // 对照 offscreenPageLimit
                key = { tabs.getOrNull(it)?.title ?: it.toString() },
                userScrollEnabled = !eInk,
            ) { page ->
                tabs.getOrNull(page)?.let { tab ->
                    HomeTabPage(
                        state = state,
                        tabTitle = tab.title,
                        gridState = gridStates.getOrPut(tab.title) { LazyGridState() },
                        actions = actions,
                        sectionBlockSlot = stableSectionBlockSlot,
                        infiniteHeaderSlot = stableInfiniteHeaderSlot,
                        infiniteGridCardSlot = stableInfiniteGridCardSlot,
                    )
                }
            }
        }
    }
}

/** 顶栏: 分组 tab (>1 才显示, 否则标题"主页") + 管理展示项/管理分组入口 (对照 main_home 菜单)。 */
@Composable
private fun HomeTopBar(
    actions: HomeUiActions,
    tabs: List<HomeTab>,
    pagerState: PagerState,
    eInk: Boolean,
    onSelectTab: (Int) -> Unit,
) {
    val colors = AppTheme.colors
    val bg = if (eInk) Color.White else colors.background
    Box(Modifier.fillMaxWidth().background(bg).then(if (eInk) Modifier else Modifier.transitionStatusBarPadding())) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (tabs.size > 1) {
                val selected = pagerState.currentPage.coerceIn(0, tabs.lastIndex)
                AppScrollTabRow(
                    tabCount = tabs.size,
                    selectedIndex = selected,
                    indicatorColor = colors.accent,
                    modifier = Modifier.weight(1f),
                ) { index ->
                    HomeTabItem(
                        title = tabs[index].title,
                        selected = index == selected,
                        onClick = { onSelectTab(index) },
                    )
                }
            } else {
                Text(
                    text = stringResource(Res.string.home),
                    color = colors.primaryText,
                    fontSize = 20.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
            IconButton(onClick = { actions.openManageSection() }) {
                Icon(
                    painter = painterResource(Res.drawable.ic_cfg_other),
                    contentDescription = stringResource(Res.string.home_manage),
                    tint = colors.primaryText,
                )
            }
            IconButton(onClick = { actions.openManageTab() }) {
                Icon(
                    painter = painterResource(Res.drawable.ic_groups),
                    contentDescription = stringResource(Res.string.home_tab_manage),
                    tint = colors.primaryText,
                )
            }
        }
        if (eInk) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.secondaryText.copy(alpha = 0.4f))
                    .align(Alignment.BottomStart),
            )
        }
    }
}

@Composable
private fun HomeTabItem(title: String, selected: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Box(
        Modifier
            // 48dp 对照原版 TabLayout 默认高 (DEFAULT_HEIGHT), 8dp 对照 tabPaddingStart/End
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            color = if (selected) colors.accent else colors.primaryText,
            fontSize = 14.sp,
            maxLines = 1,
        )
    }
}

/**
 * 单个 tab 内容: 整页 2 列网格 (对照原 GridLayoutManager+spanSizeLookup)。
 * 非无限流展示项各占整行 (full span); 无限流的标题/参数行占整行、书籍占单列、底部
 * LoadMore 占整行。下拉刷新触发 refreshTab，指示器跟随该次任务的 sectionLoading。
 */
@Composable
private fun HomeTabPage(
    state: HomeUiState,
    tabTitle: String,
    gridState: LazyGridState,
    actions: HomeUiActions,
    sectionBlockSlot: @Composable (String, HomeSection) -> Unit,
    infiniteHeaderSlot: @Composable (String, HomeSection) -> Unit,
    infiniteGridCardSlot: @Composable (String, HomeSection, SearchBook) -> Unit,
) {
    val colors = AppTheme.colors
    val sections = state.tabSections[tabTitle] ?: emptyList()
    if (sections.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(Res.string.home_tab_empty),
                color = colors.secondaryText,
                fontSize = 14.sp,
            )
        }
        return
    }
    val infinite = sections.find { it.style == HomeSection.STYLE_INFINITE_GRID }
    // 触底自动加载下一页 (对照原 onScrolled 距末 4 项)。loadInfinite 内部有加载中/无更多守卫。
    if (infinite != null) {
        LaunchedEffect(gridState, tabTitle) {
            snapshotFlow {
                val info = gridState.layoutInfo
                (info.visibleItemsInfo.lastOrNull()?.index ?: 0) to info.totalItemsCount
            }.collect { (last, total) ->
                if (total > 0 && last >= total - 4) actions.loadInfinite(tabTitle)
            }
        }
    }
    val pullState = rememberPullToRefreshState()
    val isRefreshing = sections.any { section ->
        state.sectionLoading[homeSectionKey(tabTitle, section.id)] == true
    }
    Box(
        Modifier
            .fillMaxSize()
            .pullToRefresh(
                isRefreshing = isRefreshing,
                state = pullState,
                onRefresh = { actions.refreshTab(tabTitle) },
            ),
    ) {
        LazyVerticalGrid(
            columns = rememberResponsiveColumns(2),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            sections.forEach { section ->
                if (section.style == HomeSection.STYLE_INFINITE_GRID) {
                    item(key = "hdr_${section.id}", span = { GridItemSpan(maxLineSpan) }) {
                        infiniteHeaderSlot(tabTitle, section)
                    }
                    items(
                        state.sectionBooks[homeSectionKey(tabTitle, section.id)] ?: emptyList(),
                        key = { "g_${it.bookUrl}" },
                    ) { book ->
                        infiniteGridCardSlot(tabTitle, section, book)
                    }
                    item(key = "footer_${section.id}", span = { GridItemSpan(maxLineSpan) }) {
                        LoadMoreFooter(
                            loading = state.sectionLoading[homeSectionKey(tabTitle, section.id)] ?: false,
                            hasMore = state.infiniteHasMore[tabTitle] ?: true,
                        )
                    }
                } else {
                    item(key = section.id, span = { GridItemSpan(maxLineSpan) }) {
                        sectionBlockSlot(tabTitle, section)
                    }
                }
            }
        }
        PullToRefreshDefaults.Indicator(
            state = pullState,
            isRefreshing = isRefreshing,
            modifier = Modifier.align(Alignment.TopCenter),
            color = colors.accent,
        )
    }
}

/**
 * 无限流底部加载态 (对照 LoadMoreView 的 view_load_more.xml):
 * 加载中 36dp 转圈 (+8dp 边距, 总高 52dp); 无更多显示 bottom_line (14sp + 12dp 纵向 padding);
 * 空闲时文本 invisible 恒占位 (约 41dp 高空白, 对照原版 tv_text invisible 保留布局空间)。
 */
@Composable
private fun LoadMoreFooter(loading: Boolean, hasMore: Boolean) {
    val colors = AppTheme.colors
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (loading) {
            CircularProgressIndicator(
                color = colors.accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(36.dp).padding(8.dp),
            )
        }
        // 对照 view_load_more.xml tv_text: 恒占位, 无更多时显示 bottom_line
        Text(
            text = if (hasMore) "" else stringResource(Res.string.bottom_line),
            color = colors.secondaryText,
            fontSize = 14.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        )
    }
}
