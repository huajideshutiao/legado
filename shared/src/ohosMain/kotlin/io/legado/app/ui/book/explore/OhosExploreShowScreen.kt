package io.legado.app.ui.book.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.Text
import androidx.compose.material.TextButton
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
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.book.addType
import io.legado.app.help.source.exploreKinds
import io.legado.app.model.webBook.ExploreOption
import io.legado.app.ui.book.explore.ExploreShowScreen as SharedExploreShowScreen
import io.legado.app.ui.explore.ExploreShowViewModelShared
import io.legado.app.ui.compose.component.RadioChip
import io.legado.app.ui.compose.component.StrokeTextChip
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.dialog.NumberPickerDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 鸿蒙端发现结果页 Screen 入口 (包装 shared/sharedUiMain 的 [SharedExploreShowScreen])。
 *
 * 对照 iOS 端 [io.legado.app.ui.book.explore.IosExploreShowScreen] / desktop `ExploreShowScreen.kt`
 * 的包装模式, 鸿蒙端在 OhosNavHost 的 EXPLORE_SHOW 路由分支调用本入口。
 *
 * # 职责
 *
 * 仅做鸿蒙平台适配, 业务展示与交互逻辑全部下沉到 shared/sharedUiMain:
 * - **VM**: [ExploreShowViewModelShared] (commonMain, scope) 处理 initData/explore/toggleFavorite/
 *   switchLayout/setColumnCount/isInBookShelf, 内部已订阅 bookDao.flowAll 维护 bookshelf
 * - **状态**: [OhosExploreShowStateHolder] 实现 [ExploreShowUiActions], 持有 books/footer/error
 *   等 mutableStateOf 字段, 收集 shared 的 StateFlow 桥接到 Compose state
 * - **封面槽**: 占位 Box (KP-ohos: 后续接入 Coil3 KMP 图片加载, 对照 OhosInfoCover)
 * - **视频卡**: 占位 Row (L3 ItemExploreVideoBinding 未下沉)
 * - **参数 chip 行**: 内联 [OhosExploreOptionsRow] (Compose chip, 替代 L3 LinearLayout)
 * - **列数选择**: 复用 shared [NumberPickerDialog]
 *
 * # 与 iOS 差异
 *
 * - 入参用 sourceUrl (String) 而非 BookSource 对象: OhosExploreTab 仅传 sourceUrl,
 *   本入口内部 LaunchedEffect 加载 BookSource + 首个发现分类后初始化 VM
 * - 不订阅 FlowBus (UP_EXPLORE_PINNED): 鸿蒙端无收藏变更事件总线, 收藏状态由
 *   upStarFlow 驱动刷新 (toggleFavorite 后 shared 推送)
 *
 * @param sourceUrl 发现目标书源 URL (由 OhosExploreTab 传入)
 * @param onBack 返回回调 (切回 EXPLORE 路由, 由 OhosNavHost 注入)
 * @param onBookClick 书籍点击回调 (携带 BaseBook 跳 BOOK_INFO 详情路由)
 */
@Composable
fun OhosExploreShowScreen(
    sourceUrl: String,
    onBack: () -> Unit,
    onBookClick: (BaseBook) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val discoveryLabel = rememberString("discovery")
    val bottomReachedLabel = rememberString("bottom_reached")
    val loadingErrorClickDetailLabel = rememberString("loading_error_click_detail")
    val retryLabel = rememberString("retry")
    val closeLabel = rememberString("close")
    val loadingErrorLabel = rememberString("loading_error")
    val emptyLabel = rememberString("explore_show_empty")

    val shared = remember(scope) { ExploreShowViewModelShared(scope = scope) }
    val state = remember(sourceUrl, scope, onBack, onBookClick, shared) {
        OhosExploreShowStateHolder(
            sourceUrl = sourceUrl,
            scope = scope,
            shared = shared,
            onBack = onBack,
            onBookClick = onBookClick,
            discoveryLabel = discoveryLabel,
            bottomReachedLabel = bottomReachedLabel,
            loadingErrorClickDetailLabel = loadingErrorClickDetailLabel,
            emptyLabel = emptyLabel,
        )
    }

    // 初始化: 加载书源 + 首个发现分类后调 shared.initData
    LaunchedEffect(sourceUrl) { state.initData() }

    // 订阅 shared 的 StateFlow
    @OptIn(ExperimentalCoroutinesApi::class)
    LaunchedEffect(shared) { shared.booksFlow.collect { it?.let { state.onBooksUpdate(it) } } }
    LaunchedEffect(shared) { shared.errorFlow.collect { it?.let { state.onErrorUpdate(it) } } }
    LaunchedEffect(shared) { shared.upAdapterFlow.collect { it?.let { state.onUpAdapterUpdate() } } }
    LaunchedEffect(shared) { shared.upStarFlow.collect { it?.let { state.onUpStarUpdate(it) } } }
    LaunchedEffect(shared) { shared.optionsReadyFlow.collect { it?.let { state.onOptionsReadyUpdate() } } }
    LaunchedEffect(shared) { shared.sourceReadyFlow.collect { it?.let { state.onSourceReadyUpdate() } } }

    val uiState = ExploreShowUiState(
        title = state.title,
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
            OhosExploreOptionsRow(
                options = shared.exploreOptions,
                version = state.optionsVersion,
                onOptionChanged = state::onExploreOptionChanged,
            )
        },
        { book, inBookshelf, onClick, onLongClick ->
            // 视频卡占位 (L3 ItemExploreVideoBinding 未下沉)
            OhosVideoItemPlaceholder(book, inBookshelf, onClick, onLongClick)
        },
        { book, _, _, modifier ->
            // 封面占位 (KP-ohos: 后续接入 Coil3 KMP 图片加载)
            OhosExploreCover(book.toBook(), modifier)
        },
    )

    // 错误详情+重试对话框
    if (state.footerErrorDialog) {
        AlertDialog(
            onDismissRequest = { state.footerErrorDialog = false },
            title = { Text(loadingErrorLabel) },
            text = { Text(state.errorMsg) },
            confirmButton = {
                TextButton(onClick = {
                    state.footerErrorDialog = false
                    state.retryFooterLoad()
                }) { Text(retryLabel) }
            },
            dismissButton = {
                TextButton(onClick = { state.footerErrorDialog = false }) { Text(closeLabel) }
            },
        )
    }

    // 列数选择对话框
    if (state.showColumnPickerDialog) {
        val currentSource = shared.bookSource
        if (currentSource != null) {
            NumberPickerDialog(
                title = rememberString("explore_cols"),
                value = BookSource.exploreStyleCols(currentSource.exploreStyle),
                range = 0..6,
                onConfirm = { cols ->
                    state.setColumnCount(cols)
                    state.dismissColumnPickerDialog()
                },
                onDismiss = { state.dismissColumnPickerDialog() },
            )
        }
    }

    // 源过滤规则对话框 (暂用提示, 后续接入完整 Dialog)
    if (state.showSourceFilterRuleDialog) {
        AlertDialog(
            onDismissRequest = { state.dismissSourceFilterRuleDialog() },
            title = { Text(rememberString("source_filter_rule")) },
            text = { Text(rememberString("source_filter_rule_no_match")) },
            confirmButton = {
                TextButton(onClick = { state.dismissSourceFilterRuleDialog() }) {
                    Text(closeLabel)
                }
            },
        )
    }
}

