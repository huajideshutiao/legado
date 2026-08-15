package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.book.source.debug.BookSourceDebugScreen
import io.legado.app.ui.book.source.debug.BookSourceDebugScreenModel
import io.legado.app.ui.book.source.debug.BookSourceDebugUiActions
import io.legado.app.ui.book.source.debug.BookSourceDebugUiEvent
import io.legado.app.ui.compose.component.AppSelectorDialog
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.widget.dialog.HelpDialog
import io.legado.app.ui.widget.dialog.TextDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.book_src
import legado.shared.generated.resources.content_src
import legado.shared.generated.resources.debug_explore_error
import legado.shared.generated.resources.debug_explore_json_error
import legado.shared.generated.resources.debug_fx_default
import legado.shared.generated.resources.my
import legado.shared.generated.resources.no_source_found
import legado.shared.generated.resources.review_src
import legado.shared.generated.resources.search_src
import legado.shared.generated.resources.select_explore
import legado.shared.generated.resources.system
import legado.shared.generated.resources.toc_src
import org.jetbrains.compose.resources.stringResource

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
    val defaultTextMy = stringResource(Res.string.my)
    val defaultTextFx = stringResource(Res.string.debug_fx_default)
    val systemText = stringResource(Res.string.system)
    val exploreErrorTemplate = stringResource(Res.string.debug_explore_error)
    val exploreJsonErrorTemplate = stringResource(Res.string.debug_explore_json_error)

    // 对话框标题文案 (对照 app 端 DebugActions 菜单项)
    val strSelectExplore = stringResource(Res.string.select_explore)
    val strSearchSrc = stringResource(Res.string.search_src)
    val strBookSrc = stringResource(Res.string.book_src)
    val strTocSrc = stringResource(Res.string.toc_src)
    val strContentSrc = stringResource(Res.string.content_src)
    val strReviewSrc = stringResource(Res.string.review_src)
    // 调试失败提示 (对照 app 端 toastOnUi(R.string.no_source_found))
    val noSourceFoundText = stringResource(Res.string.no_source_found)

    val screenModel = screenModelStore.getOrCreateTyped(entry) {
        BookSourceDebugScreenModel(
            defaultTextMy = defaultTextMy,
            defaultTextFx = defaultTextFx,
            systemText = systemText,
            exploreErrorText = { url -> exploreErrorTemplate.replace("%1\$s", url) },
            exploreJsonErrorText = { e -> exploreJsonErrorTemplate.replace("%1\$s", e.toString()) },
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
    val scope = rememberCoroutineScope()

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
                    // 延迟一帧再弹出: 长按触发时鼠标仍处于按下状态, 立即弹出会让释放事件
                    // 落到对话框 scrim 上误触 dismissOnClickOutside 关闭对话框
                    scope.launch {
                        delay(100)
                        showFxSelector = true
                    }
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

            // 原版即使 src 为 null 也弹空对话框, 不做静默吞掉
            override fun onShowSearchSrc() {
                srcDialog = strSearchSrc to screenModel.searchSrc.orEmpty()
            }

            override fun onShowBookSrc() {
                srcDialog = strBookSrc to screenModel.bookSrc.orEmpty()
            }

            override fun onShowTocSrc() {
                srcDialog = strTocSrc to screenModel.tocSrc.orEmpty()
            }

            override fun onShowContentSrc() {
                srcDialog = strContentSrc to screenModel.contentSrc.orEmpty()
            }

            override fun onShowReviewSrc() {
                srcDialog = strReviewSrc to screenModel.reviewSrc.orEmpty()
            }

            override fun onRefreshExplore() {
                screenModel.dispatch(BookSourceDebugUiEvent.RefreshExplore)
            }

            override fun onShowHelp() {
                showHelp = true
            }
        }
    }

    BookSourceDebugScreen(
        state = state,
        actions = actions,
        clearFocusSignal = screenModel.clearFocusFlow,
    )

    // 发现分类长按选择器 (对照 app 端 onChipFxLongClick / selector)
    // dismissOnClickOutside 默认 true: 对齐 app 端 selector 点击外部可取消;
    // 长按弹出的释放误触已由 onChipFxLongClick 内 delay(100) 规避
    if (showFxSelector) {
        AppSelectorDialog(
            onDismissRequest = { showFxSelector = false },
            title = strSelectExplore,
            items = screenModel.exploreKinds.map { it.title },
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
