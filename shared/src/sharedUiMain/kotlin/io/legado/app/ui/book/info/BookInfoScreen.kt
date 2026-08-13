package io.legado.app.ui.book.info

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.TextNode
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.help.book.isVideo
import io.legado.app.help.book.isWebFile
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppMenuCheckbox
import io.legado.app.ui.compose.component.PullToRefreshDefaults
import io.legado.app.ui.compose.component.pullToRefresh
import io.legado.app.ui.compose.component.rememberPullToRefreshState
import io.legado.app.ui.compose.platform.rememberColor
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.platform.transitionStatusBarPadding
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.preview.LegadoThemePreview
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.splitNotBlank
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.allow_update
import legado.shared.generated.resources.author
import legado.shared.generated.resources.book_info
import legado.shared.generated.resources.book_intro
import legado.shared.generated.resources.book_name
import legado.shared.generated.resources.clear_cache
import legado.shared.generated.resources.copy_book_url
import legado.shared.generated.resources.copy_toc_url
import legado.shared.generated.resources.download_to_local
import legado.shared.generated.resources.edit
import legado.shared.generated.resources.ic_arrow_back
import legado.shared.generated.resources.ic_book_last
import legado.shared.generated.resources.ic_edit
import legado.shared.generated.resources.ic_more_vert
import legado.shared.generated.resources.ic_share
import legado.shared.generated.resources.loading
import legado.shared.generated.resources.log
import legado.shared.generated.resources.login
import legado.shared.generated.resources.more_menu
import legado.shared.generated.resources.other
import legado.shared.generated.resources.read_dur_progress
import legado.shared.generated.resources.reading
import legado.shared.generated.resources.review
import legado.shared.generated.resources.set_book_variable
import legado.shared.generated.resources.set_source_variable
import legado.shared.generated.resources.share
import legado.shared.generated.resources.split_long_chapter
import legado.shared.generated.resources.to_top
import legado.shared.generated.resources.upload_to_remote
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/*
 * 下沉所需资源 key 清单 (供 ResourceProvider 各平台 actual 补全)
 *
 * Painter key (drawable):
 *   - ic_arrow_back     (返回箭头)
 *   - ic_edit           (编辑)
 *   - ic_share          (分享)
 *   - ic_more_vert      (更多菜单, 已存在)
 *   - ic_book_last      (最新章节图标, 已存在)
 *   - ic_web_outline    (来源图标)
 *   - ic_groups         (分组图标)
 *   - ic_folder_open    (目录图标)
 *   - ic_author         (作者图标, 已存在)
 *
 * String key (string):
 *   - book_info / edit / share / more_menu (已存在) / bg_image
 *   - upload_to_remote / download_to_local / to_top / login / review
 *   - set_source_variable / set_book_variable / copy_book_url / copy_toc_url
 *   - allow_update / split_long_chapter (已存在) / clear_cache / log (已存在)
 *   - book_name / author / loading / read_dur_progress / other / book_intro
 *   - remove_from_bookshelf / add_to_bookshelf / reading
 *
 * Color key (color):
 *   - primaryText (已存在) / secondaryText (已存在) / tv_text_summary (已存在)
 *   - btn_bg (已存在) / arco_fill_3 / bottomBackground
 *   (注: bottomBackground 已通过 AppTheme.colors.bottomBackground 暴露, 不需要 rememberColor)
 *
 * L3 不可下沉项 (保留 app 端, 通过 slot 注入):
 *   - BlurCoverBg: Glide + BookInfoBgTransformation + AndroidView(AppCompatImageView)
 *     → blurCoverBgSlot: @Composable (Modifier) -> Unit
 *   - InfoCover 中的 ShelfCover: AndroidView + CoverImageView + Glide
 *     → coverSlot: @Composable (Book?, Modifier) -> Unit
 *   - IntroImage: Glide + asBitmap + CustomTarget<Bitmap>
 *     → introImageSlot: @Composable (src: String, onClick: () -> Unit) -> Unit
 */

/**
 * 书籍详情页展示状态。
 *
 * app 端 [BookInfoActivity] 在 `Content()` 内将自己的 `var` 状态字段打包为本数据类传入;
 * 桌面端/iOS 端可同样构造本类复用 [BookInfoScreen]。
 *
 * 字段语义对照原 `BookInfoActivity` 同名字段:
 * - [book] / [bookTick] / [coverTick] / [inBookshelf] / [groupName] /
 *   [tocText] / [lastedTitle] / [wordCountText]: 与 Activity 同名字段一一对应
 * - [isLandscape] / [useDevFeat] / [isDarkTheme]: 由 Activity 计算后传入
 *   (useDevFeat = bookInfoHorizontalLayout && !isVideo && !isLandscape)
 * - [menuState]: 溢出菜单显隐控制状态 (对照原 onMenuOpened 内联求值)
 */
