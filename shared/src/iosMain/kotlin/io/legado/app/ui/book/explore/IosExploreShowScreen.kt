package io.legado.app.ui.book.explore

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import io.legado.app.help.book.addType
import io.legado.app.model.webBook.ExploreOption
import io.legado.app.ui.book.explore.ExploreShowScreen as SharedExploreShowScreen
import io.legado.app.ui.bookshelf.IosInfoCover
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.RadioChip
import io.legado.app.ui.compose.component.StrokeTextChip
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.dialog.NumberPickerDialog
import io.legado.app.utils.FlowBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * iOS 端发现结果页 Screen 入口 (包装 shared/sharedUiMain 的 [SharedExploreShowScreen])。
 *
 * # 职责
 *
 * 对照 desktop `ExploreShowScreen.kt` 包装模式, 仅做 iOS 平台适配, 业务展示与交互逻辑
 * 全部下沉到 shared/sharedUiMain 的 [SharedExploreShowScreen]:
 *
 * - **VM**: [ExploreShowViewModelShared] (commonMain, scope) 处理 initData/explore/toggleFavorite/
 *   switchLayout/setColumnCount/isInBookShelf, 内部已订阅 bookDao.flowAll 维护 bookshelf
 * - **状态**: [IosExploreShowStateHolder] 实现 [ExploreShowUiActions], 持有 books/footer/error
 *   等 mutableStateOf 字段, 收集 shared 的 StateFlow 桥接到 Compose state
 * - **封面槽**: [IosInfoCover] (SearchBook.toBook() 转 Book, UIImage + Skia ImageBitmap)
 * - **视频卡**: 占位 [Box] (L3 ItemExploreVideoBinding 未下沉)
 * - **参数 chip 行**: 内联 [IosExploreOptionsRow] (Compose chip, 替代 L3 LinearLayout)
 * - **列数选择**: 复用 shared [NumberPickerDialog] (Arco Design 规范)
 *
 * # 简化项 (iOS 端 KP5 阶段)
 *
 * - 源过滤规则对话框: 暂用 AlertDialog 提示 "未实现" (SourceFilterEditDialog 下沉但列表 Dialog 未接入)
 * - 视频卡: 占位 Box (Android 专属 ViewBinding 未下沉)
 *
 * @param source 发现目标书源 (由 IosNavHost 注入)
 * @param title 发现分类名
 * @param exploreUrl 发现 URL (含参数 chip 声明, 可能为 null)
 * @param onBack 返回回调 (切回 EXPLORE 路由)
 * @param onBookClick 书籍点击回调 (携带 SearchBook 跳 BOOK_INFO 详情路由)
 */
