package io.legado.desktop.ui.explore

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.PinnedExplore
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.data.entities.rule.FlexChildStyle
import io.legado.app.data.entities.rule.RowUi
import io.legado.app.help.PinnedExploreHelp
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.model.script.runScriptWithContext
import io.legado.app.ui.book.source.SourceLoginDialog
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopEventBusProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.explore.ExploreViewModelShared
import io.legado.app.ui.main.explore.ExploreScreen as SharedExploreScreen
import io.legado.app.ui.main.explore.ExploreUiActions
import io.legado.app.ui.main.explore.ExploreUiState
import io.legado.app.utils.FlowBus
import io.legado.app.utils.GSON
import io.legado.app.utils.browseUrl
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.isJsonArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 桌面端发现页入口 (包装 shared/sharedUiMain 的 [SharedExploreScreen])。
 *
 * 对照 desktop [io.legado.desktop.ui.booksource.BookSourceScreen] 模式, 仅做桌面平台
 * 适配, 渲染与交互逻辑全部下沉到 shared/sharedUiMain 的 [SharedExploreScreen]:
 *
 * - 注入 desktop 平台 Provider (ThemeStore / AppConfig / EventBus), 让 commonMain 的
 *   [AppTheme] / [SharedExploreScreen] 可跨平台运行
 * - 持有 [ExploreStateHolder] (实现 [ExploreUiActions]) 收集 DB flow + FlowBus 写入 state
 * - 打包 [ExploreUiState] 传入 shared [SharedExploreScreen]
 *
 * # 路由回调 (由 DesktopApp 顶层注入)
 *
 * - [onOpenExplore]: 点击发现分类 → 跳 ExploreShow 路由 (桌面端 ExploreShowScreen 包装未实现,
 *   待后续补; 默认 no-op)
 * - [onEditSource]: 项菜单"编辑" → 跳 BookSourceEdit 路由 (桌面端未实现, 默认 no-op)
 * - [onSearchBook]: 项菜单"搜索本书" → 跳 SEARCH 路由 (与 BookSourceScreen 单项菜单一致)
 *
 * # 未下沉功能 (no-op + TODO)
 *
 * - [ExploreUiActions.onLogin]: 已接入 [SourceLoginDialog] (shared/sharedUiMain 下沉)
 * - [ExploreUiActions.onRunKindJs]: 已接入 shared commonMain 的
 *   [runScriptWithContext] + [BookSource.evalJS] (对照 app 端 runKindJs),
 *   desktop Main 已注册 QuickJsJsEngine, source.evalJS/jsBindings 编排可用
 *
 * @param onOpenExplore 点击发现分类回调 (source, title, exploreUrl) → 跳 ExploreShow
 * @param onEditSource 编辑书源回调 (sourceUrl) → 跳 BookSourceEdit
 * @param onSearchBook 搜索本书回调 (source) → 跳 SEARCH 路由
 */
@Composable
fun ExploreScreen(
    onOpenExplore: (BookSource, String, String?) -> Unit = { _, _, _ -> },
    onEditSource: (String) -> Unit = {},
    onSearchBook: (BookSourcePart) -> Unit = {},
) {
    // 注入 desktop 平台 Provider (commonMain AppTheme 依赖)
    val themeStore = remember { DesktopThemeStoreProvider() }
    val appConfig = remember { DesktopAppConfigProvider() }
    val eventBus = remember { DesktopEventBusProvider() }
    CompositionLocalProvider(
        LocalThemeStoreProvider provides themeStore,
        LocalAppConfigProvider provides appConfig,
        LocalEventBusProvider provides eventBus,
    ) {
        AppTheme {
            ExploreContent(
                onOpenExplore = onOpenExplore,
                onEditSource = onEditSource,
                onSearchBook = onSearchBook,
            )
        }
    }
}

