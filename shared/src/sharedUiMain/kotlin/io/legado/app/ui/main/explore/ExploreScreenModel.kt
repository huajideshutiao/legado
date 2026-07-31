package io.legado.app.ui.main.explore

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.PinnedExplore
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.help.PinnedExploreHelp
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.help.source.exploreKinds
import io.legado.app.model.script.runScriptWithContext
import io.legado.app.ui.explore.ExploreViewModelShared
import io.legado.app.ui.root.ScreenModel
import io.legado.app.utils.FlowBus
import io.legado.app.utils.throttleLatest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 发现页 shared ScreenModel：托管 [ExploreScreenState], 通过 [dispatch] 处理 UI 事件。
 *
 * 对照 app 端 [ExploreTabState]：
 * - DB flow 收集 (groups/sources) 由本类自管 scope 订阅, 取消旧 job 重启
 *   (替代 app 端 `flowWithLifecycleAndDatabaseChange` 的 lifecycle 绑定; ScreenModel
 *   生命周期由 [ScreenModelStore] 管理, onCleared 取消 scope)
 * - FlowBus (UP_EXPLORE_PINNED / REFRESH_EXPLORE) 订阅替代 app 端 LaunchedEffect
 * - kinds 异步加载 (loadKinds/refreshSource) 替代 app 端 Composable 内 LaunchedEffect
 * - 置顶/删除源 DB 写入复用 [ExploreViewModelShared] (组合委托)
 *
 * 宿主 Route 只负责平台专属行为 (路由跳转 / 删除确认对话框 / 错误文本对话框)。
 *
 * 模式参考 [io.legado.app.ui.book.toc.TocScreenModel]。
 */
class ExploreScreenModel : ScreenModel {

    private val appDb get() = AppDbProviders.get()

    // 自管 scope (ScreenModelStore 调 onCleared 时取消)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(ExploreScreenState())
    val state: StateFlow<ExploreScreenState> = _state.asStateFlow()

    // groups / sources 收集 job (sources 随 searchKey 变化重启)
    private var groupsJob: Job? = null
    private var sourcesJob: Job? = null

    // 置顶/删除源 DB 写入 (组合委托, 对照 app 端 ExploreViewModel)
    private val viewModelShared = ExploreViewModelShared(scope)

    init {
        collectGroups()
        collectSources()
        observePinnedEvent()
        observeRefreshEvent()
        // 对照 ExploreFragment.onFragmentCreated 的 upPinned(): 进页主动拉一次,
        // UP_EXPLORE_PINNED 非 sticky, 只订阅会导致收藏区首帧恒空
        upPinned()
    }

    /** 对照 ExploreFragment.upPinned: 从 PinnedExploreHelp 读收藏列表刷入 state */
    private fun upPinned() {
        _state.update { it.copy(pinned = PinnedExploreHelp.getPinnedExplores()) }
    }

    fun dispatch(event: ExploreUiEvent) {
        when (event) {
            is ExploreUiEvent.SetSearch -> setSearch(event.query)
            is ExploreUiEvent.SetGroup -> setGroup(event.group)
            is ExploreUiEvent.ToggleExpand -> toggleExpand(event.item)
            is ExploreUiEvent.RefreshSource -> refreshSource(event.item)
            is ExploreUiEvent.ToTop -> viewModelShared.topSource(event.item)
            is ExploreUiEvent.DeleteSource -> viewModelShared.deleteSource(event.item)
            is ExploreUiEvent.RemovePinned -> removePinned(event.item)
            is ExploreUiEvent.RunKindJs -> runKindJs(event.source, event.js)
        }
    }

    // ===== 数据收集 =====

    /** 对照 ExploreTabState.collectGroups: flowExploreGroups + distinctUntilChanged + throttleLatest 500ms */
    private fun collectGroups() {
        groupsJob?.cancel()
        groupsJob = scope.launch {
            appDb.bookSourceDao.flowExploreGroups()
                .distinctUntilChanged()
                .throttleLatest(500)
                .catch { AppLog.put("发现界面更新分组数据出错", it) }
                .collect { groups -> _state.update { it.copy(groups = groups) } }
        }
    }

    /** 对照 ExploreTabState.collectSources: 按 searchKey 选择 flow, 取消旧 job 重启 */
    private fun collectSources() {
        sourcesJob?.cancel()
        sourcesJob = scope.launch {
            val key = _state.value.searchKey
            val flow = when {
                key.isBlank() -> appDb.bookSourceDao.flowExplore()
                key.startsWith("group:") ->
                    appDb.bookSourceDao.flowGroupExplore(key.substringAfter("group:"))

                else -> appDb.bookSourceDao.flowExplore(key)
            }
            flow.catch { AppLog.put("发现界面更新数据出错", it) }
                .flowOn(IoDispatcher)
                .throttleLatest(500)
                .collect { sources -> _state.update { it.copy(sources = sources) } }
        }
    }

    /** 对照 app 端 FlowBus.with(UP_EXPLORE_PINNED) → upPinned */
    private fun observePinnedEvent() {
        scope.launch {
            FlowBus.with(EventBus.UP_EXPLORE_PINNED).collect { upPinned() }
        }
    }

    /** 对照 app 端 FlowBus.with(REFRESH_EXPLORE) → refreshCurrentExpanded */
    private fun observeRefreshEvent() {
        scope.launch {
            FlowBus.with(EventBus.REFRESH_EXPLORE).collect { refreshCurrentExpanded() }
        }
    }

