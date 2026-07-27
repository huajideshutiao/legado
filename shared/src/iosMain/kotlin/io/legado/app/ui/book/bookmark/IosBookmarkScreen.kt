package io.legado.app.ui.book.bookmark

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.copyToClipboard
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * iOS 端所有书签 Screen 入口 (包装 shared/sharedUiMain 的 [AllBookmarkScreen])。
 *
 * # 职责
 *
 * 对照 app 端 [AllBookmarkActivity] 的薄壳模式, iOS 端在 [io.legado.app.ui.IosNavHost]
 * 的 BOOKMARK 路由分支调用本入口。
 *
 * 业务展示逻辑全部下沉到 shared/sharedUiMain, 本文件仅做 iOS 平台适配:
 * - **数据订阅**: `produceState` 订阅 `appDb.bookmarkDao.flowAll()`, 打包为 [AllBookmarkUiState];
 * - **actions 实现**: 实现 [AllBookmarkUiActions] 接口, 平台依赖
 *   (导出/跳转阅读/编辑对话框) 通过回调桥接;
 * - **编辑对话框**: 复用 shared/sharedUiMain 的 [BookmarkDialog];
 * - **导出**: iOS 端无文件保存面板, 简化为复制 JSON/Markdown 到剪贴板;
 * - **跳转阅读**: 通过 [onOpenBookmark] 回调由 IosNavHost 切到 READER 路由
 *   (异步查 book → 携带 chapterIndex/pos 跳转, 对照 app 端 openBookmark)。
 *
 * @param onBack 返回回调 (切回调用方路由, 由 IosNavHost 注入)
 * @param onOpenBookmark 点击书签跳转阅读回调 (携带 Book + chapterIndex + chapterPos),
 *   由 IosNavHost 注入切到 READER 路由
 */
@Composable
fun IosBookmarkScreen(
    onBack: () -> Unit,
    onOpenBookmark: (Book, Int, Int) -> Unit,
) {
    val scope = rememberCoroutineScope()

    // 文案模板 (produceState catch / export lambda 非 @Composable, 预先 remember)
    val bookmarkLoadFailedTemplate = rememberString("bookmark_load_failed_log")
    val copiedBookmarksTemplate = rememberString("copied_bookmarks_to_clipboard_count")
    val copiedMarkdownText = rememberString("copied_markdown_to_clipboard")
    val noBookLabel = rememberString("no_book")

    // 订阅全量书签数据 (对照 AllBookmarkActivity.onActivityCreated 的 flowAll 订阅)
    val bookmarks by produceState<List<Bookmark>>(emptyList()) {
        AppDbProviders.get().bookmarkDao.flowAll().catch {
            AppLog.put(String.format(bookmarkLoadFailedTemplate, it.localizedMessage), it)
        }.flowOn(Dispatchers.Default).collect { value = it }
    }

    // 编辑对话框状态 (editBookmark 触发; 持有待编辑 bookmark)
    var editTarget by remember { mutableStateOf<Bookmark?>(null) }

    AllBookmarkScreen(
        state = AllBookmarkUiState(bookmarks),
        actions = object : AllBookmarkUiActions {
            override fun onBack() = onBack()

            override fun export() {
                // iOS 端无文件保存面板, 简化为复制 JSON 到剪贴板
                copyToClipboard(GSON.toJson(bookmarks))
                Toasters.get().toast(String.format(copiedBookmarksTemplate, bookmarks.size))
            }

            override fun exportMd() {
                // iOS 端无文件保存面板, 简化为复制 Markdown 到剪贴板
                val md = bookmarks.joinToString("\n\n") { bm ->
                    buildString {
                        append("## ").append(bm.bookName).append("\n")
                        append("**").append(bm.chapterName).append("**\n")
                        if (bm.bookText.isNotEmpty()) append("> ").append(bm.bookText).append("\n")
                        if (bm.content.isNotEmpty()) append(bm.content).append("\n")
                    }
                }
                copyToClipboard(md)
                Toasters.get().toast(copiedMarkdownText)
            }

            override fun openBookmark(bookmark: Bookmark) {
                // 异步查 book → 跳转阅读 (对照 app 端 AllBookmarkActivity.openBookmark)
                scope.launch {
                    val book = withContext(Dispatchers.Default) {
                        AppDbProviders.get().bookDao.getBook(bookmark.bookName, bookmark.bookAuthor)
                    }
                    if (book == null) {
                        Toasters.get().toast(noBookLabel)
                        return@launch
                    }
                    onOpenBookmark(book, bookmark.chapterIndex, bookmark.chapterPos)
                }
            }

            override fun editBookmark(bookmark: Bookmark, pos: Int) {
                editTarget = bookmark
            }
        },
    )

    // 编辑对话框 (复用 shared/sharedUiMain 的 BookmarkDialog)
    editTarget?.let { bookmark ->
        BookmarkDialog(
            bookmark = bookmark,
            showDelete = true,
            onConfirm = { updated ->
                // 更新书签 (对照 app 端 BookmarkDialog.onConfirm → viewModel.update)
                scope.launch(Dispatchers.Default) {
                    AppDbProviders.get().bookmarkDao.update(updated)
                }
                editTarget = null
            },
            onDismiss = { editTarget = null },
            onDelete = {
                // 删除书签 (对照 app 端 BookmarkDialog.onDelete → viewModel.delete)
                scope.launch(Dispatchers.Default) {
                    AppDbProviders.get().bookmarkDao.delete(bookmark)
                }
                editTarget = null
            },
        )
    }
}