@Composable
private fun ExploreContent(
    onOpenExplore: (BookSource, String, String?) -> Unit,
    onEditSource: (String) -> Unit,
    onSearchBook: (BookSourcePart) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    // 文案标签 (rememberString 是 @Composable, 顶层缓存后传入 ExploreStateHolder 供回调使用)
    val sureDelExploreFavoriteLabel = rememberString("sure_del_explore_favorite")
    val dialogTitleLabel = rememberString("dialog_title")
    val exploreCategoryErrorLabel = rememberString("explore_category_error")
    val sureDelSourceLabel = rememberString("sure_del_source")
    // AlertDialog 按钮文案 (替换原 JOptionPane, 与 BookSourceScreen deleteSelectionTarget 模式一致)
    val okLabel = rememberString("ok")
    val cancelLabel = rememberString("cancel")
    // 复用 shared commonMain 的 ExploreViewModelShared, 让 onToTop / onDeleteSource
    // 走下沉的统一实现 (替代 holder 内直接调 dao.minOrder/upOrder + SourceHelp.deleteBookSource)
    val exploreShared = remember(scope) { ExploreViewModelShared(scope = scope) }
    val state = remember(scope, listState) {
        // 复用 shared commonMain 的 ExploreViewModelShared, 让 onToTop / onDeleteSource
        // 走下沉的统一实现 (替代 holder 内直接调 dao.minOrder/upOrder + SourceHelp.deleteBookSource)
        ExploreStateHolder(
            scope = scope,
            shared = exploreShared,
            listState = listState,
            openExploreCb = onOpenExplore,
            editSourceCb = onEditSource,
            searchBookCb = onSearchBook,
            sureDelExploreFavoriteLabel = sureDelExploreFavoriteLabel,
            dialogTitleLabel = dialogTitleLabel,
            exploreCategoryErrorLabel = exploreCategoryErrorLabel,
            sureDelSourceLabel = sureDelSourceLabel,
        )
    }

    // 收集分组列表 (对应 app 端 ExploreTabState.collectGroups, 桌面端无 lifecycle 直接 collect)
    LaunchedEffect(Unit) {
        AppDbProviders.get().bookSourceDao.flowExploreGroups().catch {
            AppLog.put(jvmGetString("explore_update_groups_error"), it)
        }.collect { groups ->
            state.updateGroups(groups)
        }
    }

    // 搜索词变化重启收集 (对应 app 端 ExploreTabState.collectSources)
    LaunchedEffect(state.searchKey) {
        val key = state.searchKey
        val dao = AppDbProviders.get().bookSourceDao
        val flow = when {
            key.isBlank() -> dao.flowExplore()
            key.startsWith("group:") -> dao.flowGroupExplore(key.substringAfter("group:"))
            else -> dao.flowExplore(key)
        }
        flow.catch {
            AppLog.put(jvmGetString("explore_update_data_error"), it)
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

    // 打包 state 为 shared 端 ExploreUiState, 调 shared ExploreScreen
    // 位置参数调用 (函数类型参数不能用命名参数)
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
    SharedExploreScreen(uiState, state)

    // ---- AlertDialog 渲染 (替换原 javax.swing.JOptionPane) ----

    // 1. onRemovePinned 二次确认 (替换原 JOptionPane.showConfirmDialog YES_NO_OPTION;
    //    ExploreStateHolder.onRemovePinned 设置 removePinnedTarget, 此处读取渲染)
    state.removePinnedTarget?.let { pin ->
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.8f),
            onDismissRequest = { state.removePinnedTarget = null },
            title = { Text(dialogTitleLabel) },
            text = { Text(sureDelExploreFavoriteLabel) },
            confirmButton = {
                TextButton(onClick = {
                    state.removePinnedTarget = null
                    PinnedExploreHelp.removePinnedExplore(pin)
                }) { Text(okLabel) }
            },
            dismissButton = {
                TextButton(onClick = { state.removePinnedTarget = null }) {
                    Text(cancelLabel)
                }
            },
        )
    }

    // 2. onShowKindError 错误详情 (替换原 JOptionPane.showMessageDialog WARNING_MESSAGE;
    //    错误详情可能较长, 用 verticalScroll 包裹 Text 防止溢出)
    state.kindError?.let { kind ->
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.8f),
            onDismissRequest = { state.kindError = null },
            title = { Text(exploreCategoryErrorLabel) },
            text = {
                Text(
                    text = kind.url.orEmpty(),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { state.kindError = null }) { Text(okLabel) }
            },
        )
    }

    // 3. onDeleteSource 二次确认 (替换原 JOptionPane.showConfirmDialog YES_NO_OPTION;
    //    ExploreStateHolder.onDeleteSource 设置 deleteSourceTarget, 此处读取渲染)
    state.deleteSourceTarget?.let { part ->
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.8f),
            onDismissRequest = { state.deleteSourceTarget = null },
            title = { Text(dialogTitleLabel) },
            text = { Text(sureDelSourceLabel) },
            confirmButton = {
                TextButton(onClick = {
                    state.deleteSourceTarget = null
                    // 复用 shared commonMain 的 ExploreViewModelShared.deleteSource
                    // (内部 launch 协程调 SourceHelp.deleteBookSource, 无需外层 scope.launch)
                    exploreShared.deleteSource(part)
                }) { Text(okLabel) }
            },
            dismissButton = {
                TextButton(onClick = { state.deleteSourceTarget = null }) {
                    Text(cancelLabel)
                }
            },
        )
    }

    // 4. onLogin 书源登录对话框 (调用 shared/sharedUiMain 下沉的 SourceLoginDialog)
    //    登录逻辑由 shared SourceLoginDialog 内部处理 (putLoginInfo + login JS),
    //    与 app 端一致; 桌面端仅需注入 onOpenUrl 回调
    state.loginTarget?.let { src ->
        SourceLoginDialog(
            source = src,
            onDismiss = { state.loginTarget = null },
            onOpenUrl = { url -> browseUrl(url) },
        )
    }
}

