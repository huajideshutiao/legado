package io.legado.app.ui.book.source.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.component.AppFilletTextButton
import io.legado.app.ui.compose.component.AppSearchField
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.linkifyText
import io.legado.app.ui.compose.theme.AppTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.book_src
import legado.shared.generated.resources.content_src
import legado.shared.generated.resources.debug_book_info_hint
import legado.shared.generated.resources.debug_content_hint
import legado.shared.generated.resources.debug_explore_hint
import legado.shared.generated.resources.debug_search_hint
import legado.shared.generated.resources.debug_source
import legado.shared.generated.resources.debug_toc_hint
import legado.shared.generated.resources.help
import legado.shared.generated.resources.refresh_explore
import legado.shared.generated.resources.review_src
import legado.shared.generated.resources.search_book_key
import legado.shared.generated.resources.search_src
import legado.shared.generated.resources.system
import legado.shared.generated.resources.toc_src
import org.jetbrains.compose.resources.stringResource

// ===== state / actions =====

/**
 * 书源调试页展示状态 (immutable)。
 *
 * 宿主端 (app 端 [BookSourceDebugActivity] / 桌面端) 持有 `mutableStateOf`/`mutableStateListOf`,
 * 数据更新时 copy 出新实例以触发 Compose 重组; [logs] 透传 SnapshotStateList 引用以保留 item 级响应。
 *
 * 字段语义对照原 `BookSourceDebugActivity` 同名字段:
 * - [logs] / [query] / [helpVisible] / [loading] / [textMy] / [textFx]:
 *   与 Activity 同名字段一一对应, 含义见各字段 KDoc。
 */
data class BookSourceDebugUiState(
    /** 调试日志行 (Activity 的 `mutableStateListOf<String>` 引用透传, 保留 item 级响应) */
    val logs: List<String>,
    /** 搜索框当前文本 */
    val query: String,
    /** 帮助面板是否可见 (搜索框聚焦时显示, 提交搜索后隐藏) */
    val helpVisible: Boolean,
    /** 是否正在调试 (顶部 CircularProgressIndicator) */
    val loading: Boolean,
    /** 调试搜索示例文本 (取自 bookSource.searchRule.checkKeyWord, 默认 "我的") */
    val textMy: String,
    /** 调试发现示例文本 ("title::url" 形式, ERROR: 前缀表示出错) */
    val textFx: String,
)

/**
 * 书源调试页事件回调集合。
 *
 * app 端 [BookSourceDebugActivity] 实现本接口, 平台相关依赖 (viewModel / showDialogFragment /
 * showHelp / selector / toastOnUi / clearExploreKindsCache 等) 在回调内桥接, shared 端不直接持有 Android 依赖。
 *
 * 设计: 返回式回调, 无 Android Context 依赖。所有需要 Android 专属 API 的动作
 * (如 [onShowHelp] 调 showHelp、[onShowSearchSrc] 调 showDialogFragment(TextDialog)) 由 app 端实现内部桥接。
 *
 * - [onBack]: 返回 (finish)
 * - [onQueryChange]: 搜索框文本变更
 * - [onSubmitQuery]: IME 搜索键提交 (内部清焦 + setQuery(query, true))
 * - [onSearchFocusChanged]: 搜索框聚焦状态变更 (联动 helpVisible)
 * - [onChipMyClick] / [onChipSystemClick] / [onChipFxClick]: 帮助面板搜索示例 chip 点击
 * - [onChipFxLongClick]: 发现示例 chip 长按 (弹 selector 选择发现分类)
 * - [onChipDetailClick] / [onChipTocClick] / [onChipContentClick]: 详情/目录/正文示例 chip 点击
 * - [onShowSearchSrc] / [onShowBookSrc] / [onShowTocSrc] / [onShowContentSrc] / [onShowReviewSrc]:
 *   菜单查看各阶段源码 (调 showDialogFragment(TextDialog))
 * - [onRefreshExplore]: 菜单刷新发现 (clearExploreKindsCache + 重新加载)
 * - [onShowHelp]: 菜单查看帮助 (调 showHelp("debugHelp"))
 */
