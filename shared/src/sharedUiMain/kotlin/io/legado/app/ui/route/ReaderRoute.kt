package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.legado.app.data.entities.BookProgress
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.ReaderPlatformProviders
import io.legado.app.ui.book.read.ReaderScreen
import io.legado.app.ui.book.read.ReaderScreenModel
import io.legado.app.ui.book.read.ReaderUiActions
import io.legado.app.ui.book.read.ReaderUiState
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.asBook

/**
 * 小说阅读器 shared 路由入口。
 *
 * 通过 [ScreenModelStore] 复用 [ReaderScreenModel]，渲染 [ReaderScreen]。
 * [ReadMenuState] 等平台依赖经 [ReaderPlatformProviders] 注入；未注册时退化为
 * 空渲染占位（不崩溃），待平台 actual 注册后接入完整阅读页。
 *
 * 对照 [TocRoute] 的 ScreenModel + dispatch + Screen 组合模式。
 */
@Composable
fun ReaderRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val route = entry.route as AppRoute.Reader
    val book = route.book.asBook()
    val provider = ReaderPlatformProviders.getOrNull()

    // 平台未注册 ReaderPlatformProvider，保持空渲染占位（不崩溃）
    if (provider == null) return

    val screenModel = screenModelStore.getOrCreateTyped(entry) {
        ReaderScreenModel(
            menuControllerFactory = { model -> provider.createMenuController(navigator, model) },
            getBatteryLevel = { provider.getBatteryLevel() },
        )
    }

    DisposableEffect(screenModel, provider) {
        provider.onEnter(screenModel)
        onDispose { provider.onExit(screenModel) }
    }

    // 初始化书籍数据（对照 app 端 ReadBookViewModel.initData + applyBookmarkPosition）
    LaunchedEffect(book) {
        screenModel.initBook(book, route.chapterIndex, route.chapterPos)
    }

    val state = remember(screenModel) {
        ReaderUiState(
            viewModel = screenModel.viewModel,
            menuState = screenModel.menuState,
            batteryLevel = screenModel.batteryLevel,
        )
    }

    val actions = remember(navigator, screenModel) {
        object : ReaderUiActions {
            // 中心区域单击：显示菜单（菜单隐藏时 ReadMenuOverlay early return，由 ReadViewComposable 接管手势）
            // 菜单显示时 ReadMenuOverlay 的 bg Box 拦截触摸调 onBgClick 收起，不经过本回调
            override fun onPageClick(column: TextColumn?) {
                screenModel.showMenu()
            }

            override fun onPageLongClick(column: TextColumn?) {
                provider.onLongPress(screenModel)
            }

            override fun onBack() {
                navigator.pop()
            }
        }
    }

    ReaderScreen(state = state, actions = actions)

    // 云进度同步确认对话框 (对照 app 端 ReadBookActivity.sureNewProgress)
    var syncProgress by remember { mutableStateOf<BookProgress?>(null) }
    LaunchedEffect(screenModel) {
        ReadBookEvents.newProgressConfirm.collect { progress ->
            syncProgress = progress
        }
    }
    syncProgress?.let { progress ->
        AppAlertDialog(
            onDismissRequest = {
                screenModel.viewModel.dismissSyncProgress()
                syncProgress = null
            },
            title = rememberString("sync_book_progress_t"),
            message = rememberString("cloud_progress_exceeds_current"),
            okButton = AlertButton(rememberString("ok")) {
                screenModel.viewModel.confirmSyncProgress(progress)
                syncProgress = null
            },
            cancelButton = AlertButton(rememberString("no")) {},
        )
    }
}
