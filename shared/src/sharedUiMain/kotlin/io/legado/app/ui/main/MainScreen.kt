package io.legado.app.ui.main

/*
 * 下沉自 app 端 `MainScreen.kt`。
 *
 * # L3 不可下沉项说明
 *
 * app 端原 `MainScreen(activity: MainActivity)` 直接装配 4 个 app 端 Tab Composable
 * (`HomeTab` / `BookshelfTab` / `ExploreTab` / `MyTab`), 这些 Tab 深度耦合:
 * - `LocalActivity.current as AppCompatActivity` (HomeTab/BookshelfTab/ExploreTab/MyTab)
 * - `ViewModelProvider(activity)` (HomeTab/BookshelfTab/ExploreTab)
 * - 多个 `activity.startActivity<XxxActivity>` 跳转 (HomeTab/BookshelfTab/ExploreTab/MyTab)
 * - `AppConfig` / `ThemeConfig` 单例 (BookshelfTab/ExploreTab)
 * - `eventObservable` / `FlowBus` 事件总线 (BookshelfTab/ExploreTab)
 * - `appDb` 数据库 (BookshelfTab/ExploreTab)
 * - `registerHandleFile` Activity Result API (BookshelfTab)
 * 上述依赖均为 Android 专属或 app 模块内部类, 4 个 Tab 本身属 L3 不可下沉。
 *
 * # 下沉策略: Composable 装配部分下沉, Tab 内容用 lambda 注入
 *
 * - **下沉部分**: HorizontalPager 装配 + 翻页流收集 + currentPage 回写 + MainBottomBar 调用
 *   这部分是纯 Compose 通用逻辑, 不依赖 Android 专属 API
 * - **保留 app 端**: 4 个 Tab Composable 实例通过 `@Composable () -> Unit` lambda 参数注入,
 *   app 端 MainActivity.Content() 在 lambda 内调用 `HomeTab()` / `BookshelfTab(...)`
 *   / `ExploreTab(...)` / `MyTab()`, 内部的 controller 回传/style 读取/Activity 跳转
 *   全部封在 lambda 内, shared 端 MainScreen 无需感知
 *
 * # 设计说明
 *
 * - `pageSelections: SharedFlow<Pair<Int, Boolean>>` 直接传入 (kotlinx.coroutines 跨平台)
 * - `currentPageSink: (Int) -> Unit` 回调替代 `activity.currentPage = it` 写回
 * - `onSelectPage(index, smooth)` / `onReselect(tag)` 回调替代 `activity.selectPage` / `activity.onTabReselect`
 * - `bookshelfTab` lambda 捕获 `bookshelfStyle` state 时, state 变化触发 Content 重组,
 *   新 lambda 传入触发 HorizontalPager content 重组, BookshelfTab 内部 `key(style)` 重建,
 *   语义与原版 `BookshelfTab(style = activity.bookshelfStyle)` 等价
 * - 4 个 tab composable 不在 shared 引用任何 app 端类, ExploreTabController 留 app 端
 *
 * # 资源访问
 *
 * 本文件不直接访问 R.drawable/R.string/R.color, 所有资源由 [MainBottomBar] 通过
 * ResourceProvider key 访问, 见 MainBottomBar.kt 顶部注释清单。
 */

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import io.legado.app.constant.AppLog
import io.legado.app.constant.BottomNavTag
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * 主界面(附录 H): HorizontalPager 四 tab(等价 ViewPager offscreenPageLimit=3 全驻留 + 横滑)
 * + 自绘导航栏。四 tab 均为纯 composable, 由调用方经 lambda 注入; pager 跳转/重选经回调
 * 回传给宿主(供返回键/reselect/recreate 调用)。
 *
 * 导航栏按窗口宽度切换方位: ≥[DesignTokens.wideScreenMinWidth] 用左侧 [MainNavRail], 否则用底部
 * [MainBottomBar]; 两者视觉/配置项完全一致, 只差排列方向。
 *
 * @param visibleTags 可见 tag 列表(BottomNavTag 字符串, 顺序含配置校验)
 * @param initialPage 首页落点(等价旧 upHomePage, 仅初始组合时消费)
 * @param pageSelections 页面跳转指令流: index to smooth
 * @param currentPageSink pager 当前页回写回调(供返回键/重选判定)
 * @param settledPageSink pager 停稳页回写回调(供宿主按"真正翻到该页"门控 tab 内网络加载)
 * @param onSelectPage 跳转页回调(index, smooth)
 * @param onReselect 重选当前 tab 回调(传 tag)
 * @param homeTab 主页 tab composable (app 端注入)
 * @param bookshelfTab 书架 tab composable (app 端注入, 内部读 bookshelfStyle + 回传 controller)
 * @param exploreTab 发现 tab composable (app 端注入, 内部回传 controller)
 * @param myTab 我的 tab composable (app 端注入)
 * @param bottomBarIconSize 底栏图标尺寸 dp (app 端 AppConfig.bottomBarIconSize)
 * @param bottomBarHeight 底栏高度 dp (app 端 AppConfig.bottomBarHeight)
 * @param bottomBarLabelMode 底栏标签模式 (app 端 AppConfig.bottomBarLabelMode)
 */
