package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.legado.app.ui.book.manga.MangaReaderScreenContent
import io.legado.app.ui.book.manga.MangaReaderScreenModel
import io.legado.app.ui.book.manga.MangaReaderUiEvent
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.asBook
import io.legado.app.ui.root.toRouteRef

/**
 * 漫画阅读页 shared 路由入口。
 * 通过 [ScreenModelStore] 复用 [MangaReaderScreenModel], 渲染 [MangaReaderScreenContent]。
 *
 * 图片渲染层 (imageSlot) 依赖平台 Coil3/ImageIO, 待下沉后由平台注入。
 */
@Composable
fun MangaReaderRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val route = entry.route as AppRoute.MangaReader
    val book = route.book.asBook()

    val screenModel = screenModelStore.getOrCreateTyped(entry) { MangaReaderScreenModel() }
    val state by screenModel.state.collectAsState()

    // 初始化书籍数据, 透传书签跳转参数 (对照 app 端 applyBookmarkPosition: chapterIndex/chapterPos)
    LaunchedEffect(book) {
        screenModel.dispatch(
            MangaReaderUiEvent.Init(book, route.chapterIndex, route.chapterPos)
        )
    }

    // 返回栈由导航器统一管理; 目录/换源派发独立 BookRef 快照
    val onBack: () -> Unit = { navigator.pop() }
    val onOpenToc: () -> Unit = { navigator.push(AppRoute.Toc(book.toRouteRef())) }
    val onOpenChangeSource: () -> Unit =
        { navigator.push(AppRoute.ChangeSource(book.toRouteRef())) }

    MangaReaderScreenContent(
        bookName = state.bookName,
        chapterTitle = state.chapterTitle,
        images = state.images,
        curChapterIndex = state.curChapterIndex,
        chapterSize = state.chapterSize,
        horizontal = state.horizontal,
        autoPageSpeed = state.autoPageSpeed,
        loading = state.loading,
        error = state.error,
        onBack = onBack,
        onPrevChapter = { screenModel.dispatch(MangaReaderUiEvent.PrevChapter) },
        onNextChapter = { screenModel.dispatch(MangaReaderUiEvent.NextChapter) },
        onRetry = { screenModel.dispatch(MangaReaderUiEvent.Retry) },
        onOpenToc = onOpenToc,
        onOpenChangeSource = onOpenChangeSource,
        imageSlot = { url, modifier, horizontal ->
            screenModel.platformRenderer?.Image(
                url = url,
                modifier = modifier,
                horizontal = horizontal,
                book = screenModel.currentBook,
                source = screenModel.currentSource,
            )
        },
    )
}