@Composable
fun IosExploreShowScreen(
    source: BookSource,
    title: String,
    exploreUrl: String?,
    onBack: () -> Unit,
    onBookClick: (SearchBook) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val bottomReachedLabel = rememberString("bottom_reached")
    val loadingErrorClickDetailLabel = rememberString("loading_error_click_detail")
    val retryLabel = rememberString("retry")
    val closeLabel = rememberString("close")
    val loadingErrorLabel = rememberString("loading_error")
    val emptyLabel = rememberString("explore_show_empty")

    val shared = remember(scope) { ExploreShowViewModelShared(scope = scope) }
    val state = remember(source, title, exploreUrl, scope, onBack, onBookClick, shared) {
        IosExploreShowStateHolder(
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

    // 收藏变更事件 → 刷新 isFavorite
    LaunchedEffect(Unit) {
        FlowBus.with(EventBus.UP_EXPLORE_PINNED).collect { state.refreshFavorite() }
    }

    // 初始化加载
    LaunchedEffect(source, title, exploreUrl) { state.initData() }

    // 订阅 shared 的 StateFlow
    @OptIn(ExperimentalCoroutinesApi::class)
    LaunchedEffect(shared) { shared.booksFlow.collect { it?.let { state.onBooksUpdate(it) } } }
    LaunchedEffect(shared) { shared.errorFlow.collect { it?.let { state.onErrorUpdate(it) } } }
    LaunchedEffect(shared) { shared.upAdapterFlow.collect { it?.let { state.onUpAdapterUpdate() } } }
    LaunchedEffect(shared) { shared.upStarFlow.collect { it?.let { state.onUpStarUpdate(it) } } }
    LaunchedEffect(shared) { shared.optionsReadyFlow.collect { it?.let { state.onOptionsReadyUpdate() } } }
    LaunchedEffect(shared) { shared.sourceReadyFlow.collect { it?.let { state.onSourceReadyUpdate() } } }

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

    SharedExploreShowScreen(
        uiState,
        state,
        {
            // 参数 chip 行
            IosExploreOptionsRow(
                options = shared.exploreOptions,
                version = state.optionsVersion,
                onOptionChanged = state::onExploreOptionChanged,
            )
        },
        { book, inBookshelf, onClick, onLongClick ->
            // 视频卡占位 (L3 ItemExploreVideoBinding 未下沉)
            IosVideoItemPlaceholder(book, inBookshelf, onClick, onLongClick)
        },
        { book, _, _, modifier ->
            // 封面: 复用 IosInfoCover (SearchBook.toBook() 转 Book)
            IosInfoCover(book.toBook(), modifier)
        },
    )

    // 错误详情+重试对话框
    if (state.footerErrorDialog) {
        AppAlertDialog(
            onDismissRequest = { state.footerErrorDialog = false },
            title = loadingErrorLabel,
            message = state.errorMsg,
            okButton = AlertButton(retryLabel, dismissOnClick = false) {
                state.footerErrorDialog = false
                state.retryFooterLoad()
            },
            cancelButton = AlertButton(closeLabel, dismissOnClick = false) {
                state.footerErrorDialog = false
            },
        )
    }

    // 列数选择对话框
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

    // 源过滤规则对话框 (暂用提示, 后续接入完整 Dialog)
    if (state.showSourceFilterRuleDialog) {
        AppAlertDialog(
            onDismissRequest = { state.dismissSourceFilterRuleDialog() },
            title = rememberString("source_filter_rule"),
            message = rememberString("source_filter_rule_no_match"),
            okButton = AlertButton(closeLabel, dismissOnClick = false) {
                state.dismissSourceFilterRuleDialog()
            },
        )
    }
}

/**
 * 参数 chip 行 (iOS 端, 替代 L3 LinearLayout + setUpExploreOptions)。
 *
 * 对照 desktop `DesktopExploreOptionsRow`: 读 [version] 触发外部参数结构变化重组,
 * 单选 option 用 RadioChip, 多选 option 用 StrokeTextChip + 点击弹简化对话框。
 */
@Composable
private fun IosExploreOptionsRow(
    options: List<ExploreOption>,
    version: Int,
    onOptionChanged: () -> Unit,
) {
    if (options.isEmpty()) return
    var localVersion by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_EXPRESSION") version
    localVersion
    Column(Modifier.fillMaxWidth()) {
        options.forEach { option ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StrokeTextChip(
                    text = option.name,
                    textColor = AppTheme.colors.primaryText,
                ) {
                    if (option.resetToDefault()) onOptionChanged()
                }
                Spacer(Modifier.width(4.dp))
                if (!option.multiSelect) {
                    option.options.forEach { (label, value) ->
                        RadioChip(
                            text = label,
                            checked = option.selectedValue == value,
                        ) {
                            if (option.selectedValue == value) return@RadioChip
                            option.selectedValue = value
                            localVersion++; onOptionChanged()
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                } else {
                    option.options.forEach { (label, value) ->
                        if (value !in option.selectedValues) return@forEach
                        StrokeTextChip(
                            text = label,
                            textColor = AppTheme.colors.accent,
                        ) {
                            option.selectedValues.remove(value)
                            localVersion++; onOptionChanged()
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                }
            }
        }
    }
}

/**
 * 视频卡 iOS 端占位 (L3 ItemExploreVideoBinding 未下沉)。
 *
 * 简化 Row 布局 (封面占位 + 书名 + 书架绿点), 视觉接近 list tier, 仅作临时占位。
 */
@Composable
private fun IosVideoItemPlaceholder(
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
                .clip(DesignTokens.shapeSm)
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
            color = AppTheme.colors.primaryText,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp).weight(1f),
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
 * iOS 端发现结果页状态宿主 (实现 [ExploreShowUiActions] 供 shared [SharedExploreShowScreen] 回调)。
 *
 * 对照 desktop `ExploreShowStateHolder`, 差异: 无 Lifecycle 依赖, flow 直接 collect;
 * 用 Compose mutableStateOf 驱动重组; 路由跳转改为回调注入。
 */
@Stable
private class IosExploreShowStateHolder(
    val source: BookSource,
    val title: String,
    val exploreUrl: String?,
    private val scope: CoroutineScope,
    private val shared: ExploreShowViewModelShared,
    private val onBack: () -> Unit,
    private val onBookClick: (SearchBook) -> Unit,
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
    var errorMsg by mutableStateOf("")
        private set

    var footerErrorDialog by mutableStateOf(false)
        internal set
    var showColumnPickerDialog by mutableStateOf(false)
        private set
    var showSourceFilterRuleDialog by mutableStateOf(false)
        private set

    private var footerHasMore = true

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
        if (!shared.hasNextPage) noMore(bottomReachedLabel)
    }

    fun onErrorUpdate(msg: String) { upError(msg) }
    fun onUpAdapterUpdate() { bookshelfVersion++ }
    fun onUpStarUpdate(fav: Boolean) { isFavorite = fav }
    fun onOptionsReadyUpdate() { optionsVersion++ }
    fun onSourceReadyUpdate() {
        exploreStyle = shared.exploreStyle
        isFavorite = shared.isFavorite()
    }
    fun refreshFavorite() { isFavorite = shared.isFavorite() }

    fun initData() {
        hasMoreLoad()
        shared.initData(source, title, exploreUrl)
    }

    private fun startLoad() { footerLoading = true }
    private fun stopLoad() { footerLoading = false }
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

    override fun onBack() = onBack.invoke()
    override fun onTitleClick() { scrollTopEpoch++ }
    override fun onToggleFavorite() { shared.toggleFavorite() }
    override fun onSwitchLayout() {
        shared.switchLayout()
        exploreStyle = shared.exploreStyle
    }
    override fun onShowColumnPicker() { showColumnPickerDialog = true }
    fun setColumnCount(cols: Int) {
        shared.setColumnCount(cols)
        exploreStyle = shared.exploreStyle
    }
    fun dismissColumnPickerDialog() { showColumnPickerDialog = false }
    override fun onShowSourceFilterRule() { showSourceFilterRuleDialog = true }
    fun dismissSourceFilterRuleDialog() { showSourceFilterRuleDialog = false }
    override fun onFooterClick() {
        if (errorMsg.isNotBlank()) {
            footerErrorDialog = true
            return
        }
        if (!footerLoading) {
            hasMoreLoad()
            shared.explore()
        }
    }
    fun retryFooterLoad() {
        if (!footerLoading) {
            hasMoreLoad()
            shared.explore()
        }
    }
    override fun onScrollToBottom() {
        if (footerHasMore && !footerLoading) {
            hasMoreLoad()
            shared.explore()
        }
    }
    override fun onBookClick(book: SearchBook, longClick: Boolean) {
        if (!isInBookshelf(book)) book.addType(BookType.notShelf)
        onBookClick.invoke(book)
    }
    override fun isInBookshelf(book: SearchBook): Boolean = shared.isInBookShelf(book)
    override fun onExploreOptionChanged() {
        books = emptyList()
        hasMoreLoad()
        shared.explore(resetPage = true)
    }
}