interface BookSourceDebugUiActions {
    fun onBack()
    fun onQueryChange(text: String)
    fun onSubmitQuery()
    fun onSearchFocusChanged(focused: Boolean)
    fun onChipMyClick()
    fun onChipSystemClick()
    fun onChipFxClick()
    fun onChipFxLongClick()
    fun onChipDetailClick()
    fun onChipTocClick()
    fun onChipContentClick()
    fun onShowSearchSrc()
    fun onShowBookSrc()
    fun onShowTocSrc()
    fun onShowContentSrc()
    fun onShowReviewSrc()
    fun onRefreshExplore()
    fun onShowHelp()
}

// ===== BookSourceDebugScreen =====

/**
 * 书源调试 Screen (KMP 版, 替代 app 端 `BookSourceDebugActivity.Content` 下沉)。
 *
 * 二段式 API:
 * - [BookSourceDebugUiState]: 不可变展示状态, 由宿主端 (Activity/桌面) 持有 mutableStateOf 并 copy
 * - [BookSourceDebugUiActions]: 交互回调集合, 宿主端实现接口
 * - [BookSourceDebugScreen]: 纯 Composable 渲染入口, 仅依赖 state + actions
 *
 * @param clearFocusSignal 清焦信号流 (对照 app 端 searchView.clearFocus()), 每次提交搜索投递一次
 */
