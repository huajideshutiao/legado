package io.legado.app.ui.main.explore

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.PinnedExplore
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.data.entities.rule.RowUi
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppSearchField
import io.legado.app.ui.compose.component.GridPackLayout
import io.legado.app.ui.compose.component.estimateGridHeight
import io.legado.app.ui.compose.component.toGridPackSpec
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.rememberNavigationBarPaddingValues
import io.legado.app.ui.compose.platform.transitionStatusBarPadding
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.compose.theme.LocalEInk
import kotlinx.coroutines.delay
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.delete
import legado.shared.generated.resources.edit
import legado.shared.generated.resources.explore_empty
import legado.shared.generated.resources.favorite
import legado.shared.generated.resources.group
import legado.shared.generated.resources.ic_arrow_back
import legado.shared.generated.resources.ic_arrow_right
import legado.shared.generated.resources.ic_groups
import legado.shared.generated.resources.login
import legado.shared.generated.resources.refresh
import legado.shared.generated.resources.search
import legado.shared.generated.resources.search_book_source
import legado.shared.generated.resources.to_top
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/*
 * 下沉所需资源 key 清单 (供 ResourceProvider 各平台 actual 补全)
 *
 * Painter key (drawable):
 *   - ic_arrow_right     发现项展开箭头 (右侧 90° 旋转)
 *   - ic_groups          分组溢出菜单图标
 *
 * String key (string):
 *   - explore_empty      发现为空文案
 *   - favorite            收藏区标题
 *   - search_book_source 搜索框 hint
 *   - group              分组菜单 contentDescription
 *   - edit / to_top / login / search / refresh / delete  发现项菜单文本
 *
 * L3 不可下沉项 (保留 app 端, 由 ExploreTabState 内部桥接):
 *   - ThemeConfig.curBgImagePath (顶栏背景透明判断)
 *     → 替换为 LocalThemeStoreProvider.current.bgImagePath (跨平台 provider)
 *   - item.getBookSource() + item.exploreKinds() (DB/规则解析, IO 协程)
 *     → 由 app 端 ExploreTabState 在 toggleExpand/refreshSource 内加载并写入
 *       state.expandedKinds / state.expandedLoading, shared 端仅读渲染
 *   - ExploreViewModel.topSource / deleteSource (DB 写)
 *     → app 端 ExploreTabState.toTop / deleteSource 内调用
 *   - 路由跳转 (startActivity<ExploreShowActivity/BookSourceEditActivity/SearchActivity>)
 *     → actions.onOpenExplore/onEditSource/onSearchBook 回调, app 端实现
 *   - showLoginDialog / showDialogFragment(TextDialog) / alert / toastOnUi
 *     → actions.onLogin/onShowKindError/onRemovePinned 回调, app 端实现
 *   - runScriptWithContext (kind button JS 执行)
 *     → actions.onRunKindJs 回调, app 端实现
 *
 * Color 复用: transparent10 顶栏分组背景 (light #10000000 / dark #10ffffff)
 *   → 内联 isDark 计算, 复刻 R.color.transparent10 (与 AppSearchField.fillStroke 同源)
 */

/** 展开/收起动画时长, 对照原 ExploreAdapter.EXPAND_DURATION_MS (Material standard) */
private const val EXPAND_DURATION_MS = 220

/** 分类网格单行最小高度 (GridPackLayout rowUnitMinHeight; 原 tv.minimumHeight = viewHeight.large) */
private val KIND_ROW_MIN_HEIGHT = 40.dp

/**
 * 展开动画结束后的单次复查缓冲: 覆盖展开动画晚一帧开始 (animateIn 翻转) + 帧渲染余量。
 * 注: 假设系统动画时长缩放 = 1; 被放大时复查按"当前实测"兜底, 属尽力而为 (预滚本身不受影响)。
 */
private const val EXPAND_RECHECK_BUFFER_MS = 64L

