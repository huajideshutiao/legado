package io.legado.desktop.ui.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.SourceFilterRule
import io.legado.app.help.book.addType
import io.legado.app.help.source.SearchBookFilter
import io.legado.app.model.webBook.ExploreOption
import io.legado.app.ui.book.explore.ExploreShowScreen as SharedExploreShowScreen
import io.legado.app.ui.book.explore.ExploreShowUiActions
import io.legado.app.ui.book.explore.ExploreShowUiState
import io.legado.app.ui.book.filter.SourceFilterEditDialog
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppSearchField
import io.legado.app.ui.compose.component.AppSwitch
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.RadioChip
import io.legado.app.ui.compose.component.StrokeTextChip
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopEventBusProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.dialog.NumberPickerDialog
import io.legado.app.ui.explore.ExploreShowViewModelShared
import io.legado.app.utils.FlowBus
import io.legado.desktop.ui.component.DesktopBookCover
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 桌面端发现结果页 Screen 入口 (包装 shared/sharedUiMain 的 [SharedExploreShowScreen])。
 *
 * # 职责
 *
 * 对照 desktop [ExploreScreen] / [io.legado.desktop.ui.book.toc.TocScreen] 模式, 仅做
 * 桌面平台适配, 业务展示与交互逻辑全部下沉到 shared/sharedUiMain 的 [SharedExploreShowScreen]:
 *
 * - 注入 desktop 平台 Provider (ThemeStore / AppConfig / EventBus), 让 commonMain 的
 *   [AppTheme] / [SharedExploreShowScreen] 可跨平台运行
 * - 持有 [ExploreShowStateHolder] (实现 [ExploreShowUiActions]) 收集 DB flow + 加载发现结果
 * - 打包 [ExploreShowUiState] 传入 shared [SharedExploreShowScreen]
 *
 * # 数据加载 (对照 app 端 ExploreShowViewModel)
 *
 * - `bookDao.flowAll()` → 计算 bookshelf keys 集合 (name / name-author / bookUrl),
 *   驱动 isInBookshelf + bookshelfVersion++ (绿点/徽标刷新)
 * - `WebBook.getBookListAwait(source, url, page, isSearch=false)` 分页加载发现结果
 * - `SearchBookFilter.apply` 过滤命中源过滤规则的结果
 * - `parseExploreOptionsFromUrl` 解析 URL 中的参数 chip (合并去重, optionsVersion++)
 * - `PinnedExploreHelp` 管理收藏 (FlowBus UP_EXPLORE_PINNED 触发 isFavorite 刷新)
 *
 * # 路由回调 (由 DesktopApp 顶层注入)
 *
 * - [onBack]: 切回 EXPLORE 路由
 * - [onBookClick]: 携带 SearchBook 跳 BOOK_INFO 详情路由 (内部补 notShelf type, 对照 Activity)
 *
 * # 已接入功能 (原 TODO 已补齐)
 *
 * - optionsRowSlot: 桌面端用 [DesktopExploreOptionsRow] (Compose chip) 替代 L3 LinearLayout + setUpExploreOptions
 * - videoItemSlot: ItemExploreVideoBinding (L3 Android 专属), 桌面端用 [DesktopVideoItemPlaceholder] 简化占位
 * - [ExploreShowUiActions.onShowColumnPicker]: 复用 shared [NumberPickerDialog] (Arco Design 规范)
 * - [ExploreShowUiActions.onShowSourceFilterRule]: 桌面端 [DesktopSourceFilterRuleListDialog] (复用 shared [SourceFilterEditDialog])
 *
 * @param source 发现目标书源 (由 DesktopApp 注入, 内部用 bookSourceUrl / exploreStyle 等)
 * @param title 发现分类名 (标题栏显示, 收藏 PinnedExplore.categoryName 用)
 * @param exploreUrl 发现 URL (含参数 chip 声明, 可能为 null)
 * @param onBack 返回回调 (切回 EXPLORE 路由)
 * @param onBookClick 书籍点击回调 (携带 SearchBook 跳 BOOK_INFO 详情路由)
 */
@Composable
fun ExploreShowScreen(
    source: BookSource,
    title: String,
    exploreUrl: String?,
    onBack: () -> Unit,
    onBookClick: (SearchBook) -> Unit,
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
            ExploreShowContent(
                source = source,
                title = title,
                exploreUrl = exploreUrl,
                onBack = onBack,
                onBookClick = onBookClick,
            )
        }
    }
}