@Composable
fun MainScreen(
    visibleTags: List<String>,
    initialPage: Int,
    pageSelections: SharedFlow<Pair<Int, Boolean>>,
    currentPageSink: (Int) -> Unit,
    settledPageSink: (Int) -> Unit,
    onSelectPage: (Int, Boolean) -> Unit,
    onReselect: (String) -> Unit,
    homeTab: @Composable () -> Unit,
    bookshelfTab: @Composable () -> Unit,
    exploreTab: @Composable () -> Unit,
    myTab: @Composable () -> Unit,
    bottomBarIconSize: Int,
    bottomBarHeight: Int,
    bottomBarLabelMode: Int,
) {
    val pagerState = rememberPagerState(initialPage = initialPage) { visibleTags.size }

    // visibleTags 随底栏配置变更, 常驻 collector 用 rememberUpdatedState 读最新值
    // (避免 LaunchedEffect(Unit) 捕获首帧旧 visibleTags, 越界守卫失效)
    val currentVisibleTags by rememberUpdatedState(visibleTags)
    LaunchedEffect(Unit) {
        // collect 只做转发, 滚动在独立的一次性协程里执行: animateScrollToPage/scrollToPage
        // 走 PagerState 的 MutatorMutex, 被新 mutator (用户手势/下一次滚动) 抢占时按框架契约
        // 取消当前滚动任务 —— 内联在 collect 里会把该取消异常传播杀掉本协程 (pageSelections
        // replay=0, 消费者永久死亡, 底栏/返回键的后续请求全部静默丢弃, 即"偶尔不可用");
        // launch 后每次被打断只结束那一次滚动, 后到的请求按 mutator 抢占语义取代前一个。
        pageSelections.collect { (index, smooth) ->
            if (index !in currentVisibleTags.indices) return@collect
            launch {
                if (smooth) pagerState.animateScrollToPage(index)
                else pagerState.scrollToPage(index)
            }
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { currentPageSink(it) }
    }
    // 停稳页回写: 对照原版 FragmentStatePagerAdapter(BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) +
    // offscreenPageLimit=3 全预创建——tab 预组合但仅真正翻到的页才加载。settledPage 在拖拽回弹/
    // 滑动动画中不变, 宿主据此门控各 tab 的网络加载, 不会"露一半又滑回"就误触发。
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { settledPageSink(it) }
    }

    // 状态栏回避由各 Tab 顶栏自行处理 (HomeTopBar/BookshelfTopBar/ExploreTitleBar/MyTabTitleBar
    // 均带背景 + statusBarsPadding), 容器层不再重复 padding, 否则顶栏会双倍回避状态栏。

    // 窗口尺寸决定导航栏方位: 最短边(shortestSide) ≥ wideScreenMinWidth 的宽窗口
    // (平板横屏/桌面)走左侧竖排, 手机(横竖屏最短边均 < 600dp)维持底部底栏。
    // 与 Material windowSizeClass Compact/Medium 分界同源(最短边判定),
    // 修复手机横屏宽度 ≥600dp 误触发 NavRail 的问题。
    // derivedStateOf: 拖动窗口时 containerSize 每帧变化, 只在越过阈值时才重组
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val useNavRail by remember(windowInfo, density) {
        derivedStateOf {
            val size = windowInfo.containerSize
            val wDp = with(density) { size.width.toDp() }
            val hDp = with(density) { size.height.toDp() }
            minOf(wDp, hDp) >= DesignTokens.wideScreenMinWidth
        }
    }

    val pager: @Composable (Modifier) -> Unit = { modifier ->
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 3,
            key = { i -> visibleTags[i] },
            modifier = modifier,
        ) { page ->
            when (visibleTags[page]) {
                BottomNavTag.HOME -> homeTab()
                // style 切换由 BookshelfTab 内部 key(style) 重建（旧 adapter POSITION_NONE 语义）
                BottomNavTag.BOOKSHELF -> bookshelfTab()
                BottomNavTag.DISCOVERY -> exploreTab()
                else -> myTab()
            }
        }
    }

    if (useNavRail) {
        Row(Modifier.fillMaxSize()) {
            MainNavRail(
                tags = visibleTags,
                selectedIndex = pagerState.currentPage,
                onSelect = { onSelectPage(it, true) },
                onReselect = onReselect,
                iconSize = bottomBarIconSize,
                labelMode = bottomBarLabelMode,
            )
            pager(Modifier.fillMaxHeight().weight(1f))
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            pager(Modifier.fillMaxWidth().weight(1f))
            MainBottomBar(
                tags = visibleTags,
                selectedIndex = pagerState.currentPage,
                onSelect = { onSelectPage(it, true) },
                onReselect = onReselect,
                iconSize = bottomBarIconSize,
                barHeight = bottomBarHeight,
                labelMode = bottomBarLabelMode,
            )
        }
    }
}