data class BookInfoUiState(
    val book: Book?,
    val bookTick: Int,
    val coverTick: Int,
    val inBookshelf: Boolean,
    val groupName: String,
    val tocText: String?,
    val lastedTitle: String,
    val wordCountText: String?,
    val isLandscape: Boolean,
    val useDevFeat: Boolean,
    val isDarkTheme: Boolean,
    val menuState: BookInfoMenuState,
)

/** 溢出菜单项显隐控制 (对照原 onMenuOpened 内联求值的可见性条件) */
data class BookInfoMenuState(
    val isLocal: Boolean,              // book?.origin == BookType.localTag
    val isWebDav: Boolean,             // book?.origin?.startsWith(BookType.webDavTag)
    val hasSource: Boolean,            // vm.curBookSource != null
    val sourceHasLogin: Boolean,       // vm.curBookSource?.hasLogin() == true
    val sourceHasReviewRule: Boolean,  // vm.curBookSource?.reviewRule?.reviewUrl 非空
    val canUpdate: Boolean,            // book?.canUpdate ?: true
    val isLocalTxt: Boolean,           // book?.isLocalTxt == true
    val splitLongChapter: Boolean,     // book?.config.splitLongChapter
    val bookUrl: String?,              // book?.bookUrl (用于复制)
    val tocUrl: String?,               // book?.tocUrl  (用于复制)
)

/**
 * 书籍详情页用户交互回调。
 *
 * app 端 [BookInfoActivity] 实现本接口 (已有同名方法直接 `override`);
 * 桌面端/iOS 端可自行实现本接口接入各自平台行为。
 *
 * 设计:返回式回调,无 Android Context 依赖。所有需要 Android 专属 API 的动作
 * (如 [onShowLog] 调 showDialogFragment、[onCopyBookUrl] 调 sendToClip)
 * 由 app 端实现内部桥接到 Activity。
 */
interface BookInfoUiActions {
    fun onBack()
    fun onEdit()
    fun onShare()
    fun onRefresh()
    fun onUploadBook()
    fun onDownloadToLocal()
    fun onTopBook()
    fun onLogin()
    fun onOpenCommentDialog()
    fun onSetSourceVariable()
    fun onSetBookVariable()
    fun onCopyBookUrl()
    fun onCopyTocUrl()
    fun onToggleCanUpdate()
    fun onToggleSplitLongChapter()
    fun onClearCache()
    fun onShowLog()
    fun onNameClick()
    fun onCoverClick()
    fun onCoverLongClick()
    fun onOriginClick()
    fun onOriginLongClick()
    fun onTocClick()
    fun onGroupClick()
    fun onShelfClick()
    fun onReadClick()
    /** 作者搜索 (submit=false 表示长按探索) */
    fun onSearchAuthor(author: String, submit: Boolean)
    /** 分类标签搜索 (submit=false 表示长按探索) */
    fun onSearchKind(kind: String, submit: Boolean)
    /** 简介内 <button onclick="..."> 动作派发 */
    fun onDispatchIntroAction(action: String)
    /** 简介内 <img src="..."> 点击查看大图 */
    fun onShowPhoto(src: String)
}

/**
 * [BookInfoUiActions] 的 CompositionLocal, 供深层 Composable (NameText/InfoCover)
 * 直接取用, 避免层层透传 actions 参数。
 *
 * 由 [BookInfoScreen] 顶层 provides, 子树所有 Composable 可通过
 * [LocalBookInfoActions.current] 取到 actions 实例。
 */
val LocalBookInfoActions = compositionLocalOf<BookInfoUiActions> {
    error("BookInfoUiActions not provided, wrap content in BookInfoScreen")
}

/**
 * 书籍详情页(对照 activity_book_info.xml / layout-land 变体)：
 * 竖屏=模糊封面背景+纵向头部(bookInfoHorizontalLayout 时横向)，横屏=左右分栏。
 *
 * 下沉自 app 端原 `BookInfoScreen(activity: BookInfoActivity)`，将 `BookInfoActivity`
 * 直接依赖拆为 [state] (展示状态) + [actions] (交互回调) + 三个 slot (Android 专属
 * Glide/AndroidView 注入)，去除 Android `Context` / `AppConfig` / `activity` 依赖。
 *
 * 视觉/布局/动画/手势/状态管理完全与 app 端原版一致 (宽高/边距/颜色/层级)。
 *
 * @param blurCoverBgSlot 模糊封面背景 (Glide + BookInfoBgTransformation + AndroidView, L3)
 *   - 调用方传入 modifier 已经包含尺寸约束 (fillMaxSize / fillMaxWidth+height(300.dp))
 *   - slot 内部自行从 state 读 coverTick/book/isEInkMode 等判断是否加载
 * @param coverSlot 书籍封面 (AndroidView + CoverImageView + Glide, L3)
 *   - 调用方传入的 modifier 已包含 height(144.dp)/clip/background/clickable
 *   - slot 内部自行从 state 读 coverTick/inBookshelf 等
 * @param introImageSlot 简介内整宽图 (Glide + CustomTarget<Bitmap>, L3)
 *   - 调用方只传 src 和点击回调, slot 内部自行处理加载与渲染
 */