/**
 * 发现结果页内容主体 (Provider + AppTheme 内部)。
 *
 * 持有 [ExploreShowStateHolder], 在 [LaunchedEffect] 内收集 DB flow + FlowBus,
 * 构造 [ExploreShowUiState] + slots 调用 [SharedExploreShowScreen]。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
private fun ExploreShowContent(
    source: BookSource,
    title: String,
    exploreUrl: String?,
    onBack: () -> Unit,
    onBookClick: (SearchBook) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // 文案标签 (rememberString 是 @Composable, 顶层缓存后传入 ExploreShowStateHolder 供 footer 状态机使用)
    val bottomReachedLabel = rememberString("bottom_reached")
    val loadingErrorClickDetailLabel = rememberString("loading_error_click_detail")
    val retryLabel = rememberString("retry")
    val closeLabel = rememberString("close")
    val loadingErrorLabel = rememberString("loading_error")
    val emptyLabel = rememberString("explore_show_empty")
    // 复用 shared commonMain 的 ExploreShowViewModelShared, 让 initData / explore /
    // toggleFavorite / switchLayout / setColumnCount / isInBookShelf 走下沉的统一实现
    // (替代 holder 内重复的 WebBook/SearchBookFilter/PinnedExploreHelp/dao 调用)。
    // shared 内部 init 块已订阅 bookDao.flowAll 维护 bookshelf, holder 不再重复订阅。
    val shared = remember(scope) { ExploreShowViewModelShared(scope = scope) }
    val state = remember(source, title, exploreUrl, scope, onBack, onBookClick, shared) {
        ExploreShowStateHolder(
            source = source,
            title = title,
            exploreUrl = exploreUrl,
            scope = scope,
            shared = shared,
            onBack = onBack,
            onBookClick = onBookClick,
            bottomReachedLabel = bottomReachedLabel,
            loadingErrorClickDetailLabel = loadingErrorClickDetailLabel,
            retryLabel = retryLabel,
            closeLabel = closeLabel,
            loadingErrorLabel = loadingErrorLabel,
            emptyLabel = emptyLabel,
        )
    }

    // 收藏变更事件 → 刷新 isFavorite (对照 PinnedExploreHelp 内部 postEvent;
    //   shared 不监听 FlowBus, 由 holder 在事件时调 shared.isFavorite 刷新)
    LaunchedEffect(Unit) {
        FlowBus.with(EventBus.UP_EXPLORE_PINNED).collect {
            state.refreshFavorite()
        }
    }

    // 初始化加载 (对照 ViewModel.initData + Activity.onActivityCreated)
    // shared.initData 内部会触发 explore, holder.initData 先调 hasMoreLoad 设 loading=true
    LaunchedEffect(source, title, exploreUrl) {
        state.initData()
    }

    // 订阅 shared 的 StateFlow, 桥接到 holder state (对照 app 端 ViewModel collect shared → postValue)
    // 6 个 StateFlow 各自一个 LaunchedEffect; 过滤 null 避免初始假触发
    // (StateFlow 是 hot flow, collect 立即收到当前值, shared 的可空 StateFlow 初始都是 null)
    LaunchedEffect(shared) {
        shared.booksFlow.collect { newBooks ->
            newBooks?.let { state.onBooksUpdate(it) }
        }
    }
    LaunchedEffect(shared) {
        shared.errorFlow.collect { msg ->
            msg?.let { state.onErrorUpdate(it) }
        }
    }
    LaunchedEffect(shared) {
        shared.upAdapterFlow.collect {
            it?.let { state.onUpAdapterUpdate() }
        }
    }
    LaunchedEffect(shared) {
        shared.upStarFlow.collect { fav ->
            fav?.let { state.onUpStarUpdate(it) }
        }
    }
    LaunchedEffect(shared) {
        shared.optionsReadyFlow.collect {
            it?.let { state.onOptionsReadyUpdate() }
        }
    }
    LaunchedEffect(shared) {
        shared.sourceReadyFlow.collect {
            it?.let { state.onSourceReadyUpdate() }
        }
    }

    val uiState = ExploreShowUiState(
        title = title,
        books = state.books,
        exploreStyle = state.exploreStyle,
        isFavorite = state.isFavorite,
        bookshelfVersion = state.bookshelfVersion,
        optionsVersion = state.optionsVersion,
        scrollTopEpoch = state.scrollTopEpoch,
        footerLoading = state.footerLoading,
        footerText = state.footerText,
    )

    // 位置参数调用 (shared Screen 函数类型参数不能用命名参数, 见 ExploreScreen 模式)
    SharedExploreShowScreen(
        uiState,
        state,
        {
            // 参数 chip 行: 读 state.optionsVersion 触发重组, chip 选中后调 state.onExploreOptionChanged
            // (shared.exploreOptions 是 MutableList<ExploreOption>, option 字段 mutable 可直接改)
            DesktopExploreOptionsRow(
                options = shared.exploreOptions,
                version = state.optionsVersion,
                onOptionChanged = state::onExploreOptionChanged,
            )
        },
        { book, inBookshelf, onClick, onLongClick ->
            // 桌面端 L3 ItemExploreVideoBinding 未下沉, 用简化占位
            DesktopVideoItemPlaceholder(book, inBookshelf, onClick, onLongClick)
        },
        { book, _, _, modifier ->
            // 桌面端封面: 复用 DesktopBookCover.InfoCover (SearchBook.toBook() 转 Book,
            // 与 BookInfoScreen coverSlot 模式一致; 缓存按 coverUrl 命中)
            DesktopBookCover.InfoCover(book.toBook(), modifier)
        },
    )

    // ---- AlertDialog 渲染 (替换原 javax.swing.JOptionPane.showOptionDialog) ----
    // onFooterClick 错误详情+重试对话框 (state.footerErrorDialog 触发, errorMsg 已是 state 字段;
    //   重试按钮调 state.retryFooterLoad() 复刻原 choice==0 分支,
    //   关闭按钮/外部 dismiss 仅关闭对话框, 复刻原 choice==1 / 关闭分支)
    if (state.footerErrorDialog) {
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.8f),
            onDismissRequest = { state.footerErrorDialog = false },
            title = { Text(loadingErrorLabel) },
            text = {
                // 错误详情可能较长 (stackTrace), 用 verticalScroll 包裹防止溢出
                Text(
                    text = state.errorMsg,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                // 重试 (原 choice==0): 调 retryFooterLoad() 触发 hasMoreLoad + explore
                TextButton(onClick = {
                    state.footerErrorDialog = false
                    state.retryFooterLoad()
                }) { Text(retryLabel) }
            },
            dismissButton = {
                // 关闭 (原 choice==1): 仅关闭对话框
                TextButton(onClick = { state.footerErrorDialog = false }) {
                    Text(closeLabel)
                }
            },
        )
    }

    // ---- NumberPickerDialog 渲染 (替换原 NumberPicker Dialog 未下沉 no-op) ----
    // 对照 app 端 showColumnPicker: 弹 NumberPicker 选 0..6 列, 确认后调 setColumnCount + dismiss。
    // 用 shared/sharedUiMain 的 NumberPickerDialog (Arco Design 规范, AlertDialog + Slider + 步进按钮)。
    if (state.showColumnPickerDialog) {
        NumberPickerDialog(
            title = rememberString("explore_cols"),
            value = BookSource.exploreStyleCols(source.exploreStyle),
            range = 0..6,
            onConfirm = { cols ->
                state.setColumnCount(cols)
                state.dismissColumnPickerDialog()
            },
            onDismiss = { state.dismissColumnPickerDialog() },
        )
    }

    // ---- SourceFilterRuleListDialog 渲染 (替换原 SourceFilterRule Dialog 未下沉 no-op) ----
    // 对照 app 端 showSourceFilterRule: 弹当前 scope (SearchScope(source)) 命中规则列表,
    // 支持启用切换/编辑/新增/删除 (复用 shared SourceFilterEditDialog), 由 DesktopSourceFilterRuleListDialog 内部落库。
    if (state.showSourceFilterRuleDialog) {
        DesktopSourceFilterRuleListDialog(
            source = source,
            onDismiss = { state.dismissSourceFilterRuleDialog() },
        )
    }
}

/**
 * 参数 chip 行 (桌面端, 替代 L3 LinearLayout + setUpExploreOptions)。
 *
 * 对照 app 端 `ExploreOptionsRow(activity)` + `LinearLayout.setUpExploreOptions`:
 * - 读 [version] (state.optionsVersion) 触发外部参数结构变化重组 (shared.optionsReadyFlow → optionsVersion++)
 * - 本地 [localVersion] trigger 驱动 chip 选中高亮刷新 (option.selectedValue 是 mutable 字段,
 *   Compose 不自动感知, 需手动 ++ 触发重组, 对齐 app 端 refreshAlpha)
 * - 单选 option: title chip (点击 resetToDefault) + 各值 RadioChip (选中高亮)
 * - 多选 option: title chip + 已选 StrokeTextChip, 整行点击弹 [MultiSelectOptionDialog]
 *
 * @param options shared.exploreOptions (mutable List<ExploreOption>, option 字段可直接改)
 * @param version state.optionsVersion (外部参数结构变化信号)
 * @param onOptionChanged 选中变化回调 (调 state.onExploreOptionChanged → 清 books + explore resetPage)
 */
