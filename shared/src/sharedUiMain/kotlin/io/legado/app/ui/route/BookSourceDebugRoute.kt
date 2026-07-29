package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.book.source.debug.BookSourceDebugScreen
import io.legado.app.ui.book.source.debug.BookSourceDebugScreenModel
import io.legado.app.ui.book.source.debug.BookSourceDebugUiActions
import io.legado.app.ui.book.source.debug.BookSourceDebugUiEvent
import io.legado.app.ui.compose.component.AppSelectorDialog
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.widget.dialog.HelpDialog
import io.legado.app.ui.widget.dialog.TextDialog

/**
 * AppRoute.BookSourceDebug 路由下沉入口: 桥接 [BookSourceDebugScreenModel] 状态与 [BookSourceDebugScreen] 渲染。
 *
 * 平台字符串经 [rememberString] 注入 ScreenModel; sourceUrl 取自路由, dispatch Init 触发调试初始化。
 * 发现分类长按选择器用 shared [AppSelectorDialog]; 各阶段源码查看用 shared [TextDialog]; 帮助用 shared [HelpDialog]。
 */
@Composable
fun BookSourceDebugRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val route = entry.route as AppRoute.BookSourceDebug
    val sourceUrl = route.sourceUrl

    // ScreenModel 构造所需平台字符串 (对齐 app 端 R.string 注入)
    val defaultTextMy = rememberString("my")
    val defaultTextFx = rememberString("debug_fx_default")
    val systemText = rememberString("system")
    val exploreErrorTemplate = rememberString("debug_explore_error")
    val exploreJsonErrorTemplate = rememberString("debug_explore_json_error")

    // 对话框标题文案 (对照 app 端 DebugActions 菜单项)
    val strSelectExplore = rememberString("select_explore")
    val strSearchSrc = rememberString("search_src")
    val strBookSrc = rememberString("book_src")
    val strTocSrc = rememberString("toc_src")
    val strContentSrc = rememberString("content_src")
    val strReviewSrc = rememberString("review_src")
    // 调试失败提示 (对照 app 端 toastOnUi(R.string.no_source_found))
    val noSourceFoundText = rememberString("no_source_found")

    val screenModel = screenModelStore.getOrCreateTyped(entry) {
        BookSourceDebugScreenModel(
            defaultTextMy = defaultTextMy,
            defaultTextFx = defaultTextFx,
            systemText = systemText,
            exploreErrorText = { url -> exploreErrorTemplate.replace("%s", url) },
            exploreJsonErrorText = { e -> exploreJsonErrorTemplate.replace("%s", e.toString()) },
        )
    }

    // 对齐 app 端 onActivityCreated: dispatch Init(sourceUrl) 触发书源加载与帮助面板填充
    LaunchedEffect(sourceUrl) {
        screenModel.dispatch(BookSourceDebugUiEvent.Init(sourceUrl))
    }

    // 注入调试失败 toast 副作用 (对照 app 端 startSearch 的 error 回调 toastOnUi)
    LaunchedEffect(screenModel, noSourceFoundText) {
        screenModel.onError = { Toasters.get().toast(noSourceFoundText) }
    }

    val state by screenModel.uiState.collectAsState()

    // 对话框状态
    var showFxSelector by remember { mutableStateOf(false) }
    // 源码对话框: title to content (对照 app 端 showDialogFragment(TextDialog("html", src)))
    var srcDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showHelp by remember { mutableStateOf(false) }

    val actions = remember(navigator, screenModel) {
        object : BookSourceDebugUiActions {
            override fun onBack() {
                navigator.pop()
            }

            override fun onQueryChange(text: String) {
                screenModel.dispatch(BookSourceDebugUiEvent.QueryChange(text))
            }

            override fun onSubmitQuery() {
                screenModel.dispatch(BookSourceDebugUiEvent.SubmitQuery)
            }

            override fun onSearchFocusChanged(focused: Boolean) {
                screenModel.dispatch(BookSourceDebugUiEvent.SearchFocusChanged(focused))
            }

            override fun onChipMyClick() {
                screenModel.dispatch(BookSourceDebugUiEvent.ChipMyClick)
            }

            override fun onChipSystemClick() {
                screenModel.dispatch(BookSourceDebugUiEvent.ChipSystemClick)
            }

            override fun onChipFxClick() {
                screenModel.dispatch(BookSourceDebugUiEvent.ChipFxClick)
            }

            override fun onChipFxLongClick() {
                // 对照 app 端: 仅当 exploreKinds 非空时弹 selector
                if (screenModel.exploreKinds.isNotEmpty()) {
                    showFxSelector = true
                }
            }

            override fun onChipDetailClick() {
                screenModel.dispatch(BookSourceDebugUiEvent.ChipDetailClick)
            }

            override fun onChipTocClick() {
                screenModel.dispatch(BookSourceDebugUiEvent.ChipTocClick)
            }

            override fun onChipContentClick() {
                screenModel.dispatch(BookSourceDebugUiEvent.ChipContentClick)
            }

            override fun onShowSearchSrc() {
                screenModel.searchSrc?.let { srcDialog = strSearchSrc to it }
            }

            override fun onShowBookSrc() {
                screenModel.bookSrc?.let { srcDialog = strBookSrc to it }
            }

            override fun onShowTocSrc() {
                screenModel.tocSrc?.let { srcDialog = strTocSrc to it }
            }

            override fun onShowContentSrc() {
                screenModel.contentSrc?.let { srcDialog = strContentSrc to it }
            }

            override fun onShowReviewSrc() {
                screenModel.reviewSrc?.let { srcDialog = strReviewSrc to it }
            }

            override fun onRefreshExplore() {
                screenModel.dispatch(BookSourceDebugUiEvent.RefreshExplore)
            }

            override fun onShowHelp() {
                showHelp = true
            }
        }
    }

    BookSourceDebugScreen(state = state, actions = actions)

    // 发现分类长按选择器 (对照 app 端 onChipFxLongClick / selector)
    if (showFxSelector) {
        AppSelectorDialog(
            onDismissRequest = { showFxSelector = false },
            title = strSelectExplore,
            items = screenModel.exploreKinds.map { it.title ?: "" },
            onItemSelected = { index ->
                showFxSelector = false
                screenModel.dispatch(BookSourceDebugUiEvent.SelectExplore(index))
            },
        )
    }

    // 源码查看对话框 (对照 app 端 showDialogFragment(TextDialog("html", src)))
    srcDialog?.let { (title, content) ->
        TextDialog(
            title = title,
            content = content,
            onConfirm = { srcDialog = null },
            onDismiss = { srcDialog = null },
        )
    }

    // 帮助对话框 (对照 app 端 showHelp("debugHelp"))
    if (showHelp) {
        HelpDialog("debugHelp") { showHelp = false }
    }
}