/**
 * 桌面端发现页状态宿主 (实现 [ExploreUiActions] 供 shared [SharedExploreScreen] 回调)。
 *
 * 对照 app 端 `ExploreTabState`: snapshot state + 交互回调。差异:
 * - 无 Lifecycle 依赖 (桌面端无 androidx.lifecycle), flow 直接 collect
 * - kinds 异步加载走桌面端简化版 [exploreKindsDesktop] (不执行 JS)
 * - 路由跳转改为回调注入 (onOpenExplore/onEditSource/onSearchBook)
 * - 删除收藏项/书源弹 AlertDialog 二次确认 (与 BookSourceScreen 风格统一,
 *   ExploreContent 末尾渲染分支读取 state.removePinnedTarget / deleteSourceTarget)
 * - 显示分类错误用 AlertDialog (ExploreContent 末尾渲染分支读取 state.kindError)
 *
 * @param scope 协程作用域 (rememberCoroutineScope 提供)
 * @param listState LazyColumn 滚动位 (reselect 滚顶用)
 * @param openExploreCb 点击发现分类回调 (注入路由跳转)
 * @param editSourceCb 编辑书源回调 (注入路由跳转)
 * @param searchBookCb 搜索本书回调 (注入路由跳转)
 */
@Stable
private class ExploreStateHolder(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val shared: ExploreViewModelShared,
    val listState: LazyListState,
    private val openExploreCb: (BookSource, String, String?) -> Unit,
    private val editSourceCb: (String) -> Unit,
    private val searchBookCb: (BookSourcePart) -> Unit,
    // 文案标签 (rememberString 顶层缓存后传入, 避免 JOptionPane 硬编码中文)
    private val sureDelExploreFavoriteLabel: String,
    private val dialogTitleLabel: String,
    private val exploreCategoryErrorLabel: String,
    private val sureDelSourceLabel: String,
) : ExploreUiActions {

    var sources by mutableStateOf<List<BookSourcePart>>(emptyList())
        private set
    var pinned by mutableStateOf(PinnedExploreHelp.getPinnedExplores())
        private set
    var groups by mutableStateOf<List<String>>(emptyList())
        private set
    var searchKey by mutableStateOf("")
        private set

    // 当前展开的书源 (按 url 稳定 key, 替代原 exIndex 位置)
    var expandedUrl by mutableStateOf<String?>(null)
        private set

    // 已展开项的发现分类缓存 (url → source+kinds), 收起后保留旧值供动画期间渲染
    var expandedKinds by mutableStateOf<Map<String, Pair<BookSource?, List<ExploreKind>>>>(emptyMap())
        private set
    // 正在异步加载 kinds 的 url 集合
    var expandedLoading by mutableStateOf<Set<String>>(emptySet())
        private set

    // ---- AlertDialog 显示状态 (替换原 javax.swing.JOptionPane 同步阻塞;
    //   null = 隐藏, 非空 = 显示; ExploreContent 末尾 AlertDialog 渲染分支读取) ----
    // onRemovePinned 二次确认: 待删除的收藏项 (null=隐藏)
    // setter 改 internal: AlertDialog onDismissRequest/确认按钮需在 ExploreContent 中置 null
    var removePinnedTarget by mutableStateOf<PinnedExplore?>(null)
        internal set
    // onShowKindError 错误详情: 待显示的错误分类 (null=隐藏)
    var kindError by mutableStateOf<ExploreKind?>(null)
        internal set
    // onDeleteSource 二次确认: 待删除的书源 (null=隐藏)
    var deleteSourceTarget by mutableStateOf<BookSourcePart?>(null)
        internal set
    // onLogin 书源登录: 待登录的完整 BookSource (null=隐藏)
    // BookSourcePart 是 DatabaseView 不含 header/loginUrl, onLogin 异步按 url 查完整 BookSource 填入;
    // ExploreContent 末尾 SourceLoginDialog 渲染分支读取, 确认按钮调 dao.update 写回 header
    var loginTarget by mutableStateOf<BookSource?>(null)
        internal set

    fun upPinned() {
        pinned = PinnedExploreHelp.getPinnedExplores()
    }

    /** 更新分组列表 (由 ExploreContent 的 LaunchedEffect 收集 flow 后回调)。 */
    fun updateGroups(value: List<String>) {
        groups = value
    }

    /** 更新书源列表 (由 ExploreContent 的 LaunchedEffect 收集 flow 后回调)。 */
    fun updateSources(value: List<BookSourcePart>) {
        sources = value
    }

    /** 切换展开/收起, 展开时触发 kinds 异步加载 (对应 app 端 toggleExpand)。 */
    fun toggleExpand(item: BookSourcePart) {
        val url = item.bookSourceUrl
        if (expandedUrl == url) {
            expandedUrl = null
        } else {
            expandedUrl = url
            loadKinds(item)
        }
    }

    /**
     * 异步加载某书源的发现分类 (对应 app 端 loadKinds)。
     * 已缓存则跳过 (force=false); force=true 时强制重载 (refresh 用)。
     */
    fun loadKinds(item: BookSourcePart, force: Boolean = false) {
        val url = item.bookSourceUrl
        if (!force && expandedKinds.containsKey(url)) return
        scope.launch {
            expandedLoading = expandedLoading + url
            try {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        val source = AppDbProviders.get().bookSourceDao.getBookSource(url)
                        val kinds = source?.exploreKindsDesktop() ?: emptyList()
                        source to kinds
                    }
                }.getOrDefault(null to emptyList())
                expandedKinds = expandedKinds + (url to result)
            } finally {
                expandedLoading = expandedLoading - url
            }
        }
    }

    /** 刷新分类: 清缓存 + 强制重载 (对应 app 端 refreshSource)。 */
    fun refreshSource(item: BookSourcePart) {
        scope.launch {
            item.clearExploreKindsCache()
            loadKinds(item, force = true)
        }
    }

    // ---- ExploreUiActions 实现 (对照 app 端 ExploreTabState 别名桥接) ----

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
                AppLog.put(jvmGetString("explore_pinned_source_not_found", pin.sourceUrl))
            }
        }
    }

    override fun onRemovePinned(pin: PinnedExplore) {
        // 弹 AlertDialog 二次确认 (替换原 JOptionPane.showConfirmDialog 同步阻塞;
        // ExploreContent 末尾 AlertDialog 渲染分支读取 removePinnedTarget,
        // 用户确认 (YES) 则调 PinnedExploreHelp.removePinnedExplore(pin))
        removePinnedTarget = pin
    }

    override fun onOpenExplore(source: BookSource, title: String, exploreUrl: String?) {
        openExploreCb.invoke(source, title, exploreUrl)
    }

    override fun onShowKindError(kind: ExploreKind) {
        // 弹 AlertDialog 显示错误详情 (替换原 JOptionPane.showMessageDialog WARNING_MESSAGE;
        // ExploreContent 末尾 AlertDialog 渲染分支读取 kindError, text 用 verticalScroll 防溢出)
        kindError = kind
    }

    override fun onRunKindJs(source: BookSource, js: String) {
        // 执行 button 类型分类的 JS (对照 app 端 runKindJs)
        // shared commonMain 的 runScriptWithContext + BaseSource.evalJS 已下沉, 桌面端可用
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    runScriptWithContext { source.evalJS(js) }
                }
            }.onFailure { e ->
                // 协程取消不记日志 (对照 app 端 ensureActive())
                if (e is kotlinx.coroutines.CancellationException) return@onFailure
                AppLog.put(jvmGetString("explore_js_error", e.localizedMessage), e, true)
            }
        }
    }

    override fun onEditSource(sourceUrl: String) {
        editSourceCb.invoke(sourceUrl)
    }

    override fun onToTop(source: BookSourcePart) {
        // 复用 shared commonMain 的 ExploreViewModelShared.topSource (无 Android 依赖, 已下沉)
        // shared 内部 launch 协程执行 dao.minOrder/upOrder; 滚顶是 UI 行为, 仍在 holder 内做
        shared.topSource(source)
        scope.launch { listState.animateScrollToItem(0) }
    }

    override fun onLogin(source: BookSourcePart) {
        // BookSourcePart 是 DatabaseView 不含 header/loginUrl, 按 url 查完整 BookSource;
        // 查到后设置 loginTarget, ExploreContent 末尾 SourceLoginDialog 渲染分支读取展示
        scope.launch {
            loginTarget = AppDbProviders.get().bookSourceDao.getBookSource(source.bookSourceUrl)
        }
    }

    override fun onSearchBook(source: BookSourcePart) {
        searchBookCb.invoke(source)
    }

    override fun onRefreshSource(source: BookSourcePart) = refreshSource(source)

    override fun onDeleteSource(source: BookSourcePart) {
        // 对应 app 端 ExploreViewModel.deleteSource → SourceHelp.deleteBookSource
        // 弹 AlertDialog 二次确认 (替换原 JOptionPane.showConfirmDialog 同步阻塞;
        // ExploreContent 末尾 AlertDialog 渲染分支读取 deleteSourceTarget,
        // 用户确认 (YES) 则调 SourceHelp.deleteBookSource(source.bookSourceUrl))
        deleteSourceTarget = source
    }
}

