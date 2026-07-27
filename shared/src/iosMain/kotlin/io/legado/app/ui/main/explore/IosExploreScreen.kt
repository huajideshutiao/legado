package io.legado.app.ui.main.explore

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.PinnedExplore
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.help.PinnedExploreHelp
import io.legado.app.help.openURL
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.help.source.exploreKinds
import io.legado.app.model.script.runScriptWithContext
import io.legado.app.ui.book.source.SourceLoginDialog
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.explore.ExploreViewModelShared
import io.legado.app.utils.FlowBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * iOS 端发现页入口 (包装 shared/sharedUiMain 的 [ExploreScreen], 对照 desktop
 * `desktop/ui/explore/ExploreScreen.kt` 的 ExploreContent + ExploreStateHolder 模式)。
 *
 * kinds 加载走 commonMain 完整版 [exploreKinds] (含 JS 求值 + 磁盘缓存,
 * iOS 已注册 IosJsEngine 与 ExploreKindsCacheProvider), 与 desktop/app 端同一路径。
 *
 * @param onBack 返回回调 (IosNavHost 注入, 顶栏返回箭头)
 * @param onOpenExplore 点击发现分类/收藏项 → EXPLORE_SHOW 路由 (source, title, exploreUrl)
 * @param onSearchBook 项菜单"搜索本书" → SEARCH 路由
 */
@Composable
fun IosExploreScreen(
    onBack: () -> Unit,
    onOpenExplore: (BookSource, String, String?) -> Unit,
    onSearchBook: (BookSourcePart) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    // 文案 (回调 lambda/协程非 @Composable, 预先缓存; K/N 无 String.format, %s 用 replace 填充)
    val pinnedSourceNotFoundTemplate = rememberString("explore_pinned_source_not_found")
    val jsErrorTemplate = rememberString("explore_js_error")
    val updateGroupsErrorText = rememberString("explore_update_groups_error")
    val updateDataErrorText = rememberString("explore_update_data_error")
    val dialogTitleLabel = rememberString("dialog_title")
    val sureDelExploreFavoriteLabel = rememberString("sure_del_explore_favorite")
    val exploreCategoryErrorLabel = rememberString("explore_category_error")
    val sureDelSourceLabel = rememberString("sure_del_source")
    val okLabel = rememberString("ok")
    val cancelLabel = rememberString("cancel")

    // 复用 shared commonMain 的 ExploreViewModelShared (onToTop/onDeleteSource 下沉实现)
    val exploreShared = remember(scope) { ExploreViewModelShared(scope = scope) }
    val state = remember(scope, listState) {
        IosExploreStateHolder(
            scope = scope,
            shared = exploreShared,
            listState = listState,
            openExploreCb = onOpenExplore,
            searchBookCb = onSearchBook,
            pinnedSourceNotFoundTemplate = pinnedSourceNotFoundTemplate,
            jsErrorTemplate = jsErrorTemplate,
        )
    }

    // 收集分组列表 (对照 desktop ExploreContent, iOS 无 lifecycle 直接 collect)
    LaunchedEffect(Unit) {
        AppDbProviders.get().bookSourceDao.flowExploreGroups().catch {
            AppLog.put(updateGroupsErrorText, it)
        }.collect { groups ->
            state.updateGroups(groups)
        }
    }

    // 搜索词变化重启收集 (空=全部 / group: 前缀=按分组 / 其余=关键词)
    LaunchedEffect(state.searchKey) {
        val key = state.searchKey
        val dao = AppDbProviders.get().bookSourceDao
        val flow = when {
            key.isBlank() -> dao.flowExplore()
            key.startsWith("group:") -> dao.flowGroupExplore(key.substringAfter("group:"))
            else -> dao.flowExplore(key)
        }
        flow.catch {
            AppLog.put(updateDataErrorText, it)
        }.collect { sources ->
            state.updateSources(sources)
        }
    }

    // 收藏变更事件 (PinnedExploreHelp 内部 postEvent)
    LaunchedEffect(Unit) {
        FlowBus.with(EventBus.UP_EXPLORE_PINNED).collect { state.upPinned() }
    }

    // 刷新当前展开项 (对应 app 端 EventBus.REFRESH_EXPLORE)
    LaunchedEffect(Unit) {
        FlowBus.with(EventBus.REFRESH_EXPLORE).collect {
            val url = state.expandedUrl ?: return@collect
            val source = state.sources.find { it.bookSourceUrl == url } ?: return@collect
            state.refreshSource(source)
        }
    }

    val uiState = ExploreUiState(
        sources = state.sources,
        pinned = state.pinned,
        groups = state.groups,
        searchKey = state.searchKey,
        expandedUrl = state.expandedUrl,
        expandedKinds = state.expandedKinds,
        expandedLoading = state.expandedLoading,
        listState = state.listState,
    )
    ExploreScreen(uiState, state, onBack)

    // ---- 确认/错误对话框渲染 (对照 desktop ExploreContent 末尾 4 个分支) ----

    // 1. 删除收藏项二次确认
    state.removePinnedTarget?.let { pin ->
        AppAlertDialog(
            onDismissRequest = { state.removePinnedTarget = null },
            title = dialogTitleLabel,
            message = sureDelExploreFavoriteLabel,
            widthFraction = 0.8f,
            okButton = AlertButton(okLabel, dismissOnClick = false) {
                state.removePinnedTarget = null
                PinnedExploreHelp.removePinnedExplore(pin)
            },
            cancelButton = AlertButton(cancelLabel, dismissOnClick = false) {
                state.removePinnedTarget = null
            },
        )
    }

    // 2. 发现分类错误详情 (长 url 由 AppAlertDialogContent 的 message 滚动区收纳)
    state.kindError?.let { kind ->
        AppAlertDialog(
            onDismissRequest = { state.kindError = null },
            title = exploreCategoryErrorLabel,
            message = kind.url.orEmpty(),
            widthFraction = 0.8f,
            okButton = AlertButton(okLabel, dismissOnClick = false) {
                state.kindError = null
            },
        )
    }

    // 3. 删除书源二次确认
    state.deleteSourceTarget?.let { part ->
        AppAlertDialog(
            onDismissRequest = { state.deleteSourceTarget = null },
            title = dialogTitleLabel,
            message = sureDelSourceLabel,
            widthFraction = 0.8f,
            okButton = AlertButton(okLabel, dismissOnClick = false) {
                state.deleteSourceTarget = null
                exploreShared.deleteSource(part)
            },
            cancelButton = AlertButton(cancelLabel, dismissOnClick = false) {
                state.deleteSourceTarget = null
            },
        )
    }

    // 4. 书源登录 (shared/sharedUiMain SourceLoginDialog, onOpenUrl 走系统浏览器)
    state.loginTarget?.let { src ->
        SourceLoginDialog(
            source = src,
            onDismiss = { state.loginTarget = null },
            onOpenUrl = { url -> openURL(url) },
        )
    }
}