@Composable
fun BookInfoScreen(
    state: BookInfoUiState,
    actions: BookInfoUiActions,
    blurCoverBgSlot: @Composable (Modifier, Boolean) -> Unit,
    coverSlot: @Composable (Book?, Modifier) -> Unit,
    introImageSlot: @Composable (String, () -> Unit) -> Unit,
) {
    state.bookTick // 读 tick: book 原地可变对象, post 时递增驱动重组
    val titleColor = if (state.useDevFeat) AppTheme.colors.primaryText else Color.White
    CompositionLocalProvider(LocalBookInfoActions provides actions) {
        if (state.isLandscape) {
            LandscapeLayout(
                state, actions, titleColor,
                blurCoverBgSlot, coverSlot, introImageSlot,
            )
        } else {
            PortraitLayout(
                state, actions, titleColor,
                blurCoverBgSlot, coverSlot, introImageSlot,
            )
        }
    }
}

@Composable
private fun PortraitLayout(
    state: BookInfoUiState,
    actions: BookInfoUiActions,
    titleColor: Color,
    blurCoverBgSlot: @Composable (Modifier, Boolean) -> Unit,
    coverSlot: @Composable (Book?, Modifier) -> Unit,
    introImageSlot: @Composable (String, () -> Unit) -> Unit,
) {
    val pullState = rememberPullToRefreshState()
    val isRefreshing = state.tocText == null
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .pullToRefresh(
                    isRefreshing = isRefreshing,
                    state = pullState,
                    onRefresh = { actions.onRefresh() },
                ),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                Box(Modifier.fillMaxWidth()) {
                    if (!state.useDevFeat) {
                        blurCoverBgSlot(
                            Modifier
                                .fillMaxWidth()
                                .height(300.dp),
                            false,
                        )
                    }
                    Column(Modifier.fillMaxWidth()) {
                        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                        Spacer(Modifier.height(56.dp)) // actionBarSize
                        if (state.useDevFeat) TopSectionHorizontal(state, coverSlot)
                        else TopSectionVertical(state, coverSlot, land = false)
                        ActionsRow(state, actions, Modifier.padding(horizontal = 8.dp))
                        KindsSection(state, actions, Modifier.padding(horizontal = 8.dp))
                        IntroSection(
                            state, actions, introImageSlot,
                            Modifier.padding(start = 16.dp, end = 8.dp, bottom = 8.dp),
                        )
                        // 底部按钮悬浮，滚动内容留出等高空间(原 scrollView bottom padding)
                        Spacer(Modifier.height(64.dp))
                        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                    }
                }
            }
            PullToRefreshDefaults.Indicator(
                state = pullState,
                isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                color = AppTheme.colors.accent,
            )
        }
        InfoTitleBar(state, actions, titleColor, Modifier.align(Alignment.TopCenter))
        BottomButtons(state, actions, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun LandscapeLayout(
    state: BookInfoUiState,
    actions: BookInfoUiActions,
    titleColor: Color,
    blurCoverBgSlot: @Composable (Modifier, Boolean) -> Unit,
    coverSlot: @Composable (Book?, Modifier) -> Unit,
    introImageSlot: @Composable (String, () -> Unit) -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        // 左半:模糊背景铺满 + 标题栏 + 居中头部
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            blurCoverBgSlot(Modifier.matchParentSize(), true)
            Column(Modifier.fillMaxSize()) {
                InfoTitleBar(state, actions, titleColor, Modifier)
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                ) {
                    TopSectionVertical(state, coverSlot, land = true)
                }
            }
        }
        // 右半:下拉刷新 + 动作行/分类/简介 + 底部按钮
        val pullState = rememberPullToRefreshState()
        val isRefreshing = state.tocText == null
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pullToRefresh(
                        isRefreshing = isRefreshing,
                        state = pullState,
                        onRefresh = { actions.onRefresh() },
                    ),
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp),
                ) {
                    Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                    ActionsRow(state, actions, Modifier)
                    KindsSection(state, actions, Modifier)
                    IntroSection(state, actions, introImageSlot, Modifier.padding(start = 8.dp, bottom = 8.dp))
                    Spacer(Modifier.height(64.dp))
                    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = isRefreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                    color = AppTheme.colors.accent,
                )
            }
            BottomButtons(state, actions, Modifier.align(Alignment.BottomCenter))
        }
    }
}

// ---- 标题栏(透明背景悬浮，menu 对照 R.menu.book_info) ----

