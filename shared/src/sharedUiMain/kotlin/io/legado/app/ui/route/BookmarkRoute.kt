package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.legado.app.constant.AppLog
import io.legado.app.constant.ThreadSafeDateFormat
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.storage.BackupFileOps
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.book.bookmark.AllBookmarkScreen
import io.legado.app.ui.book.bookmark.AllBookmarkScreenModel
import io.legado.app.ui.book.bookmark.AllBookmarkUiActions
import io.legado.app.ui.book.bookmark.BookmarkDialog
import io.legado.app.ui.compose.platform.sharedStringTable
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.root.asBook
import io.legado.app.ui.root.toRouteRef
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * AppRoute.Bookmark 路由下沉入口。
 *
 * 复用 shared [AllBookmarkScreen] + [AllBookmarkScreenModel];
 * 平台专属逻辑 (导出文件选择器/书签编辑弹窗) 经 [AllBookmarkUiActions] 桥接:
 * 导出走 [PlatformServiceProviders] 文件选择器 + [BackupFileOps], 编辑弹窗用 shared [BookmarkDialog]。
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

            // 导出书签 JSON (对照 AllBookmarkViewModel.exportBookmark: 平台文件选择器 + BackupFileOps)
            // 按书过滤时仅导出该书书签 (对照 TocRoute.exportBookmark 用 getByBook)
            override fun export() {
                scope.launch {
                    try {
                        val fileName = "bookmark-${
                            ThreadSafeDateFormat("yyMMddHHmmss").format(systemCurrentTimeMillis())
                        }.json"
                        val path = PlatformServiceProviders.get().files.saveFile(fileName)
                            ?: return@launch
                        val dao = AppDbProviders.get().bookmarkDao
                        val bookmarks = if (book != null) {
                            dao.getByBook(book.name, book.author)
                        } else {
                            dao.all()
                        }
                        BackupFileOps.writeText(path, Json.encodeToString(bookmarks))
                        Toasters.get().toast("导出成功")
                    } catch (e: Throwable) {
                        AppLog.put("导出失败\n${e.localizedMessage}", e, true)
                    }
                }
            }

            // 导出书签 Markdown (对照 AllBookmarkViewModel.exportBookmarkMd: 按书分组)
            // 按书过滤时仅导出该书书签
            override fun exportMd() {
                scope.launch {
                    try {
                        val fileName = "bookmark-${
                            ThreadSafeDateFormat("yyMMddHHmmss").format(systemCurrentTimeMillis())
                        }.md"
                        val path = PlatformServiceProviders.get().files.saveFile(fileName)
                            ?: return@launch
                        val dao = AppDbProviders.get().bookmarkDao
                        val bookmarks = if (book != null) {
                            dao.getByBook(book.name, book.author)
                        } else {
                            dao.all()
                        }
                        val sb = StringBuilder()
                        var name = ""
                        var author = ""
                        bookmarks.forEach {
                            if (it.bookName != name && it.bookAuthor != author) {
                                name = it.bookName
                                author = it.bookAuthor
                                sb.append("## ${it.bookName} ${it.bookAuthor}\n\n")
                            }
                            sb.append("#### ${it.chapterName}\n\n")
                            sb.append("###### 原文\n ${it.bookText}\n\n")
                            sb.append("###### 摘要\n ${it.content}\n\n")
                        }
                        BackupFileOps.writeText(path, sb.toString())
                        Toasters.get().toast("导出成功")
                    } catch (e: Throwable) {
                        AppLog.put("导出失败\n${e.localizedMessage}", e, true)
                    }
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
                        Toasters.get().toast(sharedStringTable["no_book"] ?: "没有书籍")
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