/**
 * 发现 tab 展示状态 (KMP 共享)。
 *
 * app 端 `ExploreTabState` 在 `ExploreTab` Composable 内将自己的 `mutableStateOf` 字段
 * 打包为本 data class 传入; 桌面/iOS 端可同样构造本类复用 [ExploreScreen]。
 *
 * 字段语义对照 app 端原 `ExploreTabState`:
 * - [sources] / [pinned] / [groups] / [searchKey] / [expandedUrl]:
 *   与原同名字段一一对应 (搜索/展开驱动)
 * - [expandedKinds]: 已展开项的发现分类缓存 (url → source+kinds)
 *   替代原 Composable 内 `remember(url) { kindState }` 局部缓存,
 *   上浮到 state 后跨展开/收起周期复用 (原行为: 收起后保留旧值供动画)
 * - [expandedLoading]: 正在异步加载 kinds 的 url 集合
 * - [listState]: LazyColumn 滚动位 (reselect 滚顶用)
 *
 * 注: 原 ExploreTabState.refreshTick 字段下沉后不需要 - 由 ExploreTabState.refreshSource
 * 直接调用 loadKinds(force=true) 重载并更新 expandedKinds, 不再依赖 LaunchedEffect 重键。
 */
data class ExploreUiState(
    val sources: List<BookSourcePart>,
    val pinned: List<PinnedExplore>,
    val groups: List<String>,
    val searchKey: String,
    val expandedUrl: String?,
    val expandedKinds: Map<String, Pair<BookSource?, List<ExploreKind>>>,
    val expandedLoading: Set<String>,
    val listState: LazyListState,
)

/**
 * 发现 tab 交互回调 (KMP 共享)。
 *
 * app 端 `ExploreTabState` 实现本接口 (已有同名方法直接 `override` 别名桥接);
 * 桌面/iOS 端可自行实现接入各自平台行为。
 *
 * 设计: 返回式回调, 无 Android Context 依赖。所有需要 Android 专属 API 的动作
 * (如 [onOpenExplore] 跳 ExploreShowActivity、[onLogin] 调 showLoginDialog)
 * 由 app 端实现内部桥接到 Activity。
 */
interface ExploreUiActions {
    /** 搜索过滤: 空=全部发现、group: 前缀=按分组、其余=关键词 */
    fun onSearch(query: String)

    /** 点击分组菜单项 (宿主自行包 "group:" 前缀) */
    fun onGroup(group: String)

    /** 切换某书源展开/收起 (宿主触发 kinds 异步加载并写入 state) */
    fun onToggleExpand(item: BookSourcePart)

    /** 点击收藏项 (宿主查 DB 取 source 后调 [onOpenExplore]) */
    fun onOpenPinned(pin: PinnedExplore)

    /** 长按收藏项 (宿主弹删除确认框) */
    fun onRemovePinned(pin: PinnedExplore)

    /** 点击发现分类: 跳 ExploreShowActivity */
    fun onOpenExplore(source: BookSource, title: String, exploreUrl: String?)

    /** 分类项 url 形如 "ERROR:..." (宿主弹 TextDialog 显示详情) */
    fun onShowKindError(kind: ExploreKind)

    /** 分类项为 button 类型: 执行其 JS (宿主 runScriptWithContext) */
    fun onRunKindJs(source: BookSource, js: String)

    /** 项菜单 - 编辑书源 (跳 BookSourceEditActivity) */
    fun onEditSource(sourceUrl: String)

    /** 项菜单 - 置顶 (调 viewModel.topSource + 滚顶) */
    fun onToTop(source: BookSourcePart)

    /** 项菜单 - 登录 (showLoginDialog) */
    fun onLogin(source: BookSourcePart)

    /** 项菜单 - 搜索本书 (跳 SearchActivity) */
    fun onSearchBook(source: BookSourcePart)

    /** 项菜单 - 刷新分类 (clearExploreKindsCache + 重载) */
    fun onRefreshSource(source: BookSourcePart)

    /** 项菜单 - 删除 (调 viewModel.deleteSource) */
    fun onDeleteSource(source: BookSourcePart)
}

/**
 * 发现界面 Composable (KMP 版, 下沉自 app 端 ExploreScreen.kt)。
 *
 * 顶栏搜索框占位 (替 SearchView) + 分组溢出菜单; 列表 LazyColumn key=bookSourceUrl;
 * 展开的发现分类用 GridPackLayout 复刻原 GridLayout 占格打包
 * (cols→列跨度、rows→纵跨行数)。
 *
 * @param state  发现 tab 展示状态
 * @param actions 发现 tab 交互回调
 * @param onBack 非空时顶栏左侧显示返回箭头 (全屏路由包装平台注入, 如 iOS; tab 场景保持 null 无变化)
 */
