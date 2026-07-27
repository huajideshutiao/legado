package io.legado.app.ui.book.bookmark

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Bookmark
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 所有书签(纯 Compose)：按书分组吸顶列表，点击跳转/长按编辑/导出。
 *
 * 薄壳：实现 [AllBookmarkUiActions] 接口供下沉到 shared/sharedUiMain 的 [AllBookmarkScreen] 回调；
 * Composable 渲染与分组逻辑已下沉到 shared，本类只负责数据订阅、平台依赖桥接
 * (exportDir / showDialogFragment / startActivityForBook / toastOnUi)。
 */
class AllBookmarkActivity : BaseComposeActivity(), AllBookmarkUiActions {

    private val viewModel by viewModels<AllBookmarkViewModel>()

    private var bookmarks by mutableStateOf<List<Bookmark>>(emptyList())

    private val exportDir = registerHandleFile {
        it.uri?.let { uri ->
            when (it.requestCode) {
                1 -> viewModel.exportBookmark(uri)
                2 -> viewModel.exportBookmarkMd(uri)
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        lifecycleScope.launch {
            appDb.bookmarkDao.flowAll().catch {
                AppLog.put("所有书签界面获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).collect {
                bookmarks = it
            }
        }
    }

    @Composable
    override fun Content() {
        val state = remember(bookmarks) { AllBookmarkUiState(bookmarks) }
        AllBookmarkScreen(state = state, actions = this)
    }

    // ===== AllBookmarkUiActions 适配 =====

    /** 返回回调 (替代原 Content 内 `finish()`)。 */
    override fun onBack() = finish()

    /** 导出 JSON (对照原 OverflowMenu 项 `exportDir.launch { requestCode = 1 }`)。 */
    override fun export() {
        exportDir.launch { requestCode = 1 }
    }

    /** 导出 Markdown (对照原 OverflowMenu 项 `exportDir.launch { requestCode = 2 }`)。 */
    override fun exportMd() {
        exportDir.launch { requestCode = 2 }
    }

    /** 点击书签跳转阅读 (复刻原 private openBookmark)。 */
    override fun openBookmark(bookmark: Bookmark) {
        lifecycleScope.launch {
            val book = withContext(IO) {
                appDb.bookDao.getBook(bookmark.bookName, bookmark.bookAuthor)
            }
            if (book == null) {
                toastOnUi(R.string.no_book)
                return@launch
            }
            startActivityForBook(book) {
                putExtra("chapterIndex", bookmark.chapterIndex)
                putExtra("chapterPos", bookmark.chapterPos)
                putExtra("chapterChanged", true)
            }
        }
    }

    /** 长按书签弹编辑对话框 (对照原 BookmarkItem `showDialogFragment(BookmarkDialog(item, pos))`)。 */
    override fun editBookmark(bookmark: Bookmark, pos: Int) {
        showDialogFragment(BookmarkDialog(bookmark, pos))
    }

}
