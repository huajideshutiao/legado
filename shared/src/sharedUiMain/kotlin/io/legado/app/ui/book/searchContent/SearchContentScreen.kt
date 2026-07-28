package io.legado.app.ui.book.searchContent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.component.AppMenuCheckbox
import io.legado.app.ui.compose.component.AppSearchField
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.FastScrollLazyColumn
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppColors
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.LocalEInk
import io.legado.app.utils.ColorUtils
import kotlinx.coroutines.launch

/*
 * 下沉所需资源 key 清单 (供 ResourceProvider 各平台 actual 补全)
 *
 * Painter key (drawable):
 *   - ic_stop_black_24dp   (停止按钮, 新增)
 *   - ic_arrow_drop_up     (回顶箭头, 已存在)
 *   - ic_arrow_drop_down   (到底箭头, 已存在)
 *
 * String key (string):
 *   - search               (搜索框 hint, 已存在)
 *   - replace              (替换菜单项, 新增)
 *   - stop                 (停止按钮描述, 新增)
 *   - search_content_size  (结果计数标签, 新增)
 *   - go_to_top            (回顶按钮描述, 已存在)
 *   - go_to_bottom         (到底按钮描述, 已存在)
 *
 * L3 不可下沉项: 无
 *   - 所有 Android 专属 API (Context/Intent/getString/finish 等) 留 app 端 Activity,
 *     通过 [SearchContentUiActions] 接口回调, 无需 slot 注入。
 *   - LocalSoftwareKeyboardController / LocalFocusManager 为 Compose 多平台 API,
 *     shared 端可直接使用 (参考 SearchScreen.kt)。
 */

/**
 * 书内全文搜索展示状态。
 *
 * app 端 [SearchContentActivity] 在 `Content()` 内将自己的 `var` 状态字段打包为本数据类传入;
 * 桌面端/iOS 端可同样构造本类复用 [SearchContentScreen]。
 *
 * 字段语义对照原 `SearchContentActivity` 同名字段:
 * - [query] / [resultCount] / [searching] / [durChapterIndex] / [replaceEnabled] /
 *   [focusEpoch] / [pendingScrollIndex]: 与 Activity 同名字段一一对应
 * - [results]: Activity 的 `mutableStateListOf<SearchResult>` 引用, 透传给
 *   `itemsIndexed` 以保留 SnapshotStateList 的 item 级响应 (引用不变, 数据类
 *   equals 走 List 引用短路, 无额外开销)
 */
data class SearchContentUiState(
    val query: String,
    val results: List<SearchResult>,
    val resultCount: Int,
    val searching: Boolean,
    val durChapterIndex: Int,
    val replaceEnabled: Boolean,
    val focusEpoch: Int,
    val pendingScrollIndex: Int?,
)

/**
 * 书内全文搜索用户交互回调。
 *
 * app 端 [SearchContentActivity] 实现本接口; 已有同名方法直接 `override`
 * (如 [onQueryChange]), 其余方法以 `override fun onXxx() = xxx()` 桥接现有方法,
 * 不改动 Activity 内部其它调用点 (参考 BookInfoActivity 薄壳模式)。
 *
 * 设计: 返回式回调, 无 Android Context 依赖。所有需要 Android 专属 API 的动作
 * (如 [onBack] 调 finish、[onSubmitSearch] 内部走 lifecycleScope 协程) 由 app 端
 * 实现内部桥接到 Activity。
 *
 * - [setClearFocusHandler] / [clearFocus]: 对照原 `activity.clearFocusHandler` 属性,
 *   UI 在 DisposableEffect 注册清焦回调, Activity 在 initBook 等处通过 `clearFocus()`
 *   触发; 保留原回调机制, 不改为信号量驱动。
 * - [onConsumePendingScrollIndex]: UI 消费 pendingScrollIndex 后回调置空,
 *   保留原 `activity.pendingScrollIndex = null` 行为。
 */
