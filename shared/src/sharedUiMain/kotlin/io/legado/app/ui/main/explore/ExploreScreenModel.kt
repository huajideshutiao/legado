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
import io.legado.app.ui.root.screenModelScope
import io.legado.app.utils.FlowBus
import io.legado.app.utils.throttleLatest
import kotlinx.coroutines.Job
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
    private val scope = screenModelScope("发现")

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

    /**
     * 展开项现查现取源与分类 (对照原版 ExploreAdapter.handleExpand:
     * 每次 bind 都 `item.getBookSource()` 现查 + `bookSource.exploreKinds()` 现算)。
     *
     * 本层不再做跨展开历史的 kinds 快照: 底层 exploreKinds() 自带
     * md5(bookSourceUrl + exploreUrl) 内存 + 磁盘两级缓存, 命中即返回, 现取无额外解析成本;
     * 而 UI 层再按 bookSourceUrl 缓一份快照, 会让底层 md5 自动失效机制完全落空
     * (书源编辑后分类陈旧、进 show 用旧源对象), 故每次展开必现取。
     */
    private fun loadKinds(item: BookSourcePart) {
        val url = item.bookSourceUrl
        // 清槽 + 置 loading 同步做 (在改 expandedUrl 的同一次调用内、协程派发前):
        // 若放进 scope.launch 延后一帧, toggleExpand 已把 expandedUrl 改成 B、
        // 而槽里仍是 A 的残留 → B 行渲染 A 的数据 (原版按 position 判定无此窗口)
        _state.update {
            it.copy(
                loadingUrl = url,
                expandedSource = null,
                expandedKinds = emptyList(),
            )
        }
        scope.launch {
            try {
                val (source, kinds) = runCatching {
                    withContext(IoDispatcher) {
                        val source = appDb.bookSourceDao.getBookSource(url)
                        source to (source?.exploreKinds() ?: emptyList())
                    }
                }.getOrDefault(null to emptyList())
                // 归属校验: 仅当 url 仍是当前展开行才写槽 (恢复原版 ExploreAdapter.handleRefresh
                // 回写前 "pos 未变才写" 的保护): 防收起/切到别的行后 A 的慢查询后到,
                // 把 A 的源/分类覆盖成展开中 B 行的数据并点分类跳错发现页
                _state.update {
                    if (it.expandedUrl != url) it
                    else it.copy(expandedSource = source, expandedKinds = kinds)
                }
            } finally {
                _state.update { it.copy(loadingUrl = if (it.loadingUrl == url) null else it.loadingUrl) }
            }
        }
    }

    /** 对照原版长按菜单"刷新分类": clearExploreKindsCache (清底层 md5 缓存) + 现查重取 */
    private fun refreshSource(item: BookSourcePart) {
        scope.launch {
            withContext(IoDispatcher) { item.clearExploreKindsCache() }
            // 对齐原版: 未展开的行只清底层缓存 (下次展开 loadKinds 现取天然新鲜),
            // 只有当前展开行才立即重查写槽 —— 否则刷新未展开的 C 会同步清掉展开中 A 的槽 (A 突然空白)
            if (_state.value.expandedUrl == item.bookSourceUrl) loadKinds(item)
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
                AppLog.put("JS错误${e.message}", e, true)
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
    /** 当前展开行 url (null = 全部收起); 对应原版 ExploreAdapter.exIndex */
    val expandedUrl: String? = null,
    /** 当前展开行本次现查的源对象: 点分类进 show 直接用
     *  (对齐原版 bind 现查 + 传对象不二次查库; 收起动画期间的渲染由 Compose 局部冻结负责) */
    val expandedSource: BookSource? = null,
    /** 当前展开行本次现算的分类列表 */
    val expandedKinds: List<ExploreKind> = emptyList(),
    /** 正在异步加载 kinds 的行 url (单槽不变式: 同一时刻至多一行在加载; null=无) */
    val loadingUrl: String? = null,
)

/** ExploreScreenModel 可下沉处理的 UI 事件 (平台相关回调仍走 ExploreUiActions) */
sealed interface ExploreUiEvent {
    /** 搜索过滤: 空=全部发现、group: 前缀=按分组、其余=关键词 */
    data class SetSearch(val query: String) : ExploreUiEvent

    /** 点击分组菜单项 (自动包 "group:" 前缀) */
    data class SetGroup(val group: String) : ExploreUiEvent

    /** 切换某书源展开/收起 (触发 kinds 异步加载) */
    data class ToggleExpand(val item: BookSourcePart) : ExploreUiEvent

    /** 刷新分类 (清底层 md5 缓存 + 现查重取) */
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