@Composable
private fun InfoTitleBar(
    state: BookInfoUiState,
    actions: BookInfoUiActions,
    contentColor: Color,
    modifier: Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .transitionStatusBarPadding()
            .height(DesignTokens.viewHeightMax),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { actions.onBack() }) {
            Icon(painterResource(Res.drawable.ic_arrow_back), null, tint = contentColor)
        }
        Text(
            text = stringResource(Res.string.book_info),
            color = contentColor,
            fontSize = 20.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (state.inBookshelf) {
            IconButton(onClick = { actions.onEdit() }) {
                Icon(
                    painterResource(Res.drawable.ic_edit),
                    stringResource(Res.string.edit),
                    tint = contentColor,
                )
            }
        }
        IconButton(onClick = { actions.onShare() }) {
            Icon(
                painterResource(Res.drawable.ic_share),
                stringResource(Res.string.share),
                tint = contentColor,
            )
        }
        InfoOverflowMenu(state, actions, contentColor)
    }
}

@Composable
private fun InfoOverflowMenu(
    state: BookInfoUiState,
    actions: BookInfoUiActions,
    tint: Color,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painterResource(Res.drawable.ic_more_vert),
                stringResource(Res.string.more_menu),
                tint = tint,
            )
        }
        AppDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // 展开时求值可见性,对照 onMenuOpened
            val menu = state.menuState
            val dismiss = { expanded = false }
            // 刷新: 等价下拉刷新 (onRefresh), 无下拉手势的端 (如桌面) 走此入口
            MenuItem(rememberString("refresh")) { dismiss(); actions.onRefresh() }
            if (menu.isLocal) {
                MenuItem(stringResource(Res.string.upload_to_remote)) {
                    dismiss(); actions.onUploadBook()
                }
            }
            if (menu.isWebDav) {
                MenuItem(stringResource(Res.string.download_to_local)) {
                    dismiss(); actions.onDownloadToLocal()
                }
            }
            MenuItem(stringResource(Res.string.to_top)) { dismiss(); actions.onTopBook() }
            if (menu.sourceHasLogin) {
                MenuItem(stringResource(Res.string.login)) { dismiss(); actions.onLogin() }
            }
            if (menu.sourceHasReviewRule) {
                MenuItem(stringResource(Res.string.review)) {
                    dismiss(); actions.onOpenCommentDialog()
                }
            }
            if (menu.hasSource) {
                MenuItem(stringResource(Res.string.set_source_variable)) {
                    dismiss(); actions.onSetSourceVariable()
                }
                MenuItem(stringResource(Res.string.set_book_variable)) {
                    dismiss(); actions.onSetBookVariable()
                }
            }
            MenuItem(stringResource(Res.string.copy_book_url)) {
                dismiss(); menu.bookUrl?.let { actions.onCopyBookUrl() }
            }
            MenuItem(stringResource(Res.string.copy_toc_url)) {
                dismiss(); menu.tocUrl?.let { actions.onCopyTocUrl() }
            }
            if (menu.hasSource) {
                CheckMenuItem(stringResource(Res.string.allow_update), menu.canUpdate) {
                    dismiss(); actions.onToggleCanUpdate()
                }
            }
            if (menu.isLocalTxt) {
                CheckMenuItem(
                    stringResource(Res.string.split_long_chapter),
                    menu.splitLongChapter,
                ) {
                    dismiss(); actions.onToggleSplitLongChapter()
                }
            }
            MenuItem(stringResource(Res.string.clear_cache)) { dismiss(); actions.onClearCache() }
            MenuItem(stringResource(Res.string.log)) {
                dismiss(); actions.onShowLog()
            }
        }
    }
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(
        onClick = onClick,
    ) {
        Text(text, color = AppTheme.colors.primaryText)
    }
}

@Composable
private fun CheckMenuItem(text: String, checked: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    DropdownMenuItem(
        onClick = onClick,
    ) {
        Text(text, color = colors.primaryText)
        Spacer(Modifier.width(12.dp))
        AppMenuCheckbox(checked = checked)
    }
}

// ---- 头部(封面+书名/字数/最新章) ----

@Composable
private fun TopSectionVertical(
    state: BookInfoUiState,
    coverSlot: @Composable (Book?, Modifier) -> Unit,
    land: Boolean,
) {
    Column(Modifier.fillMaxWidth()) {
        InfoCover(
            state, coverSlot, cardBg = !land,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(8.dp),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = if (land) 16.dp else 8.dp)
                .padding(bottom = if (land) 8.dp else 0.dp),
        ) {
            NameText(state, TextAlign.Center, if (land) 3 else Int.MAX_VALUE)
            WordCountText(state, TextAlign.Center)
            LastedRow(
                state,
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = if (land) 3.dp else 4.dp),
            )
        }
    }
}