@Composable
fun ExploreScreen(
    state: ExploreUiState,
    actions: ExploreUiActions,
    onBack: (() -> Unit)? = null,
    /** 独立发现页 (ExploreRoute) 无底栏兜底, Android 15+ 强制 edge-to-edge 时需自行回避导航栏;
     *  主界面 tab 内由 MainBottomBar 兜底, 保持 false */
    bottomInsetPadding: Boolean = false,
) {
    val colors = AppTheme.colors
    val eInk = LocalEInk.current
    // 导航栏底部 padding: 仅独立路由需要 (主 tab 内列表底部落在 MainBottomBar 之上, 加了反而多空)
    val navBarBottom = if (bottomInsetPadding) {
        rememberNavigationBarPaddingValues().calculateBottomPadding()
    } else {
        0.dp
    }
    Column(Modifier.fillMaxSize().background(colors.background)) {
        ExploreTitleBar(
            searchKey = state.searchKey,
            onSearch = actions::onSearch,
            groups = state.groups,
            onGroup = { actions.onGroup(it) },
            onBack = onBack,
        )
        Box(Modifier.fillMaxSize()) {
            val sources = state.sources
            val pinned = state.pinned
            if (sources.isEmpty() && state.searchKey.isEmpty()) {
                Text(
                    text = stringResource(Res.string.explore_empty),
                    color = colors.secondaryText,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            LazyColumn(
                state = state.listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    bottom = navBarBottom
                ), // space.md
            ) {
                if (pinned.isNotEmpty()) {
                    item(key = "__pinned__", contentType = "pinned") {
                        PinnedSection(pinned, actions)
                    }
                }
                items(
                    sources,
                    key = { it.bookSourceUrl },
                    contentType = { if (state.expandedUrl == it.bookSourceUrl) "expanded" else "collapsed" },
                ) { item ->
                    ExploreSourceItem(state, actions, item, expanded = state.expandedUrl == item.bookSourceUrl)
                }
            }
            // ===== 展开并行预滚 =====
            // 分类展开区是固定模板 (GridPackLayout 12 列网格 + 定高标签), 内容最终高度可在
            // 动画开始前精确预计算 (见 estimateKindGridHeightPx): 因此展开动画一开始就按
            // "最终几何"并行预滚 (底部超出 → 标题贴顶), 不必等动画结束再测量 ——
            // F55 的逐帧轮询 (awaitItemSizeSettled) 已删除。
            // 预计算是实际高度的下界 (行实际高 = max(最小行高, 标签固有高), 文字行高随字体缩放),
            // 预滚只会"少滚"不会"多滚", 剩余误差由动画结束后的单次实测复查兜底。
            val density = LocalDensity.current
            val lastContentHeightPx = remember { mutableMapOf<String, Int>() }
            LaunchedEffect(state.expandedUrl, state.expandedKinds[state.expandedUrl]) {
                val url = state.expandedUrl ?: run {
                    lastContentHeightPx.clear() // 收起: 下次展开内容从 0 高开始, 旧高度记录失效
                    return@LaunchedEffect
                }
                val kindPair = state.expandedKinds[url] ?: return@LaunchedEffect
                val (_, kinds) = kindPair
                // 无展开内容时无高度变化, 无需滚动 (对照 origin 仅动画结束时检查)
                if (kinds.isEmpty()) {
                    lastContentHeightPx[url] = 0
                    return@LaunchedEffect
                }
                val contentPx = estimateKindGridHeightPx(kinds, density)
                // 刷新时 item 此刻仍渲染旧内容: 最终高度 = 当前测量高 + 新内容高 - 旧内容高
                val oldContentPx = lastContentHeightPx[url] ?: 0
                lastContentHeightPx[url] = contentPx
                if (eInk) {
                    // eInk 无展开动画, 内容即时就位; 等一帧后按实际测量尺寸滚动 (无需预滚)
                    delay(32L)
                    ensureExpandedItemVisible(state.listState, url)
                } else {
                    // 并行预滚: 动画开始时 item 内容高度仍为 0/旧值, 用预计算最终高度算几何,
                    // 与展开动画同时 animateScrollBy (滚动目标 = 标题贴顶, 与内容高度无关, 天然精确)
                    ensureExpandedItemVisible(
                        state.listState, url, contentDeltaPx = contentPx - oldContentPx,
                    )
                    // 单次复查兜底 (非轮询): 预计算是下界, 实际渲染更高时 (字体缩放等) 补滚一次;
                    // 若预滚已滚过, 此项必然为 no-op (标题已贴顶, 实测仍溢出也无需再动)
                    delay(EXPAND_DURATION_MS + EXPAND_RECHECK_BUFFER_MS)
                    ensureExpandedItemVisible(state.listState, url)
                }
            }
        }
    }
}

/**
 * 分类展开区内容高度的预计算 (px)。
 *
 * 原理: 展开区是固定模板 (GridPackLayout 12 列虚拟网格 + FilletTag 定高标签), 最终高度
 * 在动画开始前就能算出, 无需等测量:
 * - 每项占 12/cols 列宽 (权重): JSON 源由 style{cols:1..4} 指定 (旧版 layout_flexBasisPercent 兜底);
 *   非 JSON 源 style 恒为 null → 默认 cols=3 → 权重恒 4;
 * - 总行数 = packGridCells 精确打包 (先到先占格, 纵跨项抬水位; 无纵跨项时退化为
 *   ceil(总权重/12), 如非 JSON 源: ceil(n×4/12) = ceil(n/3));
 * - 高度 = 总行数 × KIND_ROW_MIN_HEIGHT (单行最小高度)。
 *
 * 用途:
 * 1. 展开动画开始时的并行预滚 (见 ExploreScreen 内 LaunchedEffect) —— 滚动几何按最终高度算,
 *    与动画并行执行, 动画结束时已就位;
 * 2. 展开动画目标高度已知 (实际渲染高度 ≥ 预计算, 差值 = 行高超出部分, 随字体缩放增长)。
 *
 * 注意: 预计算是实际高度的下界 (行实际高 = max(最小行高, 标签固有高), 文字行高随字体缩放),
 * 因此只可用于"下界判断" (该滚才滚), 误差由动画结束后的单次实测复查兜底。
 *
 * 可扩展点:
 * - 若网格改用固定行高 (行高 ≡ KIND_ROW_MIN_HEIGHT), 预计算即精确值: 可直接作为
 *   AnimatedVisibility 的 targetHeight (动画曲线完全确定、无结束跳变) 与 LazyColumn 滚动条/占位预估;
 * - eInk 模式可直接按最终高度一次性布局 (当前仍走实测, 行为等价, 改动无收益)。
 */
private fun estimateKindGridHeightPx(kinds: List<ExploreKind>, density: Density): Int {
    if (kinds.isEmpty()) return 0
    val specs = kinds.map { it.style().toGridPackSpec() }
    return with(density) {
        estimateGridHeight(
            specs,
            rowUnitMinHeight = KIND_ROW_MIN_HEIGHT
        ).roundToPx()
    }
}

/**
 * 展开后按需平滑滚动，让展开项尽量完整可见 (对照 origin/quickjs ExploreAdapter.ensureExpandedItemVisible)。
 * 优先策略：底部超出 → 让标题贴顶（但不会把标题滚到顶部以上）；
 *           顶部被切 → 把标题拉回到顶部；
 *           其他情况不动。
 *
 * @param contentDeltaPx 展开内容的高度增量, 用于动画开始时的预滚: 此刻 item 的实测高度还是
 *                       未展开值, 最终高度 = 当前实测高 + contentDeltaPx; 0 = 按当前实测高度判断 (复查)。
 */
private suspend fun ensureExpandedItemVisible(
    listState: LazyListState,
    url: String,
    contentDeltaPx: Int = 0,
) {
    val layoutInfo = listState.layoutInfo
    val info = layoutInfo.visibleItemsInfo.firstOrNull { it.key == url } ?: return
    val viewportTop = layoutInfo.viewportStartOffset
    val viewportBottom = layoutInfo.viewportEndOffset
    val viewTop = info.offset - viewportTop
    val viewBottom = viewTop + info.size + contentDeltaPx

    val dy = when {
        viewBottom > viewportBottom -> viewTop.coerceAtLeast(0)
        viewTop < 0 -> viewTop
        else -> 0
    }
    if (dy != 0) listState.animateScrollBy(dy.toFloat())
}

/** 收藏区 (替原 flexbox header): 标题 + 收藏项流式标签, 长按删除。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PinnedSection(pinned: List<PinnedExplore>, actions: ExploreUiActions) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(
            text = stringResource(Res.string.favorite),
            color = AppTheme.colors.secondaryText,
            modifier = Modifier.padding(start = 4.dp), // space.xs
        )
        FlowRow(Modifier.fillMaxWidth()) {
            pinned.forEach { pin ->
                FilletTag(
                    text = "${pin.sourceName}-${pin.categoryName}",
                    onClick = { actions.onOpenPinned(pin) },
                    onLongClick = { actions.onRemovePinned(pin) },
                )
            }
        }
    }
}

@Composable
private fun ExploreSourceItem(
    state: ExploreUiState,
    actions: ExploreUiActions,
    item: BookSourcePart,
    expanded: Boolean,
) {
    val colors = AppTheme.colors
    val eInk = LocalEInk.current
    var showMenu by remember { mutableStateOf(false) }
    // kinds 上浮到 state holder: 收起后保留旧值供动画期间渲染 (原局部 kindState 行为)
    val url = item.bookSourceUrl
    val kindPair = state.expandedKinds[url]
    val loading = url in state.expandedLoading
    // 箭头 right→down 用旋转 90° 过渡 (原版直接换图, Compose 补动画)
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = if (eInk) snap() else tween(EXPAND_DURATION_MS, easing = FastOutSlowInEasing),
        label = "exploreArrow",
    )

    // 对照 item_explore_source.xml: 外层 paddingTop=4dp (arco_spacing_xs)
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        // ll_title: bg_find_book_group (transparent10 填充+8dp 圆角) + padding 8dp
        // 外层 Box 承载下拉菜单: DropdownMenu 锚点取本 Box 左上角, 复刻原 PopupMenu(view=llTitle) 左侧弹出
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(DesignTokens.shapeDefault)
                    .background(transparent10())
                    .combinedClickable(
                        onClick = { actions.onToggleExpand(item) },
                        onLongClick = { showMenu = true },
                    )
                    .padding(8.dp), // arco_spacing_default
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.bookSourceName,
                    color = colors.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // 原 tv_name 是默认 14sp TextView; 显式 style 避开 M3 bodyLarge 16sp/24sp 行高
                    style = TextStyle(fontSize = 14.sp),
                    modifier = Modifier.weight(1f),
                )
                if (expanded && loading) {
                    // 原 rotate_loading: 20dp、trackThickness 1dp、marginEnd 4dp
                    CircularProgressIndicator(
                        color = colors.accent,
                        strokeWidth = 1.dp,
                        modifier = Modifier.padding(end = 4.dp).size(20.dp),
                    )
                }
                // 原 iv_status: 20dp、tint secondaryText
                Icon(
                    painter = painterResource(Res.drawable.ic_arrow_right),
                    contentDescription = null,
                    tint = colors.secondaryText,
                    // 收起态旋转恒 0, 不挂 rotate 省掉一层 graphicsLayer (整屏几十项)
                    modifier = if (arrowRotation == 0f) {
                        Modifier.size(20.dp)
                    } else {
                        Modifier.size(20.dp).rotate(arrowRotation)
                    },
                )
            }
            // 锚点对齐 llTitle 左上角 (复刻原 PopupMenu(view=llTitle) 行为)
            ExploreItemMenu(actions, item, showMenu) { showMenu = false }
        }
        // 分类区外框: 恒定 paddingTop=4dp (对照原 FrameLayout - GridLayout 收起仅 gone,
        // 外框始终占 4dp)。收起态相邻项间距 = 本项尾 4dp + 下项根 paddingTop 4dp = 8dp
        Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            val kindContent: @Composable () -> Unit = {
                kindPair?.let { (source, kinds) ->
                    if (source != null && kinds.isNotEmpty()) {
                        Box(Modifier.fillMaxWidth()) {
                            KindFlow(actions, source, kinds)
                        }
                    }
                }
            }
            if (eInk) {
                if (expanded) kindContent()
            } else if (expanded || kindPair != null) {
                // 从没展开过的项不建 AnimatedVisibility 的 Transition (整屏几十项都要建一份);
                // 首次展开时它以 visible=false 建立再翻 true, 进场动画与原来一致
                //
                // 修复首次展开闪现: expandedUrl 与 expandedKinds 常在同一帧内就绪
                // (exploreKinds 有内存+磁盘缓存, 加载快于一帧), 导致 AnimatedVisibility
                // 首次组合时 visible 已为 true —— 初始状态即目标状态, 进场动画被跳过,
                // 内容直接出现。这里用本地 animateIn 强制先以 visible=false 完成首帧组合,
                // 下一帧再翻 true, 保证 false→true 转变必然发生、进场动画必然触发。
                // (第二次展开: AnimatedVisibility 已被 kindPair 保活, 行为不变, 仅动画晚一帧开始)
                val contentReady = expanded && kindPair != null
                var animateIn by remember(contentReady) { mutableStateOf(false) }
                LaunchedEffect(contentReady) {
                    if (contentReady) {
                        // 等隐藏态首帧绘制完成后再翻转, 确保触发进场动画
                        withFrameNanos { }
                        animateIn = true
                    } else {
                        animateIn = false
                    }
                }
                // 展开动画: 高度 0 → 内容自然高度。内容是固定模板, 动画期间自然高度恒定,
                // 曲线天然平滑 (目标 ≈ 预计算高度, 见 estimateKindGridHeightPx)。
                // 刻意不把 targetHeight 设为预计算值: 预计算是实际高度的下界 (行高 = max(40dp,
                // 标签固有高), 文字行高随字体缩放), 动画结束瞬间会从预计算值跳到实测值造成跳变;
                // 保持默认 (目标 = 实测自然高) 动画全程无跳变。预计算高度的真正用途是
                // ExploreScreen 内 LaunchedEffect 的并行预滚。
                AnimatedVisibility(
                    visible = animateIn,
                    enter = expandVertically(
                        tween(EXPAND_DURATION_MS, easing = FastOutSlowInEasing),
                        expandFrom = Alignment.Top,
                        initialHeight = { 0 },
                    ) + fadeIn(tween(EXPAND_DURATION_MS)),
                    exit = shrinkVertically(
                        tween(EXPAND_DURATION_MS, easing = FastOutSlowInEasing),
                        shrinkTowards = Alignment.Top,
                    ) + fadeOut(tween(EXPAND_DURATION_MS)),
                ) {
                    // 刷新分类时内容高度变化平滑过渡 (对照原 animateRefreshHeight)
                    Box(Modifier.animateContentSize(tween(EXPAND_DURATION_MS, easing = FastOutSlowInEasing))) {
                        kindContent()
                    }
                }
            }
        }
    }
}

/**
 * 展开的发现分类占格网格 (复刻原 GridLayout 12 列先到先占格)。
 * cols→列跨度、rows→纵跨行数, 纵跨项占住的列后续标签绕开填空, 而非流式换行挤到更下方。
 */
@Composable
private fun KindFlow(actions: ExploreUiActions, source: BookSource, kinds: List<ExploreKind>) {
    // 占格规格随 kinds 走, 别每次重组重建一遍列表
    val specs = remember(kinds) { kinds.map { it.style().toGridPackSpec() } }
    GridPackLayout(
        specs = specs,
        rowUnitMinHeight = KIND_ROW_MIN_HEIGHT, // 原 tv.minimumHeight = 40dp × rows (viewHeight.large)
        modifier = Modifier.fillMaxWidth(),
    ) {
        kinds.forEach { kind ->
            FilletTag(
                text = kind.title,
                onClick = {
                    val kindUrl = kind.url
                    when {
                        kindUrl.isNullOrBlank() -> {}
                        kind.title.startsWith("ERROR:") -> actions.onShowKindError(kind)
                        kind.type == RowUi.Type.button -> actions.onRunKindJs(source, kindUrl)
                        else -> actions.onOpenExplore(source, kind.title, kindUrl)
                    }
                },
            )
        }
    }
}

@Composable
private fun ExploreItemMenu(
    actions: ExploreUiActions,
    item: BookSourcePart,
    expanded: Boolean,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    AppDropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            onClick = { onDismiss(); actions.onEditSource(item.bookSourceUrl) },
        ) {
            Text(stringResource(Res.string.edit), color = colors.primaryText)
        }
        DropdownMenuItem(
            onClick = { onDismiss(); actions.onToTop(item) },
        ) {
            Text(stringResource(Res.string.to_top), color = colors.primaryText)
        }
        if (item.hasLoginUrl) {
            DropdownMenuItem(
                onClick = { onDismiss(); actions.onLogin(item) },
            ) {
                Text(stringResource(Res.string.login), color = colors.primaryText)
            }
        }
        DropdownMenuItem(
            onClick = { onDismiss(); actions.onSearchBook(item) },
        ) {
            Text(stringResource(Res.string.search), color = colors.primaryText)
        }
        DropdownMenuItem(
            onClick = { onDismiss(); actions.onRefreshSource(item) },
        ) {
            Text(stringResource(Res.string.refresh), color = colors.primaryText)
        }
        DropdownMenuItem(
            onClick = { onDismiss(); actions.onDeleteSource(item) },
        ) {
            Text(stringResource(Res.string.delete), color = colors.primaryText)
        }
    }
}

