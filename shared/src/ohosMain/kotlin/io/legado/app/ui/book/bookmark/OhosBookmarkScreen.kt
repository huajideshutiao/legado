package io.legado.app.ui.book.bookmark

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 鸿蒙端所有书签 Screen 入口 (包装 shared/sharedUiMain 的 [AllBookmarkScreen])。
 *
 * # 职责
 *
 * 对照 app 端 [AllBookmarkActivity] 与 iOS `IosBookmarkScreen.kt` 的薄壳模式,
 * 鸿蒙端在 `OhosNavHost` 的 BOOKMARK 路由分支调用本入口。
 *
 * 业务展示逻辑全部下沉到 shared/sharedUiMain, 本文件仅做鸿蒙平台适配:
 * - **数据订阅**: `produceState` 订阅 `appDb.bookmarkDao.flowAll()`, 打包为 [AllBookmarkUiState];
 * - **actions 实现**: 实现 [AllBookmarkUiActions] 接口, 平台依赖
 *   (导出/跳转阅读/编辑对话框) 通过回调桥接;
 * - **编辑对话框**: 复用 shared/sharedUiMain 的 [BookmarkDialog];
 * - **导出**: 鸿蒙端无文件保存面板, 简化为复制 JSON/Markdown 到剪贴板 (与 iOS 一致);
 * - **跳转阅读**: 通过 [onOpenBookmark] 回调由 OhosNavHost 切到 READER 路由
 *   (异步查 book → 携带 chapterIndex/pos 跳转, 对照 app 端 openBookmark)。
 *
 * # 简化项
 *
 * - **剪贴板**: 鸿蒙端 [ohosCopyToClipboard] 当前为 stub (println 占位), 真实实现需接入
 *   `ohos.pasteboard.SystemPasteboard` (tsfn 桥接), 后续补全
 *
 * @param onBack 返回回调 (切回调用方路由, 由 OhosNavHost 注入)
 * @param onOpenBookmark 点击书签跳转阅读回调 (携带 Book + chapterIndex + chapterPos),
 *   由 OhosNavHost 注入切到 READER 路由
 */
@Composable
fun OhosBookmarkScreen(
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
                // 鸿蒙端无文件保存面板, 简化为复制 JSON 到剪贴板 (与 iOS 一致)
                ohosCopyToClipboard(GSON.toJson(bookmarks))
                Toasters.get().toast(String.format(copiedBookmarksTemplate, bookmarks.size))
            }

            override fun exportMd() {
                // 鸿蒙端无文件保存面板, 简化为复制 Markdown 到剪贴板 (与 iOS 一致)
                val md = bookmarks.joinToString("\n\n") { bm ->
                    buildString {
                        append("## ").append(bm.bookName).append("\n")
                        append("**").append(bm.chapterName).append("**\n")
                        if (bm.bookText.isNotEmpty()) append("> ").append(bm.bookText).append("\n")
                        if (bm.content.isNotEmpty()) append(bm.content).append("\n")
                    }
                }
                ohosCopyToClipboard(md)
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

/**
 * 鸿蒙端剪贴板写入 (替代 iOS 端 `copyToClipboard` 用 UIPasteboard)。
 *
 * TODO: 接入 `ohos.pasteboard.SystemPasteboard` (通过 tsfn 桥接 ArkTS),
 *  当前为 println 占位 (与 OpenUrlProvider 鸿蒙 stub 一致), 保证导出链路不崩;
 *  真实实现需在 EntryAbility 注册 pasteboard tsfn 回调, Kotlin 侧调 setTextData。
 */
private fun ohosCopyToClipboard(text: String) {
    println("[Clipboard] ${text.take(50)}")
}