interface SearchContentUiActions {
    /** 关闭页面 (对照 activity.finish()) */
    fun onBack()
    /** 搜索框文本变更 (对照 activity.onQueryChange) */
    fun onQueryChange(text: String)
    /** 提交搜索 (对照 activity.startContentSearch) */
    fun onSubmitSearch(query: String)
    /** 切换替换开关 (对照 activity.toggleReplaceEnabled) */
    fun onToggleReplaceEnabled()
    /** 停止搜索 (对照 activity.stopSearch) */
    fun onStopSearch()
    /** 打开搜索结果 (对照 activity.openSearchResult) */
    fun onOpenResult(item: SearchResult, index: Int)
    /** 请求搜索框聚焦 (对照 activity.requestFocusSearch) */
    fun onRequestFocusSearch()
    /** UI 注册清焦回调 (对照 activity.clearFocusHandler = ...) */
    fun setClearFocusHandler(handler: (() -> Unit)?)
    /** 触发已注册的清焦回调 (对照 activity.clearFocusHandler?.invoke()) */
    fun clearFocus()
    /** 消费 pendingScrollIndex (对照 activity.pendingScrollIndex = null) */
    fun onConsumePendingScrollIndex()
}

/**
 * 书内全文搜索内容：标题栏(搜索框+替换开关菜单) + 结果列表 +
 * 底部计数/回顶/到底栏，顶部 2dp 搜索进度条与右下停止按钮叠加其上。
 *
 * 下沉自 app 端原 `SearchContentScreen(activity: SearchContentActivity)`，将
 * `SearchContentActivity` 直接依赖拆为 [state] (展示状态) + [actions] (交互回调)，
 * 去除 Android `Context` / `activity` 依赖。
 *
 * 视觉/布局/动画/手势/状态管理完全与 app 端原版一致 (宽高/边距/颜色/层级)。
 *
 * 无 L3 slot: 所有 Android 专属 API (painterResource/stringResource 等) 经
 * `rememberPainter`/`rememberString` 抽象, 无需 slot 注入。
 */
@Composable
fun SearchContentScreen(state: SearchContentUiState, actions: SearchContentUiActions) {
    val colors = AppTheme.colors
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        AppTitleBar(
            title = "",
            onBack = { actions.onBack() },
            titleContent = { SearchField(state, actions) },
            actions = { SearchContentActions(state, actions) },
        )
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            ResultList(state, actions, listState)
            RefreshBar(state.searching, Modifier.align(Alignment.TopStart))
            StopFab(state, actions, Modifier.align(Alignment.BottomEnd))
        }
        BottomInfoBar(
            state = state,
            actions = actions,
            onScrollTop = { scope.launch { listState.scrollToItem(0) } },
            onScrollBottom = {
                scope.launch {
                    if (state.results.isNotEmpty()) {
                        listState.scrollToItem(state.results.lastIndex)
                    }
                }
            },
        )
    }
}

// ===== 搜索框 =====

@Composable
private fun SearchField(state: SearchContentUiState, actions: SearchContentUiActions) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    DisposableEffect(Unit) {
        actions.setClearFocusHandler {
            focusManager.clearFocus()
            keyboard?.hide()
        }
        onDispose { actions.setClearFocusHandler(null) }
    }
    // 无既有结果进入/点击底栏计数时聚焦并弹键盘(对齐 isIconified=false 与 showSoftInput)
    LaunchedEffect(state.focusEpoch) {
        if (state.focusEpoch > 0) runCatching {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    AppSearchField(
        value = state.query,
        onValueChange = actions::onQueryChange,
        hint = rememberString("search"),
        onSearch = {
            actions.onSubmitSearch(state.query.trim())
            actions.clearFocus()
        },
        textFieldModifier = Modifier.focusRequester(focusRequester),
    )
}

// ===== 菜单(对齐 content_search.xml 的替换开关) =====

@Composable
private fun SearchContentActions(state: SearchContentUiState, actions: SearchContentUiActions) {
    val colors = AppTheme.colors
    OverflowMenu { dismiss ->
        DropdownMenuItem(
            onClick = { dismiss(); actions.onToggleReplaceEnabled() },
        ) {
            Text(rememberString("replace"), color = colors.primaryText)
            Spacer(Modifier.weight(1f))
            AppMenuCheckbox(checked = state.replaceEnabled)
        }
    }
}

// ===== 结果列表 =====

@Composable
private fun ResultList(
    state: SearchContentUiState,
    actions: SearchContentUiActions,
    listState: LazyListState,
) {
    // 恢复上次结果时定位到当前条目(对齐 scrollToPosition)
    LaunchedEffect(state.pendingScrollIndex) {
        val index = state.pendingScrollIndex ?: return@LaunchedEffect
        if (state.results.isNotEmpty()) {
            listState.scrollToItem(index.coerceIn(0, state.results.lastIndex))
        }
        actions.onConsumePendingScrollIndex()
    }
    FastScrollLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        itemsIndexed(state.results) { index, item ->
            SearchResultItem(
                item = item,
                isDur = state.durChapterIndex == item.chapterIndex,
                onClick = { actions.onOpenResult(item, index) },
            )
        }
    }
}