/** 发现顶栏: 搜索框占标题区 + 右侧分组溢出菜单; tab 页无返回箭头, onBack 非空时左侧加返回箭头。 */
@Composable
private fun ExploreTitleBar(
    searchKey: String,
    onSearch: (String) -> Unit,
    groups: List<String>,
    onGroup: (String) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val colors = AppTheme.colors
    val eInk = LocalEInk.current
    // 替代 ThemeConfig.curBgImagePath: 跨平台 LocalThemeStoreProvider
    val hasBgImage = !LocalThemeStoreProvider.current.bgImagePath.isNullOrBlank()
    val bg = when {
        eInk -> Color.White
        hasBgImage -> Color.Transparent
        else -> colors.background
    }
    val insetsModifier = if (eInk) {
        Modifier.windowInsetsPadding(WindowInsets(0))
    } else {
        Modifier.transitionStatusBarPadding()
    }
    Box(Modifier.fillMaxWidth().background(bg).then(insetsModifier)) {
        Row(
            // 有返回箭头时 IconButton 自带 48dp 宽度, 去掉 12dp 起始留白
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .padding(start = if (onBack == null) 12.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_arrow_back),
                        contentDescription = null,
                        tint = colors.primaryText,
                    )
                }
            }
            AppSearchField(
                value = searchKey,
                onValueChange = onSearch,
                hint = stringResource(Res.string.search_book_source),
                modifier = Modifier.weight(1f),
            )
            GroupMenu(groups, onGroup)
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

