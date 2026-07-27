package io.legado.app.ui.main.explore

import android.content.Context
import androidx.activity.compose.LocalActivity
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.R
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.PinnedExplore
import io.legado.app.data.entities.getBookSource
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.help.IntentData
import io.legado.app.help.PinnedExploreHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.help.source.exploreKinds
import io.legado.app.model.script.runScriptWithContext
import io.legado.app.ui.book.explore.ExploreShowActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.login.showLoginDialog
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.FlowBus
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 发现 tab 对外控制器：底栏 reselect 收起展开项/滚顶。 */
interface ExploreTabController {
    fun compressExplore()
}

/**
 * 发现 tab (纯 Compose, 薄壳模式)：原 ExploreFragment 的状态/副作用上浮至此, 壳只剩薄包装。
 * DB flow 由 LaunchedEffect 收集 (tab 常驻组合, 等价原 offscreenPageLimit=3 驻留);
 * 搜索词/展开态 rememberSaveable, 滚动位 rememberLazyListState。
 *
 * UI 渲染下沉到 shared `ExploreScreen`, 本 Composable 只负责:
 * - 构造 [ExploreTabState] (实现 [ExploreUiActions] 接口)
 * - 收集 DB flow / FlowBus 写入 state
 * - 打包 [ExploreUiState] 传入 shared [ExploreScreen]
 */
@Composable
fun ExploreTab(onController: (ExploreTabController) -> Unit = {}) {
    val context = LocalContext.current
    val activity = LocalActivity.current as? AppCompatActivity
    val scope = rememberCoroutineScope()
    val viewModelOwner = checkNotNull(LocalView.current.findViewTreeViewModelStoreOwner())
    val listState = rememberLazyListState()
    val searchKeyState = rememberSaveable { mutableStateOf("") }
    val expandedUrlState = rememberSaveable { mutableStateOf<String?>(null) }
    val state = remember {
        ExploreTabState(
            context = context,
            activity = activity,
            scope = scope,
            viewModel = ViewModelProvider(viewModelOwner)[ExploreViewModel::class.java],
            listState = listState,
            searchKeyState = searchKeyState,
            expandedUrlState = expandedUrlState,
        )
    }
    LaunchedEffect(state) { onController(state) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) { state.collectGroups(lifecycle) }
    // 搜索词变化重启收集 (等价原 upExploreData 的 job 取消重启)
    LaunchedEffect(state.searchKey) { state.collectSources(lifecycle) }
    LaunchedEffect(Unit) {
        FlowBus.with(EventBus.UP_EXPLORE_PINNED).collect { state.upPinned() }
    }
    LaunchedEffect(Unit) {
        FlowBus.with(EventBus.REFRESH_EXPLORE).collect { state.refreshCurrentExpanded() }
    }

    // 打包 state 为 shared 端 ExploreUiState, 调 shared ExploreScreen
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
    ExploreScreen(
        state = uiState,
        actions = state,
    )
}

/**
 * 发现 tab 状态宿主 (实现 [ExploreUiActions] 供 shared [ExploreScreen] 回调):
 * snapshot state + 交互回调 (对照原 ExploreAdapter.CallBack + 菜单)。
 *
 * kinds 异步加载从原 shared Composable 内 LaunchedEffect 上浮至此:
 * - [toggleExpand] 触发 [loadKinds] (已缓存则跳过)
 * - [refreshSource] clearExploreKindsCache + [loadKinds] force=true
 * - 加载结果写入 [expandedKinds], 加载中标记 [expandedLoading], shared 端仅读渲染
 */