/**
 * iOS 端发现页状态宿主 (实现 [ExploreUiActions], 对照 desktop ExploreStateHolder)。
 * 无 Lifecycle 依赖, flow 由 IosExploreScreen 的 LaunchedEffect 收集后回写。
 */
@Stable
private class IosExploreStateHolder(
    private val scope: CoroutineScope,
    private val shared: ExploreViewModelShared,
    val listState: LazyListState,
    private val openExploreCb: (BookSource, String, String?) -> Unit,
    private val searchBookCb: (BookSourcePart) -> Unit,
    private val pinnedSourceNotFoundTemplate: String,
    private val jsErrorTemplate: String,
) : ExploreUiActions {

    var sources by mutableStateOf<List<BookSourcePart>>(emptyList())
        private set
    var pinned by mutableStateOf(PinnedExploreHelp.getPinnedExplores())
        private set
    var groups by mutableStateOf<List<String>>(emptyList())
        private set
    var searchKey by mutableStateOf("")
        private set

    // 当前展开的书源 (按 url 稳定 key)
    var expandedUrl by mutableStateOf<String?>(null)
        private set

    // 已展开项的发现分类缓存 (url → source+kinds), 收起后保留旧值供动画期间渲染
    var expandedKinds by mutableStateOf<Map<String, Pair<BookSource?, List<ExploreKind>>>>(emptyMap())
        private set

    // 正在异步加载 kinds 的 url 集合
    var expandedLoading by mutableStateOf<Set<String>>(emptySet())
        private set

    // ---- 对话框显示状态 (null=隐藏; IosExploreScreen 末尾渲染分支读取并可置 null) ----
    var removePinnedTarget by mutableStateOf<PinnedExplore?>(null)
        internal set
    var kindError by mutableStateOf<ExploreKind?>(null)
        internal set
    var deleteSourceTarget by mutableStateOf<BookSourcePart?>(null)
        internal set
    var loginTarget by mutableStateOf<BookSource?>(null)
        internal set

    fun upPinned() {
        pinned = PinnedExploreHelp.getPinnedExplores()
    }

    fun updateGroups(value: List<String>) {
        groups = value
    }

    fun updateSources(value: List<BookSourcePart>) {
        sources = value
    }

    /** 切换展开/收起, 展开时触发 kinds 异步加载。 */
    fun toggleExpand(item: BookSourcePart) {
        val url = item.bookSourceUrl
        if (expandedUrl == url) {
            expandedUrl = null
        } else {
            expandedUrl = url
            loadKinds(item)
        }
    }

    /** 异步加载某书源的发现分类; force=true 强制重载 (refresh 用)。 */
    fun loadKinds(item: BookSourcePart, force: Boolean = false) {
        val url = item.bookSourceUrl
        if (!force && expandedKinds.containsKey(url)) return
        scope.launch {
            expandedLoading = expandedLoading + url
            try {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        val source = AppDbProviders.get().bookSourceDao.getBookSource(url)
                        val kinds = source?.exploreKinds() ?: emptyList()
                        source to kinds
                    }
                }.getOrDefault(null to emptyList())
                expandedKinds = expandedKinds + (url to result)
            } finally {
                expandedLoading = expandedLoading - url
            }
        }
    }

    /** 刷新分类: 清缓存 + 强制重载。 */
    fun refreshSource(item: BookSourcePart) {
        scope.launch {
            item.clearExploreKindsCache()
            loadKinds(item, force = true)
        }
    }

    // ---- ExploreUiActions 实现 ----

    override fun onSearch(query: String) {
        searchKey = query
    }

    override fun onGroup(group: String) {
        searchKey = "group:$group"
    }

    override fun onToggleExpand(item: BookSourcePart) = toggleExpand(item)

    override fun onOpenPinned(pin: PinnedExplore) {
        scope.launch {
            val source = withContext(Dispatchers.IO) {
                AppDbProviders.get().bookSourceDao.getBookSource(pin.sourceUrl)
            }
            if (source != null) {
                openExploreCb(source, pin.categoryName, pin.categoryUrl)
            } else {
                AppLog.put(pinnedSourceNotFoundTemplate.replace("%s", pin.sourceUrl))
            }
        }
    }

    override fun onRemovePinned(pin: PinnedExplore) {
        removePinnedTarget = pin
    }

    override fun onOpenExplore(source: BookSource, title: String, exploreUrl: String?) {
        openExploreCb(source, title, exploreUrl)
    }

    override fun onShowKindError(kind: ExploreKind) {
        kindError = kind
    }

    override fun onRunKindJs(source: BookSource, js: String) {
        // button 类型分类执行 JS (对照 app 端 runKindJs; IosJsEngine 已注册)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    runScriptWithContext { source.evalJS(js) }
                }
            }.onFailure { e ->
                if (e is CancellationException) return@onFailure
                AppLog.put(jsErrorTemplate.replace("%s", e.message.orEmpty()), e, true)
            }
        }
    }

    // iOS 端书源编辑是 IosBookSourceScreen 内嵌 Dialog, 发现页项菜单暂不接编辑跳转 (对照 desktop 默认 no-op)
    override fun onEditSource(sourceUrl: String) {}

    override fun onToTop(source: BookSourcePart) {
        shared.topSource(source)
        scope.launch { listState.animateScrollToItem(0) }
    }

    override fun onLogin(source: BookSourcePart) {
        // BookSourcePart 是 DatabaseView 不含 header/loginUrl, 按 url 查完整 BookSource
        scope.launch {
            loginTarget = AppDbProviders.get().bookSourceDao.getBookSource(source.bookSourceUrl)
        }
    }

    override fun onSearchBook(source: BookSourcePart) = searchBookCb(source)

    override fun onRefreshSource(source: BookSourcePart) = refreshSource(source)

    override fun onDeleteSource(source: BookSourcePart) {
        deleteSourceTarget = source
    }
}
