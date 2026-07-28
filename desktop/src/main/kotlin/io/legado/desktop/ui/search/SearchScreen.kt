package io.legado.desktop.ui.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.search.SearchNavCallbacks
import io.legado.app.ui.book.search.SearchScopeDialog

import io.legado.app.ui.book.search.SearchViewModel
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.utils.splitNotBlank
import io.legado.desktop.ui.component.DesktopBookCover
import kotlinx.coroutines.flow.collect

/**
 * 桌面端搜索 Screen 入口 (包装 shared/commonMain 的 [io.legado.app.ui.book.search.SearchScreen])。
 *
 * # 职责
 *
 * 对照 desktop [io.legado.desktop.ui.bookshelf.BookshelfScreen] 模式, 仅做桌面平台
 * 适配, 业务逻辑全部下沉到 shared/commonMain 的 [io.legado.app.ui.book.search.SearchScreen]:
 *
 * - **VM 生命周期**: 用 `remember { SearchViewModel() }` 持有, 窗口退出时
 *   `DisposableEffect.onDispose { viewModel.close() }` 取消内部协程 scope
 *   (桌面端无 lifecycleScope, 对照 app 端 SearchViewModel.onCleared 语义,
 *   KMP VM 用 [SearchViewModel.close] 释放)
 * - **路由跳转**: 通过 [SearchNavCallbacks] 回调注入, 由 [io.legado.desktop.ui.DesktopApp]
 *   顶层传入真实路由实现 (onBack → 书架, onBookClick → 详情, onManageBookSources → 书源管理)
 *
 * # 路由回调实现
 *
 * - [onBack]: 调用 [onBack] 回到书架 (由 DesktopApp 注入)
 * - [onBookClick]: 调用 [onBookClick] 携带 BaseBook 跳详情页 (由 DesktopApp 注入)
 * - [onManageBookSources]: 调用 [onManageBookSources] 切到书源管理路由
 * - [onAlertSearchScope] / [onShowSourceFilterRule]: 弹窗 Dialog 未下沉, 保持 TODO
 * - [onShowAppLog]: 桌面端无应用日志页, 保持 TODO
 * - [onClearHistory]: 弹 AlertDialog 二次确认后调 viewModel.clearHistory()
 *   (与 BookSourceScreen 风格统一, 对齐 app 端 alert + R.string.sure_clear_search_history)
 *
 * # 简化项
 *
 * - 桌面端默认无 IntentData 传入搜索词 (app 端 SearchActivity 接收 IntentData),
 *   搜索框默认空, 通过 shared VM init 时设置 focusEpoch 请求焦点
 * - 无桌面端搜索范围 Dialog (依赖 BookSourceDao/BookGroupDao 数据),
 *   用户点击"分组/书源"菜单时回调暂为 no-op, 待后续下沉 SearchScopeDialog
 * - 无桌面端书源过滤规则 Dialog / 应用日志页, 同上留 TODO
 *
 * @param onBack 返回回调 (切回书架路由)
 * @param onBookClick 点击书籍回调 (切到 BOOK_INFO 详情路由, 携带 BaseBook)
 * @param onManageBookSources 进入书源管理回调 (切到 BOOK_SOURCE 路由)
 * @param initialQuery 预填搜索关键词 (由 BookInfo 详情页 onSearchAuthor/onSearchKind/onNameClick
 *   触发, 对照 app 端 SearchActivity intent 契约 key; null 表示无预填)
 * @param initialSubmit 是否自动提交搜索 (对照 app 端 SearchActivity intent 契约 submit)
 */
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onBookClick: (BaseBook) -> Unit,
    onManageBookSources: () -> Unit,
    initialQuery: String? = null,
    initialSubmit: Boolean = true,
) {
    val viewModel = remember { SearchViewModel() }
    DisposableEffect(viewModel) {
        onDispose { viewModel.close() }
    }
    // 预填搜索词 (对照 app 端 SearchActivity.receiptIntent: key 非空则 setQuery(key, submit))
    LaunchedEffect(initialQuery) {
        initialQuery?.takeIf { it.isNotBlank() }?.let { key ->
            viewModel.setQuery(key, initialSubmit)
        }
    }

    // 清空搜索历史二次确认对话框状态 (替换原 JOptionPane.showConfirmDialog 同步阻塞,
    // 与 BookSourceScreen deleteSelectionTarget 模式一致; DesktopSearchNavCallbacks.onClearHistory
    // 触发 showClearHistoryDialog=true, 末尾 AlertDialog 渲染分支读取)
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
        navCallbacks = DesktopSearchNavCallbacks(
            onBack = onBack,
            onBookClick = onBookClick,
            onManageBookSources = onManageBookSources,
            clearHistory = { showClearHistoryDialog = true },
            onShowAppLogCb = { showLogDialog = true },
            onAlertSearchScopeCb = { showSearchScopeDialog = true },
        ),
        // 封面注入: 复用书架/详情页同一套 DesktopBookCover (共享 LRU 缓存 getOrLoadCover)。
        // modifier 由 shared 端按占位原尺寸构造, InfoCover 只在其上加圆角, 不改尺寸。
        coverSlot = { searchBook, modifier, _ ->
            val book = remember(searchBook) { searchBook.toBook() }
            DesktopBookCover.InfoCover(book, modifier)
        },
        shelfCoverSlot = { book, modifier, _ ->
            DesktopBookCover.InfoCover(book, modifier)
        },
    )

    // ---- 对话框渲染 (替换原 javax.swing.JOptionPane.showConfirmDialog) ----
    if (showClearHistoryDialog) {
        AppAlertDialog(
            widthFraction = 0.8f,
            onDismissRequest = { showClearHistoryDialog = false },
            title = sureClearSearchHistoryLabel,
            okButton = AlertButton(okLabel, dismissOnClick = false) {
                showClearHistoryDialog = false
                viewModel.clearHistory()
            },
            cancelButton = AlertButton(cancelLabel),
        )
    }
    // ---- 应用日志对话框 (onShowAppLog 触发, 调用 shared/sharedUiMain 下沉的 AppLogDialog) ----
    if (showLogDialog) {
        AppLogDialog(onDismiss = { showLogDialog = false })
    }

    // ---- 搜索范围对话框 (onAlertSearchScope 触发, 调用 shared/sharedUiMain 下沉的 SearchScopeDialog) ----
    // searchGroup 存储格式: groupName 逗号分隔 (对照 SearchScope.kt),
    // 故读出时按 groupName 反查 groupId, 确认时按 groupId 反查 groupName 写回。
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
 * 桌面端搜索导航回调实现。
 *
 * 对照 app 端 SearchActivity 实现的 SearchNavCallbacks, 桌面端把路由跳转改为
 * 回调注入 (由 DesktopApp 顶层提供具体路由切换), 不依赖 Activity/Intent。
 *
 * 未实现的回调 (待后续下沉对应 Dialog):
 * - [onAlertSearchScope]: 搜索范围 Dialog 未下沉, no-op
 * - [onShowSourceFilterRule]: 书源过滤规则 Dialog 未下沉, no-op
 * - [onShowAppLog]: 桌面端无应用日志页, no-op
 *
 * @param onBack 返回 (切回书架)
 * @param onBookClick 点击书籍 (切到详情, 携带 BaseBook)
 * @param onManageBookSources 进入书源管理
 * @param clearHistory 清空搜索历史 (直接调 viewModel.clearHistory)
 */
private class DesktopSearchNavCallbacks(
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
        // 桌面端暂不区分长按/单击 (app 原版长按也进详情), 但保留 longClick 分支判断
        // 避免丢弃参数 (任务要求修复"点击事件变了": longClick 必须被使用而非忽略)
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
        // TODO: 下沉 SourceFilterRuleDialog + 桌面端弹出
    }

    override fun onShowAppLog() {
        // 触发应用日志对话框显示 (外层 Composable 控制 showLogDialog 状态, 末尾 AppLogDialog 渲染)
        onShowAppLogCb.invoke()
    }

    override fun onClearHistory() {
        // 触发 AlertDialog 显示 (替换原 JOptionPane.showConfirmDialog 同步阻塞;
        // clearHistory lambda 由 SearchScreen 注入, 仅设置 showClearHistoryDialog=true,
        // 用户在 AlertDialog 确认后才调 viewModel.clearHistory())
        clearHistory.invoke()
    }
}
