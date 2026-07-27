package io.legado.app.ui.book.search

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.AlertDialog
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.search.SearchNavCallbacks
import io.legado.app.ui.book.search.SearchScreen as SharedSearchScreen
import io.legado.app.ui.book.search.SearchViewModel
import io.legado.app.ui.bookshelf.OhosInfoCover
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.flow.collect

/**
 * 鸿蒙端搜索页 Screen 入口 (包装 shared/sharedUiMain 的 [SharedSearchScreen])。
 *
 * 对照 iOS 端 [io.legado.app.ui.search.IosSearchScreen] / desktop `SearchScreen.kt` 的包装模式,
 * 鸿蒙端在 OhosNavHost 的 SEARCH 路由分支调用本入口。
 *
 * 本文件仅做鸿蒙平台适配, 业务逻辑全部下沉到 shared/sharedUiMain:
 * - **VM 生命周期**: `remember { SearchViewModel() }` 持有, Screen 退出时
 *   `DisposableEffect.onDispose { viewModel.close() }` 取消内部协程 scope
 *   (鸿蒙端无 lifecycleScope, 对照 app 端 SearchViewModel.onCleared 语义,
 *   KMP VM 用 [SearchViewModel.close] 释放; 与 iOS / desktop 一致)
 * - **路由跳转**: 通过 [SearchNavCallbacks] 回调注入, 由 OhosNavHost 顶层传入真实路由实现
 *   (onBack → 书架, onBookClick → 详情, onManageBookSources → 书源管理)
 *
 * # 路由回调实现 (对照 iOS IosSearchNavCallbacks / desktop DesktopSearchNavCallbacks)
 *
 * - [onBack]: 调用 [onBack] 回到书架 (由 OhosNavHost 注入)
 * - [onBookClick]: 调用 [onBookClick] 携带 BaseBook 跳详情页 (由 OhosNavHost 注入)
 * - [onManageBookSources]: 调用 [onManageBookSources] 切到书源管理路由
 * - [onAlertSearchScope]: 弹 [SearchScopeDialog] (shared/sharedUiMain 已下沉, 鸿蒙端直接复用)
 * - [onShowAppLog]: 弹 [AppLogDialog] (shared/sharedUiMain 已下沉, 鸿蒙端直接复用)
 * - [onClearHistory]: 弹 [AlertDialog] 二次确认后调 viewModel.clearHistory()
 *   (对齐 app 端 alert + R.string.sure_clear_search_history; 与 iOS / desktop 一致)
 * - [onShowSourceFilterRule]: TODO (书源过滤规则 Dialog 未下沉, no-op)
 *
 * @param onBack 返回回调 (切回书架路由, 由 OhosNavHost 注入)
 * @param onBookClick 点击书籍回调 (切到 BOOK_INFO 详情路由, 携带 BaseBook)
 * @param onManageBookSources 进入书源管理回调 (切到 BOOK_SOURCE 路由)
 */