@Composable
private fun DesktopExploreOptionsRow(
    options: List<ExploreOption>,
    version: Int,
    onOptionChanged: () -> Unit,
) {
    if (options.isEmpty()) return
    // 本地重组 trigger: chip 点击改 option.selectedValue/selectedValues 后, optionsVersion 不变
    // (shared 仅在 options 结构变化才 ++), 需本地 version++ 驱动 chip 高亮刷新
    var localVersion by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_EXPRESSION") version
    localVersion
    Column(Modifier.fillMaxWidth()) {
        options.forEach { option ->
            if (option.multiSelect) {
                MultiSelectOptionRow(option) { localVersion++; onOptionChanged() }
            } else {
                SingleSelectOptionRow(option) { localVersion++; onOptionChanged() }
            }
        }
    }
}

/**
 * 单选 option 行: title chip (点击 resetToDefault) + 各值 RadioChip (选中高亮)。
 *
 * 对照 app 端 bindSingleSelect: title 点击重置, 值 chip 点击切换 selectedValue + refreshAlpha。
 */
@Composable
private fun SingleSelectOptionRow(option: ExploreOption, onOptionChanged: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // title chip: 点击 resetToDefault (对齐 app 端 addTitleChip onClick)
        StrokeTextChip(
            text = option.name,
            textColor = AppTheme.colors.primaryText,
        ) {
            if (option.resetToDefault()) onOptionChanged()
        }
        Spacer(Modifier.width(4.dp))
        // 各值 chip: RadioChip 选中高亮 (对齐 app 端 ALPHA_SELECTED/UNSELECTED)
        option.options.forEach { (label, value) ->
            RadioChip(
                text = label,
                checked = option.selectedValue == value,
            ) {
                if (option.selectedValue == value) return@RadioChip
                option.selectedValue = value
                onOptionChanged()
            }
            Spacer(Modifier.width(4.dp))
        }
    }
}

