package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.model.ActiveReadBookRegistry
import io.legado.app.ui.book.info.edit.BookInfoEditScreen
import io.legado.app.ui.book.info.edit.BookInfoEditScreenModel
import io.legado.app.ui.book.info.edit.BookInfoEditUiActions
import io.legado.app.ui.book.info.edit.BookInfoEditUiEvent
import io.legado.app.ui.book.info.edit.BookInfoEditViewModelShared
import io.legado.app.ui.bookshelf.LocalBookCoverSlot
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.FileFilter
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.RouteResultPayload
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.asBook
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 书籍信息编辑页 shared 路由入口。
 * 通过 [ScreenModelStore] 复用 [BookInfoEditScreenModel], 渲染 [BookInfoEditScreen]。
 *
 * 选图走 [PlatformServiceProviders] 文件选择器; 换封面源弹窗走 [PlatformCapabilityProviders];
 * 封面渲染用跨平台占位 (app 端通过自身 Activity 注入 ShelfCover)。
 */
@Composable
fun BookInfoEditRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val route = entry.route as AppRoute.BookInfoEdit
    val bookUrl = route.book.bookUrl
    val scope = rememberCoroutineScope()

    val screenModel = screenModelStore.getOrCreateTyped(entry) {
        BookInfoEditScreenModel(
            shared = BookInfoEditViewModelShared(
                scope = scope,
                readBookUpdater = ActiveReadBookRegistry::updateIfCurrent,
            ),
            updateCacheFolder = { oldBook, newBook ->
                BookStorageProviders.get().updateCacheFolder(oldBook, newBook)
            },
        )
    }

    LaunchedEffect(bookUrl) {
        screenModel.loadBook(route.book.asBook())
    }

    val state by screenModel.state.collectAsState()

    val actions = object : BookInfoEditUiActions {
        override fun onBack() {
            navigator.pop()
        }

        override fun onSave() {
            screenModel.dispatch(BookInfoEditUiEvent.Save {
                navigator.pop(RouteResultPayload.Ok)
            })
        }

        // 本地选图: 平台文件选择器 (对照 app 端 HandleFileContract)
        // 选择器实现是阻塞式的 (Android 端 runBlocking 等 SAF 回调), 必须切到 IO 再调
        override fun onSelectCover() {
            scope.launch {
                val path = withContext(IoDispatcher) {
                    PlatformServiceProviders.getOrNull()?.files?.pickFile(FileFilter.Images)
                }
                if (path != null) {
                    screenModel.dispatch(BookInfoEditUiEvent.CoverChangeTo(path))
                }
            }
        }

        // 换封面源弹窗 (对照 app 端 ChangeCoverDialog)
        override fun onChangeCoverSource() {
            screenModel.book?.let { book ->
                PlatformCapabilityProviders.getOrNull()?.showChangeCoverDialog(book) { coverUrl ->
                    screenModel.dispatch(BookInfoEditUiEvent.CoverChangeTo(coverUrl))
                }
            }
        }

        override fun onRefreshCover() {
            screenModel.dispatch(BookInfoEditUiEvent.RefreshCover)
        }

        override fun onNameChange(value: String) =
            screenModel.dispatch(BookInfoEditUiEvent.NameChange(value))

        override fun onAuthorChange(value: String) =
            screenModel.dispatch(BookInfoEditUiEvent.AuthorChange(value))

        override fun onTypeChange(index: Int) =
            screenModel.dispatch(BookInfoEditUiEvent.TypeChange(index))

        override fun onCoverUrlChange(value: String) =
            screenModel.dispatch(BookInfoEditUiEvent.CoverUrlChange(value))

        override fun onIntroChange(value: String) =
            screenModel.dispatch(BookInfoEditUiEvent.IntroChange(value))

        override fun onBookUrlChange(value: String) =
            screenModel.dispatch(BookInfoEditUiEvent.BookUrlChange(value))
    }

    val bookCoverSlot = LocalBookCoverSlot.current

    BookInfoEditScreen(
        state = state,
        actions = actions,
        coverSlot = { book, modifier ->
            book?.let { bookCoverSlot(it, modifier, false) }
        },
    )
}