@Stable
class ExploreTabState internal constructor(
    private val context: Context,
    private val activity: AppCompatActivity?,
    private val scope: CoroutineScope,
    private val viewModel: ExploreViewModel,
    val listState: LazyListState,
    searchKeyState: MutableState<String>,
    expandedUrlState: MutableState<String?>,
) : ExploreTabController, ExploreUiActions {

    var sources by mutableStateOf<List<BookSourcePart>>(emptyList())
        private set
    var pinned by mutableStateOf(PinnedExploreHelp.getPinnedExplores())
        private set
    var groups by mutableStateOf<List<String>>(emptyList())
        private set
    var searchKey by searchKeyState
        private set

    // 当前展开的书源 (按 url 稳定 key, 替代原 exIndex 位置)
    var expandedUrl by expandedUrlState
        private set

    // 已展开项的发现分类缓存 (url → source+kinds)
    // 替代原 Composable 内 remember(url) { kindState } 局部缓存, 上浮后跨展开/收起周期复用
    var expandedKinds by mutableStateOf<Map<String, Pair<BookSource?, List<ExploreKind>>>>(emptyMap())
        private set
    // 正在异步加载 kinds 的 url 集合
    var expandedLoading by mutableStateOf<Set<String>>(emptySet())
        private set

    fun upPinned() {
        pinned = PinnedExploreHelp.getPinnedExplores()
    }

    suspend fun collectGroups(lifecycle: Lifecycle) {
        appDb.bookSourceDao.flowExploreGroups().flowWithLifecycleAndDatabaseChange(
            lifecycle,
            Lifecycle.State.RESUMED,
            AppDatabase.BOOK_SOURCE_TABLE_NAME
        ).conflate().distinctUntilChanged().collect {
            groups = it
            delay(500)
        }
    }

    suspend fun collectSources(lifecycle: Lifecycle) {
        val key = searchKey
        when {
            key.isBlank() -> appDb.bookSourceDao.flowExplore()
            key.startsWith("group:") ->
                appDb.bookSourceDao.flowGroupExplore(key.substringAfter("group:"))

            else -> appDb.bookSourceDao.flowExplore(key)
        }.flowWithLifecycleAndDatabaseChange(
            lifecycle,
            Lifecycle.State.RESUMED,
            AppDatabase.BOOK_SOURCE_TABLE_NAME
        ).catch {
            AppLog.put("发现界面更新数据出错", it)
        }.conflate().flowOn(IO).collect {
            sources = it
            delay(500)
        }
    }

    /** 搜索过滤：空=全部发现、group: 前缀=按分组、其余=关键词，语义与原 SearchView 一致。 */
    fun setQuery(query: String) {
        searchKey = query
    }

    fun toggleExpand(item: BookSourcePart) {
        val url = item.bookSourceUrl
        if (expandedUrl == url) {
            expandedUrl = null
        } else {
            expandedUrl = url
            // 触发 kinds 异步加载 (替代原 Composable 内 LaunchedEffect)
            loadKinds(item)
        }
    }

    /**
     * 异步加载某书源的发现分类 (替代原 Composable 内 LaunchedEffect).
     * 已缓存则跳过 (force=false); force=true 时强制重载 (refresh 用).
     */
    private fun loadKinds(item: BookSourcePart, force: Boolean = false) {
        val url = item.bookSourceUrl
        if (!force && expandedKinds.containsKey(url)) return
        scope.launch {
            expandedLoading = expandedLoading + url
            try {
                val result = runCatching {
                    withContext(IO) { item.getBookSource() to item.exploreKinds() }
                }.getOrDefault(null to emptyList())
                expandedKinds = expandedKinds + (url to result)
            } finally {
                expandedLoading = expandedLoading - url
            }
        }
    }

    fun openExplore(source: BookSource, title: String, exploreUrl: String?) {
        if (exploreUrl.isNullOrBlank()) return
        IntentData.source = source
        context.startActivity<ExploreShowActivity> {
            putExtra("exploreName", title)
            putExtra("exploreUrl", exploreUrl)
        }
    }

    fun openPinned(item: PinnedExplore) {
        scope.launch {
            val source = withContext(IO) { appDb.bookSourceDao.getBookSource(item.sourceUrl) }
            if (source != null) {
                openExplore(source, item.categoryName, item.categoryUrl)
            } else {
                context.toastOnUi("Source not found")
            }
        }
    }

    fun removePinned(item: PinnedExplore) {
        context.alert(R.string.draw) {
            setMessage(context.getString(R.string.sure_del) + "\n${item.sourceName}-${item.categoryName}")
            yesButton { PinnedExploreHelp.removePinnedExplore(item) }
            noButton()
        }
    }

    fun editSource(sourceUrl: String) {
        context.startActivity<BookSourceEditActivity> {
            putExtra("sourceUrl", sourceUrl)
        }
    }

    fun toTop(source: BookSourcePart) {
        viewModel.topSource(source)
        // 置顶后滚到顶，让用户看到被置顶项(复刻原 onItemRangeInserted(0) 滚顶)
        scope.launch { listState.animateScrollToItem(0) }
    }

    fun deleteSource(source: BookSourcePart) {
        context.alert(R.string.draw) {
            setMessage(context.getString(R.string.sure_del) + "\n" + source.bookSourceName)
            noButton()
            yesButton { viewModel.deleteSource(source) }
        }
    }

    fun searchBook(source: BookSourcePart) {
        context.startActivity<SearchActivity> {
            putExtra("searchScope", SearchScope(source).toString())
        }
    }

    fun login(source: BookSourcePart) {
        source.getBookSource()?.showLoginDialog(activity ?: return)
    }

    fun refreshSource(source: BookSourcePart) {
        scope.launch {
            source.clearExploreKindsCache()
            // 强制重载已缓存项 (替代原 LaunchedEffect on refreshTick 重新拉取)
            loadKinds(source, force = true)
        }
    }

    /** 分类项为 button 类型：执行其 JS(对照原 upKindList 的 evalJS 分支)。 */
    fun runKindJs(source: BookSource, js: String) {
        scope.launch(IO) {
            runCatching {
                runScriptWithContext { source.evalJS(js) }
            }.onFailure { e ->
                ensureActive()
                AppLog.put("JS错误${e.localizedMessage}", e, true)
            }
        }
    }

    fun showKindError(kind: ExploreKind) {
        activity?.showDialogFragment(TextDialog("ERROR", kind.url))
    }

    fun refreshCurrentExpanded() {
        val url = expandedUrl ?: return
        val source = sources.find { it.bookSourceUrl == url } ?: return
        refreshSource(source)
    }

    /** reselect 发现 tab：先收起已展开项，未展开则滚动到顶(E-Ink 直达)。 */
    override fun compressExplore() {
        if (expandedUrl != null) {
            expandedUrl = null
        } else {
            scope.launch {
                if (AppConfig.isEInkMode) {
                    listState.scrollToItem(0)
                } else {
                    listState.animateScrollToItem(0)
                }
            }
        }
    }

    // ---- ExploreUiActions 实现 (别名桥接到已有同名方法, 对照 BookInfoActivity 模式) ----

    override fun onSearch(query: String) = setQuery(query)
    override fun onGroup(group: String) = setQuery("group:$group")
    override fun onToggleExpand(item: BookSourcePart) = toggleExpand(item)
    override fun onOpenPinned(pin: PinnedExplore) = openPinned(pin)
    override fun onRemovePinned(pin: PinnedExplore) = removePinned(pin)
    override fun onOpenExplore(source: BookSource, title: String, exploreUrl: String?) =
        openExplore(source, title, exploreUrl)
    override fun onShowKindError(kind: ExploreKind) = showKindError(kind)
    override fun onRunKindJs(source: BookSource, js: String) = runKindJs(source, js)
    override fun onEditSource(sourceUrl: String) = editSource(sourceUrl)
    override fun onToTop(source: BookSourcePart) = toTop(source)
    override fun onLogin(source: BookSourcePart) = login(source)
    override fun onSearchBook(source: BookSourcePart) = searchBook(source)
    override fun onRefreshSource(source: BookSourcePart) = refreshSource(source)
    override fun onDeleteSource(source: BookSourcePart) = deleteSource(source)
}