/**
 * 多选 option 行: title chip + 已选 StrokeTextChip, 整行点击弹 [MultiSelectOptionDialog]。
 *
 * 对照 app 端 bindMultiSelect: title + 已选 chip 点击行为一致 (都弹对话框),
 * 不在每个 chip 重复挂 listener, 整行 clickable 扩大点击区域。
 */
@Composable
private fun MultiSelectOptionRow(option: ExploreOption, onOptionChanged: () -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { showDialog = true }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StrokeTextChip(
            text = option.name,
            textColor = AppTheme.colors.primaryText,
            onClick = { showDialog = true },
        )
        Spacer(Modifier.width(4.dp))
        // 仅展示已选值 chip (对齐 app 端 render: value !in selectedValues 跳过)
        option.options.forEach { (label, value) ->
            if (value !in option.selectedValues) return@forEach
            StrokeTextChip(
                text = label,
                textColor = AppTheme.colors.accent,
                onClick = { showDialog = true },
            )
            Spacer(Modifier.width(4.dp))
        }
    }
    if (showDialog) {
        MultiSelectOptionDialog(
            option = option,
            onApply = {
                showDialog = false
                onOptionChanged()
            },
            onDismiss = { showDialog = false },
        )
    }
}

/**
 * 多选 tag 对话框 (对照 app 端 showMultiSelectDialog)。
 *
 * - working 拷贝 selectedValues, ok 时写回 (cancel/dismiss 不影响原状态)
 * - 搜索框过滤 label/value (对齐 app 端 TagsAdapter.filter)
 * - tag 列表用 LazyColumn + RadioChip (选中高亮), 点击 toggle working
 * - ok: working 写回 option.selectedValues + [onApply]; clear: 清空 + [onApply]; cancel/外部dismiss: [onDismiss]
 *
 * @param onApply ok/clear 后回调 (关闭对话框 + 触发 onOptionChanged 刷新)
 * @param onDismiss cancel/外部 dismiss 回调 (仅关闭对话框, 不刷新)
 */
@Composable
private fun MultiSelectOptionDialog(
    option: ExploreOption,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    // working 拷贝, ok 时才写回 (对齐 app 端 "拷贝一份 working, ok 时才写回")
    // 用 SnapshotStateList (mutableStateListOf) 让 add/remove 可观察, 驱动 RadioChip
    // checked 高亮在点击后立即刷新 (普通 MutableSet 不触发 Compose 重组)
    val working = remember { mutableStateListOf(*option.selectedValues.toTypedArray()) }
    var query by remember { mutableStateOf("") }
    val visible = remember(option, query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) option.options
        else option.options.filter {
            it.first.contains(trimmed, true) || it.second.contains(trimmed, true)
        }
    }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        okButton = AlertButton(rememberString("ok"), dismissOnClick = false) {
            // 对齐 app 端: working != selectedValues 才写回 + onConfirmed
            // (working 是 SnapshotStateList, 与 MutableSet 比较需转 Set)
            if (working.toSet() != option.selectedValues) {
                option.selectedValues.clear()
                option.selectedValues.addAll(working)
                onApply()
            }
        },
        neutralButton = AlertButton(rememberString("clear"), dismissOnClick = false) {
            // 对齐 app 端: 非空才清空 + onConfirmed
            if (option.selectedValues.isNotEmpty()) {
                option.selectedValues.clear()
                onApply()
            }
        },
        cancelButton = AlertButton(rememberString("cancel")) {},
    ) {
        // content: 搜索框 + tag 列表 (对齐 app 端 searchBox + RecyclerView FlexboxLayoutManager)
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            AppSearchField(
                value = query,
                onValueChange = { query = it },
                hint = rememberString("search"),
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                items(visible) { (label, value) ->
                    RadioChip(
                        text = label,
                        checked = value in working,
                        modifier = Modifier.padding(2.dp),
                    ) {
                        // toggle working (对齐 app 端 if (!working.add(value)) working.remove(value))
                        // SnapshotStateList 无 Boolean add, 改用 contains 判断
                        if (value in working) working.remove(value) else working.add(value)
                    }
                }
            }
        }
    }
}