/**
 * 参数 chip 行 (鸿蒙端, 替代 L3 LinearLayout + setUpExploreOptions)。
 *
 * 对照 iOS [IosExploreOptionsRow] / desktop `DesktopExploreOptionsRow`:
 * 读 [version] 触发外部参数结构变化重组, 单选 option 用 RadioChip,
 * 多选 option 用 StrokeTextChip + 点击移除。
 */
@Composable
private fun OhosExploreOptionsRow(
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
 * 视频卡鸿蒙端占位 (L3 ItemExploreVideoBinding 未下沉)。
 *
 * 对照 iOS [IosVideoItemPlaceholder], 简化 Row 布局 (封面占位 + 书名 + 书架绿点)。
 */
@Composable
private fun OhosVideoItemPlaceholder(
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
 * 鸿蒙端发现页封面占位 (KP-ohos: 后续接入 Coil3 KMP 图片加载)。
 *
 * 对照 [OhosInfoCover] / iOS [IosInfoCover], 当前仅占位让 shared ExploreShowScreen 的
 * coverSlot 编译通过。
 */
@Composable
private fun OhosExploreCover(book: io.legado.app.data.entities.Book?, modifier: Modifier = Modifier) {
    // KP-ohos: stub, 后续接入 Coil3 KMP 图片加载
    Box(modifier)
}

/**
 * 鸿蒙端发现结果页状态宿主 (实现 [ExploreShowUiActions] 供 shared [SharedExploreShowScreen] 回调)。
 *
 * 对照 iOS [IosExploreShowStateHolder] / desktop `ExploreShowStateHolder`, 差异:
 * - 入参用 sourceUrl (String) 而非 BookSource 对象, [initData] 内部加载 BookSource +
 *   首个发现分类后调 shared.initData
 * - 无 Lifecycle 依赖, flow 直接 collect
 * - 用 Compose mutableStateOf 驱动重组
 * - 路由跳转改为回调注入
 */
@Stable
private class OhosExploreShowStateHolder(
    val sourceUrl: String,
    private val scope: CoroutineScope,
    private val shared: ExploreShowViewModelShared,
    private val onBack: () -> Unit,
    private val onBookClick: (BaseBook) -> Unit,
    private val discoveryLabel: String,
    private val bottomReachedLabel: String,
    private val loadingErrorClickDetailLabel: String,
    private val emptyLabel: String,
) : ExploreShowUiActions {

    var title by mutableStateOf(discoveryLabel)
        private set
    var books by mutableStateOf<List<SearchBook>>(emptyList())
        private set
    var exploreStyle by mutableStateOf(0)
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

    /**
     * 初始化: 加载 BookSource + 首个发现分类后调 shared.initData。
     *
     * 对照 iOS IosExploreShowStateHolder.initData (直接传 source/title/exploreUrl),
     * 鸿蒙端仅传 sourceUrl, 内部异步加载 BookSource + exploreKinds 后取首个分类。
     */
    fun initData() {
        hasMoreLoad()
        scope.launch {
            val source = AppDbProviders.get().bookSourceDao.getBookSource(sourceUrl)
            if (source == null) {
                noMore(emptyLabel)
                return@launch
            }
            // 取首个发现分类作为默认 (对照 app 端 ExploreShowActivity 入口默认行为)
            val kinds = runCatching { source.exploreKinds() }.getOrDefault(emptyList())
                .filter { !it.url.isNullOrBlank() }
            val firstKind = kinds.firstOrNull()
            title = firstKind?.title ?: source.bookSourceName.ifBlank { discoveryLabel }
            shared.initData(source, title, firstKind?.url)
        }
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