    // ===== 搜索 / 分组 =====

    /** 搜索过滤: 空=全部发现、group: 前缀=按分组、其余=关键词 */
    private fun setSearch(query: String) {
        _state.update { it.copy(searchKey = query) }
        collectSources()
    }

    /** 点击分组菜单项 (包 "group:" 前缀) */
    private fun setGroup(group: String) {
        _state.update { it.copy(searchKey = "group:$group") }
        collectSources()
    }

    // ===== 展开 / 分类加载 =====

    private fun toggleExpand(item: BookSourcePart) {
        val url = item.bookSourceUrl
        if (_state.value.expandedUrl == url) {
            _state.update { it.copy(expandedUrl = null) }
        } else {
            _state.update { it.copy(expandedUrl = url) }
            loadKinds(item)
        }
    }

    /** 对照 ExploreTabState.loadKinds: 已缓存则跳过 (force=false); force=true 强制重载 */
    private fun loadKinds(item: BookSourcePart, force: Boolean = false) {
        val url = item.bookSourceUrl
        if (!force && _state.value.expandedKinds.containsKey(url)) return
        scope.launch {
            _state.update { it.copy(expandedLoading = it.expandedLoading + url) }
            try {
                val result = runCatching {
                    withContext(IoDispatcher) {
                        val source = appDb.bookSourceDao.getBookSource(url)
                        val kinds = source?.exploreKinds() ?: emptyList()
                        source to kinds
                    }
                }.getOrDefault(null to emptyList())
                _state.update { it.copy(expandedKinds = it.expandedKinds + (url to result)) }
            } finally {
                _state.update { it.copy(expandedLoading = it.expandedLoading - url) }
            }
        }
    }

    /** 对照 ExploreTabState.refreshSource: clearExploreKindsCache + loadKinds(force=true) */
    private fun refreshSource(item: BookSourcePart) {
        scope.launch {
            withContext(IoDispatcher) { item.clearExploreKindsCache() }
            loadKinds(item, force = true)
        }
    }

    private fun refreshCurrentExpanded() {
        val url = _state.value.expandedUrl ?: return
        val source = _state.value.sources.find { it.bookSourceUrl == url } ?: return
        refreshSource(source)
    }

    /** reselect 发现 tab: 收起已展开项, 返回是否实际收起 (未展开返回 false, 由 Route 滚顶) */
    fun collapseExpanded(): Boolean {
        if (_state.value.expandedUrl != null) {
            _state.update { it.copy(expandedUrl = null) }
            return true
        }
        return false
    }

    // ===== 收藏 / JS =====

    /** PinnedExploreHelp 内部 postEvent(UP_EXPLORE_PINNED), observePinnedEvent 会刷新 */
    private fun removePinned(item: PinnedExplore) {
        PinnedExploreHelp.removePinnedExplore(item)
    }

    /** 对照 ExploreTabState.runKindJs: runScriptWithContext + evalJS, 失败 AppLog.put(toast) */
    private fun runKindJs(source: BookSource, js: String) {
        scope.launch(IoDispatcher) {
            runCatching {
                runScriptWithContext { source.evalJS(js) }
            }.onFailure { e ->
                ensureActive()
                AppLog.put("JS错误${e.localizedMessage}", e, true)
            }
        }
    }

    override fun onCleared() {
        scope.cancel()
    }
}

/** 发现页 ScreenModel 内部状态 (不含 Compose 的 LazyListState, 由 Route 持有) */
data class ExploreScreenState(
    val sources: List<BookSourcePart> = emptyList(),
    val pinned: List<PinnedExplore> = emptyList(),
    val groups: List<String> = emptyList(),
    val searchKey: String = "",
    val expandedUrl: String? = null,
    val expandedKinds: Map<String, Pair<BookSource?, List<ExploreKind>>> = emptyMap(),
    val expandedLoading: Set<String> = emptySet(),
)

/** ExploreScreenModel 可下沉处理的 UI 事件 (平台相关回调仍走 ExploreUiActions) */
sealed interface ExploreUiEvent {
    /** 搜索过滤: 空=全部发现、group: 前缀=按分组、其余=关键词 */
    data class SetSearch(val query: String) : ExploreUiEvent

    /** 点击分组菜单项 (自动包 "group:" 前缀) */
    data class SetGroup(val group: String) : ExploreUiEvent

    /** 切换某书源展开/收起 (触发 kinds 异步加载) */
    data class ToggleExpand(val item: BookSourcePart) : ExploreUiEvent

    /** 刷新分类 (clearExploreKindsCache + 强制重载) */
    data class RefreshSource(val item: BookSourcePart) : ExploreUiEvent

    /** 置顶源 (ExploreViewModelShared.topSource) */
    data class ToTop(val item: BookSourcePart) : ExploreUiEvent

    /** 删除源 (ExploreViewModelShared.deleteSource) */
    data class DeleteSource(val item: BookSourcePart) : ExploreUiEvent

    /** 移除收藏 (PinnedExploreHelp.removePinnedExplore) */
    data class RemovePinned(val item: PinnedExplore) : ExploreUiEvent

    /** 分类项为 button 类型: 执行其 JS (runScriptWithContext + evalJS) */
    data class RunKindJs(val source: BookSource, val js: String) : ExploreUiEvent
}
