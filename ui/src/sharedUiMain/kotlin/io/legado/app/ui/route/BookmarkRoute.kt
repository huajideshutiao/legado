package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.legado.app.constant.ThreadSafeDateFormat
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.book.bookmark.AllBookmarkScreen
import io.legado.app.ui.book.bookmark.AllBookmarkScreenModel
import io.legado.app.ui.book.bookmark.AllBookmarkUiActions
import io.legado.app.ui.book.bookmark.BookmarkDialog
import io.legado.app.ui.book.bookmark.BookmarkExporter
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.asBook
import io.legado.app.ui.root.toRouteRef
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.coroutines.launch
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.no_book
import org.jetbrains.compose.resources.getString

/**
 * AppRoute.Bookmark 路由下沉入口。
 *
 * 复用 shared [AllBookmarkScreen] + [AllBookmarkScreenModel];
 * 平台专属逻辑 (导出文件选择器/书签编辑弹窗) 经 [AllBookmarkUiActions] 桥接:
 * 导出走共用 [BookmarkExporter] (文件选择器 + 写文件), 编辑弹窗用 shared [BookmarkDialog]。
 */
@Composable
fun BookmarkRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val route = entry.route as AppRoute.Bookmark
    // 按书过滤入口: route.book 非空时仅展示/导出该书书签 (对照 TocRoute)
    val book = route.book?.asBook()
    val scope = rememberCoroutineScope()
    val screenModel = screenModelStore.getOrCreateTyped(entry) {
        AllBookmarkScreenModel(book)
    }
    val state by screenModel.state.collectAsState()
    // 书签编辑对话框态 (shared BookmarkDialog, 对照 TocRoute.editBookmark)
    var editingBookmark by remember { mutableStateOf<Bookmark?>(null) }
    val actions = remember(navigator, book) {
        object : AllBookmarkUiActions {
            override fun onBack() {
                navigator.pop()
            }

            // 导出书签 JSON (对照 AllBookmarkViewModel.exportBookmark), 文件名带时间戳
            // 按书过滤时仅导出该书书签 (对照 TocRoute.exportBookmark 用 getByBook)
            override fun export() {
                scope.launch {
                    BookmarkExporter.exportJson(
                        fileName = "bookmark-${
                            ThreadSafeDateFormat("yyMMddHHmmss").format(systemCurrentTimeMillis())
                        }.json",
                        bookName = book?.name,
                        bookAuthor = book?.author,
                    )
                }
            }

            // 导出书签 Markdown (对照 AllBookmarkViewModel.exportBookmarkMd), 文件名带时间戳
            override fun exportMd() {
                scope.launch {
                    BookmarkExporter.exportMd(
                        fileName = "bookmark-${
                            ThreadSafeDateFormat("yyMMddHHmmss").format(systemCurrentTimeMillis())
                        }.md",
                        bookName = book?.name,
                        bookAuthor = book?.author,
                    )
                }
            }

            // 按书名+作者查书后跳转阅读页 (对照原 AllBookmarkActivity.openBookmark)
            override fun openBookmark(bookmark: Bookmark) {
                scope.launch {
                    val book = AppDbProviders.get().bookDao.getBook(
                        bookmark.bookName, bookmark.bookAuthor
                    )
                    if (book == null) {
                        // 对照原 AllBookmarkActivity: book 为空时 toastOnUi(R.string.no_book)
                        Toasters.get().toast(getString(Res.string.no_book))
                        return@launch
                    }
                    // 跳转阅读页定位到书签章节位置 (与 TocRoute.openBookmark 一致)
                    navigator.push(
                        AppRoute.Reader(
                            book.toRouteRef(),
                            bookmark.chapterIndex,
                            bookmark.chapterPos
                        )
                    )
                }
            }

            // 弹出书签编辑对话框 (shared BookmarkDialog, 对照 TocRoute.editBookmark)
            override fun editBookmark(bookmark: Bookmark, pos: Int) {
                editingBookmark = bookmark
            }
        }
    }

    // 书签编辑对话框 (onConfirm 入库 / onDelete 删除, 对照 TocRoute 同名弹窗)
    editingBookmark?.let { bookmark ->
        BookmarkDialog(
            bookmark = bookmark,
            showDelete = true,
            onConfirm = { updated ->
                scope.launch {
                    // 对照 app BookmarkDialog: insert(REPLACE) 而非 update, 对齐原入库语义
                    AppDbProviders.get().bookmarkDao.insert(updated)
                }
                editingBookmark = null
            },
            onDismiss = { editingBookmark = null },
            onDelete = {
                scope.launch {
                    AppDbProviders.get().bookmarkDao.delete(bookmark)
                }
                editingBookmark = null
            },
        )
    }

    AllBookmarkScreen(state = state, actions = actions)
}