@Composable
private fun SearchResultItem(item: SearchResult, isDur: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val eInk = LocalEInk.current
    Text(
        text = buildResultText(item, eInk, colors),
        color = colors.primaryText,
        fontSize = 14.sp,
        // 当前阅读章节加粗标识(对齐 isFakeBoldText)
        fontWeight = if (isDur) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = item.query.isNotBlank(), onClick = onClick)
            .padding(12.dp),
    )
}

/** 对照原 getHtmlCompat：章节名与命中词高亮 accent，E-Ink 用下划线。 */
private fun buildResultText(item: SearchResult, eInk: Boolean, colors: AppColors): AnnotatedString {
    return buildAnnotatedString {
        val queryIndex = item.resultText.indexOf(item.query)
        if (item.query.isBlank() || queryIndex < 0) {
            append(item.resultText)
            return@buildAnnotatedString
        }
        val highlight = if (eInk) {
            SpanStyle(textDecoration = TextDecoration.Underline)
        } else {
            SpanStyle(color = colors.accent)
        }
        withStyle(highlight) { append(item.chapterTitle) }
        append('\n')
        append(item.resultText.take(queryIndex))
        withStyle(highlight) { append(item.query) }
        append(item.resultText.substring(queryIndex + item.query.length))
    }
}

// ===== 进度条与停止按钮 =====

/** 对照 RefreshProgressBar：标题栏下 2dp 搜索中指示，E-Ink 用静态色条 */
@Composable
private fun RefreshBar(visible: Boolean, modifier: Modifier) {
    if (!visible) return
    val colors = AppTheme.colors
    if (LocalEInk.current) {
        Box(
            modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(colors.accent),
        )
    } else {
        LinearProgressIndicator(
            color = colors.accent,
            backgroundColor = Color.Transparent,
            modifier = modifier
                .fillMaxWidth()
                .height(2.dp),
        )
    }
}

/** 对照 fb_stop：mini(40dp) accent 圆钮，按压加深，搜索中可见 */
@Composable
private fun StopFab(
    state: SearchContentUiState,
    actions: SearchContentUiActions,
    modifier: Modifier,
) {
    if (!state.searching) return
    val colors = AppTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg = if (pressed) {
        Color(ColorUtils.darkenColor(colors.accent.toArgb()))
    } else {
        colors.accent
    }
    val tint = if (ColorUtils.isColorLight(colors.accent.toArgb())) Color.Black else Color.White
    Box(
        modifier
            .padding(16.dp)
            .shadow(6.dp, CircleShape)
            .size(40.dp)
            .background(bg, CircleShape)
            .clickable(interactionSource = interaction, indication = null) {
                actions.onStopSearch()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = rememberPainter("ic_stop_black_24dp"),
            contentDescription = rememberString("stop"),
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
    }
}

// ===== 底部计数/回顶/到底栏 =====

@Composable
private fun BottomInfoBar(
    state: SearchContentUiState,
    actions: SearchContentUiActions,
    onScrollTop: () -> Unit,
    onScrollBottom: () -> Unit,
) {
    val colors = AppTheme.colors
    // 对照 getPrimaryTextColor(isColorLight(bottomBackground)): 底栏色亮度反推文字/图标色
    val btc = if (ColorUtils.isColorLight(colors.bottomBackground.toArgb())) {
        Color(0xDE000000)
    } else {
        Color.White
    }
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding() // 对齐 applyNavigationBarMargin
            .height(36.dp)
            .background(colors.bottomBackground)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clickable { actions.onRequestFocusSearch() }
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = rememberString("search_content_size") + ": ${state.resultCount}",
                color = btc,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(20.dp))
        BarIcon("ic_arrow_drop_up", "go_to_top", btc, onScrollTop)
        BarIcon("ic_arrow_drop_down", "go_to_bottom", btc, onScrollBottom)
    }
}

@Composable
private fun BarIcon(iconKey: String, descKey: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .width(36.dp)
            .fillMaxHeight()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = rememberPainter(iconKey),
            contentDescription = rememberString(descKey),
            tint = tint,
        )
    }
}