/**
 * 源过滤规则列表对话框 (桌面端, 对照 app 端 SourceFilterRuleListDialog)。
 *
 * 展示当前 scope (SearchScope(source)) 下命中的启用规则, 支持:
 * - 启用/禁用切换 (AppSwitch → [SearchBookFilter.save])
 * - 编辑 (IconButton → 弹 shared [SourceFilterEditDialog], onConfirm 走 [SearchBookFilter.save])
 * - 新增 (DialogTitleBar actions IconButton → 弹 [SourceFilterEditDialog], rule=null)
 * - 删除 (IconButton → AppDropdownMenu "删除" → AppAlertDialog 确认 → [SearchBookFilter.delete])
 *
 * 与 app 端差异:
 * - 不实现"管理全部"按钮 (需路由跳转 [io.legado.desktop.ui.book.filter.SourceFilterRuleScreen],
 *   ExploreShowScreen 无此回调注入; 用户可从 MY 入口进管理页)
 * - 用 [AppAlertDialog] (Dialog) 替代 BaseComposeDialogFragment
 *
 * @param source 发现目标书源 (计算 scope 用)
 * @param onDismiss 关闭回调 (由 ExploreShowContent 末尾渲染分支调用 state.dismissSourceFilterRuleDialog)
 */
@Composable
private fun DesktopSourceFilterRuleListDialog(
    source: BookSource,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // 当前书源 scope 字符串 (对照 app 端 viewModel.bookSource?.let { SearchScope(it).toString() })
    val scopeStr = remember(source) { SearchScope(source).toString() }
    // 规则列表 (SearchBookFilter.rulesInScope 是 suspend, 异步加载)
    var rules by remember { mutableStateOf<List<SourceFilterRule>>(emptyList()) }
    // 版本号: 编辑/新增/删除/启用切换后 ++ 触发重新加载 (对齐 app 端 loadRules 在 onResume 调)
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(scopeStr, version) {
        rules = withContext(Dispatchers.IO) { SearchBookFilter.rulesInScope(scopeStr) }
    }
    // 编辑/新增对话框状态 (editingRule=null 新增, 非空 编辑)
    var showEditDialog by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<SourceFilterRule?>(null) }
    // 待删除规则 (非空时弹确认对话框)
    var pendingDelete by remember { mutableStateOf<SourceFilterRule?>(null) }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        widthFraction = 0.8f,
    ) {
        Column(Modifier.fillMaxWidth()) {
            DialogTitleBar(
                title = rememberString("source_filter_rule"),
                onBack = onDismiss,
                actions = {
                    // 新增规则 (对齐 app 端 ic_add IconButton)
                    IconButton(onClick = {
                        editingRule = null
                        showEditDialog = true
                    }) {
                        Icon(
                            painter = rememberPainter("ic_add"),
                            contentDescription = rememberString("add"),
                            tint = AppTheme.colors.primaryText,
                        )
                    }
                },
            )
            if (rules.isEmpty()) {
                // 空态文案 (对齐 app 端 source_filter_rule_no_match)
                Text(
                    text = rememberString("source_filter_rule_no_match"),
                    color = AppTheme.colors.secondaryText,
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    items(rules, key = { it.id }) { rule ->
                        SourceFilterRuleRow(
                            rule = rule,
                            onToggleEnabled = { enabled ->
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        SearchBookFilter.save(rule.copy(enabled = enabled), isNew = false)
                                    }
                                    version++
                                }
                            },
                            onEdit = {
                                editingRule = rule
                                showEditDialog = true
                            },
                            onDelete = { pendingDelete = rule },
                        )
                    }
                }
            }
            // 底部关闭按钮 (对齐 app 端 actionBar AppTextButton "close")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                AppTextButton(
                    text = rememberString("close"),
                    color = AppTheme.colors.secondaryText,
                    onClick = onDismiss,
                )
            }
        }
    }

    // 编辑/新增对话框 (复用 shared SourceFilterEditDialog, 内部仅校验+组装, 落库由调用方负责)
    if (showEditDialog) {
        SourceFilterEditDialog(
            rule = editingRule,
            onConfirm = { newRule ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        // editingRule==null 新增走 insert, 非空编辑走 update (SearchBookFilter.save 内部判断)
                        SearchBookFilter.save(newRule, isNew = editingRule == null)
                    }
                    version++
                }
            },
            onDismiss = { showEditDialog = false },
        )
    }

    // 删除确认对话框 (对齐 app 端 confirmDelete alert)
    pendingDelete?.let { rule ->
        AppAlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = rememberString("draw"),
            message = rememberString("sure_del") + "\n" + rule.name.ifEmpty { rule.pattern },
            okButton = AlertButton(rememberString("ok")) {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        SearchBookFilter.delete(rule)
                    }
                    version++
                }
            },
            cancelButton = AlertButton(rememberString("cancel")) {},
        )
    }
}

/**
 * 源过滤规则单行 (对照 app 端 SourceFilterRuleListDialog.RuleItem)。
 *
 * 名称(空回退 pattern) + 启用开关 + 编辑按钮 + 更多菜单(删除)。
 * 与 desktop SourceFilterRuleScreen.SourceFilterRuleItem 风格一致 (ic_edit + ic_more_vert + AppDropdownMenu)。
 *
 * @param rule 当前规则
 * @param onToggleEnabled 启用开关切换回调
 * @param onEdit 编辑回调
 * @param onDelete 删除回调
 */