@Composable
fun OhosSearchScreen(
    onBack: () -> Unit,
    onBookClick: (BaseBook) -> Unit,
    onManageBookSources: () -> Unit,
) {
    val viewModel = remember { SearchViewModel() }
    DisposableEffect(viewModel) {
        onDispose { viewModel.close() }
    }

    // 清空搜索历史二次确认对话框状态 (对照 iOS IosSearchScreen, 与 desktop 一致)
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    // 应用日志对话框状态 (onShowAppLog 触发, 末尾 AppLogDialog 渲染)
    var showLogDialog by remember { mutableStateOf(false) }
    // 搜索范围对话框状态 (onAlertSearchScope 触发, 末尾 SearchScopeDialog 渲染)
    var showSearchScopeDialog by remember { mutableStateOf(false) }
    // 全部分组 (订阅 bookGroupDao.flowAll(), 供 SearchScopeDialog 展示分组列表)
    val groups by produceState<List<BookGroup>>(emptyList()) {
        AppDbProviders.get().bookGroupDao.flowAll().collect { value = it }
    }
    // 文案标签 (rememberString 是 @Composable, 顶层缓存; AlertDialog 渲染分支使用)
    val sureClearSearchHistoryLabel = rememberString("sure_clear_search_history")
    val okLabel = rememberString("ok")
    val cancelLabel = rememberString("cancel")

    SharedSearchScreen(
        viewModel = viewModel,
        navCallbacks = OhosSearchNavCallbacks(
            onBack = onBack,
            onBookClick = onBookClick,
            onManageBookSources = onManageBookSources,
            clearHistory = { showClearHistoryDialog = true },
            onShowAppLogCb = { showLogDialog = true },
            onAlertSearchScopeCb = { showSearchScopeDialog = true },
        ),
        // 封面注入: 复用书架/详情页同一套 OhosInfoCover (共享 LRU + 磁盘缓存)。
        // modifier 由 shared 端按占位原尺寸构造, OhosInfoCover 只在其上加圆角, 不改尺寸。
        coverSlot = { searchBook, modifier, _ ->
            val book = remember(searchBook) { searchBook.toBook() }
            OhosInfoCover(book, modifier)
        },
        shelfCoverSlot = { book, modifier, _ ->
            OhosInfoCover(book, modifier)
        },
    )

    // ---- 清空搜索历史二次确认对话框 (对照 iOS IosSearchScreen / desktop SearchScreen) ----
    if (showClearHistoryDialog) {
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.8f),
            onDismissRequest = { showClearHistoryDialog = false },
            title = { Text(sureClearSearchHistoryLabel) },
            confirmButton = {
                TextButton(onClick = {
                    showClearHistoryDialog = false
                    viewModel.clearHistory()
                }) { Text(okLabel) }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) {
                    Text(cancelLabel)
                }
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
    // (与 iOS IosSearchScreen / desktop SearchScreen 实现完全一致, 复用 shared/sharedUiMain SearchScopeDialog)
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
 * 鸿蒙端搜索导航回调实现 (对照 iOS `IosSearchNavCallbacks` / desktop `DesktopSearchNavCallbacks`)。
 *
 * 路由跳转改为回调注入 (由 OhosNavHost 顶层提供具体路由切换), 不依赖 Activity/Intent。
 * 未实现的回调 (待后续下沉对应 Dialog):
 * - [onShowSourceFilterRule]: 书源过滤规则 Dialog 未下沉, no-op
 *
 * @param onBack 返回 (切回书架)
 * @param onBookClick 点击书籍 (切到详情, 携带 BaseBook)
 * @param onManageBookSources 进入书源管理
 * @param clearHistory 清空搜索历史 (设置 showClearHistoryDialog=true, 由外层 Composable 弹确认框)
 * @param onShowAppLogCb 触发应用日志对话框显示
 * @param onAlertSearchScopeCb 触发搜索范围对话框显示
 */
private class OhosSearchNavCallbacks(
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
        // 鸿蒙端暂不区分长按/单击 (与 iOS 端一致, app 原版长按也进详情),
        // 但保留 longClick 分支判断避免丢弃参数 (与 iOS IosSearchNavCallbacks.onBookClick 行为一致)
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
        // TODO: 下沉 SourceFilterRuleDialog + 鸿蒙端弹出
    }

    override fun onShowAppLog() {
        // 触发应用日志对话框显示 (外层 Composable 控制 showLogDialog 状态, 末尾 AppLogDialog 渲染)
        onShowAppLogCb.invoke()
    }

    override fun onClearHistory() {
        // 触发 AlertDialog 显示 (clearHistory lambda 由 OhosSearchScreen 注入,
        // 仅设置 showClearHistoryDialog=true, 用户在 AlertDialog 确认后才调 viewModel.clearHistory())
        clearHistory.invoke()
    }
}