@Composable
private fun TopSectionHorizontal(
    state: BookInfoUiState,
    coverSlot: @Composable (Book?, Modifier) -> Unit,
) {
    Row(Modifier.fillMaxWidth()) {
        InfoCover(state, coverSlot, cardBg = true, modifier = Modifier.padding(8.dp))
        Column(
            Modifier
                .weight(1f)
                .padding(end = 8.dp),
        ) {
            NameText(state, TextAlign.Start, Int.MAX_VALUE)
            WordCountText(state, TextAlign.Start)
            LastedRow(state, Modifier.padding(vertical = 4.dp))
        }
    }
}

@Composable
private fun NameText(state: BookInfoUiState, align: TextAlign, maxLines: Int) {
    val actions = LocalBookInfoActions.current
    Text(
        text = state.book?.name ?: stringResource(Res.string.book_name),
        color = AppTheme.colors.primaryText,
        fontSize = 20.sp,
        textAlign = align,
        maxLines = maxLines,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { actions.onNameClick() },
    )
}

@Composable
private fun WordCountText(state: BookInfoUiState, align: TextAlign) {
    val text = state.wordCountText ?: return
    Text(
        text = text,
        color = AppTheme.colors.secondaryText,
        fontSize = 14.sp,
        textAlign = align,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun LastedRow(state: BookInfoUiState, modifier: Modifier) {
    val iconSize = with(LocalDensity.current) { 18.sp.toDp() }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(Res.drawable.ic_book_last),
            stringResource(Res.string.read_dur_progress),
            tint = rememberColor("tv_text_summary"),
            modifier = Modifier
                .size(iconSize)
                .padding(end = 2.dp),
        )
        Text(
            text = state.lastedTitle,
            color = rememberColor("tv_text_summary"),
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun InfoCover(
    state: BookInfoUiState,
    coverSlot: @Composable (Book?, Modifier) -> Unit,
    cardBg: Boolean,
    modifier: Modifier,
) {
    val actions = LocalBookInfoActions.current
    val book = state.book
    // 对照 CoverImageView.onMeasure: 高固定 144dp, 宽按比例反推 (视频 16:9, 小说 3:4)
    val coverRatio = if (book?.isVideo == true) 16f / 9f else 3f / 4f
    coverSlot(
        book,
        modifier
            .height(144.dp)
            .aspectRatio(coverRatio, matchHeightConstraintsFirst = true)
            .clip(DesignTokens.shapeDefault)
            .then(
                if (cardBg) Modifier.background(AppTheme.colors.bottomBackground)
                else Modifier
            )
            .combinedClickable(
                onClick = { actions.onCoverClick() },
                onLongClick = { actions.onCoverLongClick() },
            )
    )
}

// ---- 动作行(作者/来源/分组/目录) ----

@Composable
private fun ActionsRow(
    state: BookInfoUiState,
    actions: BookInfoUiActions,
    modifier: Modifier,
) {
    val book = state.book
    Row(modifier.fillMaxWidth()) {
        AuthorActionCell(state, actions, Modifier.weight(1f))
        ActionCell(
            iconKey = "ic_web_outline",
            text = book?.originName.orEmpty(),
            modifier = Modifier.weight(1f),
            onClick = { actions.onOriginClick() },
            onLongClick = { actions.onOriginLongClick() },
        )
        ActionCell(
            iconKey = "ic_groups",
            text = state.groupName,
            modifier = Modifier.weight(1f),
            onClick = { actions.onGroupClick() },
        )
        if (book?.isWebFile != true) {
            ActionCell(
                iconKey = "ic_folder_open",
                text = state.tocText ?: stringResource(Res.string.loading),
                modifier = Modifier.weight(1f),
                onClick = { actions.onTocClick() },
            )
        }
    }
}

@Composable
private fun AuthorActionCell(
    state: BookInfoUiState,
    actions: BookInfoUiActions,
    modifier: Modifier,
) {
    var authorMenu by remember { mutableStateOf<List<String>>(emptyList()) }
    Box(modifier) {
        ActionCell(
            iconKey = "ic_author",
            text = state.book?.getRealAuthor() ?: stringResource(Res.string.author),
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val book = state.book
                val authors = book?.author?.splitNotBlank("\n") ?: return@ActionCell
                when {
                    authors.isEmpty() -> Unit
                    authors.size == 1 -> actions.onSearchAuthor(authors[0], true)
                    else -> authorMenu = authors.toList()
                }
            },
            onLongClick = {
                state.book?.getRealAuthor()?.let { actions.onSearchAuthor(it, false) }
            },
        )
        AppDropdownMenu(
            expanded = authorMenu.isNotEmpty(),
            onDismissRequest = { authorMenu = emptyList() },
        ) {
            authorMenu.forEach { author ->
                MenuItem(author.split("::")[0]) {
                    authorMenu = emptyList()
                    actions.onSearchAuthor(author, true)
                }
            }
        }
    }
}

@Composable
private fun ActionCell(
    iconKey: String,
    text: String,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    // 对齐原版 tv_text_summary #909090 固定淡灰 (不随主题); secondaryText 偏深
    val summary = AppTheme.colors.summaryText
    // 两行最小高度, 复刻原 XML lines=2 + gravity=center 的文本垂直居中
    val twoLineHeight = with(LocalDensity.current) { (14.sp * 1.5f * 2).toDp() }
    Column(
        modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(rememberPainter(iconKey), null, tint = summary, modifier = Modifier.size(24.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
                .heightIn(min = twoLineHeight),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                color = summary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---- 分类标签(对照 bindKinds: 组名前缀 + FlexboxLayout 换行) ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KindsSection(
    state: BookInfoUiState,
    actions: BookInfoUiActions,
    modifier: Modifier,
) {
    val otherLabel = stringResource(Res.string.other)
    val groups = remember(state.bookTick) {
        buildKindGroups(state.book?.kind, otherLabel)
    }
    if (groups.isEmpty()) return
    Column(modifier.padding(bottom = 8.dp)) {
        groups.forEach { (groupName, items) ->
            FlowRow(Modifier.fillMaxWidth()) {
                if (groups.size > 1 || groupName != otherLabel) {
                    KindChip(groupName, label = true)
                }
                items.forEach { (value, fullKind) ->
                    if (value.isNotEmpty()) {
                        KindChip(
                            value,
                            label = false,
                            onClick = { actions.onSearchKind(fullKind, true) },
                            onLongClick = { actions.onSearchKind(value, false) },
                        )
                    }
                }
            }
        }
    }
}

/** kind 解析: `组:值` 归组，其余入"其它"; fullKind 保留原串供 :: 探索跳转 */
private fun buildKindGroups(
    kind: String?,
    otherLabel: String,
): List<Pair<String, List<Pair<String, String>>>> {
    val kinds = kind?.splitNotBlank(",", "\n") ?: return emptyList()
    val groups = linkedMapOf<String, MutableList<Pair<String, String>>>()
    kinds.forEach { k ->
        val tagContent = k.substringBefore("::").trim()
        val groupSplit = tagContent.split(":", limit = 2)
        if (groupSplit.size > 1 && groupSplit.all { it.isNotBlank() }) {
            groups.getOrPut(groupSplit[0].trim()) { mutableListOf() }
                .add(groupSplit[1].trim() to k)
        } else if (tagContent.isNotBlank()) {
            groups.getOrPut(otherLabel) { mutableListOf() }.add(tagContent to k)
        }
    }
    return groups.map { (k, v) -> k to v.toList() }
}

/** 对照 item_fillet_text+selector_fillet_btn_bg; label=组名样式(加粗+0.8 透明,不可点) */
@Composable
private fun KindChip(
    text: String,
    label: Boolean,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg = if (pressed) rememberColor("arco_fill_3") else rememberColor("btn_bg")
    Box(
        Modifier
            .padding(4.dp) // selector inset
            .clip(DesignTokens.shapeDefault)
            .background(bg)
            .then(
                if (onClick != null) Modifier.combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onLongClick = onLongClick,
                    onClick = onClick,
                ) else Modifier
            )
            // 原 XML padding 16×12 自视图外缘计(含 4dp inset)，此处已扣除 inset
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = AppTheme.colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (label) FontWeight.Bold else null,
            // 显式 style: 不继承 M3 bodyLarge 的 24sp lineHeight, 保持原 TextView 自然行高
            style = TextStyle(fontSize = 14.sp),
            modifier = if (label) Modifier.alpha(0.8f) else Modifier,
        )
    }
}

// ---- 简介(可选中; <button>=胶囊动作、<img>=整宽图, 对照 setIntroWithActions) ----

private sealed interface IntroChunk
private class PlainChunk(val text: String) : IntroChunk
private class ButtonChunk(val label: String, val action: String) : IntroChunk

private sealed interface IntroPart
private class IntroTextPart(val chunks: List<IntroChunk>) : IntroPart
private class IntroImagePart(val src: String) : IntroPart

@Composable
private fun IntroSection(
    state: BookInfoUiState,
    actions: BookInfoUiActions,
    introImageSlot: @Composable (String, () -> Unit) -> Unit,
    modifier: Modifier,
) {
    val intro = state.book?.getDisplayIntro()
    if (intro.isNullOrBlank() || !intro.contains('<')) {
        SelectionContainer(modifier.fillMaxWidth()) {
            Text(
                text = intro ?: stringResource(Res.string.book_intro),
                color = AppTheme.colors.secondaryText,
                fontSize = 14.sp,
            )
        }
        return
    }
    val parts = remember(intro) { parseIntro(intro) }
    SelectionContainer(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            parts.forEach { part ->
                when (part) {
                    is IntroTextPart -> IntroRichText(part) { actions.onDispatchIntroAction(it) }
                    is IntroImagePart -> introImageSlot(part.src) { actions.onShowPhoto(part.src) }
                }
            }
        }
    }
}

private fun parseIntro(intro: String): List<IntroPart> {
    val parts = mutableListOf<IntroPart>()
    val chunks = mutableListOf<IntroChunk>()
    fun flush() {
        if (chunks.isNotEmpty()) {
            parts.add(IntroTextPart(chunks.toList()))
            chunks.clear()
        }
    }

    fun walk(parent: Element) {
        for (node in parent.childNodes()) {
            when (node) {
                is TextNode -> chunks.add(PlainChunk(node.getWholeText()))
                is Element -> when (node.tagName().lowercase()) {
                    "button" -> {
                        val label = node.text()
                        if (label.isNotBlank()) {
                            chunks.add(ButtonChunk(label, node.attr("onclick")))
                        }
                    }

                    "img" -> {
                        val src = node.absUrl("src").ifEmpty { node.attr("src") }
                        if (src.isNotBlank()) {
                            flush()
                            parts.add(IntroImagePart(src))
                        }
                    }

                    else -> walk(node)
                }
            }
        }
    }
    walk(Ksoup.parseBodyFragment(intro).body())
    flush()
    return parts
}

@Composable
private fun IntroRichText(part: IntroTextPart, onAction: (String) -> Unit) {
    val secondary = AppTheme.colors.secondaryText
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val text = buildAnnotatedString {
        part.chunks.forEachIndexed { i, chunk ->
            when (chunk) {
                is PlainChunk -> append(chunk.text)
                is ButtonChunk -> appendInlineContent("btn$i", chunk.label)
            }
        }
    }
    // 复刻 IntroButtonSpan: 文本尺寸 + 14dp×10dp 内边距的行内胶囊
    val inline = mutableMapOf<String, InlineTextContent>()
    part.chunks.forEachIndexed { i, chunk ->
        if (chunk !is ButtonChunk) return@forEachIndexed
        val layout = textMeasurer.measure(AnnotatedString(chunk.label), TextStyle(fontSize = 14.sp))
        val w = with(density) { (layout.size.width + 28.dp.toPx()).toSp() }
        val h = with(density) { (layout.size.height + 20.dp.toPx()).toSp() }
        inline["btn$i"] = InlineTextContent(
            Placeholder(w, h, PlaceholderVerticalAlign.TextCenter),
        ) {
            DisableSelection {
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val bg = if (pressed) rememberColor("arco_fill_3")
                else rememberColor("btn_bg")
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(4.dp)
                        .clip(DesignTokens.shapeDefault)
                        .background(bg)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                        ) { onAction(chunk.action) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(chunk.label, color = secondary, fontSize = 14.sp, maxLines = 1)
                }
            }
        }
    }
    Text(
        text = text,
        color = secondary,
        fontSize = 14.sp,
        inlineContent = inline,
        modifier = Modifier.fillMaxWidth(),
    )
}

// ---- 底部按钮(对照 ArcoButton tintBottom / Primary.Large) ----

@Composable
private fun BottomButtons(
    state: BookInfoUiState,
    actions: BookInfoUiActions,
    modifier: Modifier,
) {
    val colors = AppTheme.colors
    val bottomBg = colors.bottomBackground
    val shelfText = if (ColorUtils.isColorLight(bottomBg.toArgb())) Color(0xDE000000)
    else Color.White
    Row(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 16.dp),
    ) {
        ArcoSolidButton(
            text = rememberString(
                if (state.inBookshelf) "remove_from_bookshelf"
                else "add_to_bookshelf"
            ),
            bg = bottomBg,
            textColor = shelfText,
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp, end = 16.dp),
        ) { actions.onShelfClick() }
        ArcoSolidButton(
            text = stringResource(Res.string.reading),
            bg = colors.accent,
            textColor = if (ColorUtils.isColorLight(AppTheme.colors.accent.toArgb())) Color.Black else Color.White,
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp),
        ) { actions.onReadClick() }
    }
}