@Composable
private fun SourceFilterRuleRow(
    rule: SourceFilterRule,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = AppTheme.colors
    var showMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = rule.name.ifEmpty { rule.pattern },
            color = colors.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        AppSwitch(
            checked = rule.enabled,
            onCheckedChange = onToggleEnabled,
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onEdit) {
            Icon(
                painter = rememberPainter("ic_edit"),
                contentDescription = rememberString("edit"),
                tint = colors.primaryText,
            )
        }
        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(
                    painter = rememberPainter("ic_more_vert"),
                    contentDescription = rememberString("more_menu"),
                    tint = colors.primaryText,
                )
            }
            AppDropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text(rememberString("delete"), color = colors.primaryText) },
                    onClick = { showMenu = false; onDelete() },
                )
            }
        }
    }
}

/**
 * 视频卡桌面端占位 (L3 ItemExploreVideoBinding 未下沉)。
 *
 * 简化 Row 布局 (封面占位 + 书名 + 书架绿点), 视觉接近 list tier, 仅作临时占位;
 * 后续接入 Compose 版视频卡或 AndroidView 桥接后替换。
 *
 * @param book 当前搜索结果书
 * @param inBookshelf 是否在书架中 (绿点显隐)
 * @param onClick 点击回调
 * @param onLongClick 长按回调
 */
@Composable
private fun DesktopVideoItemPlaceholder(
    book: SearchBook,
    inBookshelf: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 72.dp, height = 96.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF165DFF)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = book.name.firstOrNull()?.toString() ?: "?",
                color = Color.White,
                fontSize = 24.sp,
            )
        }
        Text(
            text = book.name,
            color = Color.Black,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = 8.dp)
                .weight(1f),
        )
        if (inBookshelf) {
            Box(
                Modifier
                    .padding(start = 8.dp)
                    .size(8.dp)
                    .background(Color(0xFF43A047), RoundedCornerShape(50)),
            )
        }
    }
}

/**
 * 桌面端发现结果页状态宿主 (实现 [ExploreShowUiActions] 供 shared [SharedExploreShowScreen] 回调)。
 *
 * 对照 app 端 `ExploreShowViewModel` + `ExploreShowActivity` 状态字段 + actions 实现。
 * 差异:
 * - 无 Lifecycle 依赖 (桌面端无 androidx.lifecycle), flow 直接 collect
 * - 无 LiveData, 用 Compose `mutableStateOf` / `mutableIntStateOf` 直接驱动重组
 * - 路由跳转改为回调注入 (onBack / onBookClick)
 * - 错误详情弹窗用 AlertDialog (ExploreShowContent 末尾渲染分支读取 state.footerErrorDialog)
 * - NumberPicker Dialog 复用 shared [NumberPickerDialog]; SourceFilterRule Dialog 用桌面端 [DesktopSourceFilterRuleListDialog]
 *
 * footer 状态机 (对照 Activity 同名方法):
 * - [hasMoreLoad]: 清 error + footerHasMore=true + startLoad
 * - [noMore]: stopLoad + 清 error + footerHasMore=false + 设 footerText
 * - [upError]: stopLoad + footerHasMore=false + 记 errorMsg + 设 footerText
 *
 * @param source 发现目标书源
 * @param title 发现分类名 (收藏 PinnedExplore.categoryName 用)
 * @param exploreUrl 发现 URL (可能为 null)
 * @param scope 协程作用域 (rememberCoroutineScope 提供)
 * @param onBack 由 DesktopApp 注入的返回回调
 * @param onBookClick 由 DesktopApp 注入的书籍点击回调 (携带 SearchBook)
 */
