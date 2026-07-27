package io.legado.desktop.ui.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.legado.app.constant.AppLog
import io.legado.app.help.IntentData
import io.legado.app.ui.book.searchContent.SearchContentScreen as SharedSearchContentScreen
import io.legado.app.ui.book.searchContent.SearchContentUiActions
import io.legado.app.ui.book.searchContent.SearchContentUiState
import io.legado.app.ui.book.searchContent.SearchContentViewModelShared
import io.legado.app.ui.book.searchContent.SearchResult
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopEventBusProvider
import io.legado.app.ui.compose.platform.DesktopPreferenceStoreProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.ChineseUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 桌面端"书内全文搜索" Screen 入口 (包装 shared/sharedUiMain 的 [SharedSearchContentScreen])。
 *
 * # 职责
 *
 * - 注入 4 个 DesktopXxxProvider 供 commonMain 的 [AppTheme] / [SharedSearchContentScreen]
 *   通过 LocalXxx 取依赖
 * - 在 [SearchContentContent] 内持有 [SearchContentUiState] state + 构造
 *   [SearchContentUiActions] 实现, 位置传参调用 [SharedSearchContentScreen]
 *
 * # shared 端签名说明
 *
 * shared/sharedUiMain 的 [SharedSearchContentScreen] 接收 [SearchContentUiState] +
 * [SearchContentUiActions] 两个参数 (state + actions 拆分模式), 故桌面端需在 Content
 * 内构造 state + 实现 actions 接口, 与 [io.legado.desktop.ui.about.AboutScreen]
 * 的 Content 持有 state 模式一致。
 *
 * [SharedSearchContentScreen] 内部已自带 [io.legado.app.ui.compose.component.AppTitleBar]
 * (标题栏 + 搜索框 + 替换开关菜单), 故此处不再外加 AppTitleBar (避免双层标题栏)。
 *
 * # KMP 化接入说明
 *
 * 已接入 [SearchContentViewModelShared] (commonMain 下沉核心):
 * - 注入 [rememberCoroutineScope] + [ChineseUtils] actual lambda (shared jvmAndAndroidMain
 *   的 quick-transfer 实现, 桌面端白捡 JVM 兼容);
 * - [LaunchedEffect] 调用 [SearchContentViewModelShared.initBook] 初始化当前书籍
 *   (从 [io.legado.app.help.IntentData.book] 取, 桌面端通常为 null);
 * - `replaceEnabled` 通过 [SearchContentViewModelShared.replaceEnabled] 管理, 与 app 端一致。
 *
 * # KMP 化接入说明 (已接入)
 *
 * - 全文搜索 [onSubmitSearch]: 调 [shared.searchAllChapters] 编排搜索,
 *   依赖 [ContentProcessorProviders] (已注册 DesktopContentProcessorAccessor) +
 *   [BookStorageProviders] + [IntentData.book] (DesktopApp onOpenSearchContent 设置),
 *   增量回调更新 results state, 与 app 端 startContentSearch 行为一致;
 * - 停止搜索 [onStopSearch]: 取消搜索协程 (searchJob.cancel), 与 app 端 job?.cancel 一致;
 * - 打开搜索结果 [onOpenResult]: 调 DesktopApp onOpenResult 回调, 切回 READER 路由并
 *   通过 pendingChapterIndex 跳转章节 (对照 app 端 skipToChapterPos);
 * - 清焦回调 [setClearFocusHandler] / [clearFocus] 仅缓存 handler 引用, 透传给 shared。
 *
 * @param onBack 返回回调 (切回 SETTINGS 路由, 由 DesktopApp 注入)
 */
@Composable
fun SearchContentScreen(
    onBack: () -> Unit,
    onOpenResult: (SearchResult) -> Unit = {},
) {
    // 桌面端 Provider 注入: 供 commonMain 的 AppTheme / SharedSearchContentScreen 取依赖
    val themeStore = remember { DesktopThemeStoreProvider() }
    val appConfig = remember { DesktopAppConfigProvider() }
    val eventBus = remember { DesktopEventBusProvider() }
    val prefStore = remember { DesktopPreferenceStoreProvider() }

    CompositionLocalProvider(
        LocalThemeStoreProvider provides themeStore,
        LocalAppConfigProvider provides appConfig,
        LocalEventBusProvider provides eventBus,
        LocalPreferenceStoreProvider provides prefStore,
    ) {
        AppTheme {
            SearchContentContent(onBack = onBack, onOpenResult = onOpenResult)
        }
    }
}