/**
 * 桌面端简化版 exploreKinds (对齐 app 端 `BookSource.exploreKinds()` 扩展)。
 *
 * app 端依赖 ACache + runScriptWithContext (evalJS) + exploreKindsMap 内存缓存,
 * 桌面端:
 * - 走 [BookSource.exploreKindsJson] 取 JSON (已下沉, 走 ExploreKindsCacheProviders;
 *   桌面端未注册 ExploreKindsCacheProvider.impl, 故仅 exploreUrl 本身为 JSON 数组时能解析)
 * - JSON 数组用 [GSON.fromJsonArray] 解析为 `List<ExploreKind>`
 * - 非 JSON 走 [parseTextKinds] 文本格式解析 (split by && 或 \n, :: 分隔)
 * - 不执行 JS (若 exploreUrl 以 `<js>` / `@js:` 开头, exploreKindsJson 返回空字符串,
 *   走文本解析会得到单个 ERROR 项, 用户长按可看详情)
 *
 * 内存缓存 (对应 app 端 exploreKindsMap) 暂未实现, 每次重新解析;
 * 若性能成问题可后续补 ConcurrentHashMap 缓存。
 */
private suspend fun BookSource.exploreKindsDesktop(): List<ExploreKind> {
    return withContext(Dispatchers.IO) {
        runCatching {
            val json = exploreKindsJson()
            if (json.isJsonArray()) {
                GSON.fromJsonArray<ExploreKind>(json).getOrDefault(emptyList())
            } else {
                val ruleStr = exploreUrl
                if (ruleStr.isNullOrBlank()) {
                    emptyList()
                } else {
                    parseTextKinds(ruleStr)
                }
            }
        }.getOrElse {
            listOf(ExploreKind("ERROR:${it.localizedMessage}", url = it.stackTraceToString()))
        }
    }
}