@Stable
private class ExploreShowStateHolder(
    val source: BookSource,
    val title: String,
    val exploreUrl: String?,
    private val scope: CoroutineScope,
    private val shared: ExploreShowViewModelShared,
    private val onBack: () -> Unit,
    private val onBookClick: (SearchBook) -> Unit,
    // 文案标签 (rememberString 顶层缓存后传入, 避免 footer 状态机硬编码中文)
    private val bottomReachedLabel: String,
    private val loadingErrorClickDetailLabel: String,
    private val retryLabel: String,
    private val closeLabel: String,
    private val loadingErrorLabel: String,
    private val emptyLabel: String,
) : ExploreShowUiActions {

    var books by mutableStateOf<List<SearchBook>>(emptyList())
        private set
    var exploreStyle by mutableStateOf(source.exploreStyle)
        private set
    var isFavorite by mutableStateOf(false)
        private set
    var bookshelfVersion by mutableIntStateOf(0)
        private set
    var optionsVersion by mutableIntStateOf(0)
        private set
    var scrollTopEpoch by mutableIntStateOf(0)
        private set
    var footerLoading by mutableStateOf(false)
        private set
    var footerText by mutableStateOf<String?>(null)
        private set
    // 错误信息 (改为 mutableStateOf + public readable, 替换原 JOptionPane.showMessageDialog;
    // AlertDialog text 读取 errorMsg 驱动重组, 对照 ViewModel.errorMsg)
    var errorMsg by mutableStateOf("")
        private set

    // ---- AlertDialog 显示状态 (替换原 javax.swing.JOptionPane.showOptionDialog 同步阻塞;
    //   false = 隐藏, true = 显示; ExploreShowContent 末尾 AlertDialog 渲染分支读取) ----
    // onFooterClick 错误详情+重试对话框 (boolean, errorMsg 已是 state 字段)
    // setter 改 internal: AlertDialog onDismissRequest/按钮需在 ExploreShowContent 中置 false
    var footerErrorDialog by mutableStateOf(false)
        internal set

    // ---- 列数选择对话框显隐状态 (由 onShowColumnPicker 触发, ExploreShowContent 末尾渲染) ----
    var showColumnPickerDialog by mutableStateOf(false)
        private set

    // ---- 源过滤规则对话框显隐状态 (由 onShowSourceFilterRule 触发, ExploreShowContent 末尾渲染) ----
    var showSourceFilterRuleDialog by mutableStateOf(false)
        private set

    // 私有 footer 状态 (桌面端 UI 专属, shared 不感知)
    private var footerHasMore = true

    // ===== shared StateFlow 回调 (由 ExploreShowContent LaunchedEffect 调用) =====

    /**
     * shared.booksFlow 变化时回调, 更新 books + footer 状态机 (对照原 explore 内部成功分支)。
     *
     * footer 状态对照原 explore:
     * - newBooks 空 + 原本空 → [noMore] emptyLabel
     * - newBooks.size > prevSize → books = newBooks; [stopLoad]
     * - 否则 → [stopLoad] (books 不变, 防止重复内容覆盖)
     * - !shared.hasNextPage → [noMore] bottomReachedLabel
     */
    fun onBooksUpdate(newBooks: List<SearchBook>) {
        val prevSize = books.size
        if (newBooks.isEmpty() && prevSize == 0) {
            noMore(emptyLabel)
        } else if (newBooks.size > prevSize) {
            books = newBooks
            stopLoad()
        } else {
            stopLoad()
        }
        if (!shared.hasNextPage) {
            noMore(bottomReachedLabel)
        }
    }

    /** shared.errorFlow 变化时回调, [upError] 设 errorMsg + footer (对照原 explore 内部失败分支)。 */
    fun onErrorUpdate(msg: String) {
        upError(msg)
    }

    /** shared.upAdapterFlow 变化时回调, bookshelfVersion++ (对照原 upAdapterLiveData observe)。 */
    fun onUpAdapterUpdate() {
        bookshelfVersion++
    }

    /** shared.upStarFlow 变化时回调, 更新 isFavorite (对照原 upStarLiveData observe)。 */
    fun onUpStarUpdate(fav: Boolean) {
        isFavorite = fav
    }

    /** shared.optionsReadyFlow 变化时回调, optionsVersion++ (对照原 optionsReadyLiveData observe)。 */
    fun onOptionsReadyUpdate() {
        optionsVersion++
    }

    /**
     * shared.sourceReadyFlow 变化时回调, 初始化 exploreStyle + isFavorite。
     * (对照原 sourceReadyLiveData observe, 此时 bookSource 已就绪, 可读 exploreStyle)
     */
    fun onSourceReadyUpdate() {
        exploreStyle = shared.exploreStyle
        isFavorite = shared.isFavorite()
    }

    /** 由 FlowBus UP_EXPLORE_PINNED 触发, 重新计算 isFavorite (shared 内部不监听 FlowBus)。 */
    fun refreshFavorite() {
        isFavorite = shared.isFavorite()
    }

    /**
     * 初始化 (转发到 shared.initData(source, title, exploreUrl))。
     *
     * shared.initData 内部会: 设置 rawExploreUrl / exploreName / bookSource
     * → parseExploreOptions → 推送 sourceReadyFlow / optionsReadyFlow / upStarFlow → explore()。
     * holder 负责: 先调 [hasMoreLoad] 设 loading=true,
     * 等 shared.booksFlow / errorFlow 回调驱动后续 footer 状态。
     */
    fun initData() {
        hasMoreLoad()
        shared.initData(source, title, exploreUrl)
    }

    // ===== footer 状态机 (对照 ExploreShowActivity 同名方法, 桌面端 UI 专属) =====

    private fun startLoad() {
        footerLoading = true
    }

    private fun stopLoad() {
        footerLoading = false
    }

    private fun hasMoreLoad() {
        errorMsg = ""
        footerHasMore = true
        startLoad()
    }

    private fun noMore(msg: String? = null) {
        stopLoad()
        errorMsg = ""
        footerHasMore = false
        footerText = msg ?: bottomReachedLabel
    }

    private fun upError(msg: String) {
        stopLoad()
        footerHasMore = false
        errorMsg = msg
        footerText = loadingErrorClickDetailLabel
    }

    // ===== ExploreShowUiActions 实现 (转发到 shared, 对照 ExploreShowActivity 别名桥接) =====

    override fun onBack() = onBack.invoke()

    override fun onTitleClick() {
        // 对照 scrollToTop: scrollTopEpoch++ 驱动列表 animateScrollToItem(0)
        scrollTopEpoch++
    }

    override fun onToggleFavorite() {
        // 转发到 shared.toggleFavorite (内部会推送 upStarFlow, holder.onUpStarUpdate 接收刷新 isFavorite)
        shared.toggleFavorite()
    }

    override fun onSwitchLayout() {
        // 转发到 shared.switchLayout (内部更新 source.exploreStyle + DB);
        // 同步刷新本地 exploreStyle state (shared.exploreStyle 读 bookSource?.exploreStyle)
        shared.switchLayout()
        exploreStyle = shared.exploreStyle
    }

    override fun onShowColumnPicker() {
        // 对照 app 端 showColumnPicker: 弹 NumberPicker 选 0..6 列;
        // ExploreShowContent 末尾读取 showColumnPickerDialog 渲染 NumberPickerDialog,
        // 用户确认后调 setColumnCount(cols) + 关闭对话框
        showColumnPickerDialog = true
    }

    /**
     * 设置发现列表列数 (转发到 shared.setColumnCount, 对照 app 端 ViewModel.setColumnCount)。
     *
     * exploreStyle 解码: 低 3 bit = 列数 (EXPLORE_STYLE_COLS_MASK=0x07), 第 5 bit (0x10) = 视频标志位;
     * 列数变更需保留视频标志位, 仅替换低 3 bit。shared 内部更新 DB, holder 同步刷新 exploreStyle state。
     */
    fun setColumnCount(cols: Int) {
        shared.setColumnCount(cols)
        exploreStyle = shared.exploreStyle
    }

    /**
     * 关闭列数选择对话框 (由 ExploreShowContent 末尾 NumberPickerDialog 的 onConfirm/onDismiss 调用)。
     *
     * showColumnPickerDialog 字段为 private set, 外部无法直接赋值, 故提供此 dismiss 方法。
     */
    fun dismissColumnPickerDialog() {
        showColumnPickerDialog = false
    }

    override fun onShowSourceFilterRule() {
        // 对照 app 端 showSourceFilterRule: 弹当前 scope 命中规则列表 Dialog;
        // ExploreShowContent 末尾读取 showSourceFilterRuleDialog 渲染 DesktopSourceFilterRuleListDialog,
        // 用户编辑/新增/删除/启用切换后由 Dialog 内部直接落库 + 刷新列表
        showSourceFilterRuleDialog = true
    }

    /**
     * 关闭源过滤规则对话框 (由 ExploreShowContent 末尾 DesktopSourceFilterRuleListDialog 的 onDismiss 调用)。
     *
     * showSourceFilterRuleDialog 字段为 private set, 外部无法直接赋值, 故提供此 dismiss 方法。
     */
    fun dismissSourceFilterRuleDialog() {
        showSourceFilterRuleDialog = false
    }

    override fun onFooterClick() {
        // 对照 onFooterClickImpl: 错误时弹详情+重试, 否则触发加载下一页
        if (errorMsg.isNotBlank()) {
            // 弹 AlertDialog 选择重试/关闭 (替换原 JOptionPane.showOptionDialog 同步阻塞;
            // ExploreShowContent 末尾 AlertDialog 渲染分支读取 footerErrorDialog, errorMsg 已是 state 字段;
            // 用户点重试 (choice==0) 调 retryFooterLoad(), 用户点关闭/外部 dismiss 啥都不做)
            footerErrorDialog = true
            return
        }
        if (!footerLoading) {
            hasMoreLoad()
            shared.explore()
        }
    }

    /**
     * 由 AlertDialog 重试按钮调用 (替换原 JOptionPane.showOptionDialog choice==0 分支)。
     *
     * 复刻原 `if (choice == 0 && !footerLoading) { hasMoreLoad(); explore() }` 语义:
     * 重试前再次检查 footerLoading (用户可能在对话框显示期间触发了 onScrollToBottom)。
     */
    fun retryFooterLoad() {
        if (!footerLoading) {
            hasMoreLoad()
            shared.explore()
        }
    }

    override fun onScrollToBottom() {
        // 对照 scrollToBottom: footerHasMore && !footerLoading 时加载下一页
        if (footerHasMore && !footerLoading) {
            hasMoreLoad()
            shared.explore()
        }
    }

    override fun onBookClick(book: SearchBook, longClick: Boolean) {
        // 对照 onBookClickImpl: 不在书架则补 notShelf type, 调注入的 onBookClick
        if (!isInBookshelf(book)) {
            book.addType(BookType.notShelf)
        }
        onBookClick.invoke(book)
    }

    override fun isInBookshelf(book: SearchBook): Boolean {
        // 转发到 shared.isInBookShelf (内部按 bookshelf keys 集合判断)
        return shared.isInBookShelf(book)
    }

    override fun onExploreOptionChanged() {
        // 对照 onExploreOptionChangedImpl: 清 books + 复位 hasMore/error + explore(resetPage=true)
        books = emptyList()
        hasMoreLoad()
        shared.explore(resetPage = true)
    }
}
