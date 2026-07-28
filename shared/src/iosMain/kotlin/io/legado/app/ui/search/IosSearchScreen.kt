package io.legado.app.ui.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.search.SearchNavCallbacks

import io.legado.app.ui.book.search.SearchViewModel
import io.legado.app.ui.bookshelf.IosInfoCover
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.flow.collect

/**
 * iOS 端搜索页 Screen 入口 (KP4: 包装 shared/sharedUiMain 的 [io.legado.app.ui.book.search.SearchScreen])。
 *
 * # 职责
 *
 * 对照 desktop `desktop/src/main/kotlin/io/legado/desktop/ui/search/SearchScreen.kt`
 * 的包装模式, iOS 端在 [io.legado.app.ui.IosNavHost] 的 SEARCH 路由分支调用本入口。
 *
 * 本文件仅做 iOS 平台适配, 业务逻辑全部下沉到 shared/sharedUiMain:
 * - **VM 生命周期**: `remember { SearchViewModel() }` 持有, UIViewController 退出时
 *   `DisposableEffect.onDispose { viewModel.close() }` 取消内部协程 scope
 *   (iOS 端无 lifecycleScope, 对照 app 端 SearchViewModel.onCleared 语义,
 *   KMP VM 用 [SearchViewModel.close] 释放; 与 desktop SearchScreen line 71-74 一致)
 * - **路由跳转**: 通过 [SearchNavCallbacks] 回调注入, 由 [io.legado.app.ui.IosNavHost]
 *   顶层传入真实路由实现 (onBack → 书架, onBookClick → 详情, onManageBookSources → 书源管理)
 *
 * # 路由回调实现 (对照 desktop DesktopSearchNavCallbacks)
 *
 * - [onBack]: 调用 [onBack] 回到书架 (由 IosNavHost 注入)
 * - [onBookClick]: 调用 [onBookClick] 携带 BaseBook 跳详情页 (由 IosNavHost 注入)
 * - [onManageBookSources]: 调用 [onManageBookSources] 切到书源管理路由
 * - [onAlertSearchScope]: 弹 [SearchScopeDialog] (shared/sharedUiMain 已下沉, iOS 端直接复用)
 * - [onShowAppLog]: 弹 [AppLogDialog] (shared/sharedUiMain 已下沉, iOS 端直接复用)
 * - [onClearHistory]: 弹 [AppAlertDialog] 二次确认后调 viewModel.clearHistory()
 *   (对齐 app 端 alert + R.string.sure_clear_search_history; 与 desktop SearchScreen line 105-123 一致)
 * - [onShowSourceFilterRule]: TODO (书源过滤规则 Dialog 未下沉, no-op)
 *
 * @param onBack 返回回调 (切回书架路由, 由 IosNavHost 注入)
 * @param onBookClick 点击书籍回调 (切到 BOOK_INFO 详情路由, 携带 BaseBook)
 * @param onManageBookSources 进入书源管理回调 (切到 BOOK_SOURCE 路由)
 */