/**
 * 文本格式发现分类解析 (复刻 app 端 `BookSourceExtensions.exploreKinds` 文本分支)。
 *
 * 格式: `title::url` 多行, 用 `&&` 或 `\n` 分隔;
 *       前缀 `BUTTON:` / `TITLE:` 切换 type (对应 [RowUi.Type.button] / [RowUi.Type.title]);
 *       `TITLE:` 默认占整行 (cols = 1)。
 */
private fun parseTextKinds(ruleStr: String): List<ExploreKind> {
    val kinds = arrayListOf<ExploreKind>()
    ruleStr.split("(&&|\n)+".toRegex()).forEach { kindStr ->
        val kindCfg = kindStr.split("::")
        var title = kindCfg.first()
        var type = RowUi.Type.text
        var cols: Int? = null
        when {
            title.startsWith("BUTTON:") -> {
                title = title.substring(7)
                type = RowUi.Type.button
            }
            title.startsWith("TITLE:") -> {
                title = title.substring(6)
                type = RowUi.Type.title
                cols = 1
            }
        }
        kinds.add(
            ExploreKind(
                title = title,
                type = type,
                url = kindCfg.getOrNull(1),
                style = cols?.let { FlexChildStyle(cols = it) },
            ),
        )
    }
    return kinds
}