/**
 * 持有 [SearchContentUiState] state + 构造 [SearchContentUiActions] 实现,
 * 位置传参调用 [SharedSearchContentScreen]。
 *
 * state 字段语义对照 shared/sharedUiMain 的 [SearchContentUiState] KDoc;
 * actions 字段对照 [SearchContentUiActions] 接口方法 (详见 shared KDoc)。
 *
 * # shared 接入
 *
 * [SearchContentViewModelShared] 实例由 [remember] 持有, 注入:
 * - [rememberCoroutineScope] 协程作用域 (桌面端 Compose 作用域);
 * - [ChineseUtils] actual lambda (shared jvmAndAndroidMain 的 quick-transfer 实现):
 *   按 type 分发 t2s/s2t, type=0 原样返回, 与 app 端 when 分发逻辑一致。
 *
 * `replaceEnabled` 由 shared 管理 (读写 [SearchContentViewModelShared.replaceEnabled]),
 * 与 app 端 [io.legado.app.ui.book.searchContent.SearchContentViewModel] 行为一致。
 *
 * @param onBack 返回回调 (切回调用方路由, 由 DesktopApp 注入)
 * @param onOpenResult 搜索结果点击回调 (切回阅读页并跳转章节, 由 DesktopApp 注入)
 */
@Composable
private fun SearchContentContent(
    onBack: () -> Unit,
    onOpenResult: (SearchResult) -> Unit,
) {
    // 共享核心 VM (KMP), 注入桌面 scope + ChineseUtils actual lambda
    val scope = rememberCoroutineScope()
    val shared = remember(scope) {
        SearchContentViewModelShared(
            scope = scope,
            chineseConverter = { type, text ->
                when (type) {
                    1 -> ChineseUtils.t2s(text)
                    2 -> ChineseUtils.s2t(text)
                    else -> text
                }
            },
        )
    }
    // 初始化当前书籍 (从 IntentData.book 取, 桌面端通常为 null)
    LaunchedEffect(Unit) {
        shared.initBook { }
    }

    // 搜索框文本
    var query by remember { mutableStateOf("") }
    // 搜索结果列表 (由 shared.searchAllChapters 增量回调更新)
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    // 结果计数
    var resultCount by remember { mutableStateOf(0) }
    // 是否搜索中
    var searching by remember { mutableStateOf(false) }
    // 搜索协程 (onStopSearch 取消用)
    var searchJob by remember { mutableStateOf<Job?>(null) }
    // 当前阅读章节索引 (从 shared.book 取, 用于结果列表高亮当前章节)
    val durChapterIndex = shared.book?.durChapterIndex ?: -1
    // 替换开关 (通过 shared 管理, 与 app 端一致)
    var replaceEnabled by remember { mutableStateOf(false) }
    // 聚焦请求 epoch (递增触发 shared 内部 LaunchedEffect 聚焦搜索框)
    var focusEpoch by remember { mutableStateOf(0) }
    // 待滚动定位的索引 (消费后置 null)
    var pendingScrollIndex by remember { mutableStateOf<Int?>(null) }
    // 清焦回调缓存 (shared 内部 DisposableEffect 注册, Activity/宿主通过 clearFocus() 触发)
    var clearFocusHandler by remember { mutableStateOf<(() -> Unit)?>(null) }

    val state = SearchContentUiState(
        query = query,
        results = results,
        resultCount = resultCount,
        searching = searching,
        durChapterIndex = durChapterIndex,
        replaceEnabled = replaceEnabled,
        focusEpoch = focusEpoch,
        pendingScrollIndex = pendingScrollIndex,
    )

    // actions 不用 remember: 各回调通过闭包修改外层 state (而非持有自身可变字段),
    // 新实例无副作用; shared 内部 DisposableEffect/LaunchedEffect 不依赖 actions 引用稳定
    val actions = DesktopSearchContentActions(
        onBack = onBack,
        onQueryChange = { text -> query = text },
        onSubmitSearch = { searchQuery ->
            // 执行全文搜索 (对照 app 端 SearchContentActivity.startContentSearch)
            // shared.searchAllChapters 已下沉, 依赖 ContentProcessorProviders (已注册)
            // + BookStorageProviders + IntentData.book (DesktopApp onOpenSearchContent 设置)
            if (searching) return@DesktopSearchContentActions
            searching = true
            // 清空上次搜索结果
            shared.searchResultList.clear()
            shared.searchResultCounts = 0
            results = emptyList()
            resultCount = 0
            searchJob = scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        shared.searchAllChapters(searchQuery) { chapterResults ->
                            // 增量更新: 把 shared.searchResultList 快照赋给 results
                            results = shared.searchResultList.toList()
                            resultCount = shared.searchResultCounts
                        }
                    }
                }.onFailure {
                    AppLog.put("书内搜索失败\n${it.localizedMessage}", it)
                }
                searching = false
                searchJob = null
                // 最终再更新一次 (确保完整)
                results = shared.searchResultList.toList()
                resultCount = shared.searchResultCounts
            }
        },
        onToggleReplaceEnabled = {
            replaceEnabled = !replaceEnabled
            // 同步到 shared, 与 app 端 SearchContentViewModel.toggleReplaceEnabled 行为一致
            shared.replaceEnabled = replaceEnabled
        },
        onStopSearch = {
            // 取消搜索协程 (对照 app 端 job?.cancel())
            searchJob?.cancel()
            searchJob = null
            searching = false
        },
        onOpenResult = { result, index ->
            // 存 searchResultList + index 供阅读页 SearchMenu 使用
            // (对照 app 端 SearchContentActivity.openSearchResult: IntentData.put searchResultList + setResult index)
            IntentData.put("searchResultList", shared.searchResultList.toList())
            IntentData.put("searchResultIndex", index)
            // 切到阅读页并跳转对应章节 (由 DesktopApp onOpenResult 回调实现)
            onOpenResult(result)
        },
        onRequestFocusSearch = { focusEpoch++ },
        setClearFocusHandler = { handler -> clearFocusHandler = handler },
        clearFocus = { clearFocusHandler?.invoke() },
        onConsumePendingScrollIndex = { pendingScrollIndex = null },
    )

    SharedSearchContentScreen(state = state, actions = actions)
}