@Composable
fun IosSearchScreen(
    onBack: () -> Unit,
    onBookClick: (BaseBook) -> Unit,
    onManageBookSources: () -> Unit,
) {
    val viewModel = remember { SearchViewModel() }
    DisposableEffect(viewModel) {
        onDispose { viewModel.close() }
    }

    // 清空搜索历史二次确认对话框状态 (对照 desktop SearchScreen line 79,
    // 与 BookSourceScreen deleteSelectionTarget 模式一致)
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    // 应用日志对话框状态 (false=隐藏, true=显示; onShowAppLog 触发, 末尾 AppLogDialog 渲染)
    var showLogDialog by remember { mutableStateOf(false) }
    // 搜索范围对话框状态 (false=隐藏, true=显示; onAlertSearchScope 触发, 末尾 SearchScopeDialog 渲染)
    var showSearchScopeDialog by remember { mutableStateOf(false) }
    // 全部分组 (订阅 bookGroupDao.flowAll(), 供 SearchScopeDialog 展示分组列表)
    val groups by produceState<List<BookGroup>>(emptyList()) {
        AppDbProviders.get().bookGroupDao.flowAll().collect { value = it }
    }
    // 文案标签 (rememberString 是 @Composable, 顶层缓存; AlertDialog 渲染分支使用)
    val sureClearSearchHistoryLabel = rememberString("sure_clear_search_history")
    val okLabel = rememberString("ok")
    val cancelLabel = rememberString("cancel")

    io.legado.app.ui.book.search.SearchScreen(
        viewModel = viewModel,
        navCallbacks = IosSearchNavCallbacks(
            onBack = onBack,
            onBookClick = onBookClick,
            onManageBookSources = onManageBookSources,
            clearHistory = { showClearHistoryDialog = true },
            onShowAppLogCb = { showLogDialog = true },
            onAlertSearchScopeCb = { showSearchScopeDialog = true },
        ),
        // 封面注入: 复用书架/详情页同一套 IosInfoCover (共享 LRU 缓存)。
        // modifier 由 shared 端按占位原尺寸构造, IosInfoCover 只在其上加圆角, 不改尺寸。
        coverSlot = { searchBook, modifier, _ ->
            val book = remember(searchBook) { searchBook.toBook() }
            IosInfoCover(book, modifier)
        },
        shelfCoverSlot = { book, modifier, _ ->
            IosInfoCover(book, modifier)
        },
    )

    // ---- 清空搜索历史二次确认对话框 (对照 desktop SearchScreen line 106-123) ----
    if (showClearHistoryDialog) {
        AppAlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = sureClearSearchHistoryLabel,
            widthFraction = 0.8f,
            okButton = AlertButton(okLabel, dismissOnClick = false) {
                showClearHistoryDialog = false
                viewModel.clearHistory()
            },
            cancelButton = AlertButton(cancelLabel, dismissOnClick = false) {
                showClearHistoryDialog = false
            },
        )
    }
    // ---- 应用日志对话框 (onShowAppLog 触发, 调用 shared/sharedUiMain 下沉的 AppLogDialog) ----
    if (showLogDialog) {
        AppLogDialog(onDismiss = { showLogDialog = false })
    }

    // ---- 搜索范围对话框 (onAlertSearchScope 触发, 调用 shared/sharedUiMain 下沉的 SearchScopeDialog) ----
    // searchGroup 存储格式: groupName 逗号分隔 (对照 SearchScope.kt),
    // 故读出时按 groupName 反查 groupId, 确认时按 groupId 反查 groupName 写回。
    // (与 desktop SearchScreen line 132-151 实现完全一致, 复用 shared/sharedUiMain SearchScopeDialog)
    if (showSearchScopeDialog) {
        val searchGroup = AppConfigProviders.get().searchGroup
        // 读出: groupName 字符串集合 → groupId 集合 (通过 groups 反查)
        val selectedGroupNames = searchGroup.splitNotBlank(",").toSet()
        val selectedGroupIds = groups
            .filter { it.groupName in selectedGroupNames }
            .map { it.groupId }
            .toSet()
        SearchScopeDialog(
            groups = groups,
            selectedGroupIds = selectedGroupIds,
            onConfirm = { ids ->
                // 写回: groupId 集合 → groupName 字符串 (逗号分隔), 写入 AppConfig.searchGroup
                val names = groups.filter { it.groupId in ids }.map { it.groupName }
                AppConfigProviders.get().searchGroup = names.joinToString(",")
                showSearchScopeDialog = false
            },
            onDismiss = { showSearchScopeDialog = false },
        )
    }
}

/**
 * iOS 端搜索导航回调实现 (对照 desktop `DesktopSearchNavCallbacks`)。
 *
 * 路由跳转改为回调注入 (由 [io.legado.app.ui.IosNavHost] 顶层提供具体路由切换),
 * 不依赖 Activity/Intent。未实现的回调 (待后续下沉对应 Dialog):
 * - [onShowSourceFilterRule]: 书源过滤规则 Dialog 未下沉, no-op
 *
 * @param onBack 返回 (切回书架)
 * @param onBookClick 点击书籍 (切到详情, 携带 BaseBook)
 * @param onManageBookSources 进入书源管理
 * @param clearHistory 清空搜索历史 (设置 showClearHistoryDialog=true, 由外层 Composable 弹确认框)
 * @param onShowAppLogCb 触发应用日志对话框显示
 * @param onAlertSearchScopeCb 触发搜索范围对话框显示
 */
private class IosSearchNavCallbacks(
    private val onBack: () -> Unit,
    private val onBookClick: (BaseBook) -> Unit,
    private val onManageBookSources: () -> Unit,
    private val clearHistory: () -> Unit,
    private val onShowAppLogCb: () -> Unit,
    private val onAlertSearchScopeCb: () -> Unit,
) : SearchNavCallbacks {

    override fun onBack() {
        onBack.invoke()
    }

    override fun onBookClick(book: BaseBook, longClick: Boolean) {
        // iOS 端暂不区分长按/单击 (app 原版长按也进详情), 但保留 longClick 分支判断
        // 避免丢弃参数 (与 desktop DesktopSearchNavCallbacks.onBookClick 行为一致)
        when (longClick) {
            true -> onBookClick.invoke(book)  // 长按 → 详情 (app 原版行为)
            false -> onBookClick.invoke(book) // 单击 → 详情 (保持现有行为)
        }
    }

    override fun onManageBookSources() {
        onManageBookSources.invoke()
    }

    override fun onAlertSearchScope() {
        // 触发搜索范围对话框显示 (外层 Composable 控制 showSearchScopeDialog 状态,
        // 末尾 SearchScopeDialog 渲染分支读取 groups + AppConfig.searchGroup)
        onAlertSearchScopeCb.invoke()
    }

    override fun onShowSourceFilterRule() {
        // TODO: 下沉 SourceFilterRuleDialog + iOS 端弹出
    }

    override fun onShowAppLog() {
        // 触发应用日志对话框显示 (外层 Composable 控制 showLogDialog 状态, 末尾 AppLogDialog 渲染)
        onShowAppLogCb.invoke()
    }

    override fun onClearHistory() {
        // 触发 AlertDialog 显示 (clearHistory lambda 由 IosSearchScreen 注入,
        // 仅设置 showClearHistoryDialog=true, 用户在 AlertDialog 确认后才调 viewModel.clearHistory())
        clearHistory.invoke()
    }
}