/** 分组菜单 (替 main_explore 的 menu_group 子菜单): ic_groups 图标 + 分组下拉。 */
@Composable
private fun GroupMenu(groups: List<String>, onGroup: (String) -> Unit) {
    val colors = AppTheme.colors
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painter = painterResource(Res.drawable.ic_groups),
                contentDescription = stringResource(Res.string.group),
                tint = colors.primaryText,
            )
        }
        AppDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            groups.forEach { group ->
                DropdownMenuItem(
                    onClick = { expanded = false; onGroup(group) },
                ) {
                    Text(group, color = colors.primaryText)
                }
            }
        }
    }
}

/**
 * 圆角文字标签 (复刻 item_fillet_text / AppFilletTextButton 视觉, 另加长按)。
 * 收藏项需长按删除、分类项需被 GridPackLayout 定格拉伸, 故不复用无长按的 AppFilletTextButton。
 * 整格尺寸 (对照原 TextView minimumHeight+FILL) 由外层布局的固定约束给出, 4dp inset 在内。
 */
@Composable
private fun FilletTag(
    text: String,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val isDark = AppTheme.colors.isDark
    // btn_bg: light #100e0e0e / night #14e0e0e0
    val normalBg = if (isDark) Color(0x14e0e0e0) else Color(0x100e0e0e)
    // 按压 arco_fill_3: light #FFE6E6E6 / night #FF2A2A2A
    val pressedBg = if (isDark) Color(0xFF2A2A2A) else Color(0xFFE6E6E6)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .padding(4.dp)
            .clip(DesignTokens.shapeDefault)
            .background(if (pressed) pressedBg else normalBg)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            // 原 XML padding 16×12 自视图外缘计 (含 4dp inset), 此处已扣除 inset
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = AppTheme.colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // 显式 14sp 自然行高, 不吃 M3 bodyLarge 的 16sp/24sp 行高
            style = TextStyle(fontSize = 14.sp),
        )
    }
}

/** R.color.transparent10: light #10000000 / dark #10ffffff (与 AppSearchField.fillStroke 同源) */
@Composable
private fun transparent10(): Color =
    if (AppTheme.colors.isDark) Color(0x10ffffff) else Color(0x10000000)