@Composable
fun BookSourceDebugScreen(
    state: BookSourceDebugUiState,
    actions: BookSourceDebugUiActions,
    clearFocusSignal: Flow<Unit> = emptyFlow(),
) {
    val colors = AppTheme.colors
    // 日志文本颜色对齐原版: 原版 BookSourceDebugAdapter 的 TextView 未显式设色,
    // 继承主题 android:textColorPrimary (AppCompat DayNight: light #DE000000 / dark #FFFFFFFF);
    // AppTheme.colors.primaryText 是 arco_text_1 (#212121 / #F8F8F8), 与原版 textColorPrimary 不同,
    // 故此处按原版色值取色 (isDark 与 BaseActivity 按背景亮度选 Light/Dark 主题语义一致)
    val logTextColor = if (colors.isDark) Color(0xFFFFFFFF) else Color(0xDE000000)
    val focusManager = LocalFocusManager.current
    val searchFocus = remember { FocusRequester() }
    var searchFocused by remember { mutableStateOf(false) }
    // 对齐原版 (onActionViewExpanded): 进入即聚焦搜索框, 帮助面板随聚焦显示 (手机端弹键盘);
    // 从编辑界面 push 进入时旧界面焦点可能尚未释放, 首次 requestFocus 会被拒绝且无重试:
    // 观察焦点状态, 未获得则逐帧重试, 确认获得即停 (组合退出时 LaunchedEffect 自动取消)
    LaunchedEffect(Unit) {
        while (!searchFocused) {
            searchFocus.requestFocus()
            withFrameNanos { }
        }
    }
    LaunchedEffect(clearFocusSignal) {
        clearFocusSignal.collect { focusManager.clearFocus() }
    }
    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = stringResource(Res.string.debug_source),
            onBack = { actions.onBack() },
            titleContent = {
                AppSearchField(
                    value = state.query,
                    onValueChange = { actions.onQueryChange(it) },
                    hint = stringResource(Res.string.search_book_key),
                    textFieldModifier = Modifier
                        .focusRequester(searchFocus)
                        .onFocusChanged {
                            searchFocused = it.isFocused
                            actions.onSearchFocusChanged(it.isFocused)
                        },
                    onSearch = {
                        focusManager.clearFocus()
                        actions.onSubmitQuery()
                    },
                )
            },
            actions = { DebugActions(actions) },
        )
        Box(Modifier.fillMaxSize()) {
            // 调试日志流：逐项 SelectionContainer + Text
            // - Text 内 LinkAnnotation.Url 自动处理短按打开链接
            // - 逐项 SelectionContainer 负责长按选择文本，桌面鼠标更稳定
            // - 二者手势时长不同（tap vs long-press），不冲突
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = WindowInsets.navigationBars.asPaddingValues(),
            ) {
                items(state.logs) { item ->
                    val annotated = remember(item) { linkifyText(item, colors.accent) }
                    SelectionContainer {
                        Text(
                            text = annotated,
                            color = logTextColor,
                            fontSize = 14.sp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            if (state.loading) {
                CircularProgressIndicator(
                    color = colors.accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(8.dp)
                        .size(36.dp),
                )
            }
            if (state.helpVisible) {
                HelpPanel(state, actions)
            }
        }
    }
}

// ===== 菜单 (对齐原 DebugActions) =====

@Composable
private fun DebugActions(actions: BookSourceDebugUiActions) {
    OverflowMenu { dismiss ->
        MenuItem(stringResource(Res.string.search_src)) {
            dismiss(); actions.onShowSearchSrc()
        }
        MenuItem(stringResource(Res.string.book_src)) {
            dismiss(); actions.onShowBookSrc()
        }
        MenuItem(stringResource(Res.string.toc_src)) {
            dismiss(); actions.onShowTocSrc()
        }
        MenuItem(stringResource(Res.string.content_src)) {
            dismiss(); actions.onShowContentSrc()
        }
        MenuItem(stringResource(Res.string.review_src)) {
            dismiss(); actions.onShowReviewSrc()
        }
        MenuItem(stringResource(Res.string.refresh_explore)) {
            dismiss(); actions.onRefreshExplore()
        }
        MenuItem(stringResource(Res.string.help)) {
            dismiss(); actions.onShowHelp()
        }
    }
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(onClick = onClick) {
        Text(text, color = AppTheme.colors.primaryText)
    }
}

// ===== 帮助面板 (对齐原 HelpPanel) =====

@Composable
private fun HelpPanel(state: BookSourceDebugUiState, actions: BookSourceDebugUiActions) {
    val colors = AppTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        HelpLabel(stringResource(Res.string.debug_search_hint))
        Row {
            FilletChip(state.textMy) { actions.onChipMyClick() }
            FilletChip(stringResource(Res.string.system)) { actions.onChipSystemClick() }
        }
        HelpLabel(stringResource(Res.string.debug_explore_hint))
        FilletChip(
            state.textFx,
            onLongClick = { actions.onChipFxLongClick() },
        ) {
            if (!state.textFx.startsWith("ERROR:")) {
                actions.onChipFxClick()
            }
        }
        HelpLabel(stringResource(Res.string.debug_book_info_hint))
        FilletChip("https://m.qidian.com/book/1015609210") {
            if (state.query.isNotBlank()) {
                actions.onChipDetailClick()
            }
        }
        HelpLabel(stringResource(Res.string.debug_toc_hint))
        FilletChip("++https://www.zhaishuyuan.com/read/30394") {
            actions.onChipTocClick()
        }
        HelpLabel(stringResource(Res.string.debug_content_hint))
        FilletChip("--https://www.zhaishuyuan.com/chapter/30394/20940996") {
            actions.onChipContentClick()
        }
    }
}

@Composable
private fun HelpLabel(text: String) {
    Text(text, color = AppTheme.colors.secondaryText, fontSize = 14.sp)
}

/**
 * 复刻原布局 fillet 按钮 (selector_fillet_btn_bg)：收拢到共享 [AppFilletTextButton]，
 * 差异参数化：primaryText 14sp (区别于 item_fillet_text 的 secondaryText)、
 * 16×8 外缘内边距、桌面端不抢输入法焦点 (搜索框失焦 -> helpVisible=false ->
 * HelpPanel 按下即消失, 长按无法触发)。
 */
@Composable
private fun FilletChip(text: String, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    AppFilletTextButton(
        text = text,
        textColor = AppTheme.colors.primaryText,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        focusable = false,
        onLongClick = onLongClick,
        onClick = onClick,
    )
}