@Composable
private fun ArcoSolidButton(
    text: String,
    bg: Color,
    textColor: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val realBg = if (pressed) Color(ColorUtils.darkenColor(bg.toArgb())) else bg
    Box(
        modifier
            .height(DesignTokens.viewHeightXl)
            .clip(DesignTokens.shapeDefault)
            .background(realBg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = textColor, fontSize = 14.sp, maxLines = 1)
    }
}

// ===== @Preview 合并自 androidMain 的 book/info/BookInfoScreenPreviews.kt =====

/**
 * [BookInfoScreen] 的 @Preview。
 *
 * 假数据: [Book] 纯内存对象, [BookInfoUiState] 预设详情态字段,
 * 三个 slot 用灰色 Box 占位 (替代 Glide/AndroidView)。
 */

private val previewBook = Book(
    name = "三体",
    author = "刘慈欣",
    bookUrl = "preview://book",
    tocUrl = "preview://toc",
    origin = BookType.localTag,
    originName = "本地",
    kind = "科幻:硬科幻,小说",
    coverUrl = "https://preview/cover.jpg",
    intro = "三体世界与地球文明的接触, 黑暗森林法则下的宇宙博弈。",
    durChapterTitle = "黑暗森林",
    latestChapterTitle = "末日之战",
    durChapterIndex = 5,
    totalChapterNum = 120,
)