/**
 * 桌面端 [SearchContentUiActions] 实现。
 *
 * 各方法语义对照 shared/sharedUiMain 的 [SearchContentUiActions] KDoc;
 * 桌面端简化实现见 [SearchContentContent] 与 [SearchContentScreen] 顶层 KDoc。
 */
private class DesktopSearchContentActions(
    private val onBack: () -> Unit,
    private val onQueryChange: (String) -> Unit,
    private val onSubmitSearch: (String) -> Unit,
    private val onToggleReplaceEnabled: () -> Unit,
    private val onStopSearch: () -> Unit,
    private val onOpenResult: (SearchResult, Int) -> Unit,
    private val onRequestFocusSearch: () -> Unit,
    private val setClearFocusHandler: ((() -> Unit)?) -> Unit,
    private val clearFocus: () -> Unit,
    private val onConsumePendingScrollIndex: () -> Unit,
) : SearchContentUiActions {
    override fun onBack() = onBack.invoke()
    override fun onQueryChange(text: String) = onQueryChange.invoke(text)
    override fun onSubmitSearch(query: String) = onSubmitSearch.invoke(query)
    override fun onToggleReplaceEnabled() = onToggleReplaceEnabled.invoke()
    override fun onStopSearch() = onStopSearch.invoke()
    override fun onOpenResult(item: SearchResult, index: Int) = onOpenResult.invoke(item, index)
    override fun onRequestFocusSearch() = onRequestFocusSearch.invoke()
    override fun setClearFocusHandler(handler: (() -> Unit)?) = setClearFocusHandler.invoke(handler)
    override fun clearFocus() = clearFocus.invoke()
    override fun onConsumePendingScrollIndex() = onConsumePendingScrollIndex.invoke()
}
