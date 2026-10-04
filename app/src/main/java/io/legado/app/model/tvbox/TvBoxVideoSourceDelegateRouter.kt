package io.legado.app.model.tvbox

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookListPage
import io.legado.app.data.entities.BookSource
import io.legado.app.model.webBook.VideoSourceDelegate

/**
 * 视频取数委派路由: [VideoSourceDelegates] 注册表为单实现覆盖语义,
 * TVBox 接入时把注册时既有的委派 (漫画/视频插件链) 包裹进来, 按虚拟行前缀分流,
 * 两链路互不感知。
 *
 * 挂载时机: TvBoxManager.init() (测试/后续 App 启动接线处), 幂等。
 */
class TvBoxVideoSourceDelegateRouter(
    private val existing: VideoSourceDelegate?,
    private val tvBox: VideoSourceDelegate,
) : VideoSourceDelegate {

    override fun handles(bookSource: BookSource): Boolean =
        tvBox.handles(bookSource) || existing?.handles(bookSource) == true

    override suspend fun getBookListAwait(
        bookSource: BookSource,
        key: String,
        page: Int,
    ): BookListPage = when {
        tvBox.handles(bookSource) -> tvBox.getBookListAwait(bookSource, key, page)
        else -> existing?.getBookListAwait(bookSource, key, page)
            ?: error("无视频取数委派处理: ${bookSource.bookSourceUrl}")
    }

    override suspend fun getExploreAwait(
        bookSource: BookSource,
        url: String,
        page: Int,
    ): BookListPage = when {
        tvBox.handles(bookSource) -> tvBox.getExploreAwait(bookSource, url, page)
        else -> existing?.getExploreAwait(bookSource, url, page)
            ?: error("无视频取数委派处理: ${bookSource.bookSourceUrl}")
    }

    override suspend fun getBookInfoAwait(
        bookSource: BookSource,
        book: Book,
        canReName: Boolean,
    ): Book = when {
        tvBox.handles(bookSource) -> tvBox.getBookInfoAwait(bookSource, book, canReName)
        else -> existing?.getBookInfoAwait(bookSource, book, canReName)
            ?: error("无视频取数委派处理: ${bookSource.bookSourceUrl}")
    }

    override suspend fun getChapterListAwait(
        bookSource: BookSource,
        book: Book,
    ): Result<List<BookChapter>> = when {
        tvBox.handles(bookSource) -> tvBox.getChapterListAwait(bookSource, book)
        else -> existing?.getChapterListAwait(bookSource, book)
            ?: error("无视频取数委派处理: ${bookSource.bookSourceUrl}")
    }

    override suspend fun getContentAwait(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
    ): String = when {
        tvBox.handles(bookSource) -> tvBox.getContentAwait(bookSource, book, bookChapter)
        else -> existing?.getContentAwait(bookSource, book, bookChapter)
            ?: error("无视频取数委派处理: ${bookSource.bookSourceUrl}")
    }
}