private val previewMenuState = BookInfoMenuState(
    isLocal = true,
    isWebDav = false,
    hasSource = false,
    sourceHasLogin = false,
    sourceHasReviewRule = false,
    canUpdate = true,
    isLocalTxt = false,
    splitLongChapter = false,
    bookUrl = "preview://book",
    tocUrl = "preview://toc",
)

private val previewState = BookInfoUiState(
    book = previewBook,
    bookTick = 0,
    coverTick = 0,
    inBookshelf = true,
    groupName = "我的分组",
    tocText = "120 章",
    lastedTitle = "末日之战",
    wordCountText = "100 万字",
    isLandscape = false,
    useDevFeat = false,
    isDarkTheme = false,
    menuState = previewMenuState,
)

private val previewStateLandscape = previewState.copy(isLandscape = true)

private val previewStateNotInShelf = previewState.copy(inBookshelf = false)

/** no-op actions */
private object NoOpInfoActions : BookInfoUiActions {
    override fun onBack() {}
    override fun onEdit() {}
    override fun onShare() {}
    override fun onRefresh() {}
    override fun onUploadBook() {}
    override fun onDownloadToLocal() {}
    override fun onTopBook() {}
    override fun onLogin() {}
    override fun onOpenCommentDialog() {}
    override fun onSetSourceVariable() {}
    override fun onSetBookVariable() {}
    override fun onCopyBookUrl() {}
    override fun onCopyTocUrl() {}
    override fun onToggleCanUpdate() {}
    override fun onToggleSplitLongChapter() {}
    override fun onClearCache() {}
    override fun onShowLog() {}
    override fun onNameClick() {}
    override fun onCoverClick() {}
    override fun onCoverLongClick() {}
    override fun onOriginClick() {}
    override fun onOriginLongClick() {}
    override fun onTocClick() {}
    override fun onGroupClick() {}
    override fun onShelfClick() {}
    override fun onReadClick() {}
    override fun onSearchAuthor(author: String, submit: Boolean) {}
    override fun onSearchKind(kind: String, submit: Boolean) {}
    override fun onDispatchIntroAction(action: String) {}
    override fun onShowPhoto(src: String) {}
}

/** 占位模糊背景 slot */
private val blurCoverBgSlot: @Composable (Modifier, Boolean) -> Unit = { modifier, _ ->
    Box(modifier.background(Color(0xFF4A6B8A)))
}

/** 占位封面 slot */
private val coverSlot: @Composable (Book?, Modifier) -> Unit = { book, modifier ->
    Box(modifier.background(Color(0xFF888888)), contentAlignment = Alignment.Center) {
        Text(book?.name?.take(2).orEmpty(), color = Color.White)
    }
}

/** 占位简介图片 slot */
private val introImageSlot: @Composable (String, () -> Unit) -> Unit = { src, onClick ->
    Box(
        Modifier
            .background(Color(0xFFCCCCCC))
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("[图片] $src", color = Color.DarkGray)
    }
}

@Preview
@Composable
fun BookInfoScreenPreview() = LegadoThemePreview {
    BookInfoScreen(
        state = previewState,
        actions = NoOpInfoActions,
        blurCoverBgSlot = blurCoverBgSlot,
        coverSlot = coverSlot,
        introImageSlot = introImageSlot,
    )
}

@Preview
@Composable
fun BookInfoScreenLandscapePreview() = LegadoThemePreview {
    BookInfoScreen(
        state = previewStateLandscape,
        actions = NoOpInfoActions,
        blurCoverBgSlot = blurCoverBgSlot,
        coverSlot = coverSlot,
        introImageSlot = introImageSlot,
    )
}

@Preview
@Composable
fun BookInfoScreenNotInShelfPreview() = LegadoThemePreview {
    BookInfoScreen(
        state = previewStateNotInShelf,
        actions = NoOpInfoActions,
        blurCoverBgSlot = blurCoverBgSlot,
        coverSlot = coverSlot,
        introImageSlot = introImageSlot,
    )
}

@Preview
@Composable
fun BookInfoScreenDarkPreview() = LegadoThemePreview(dark = true) {
    BookInfoScreen(
        state = previewState,
        actions = NoOpInfoActions,
        blurCoverBgSlot = blurCoverBgSlot,
        coverSlot = coverSlot,
        introImageSlot = introImageSlot,
    )
}
