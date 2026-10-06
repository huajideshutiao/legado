package io.legado.app.model.manga

import eu.kanade.tachiyomi.source.online.HttpSource
import io.legado.app.constant.BookSourceType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookListPage
import io.legado.app.help.extension.MangaExtensionManager
import io.legado.app.model.webBook.BookChapterList
import io.legado.app.model.webBook.PluginSourceDelegate
import io.legado.app.data.entities.SearchBook
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 漫画插件源取数委派实现 (data 层 [PluginSourceDelegate] 的漫画源实现)。
 *
 * 经 MangaExtensionManager 解析插件源实例后, 四路取数直接调 Mihon source-api 的
 * suspend 契约 (getSearchManga/getMangaUpdate/getPageList), 再经 MangaSourceMapper
 * 映射回 Book/BookChapter/`<img src>` 正文, 复用现有搜索/详情/目录/漫画阅读器管线。
 * 筛选器状态取自 MangaPluginFilterCache (与搜索页 UI 同一份实例)。
 */
object MangaSourceDelegateImpl : PluginSourceDelegate {

    override fun handles(bookSource: BookSource): Boolean =
        bookSource.bookSourceType == BookSourceType.image &&
            bookSource.bookSourceUrl.startsWith(MangaSourceMapper.SOURCE_URL_PREFIX)

    override fun tocFailMessage(bookSource: BookSource, e: Exception): String =
        "获取tachiyomi插件 ${bookSource.bookSourceName} 的书籍目录失败\n${e.message}"

    override suspend fun getBookListAwait(
        bookSource: BookSource,
        key: String,
        page: Int,
    ): BookListPage {
        val source = resolveSource(bookSource)
        val mangasPage = source.getSearchManga(page, key, MangaPluginFilterCache.getOrCreate(source))
        return mangasPage.toBookListPage(bookSource)
    }

    override suspend fun getExploreAwait(
        bookSource: BookSource,
        url: String,
        page: Int,
    ): BookListPage {
        val source = resolveSource(bookSource)
        // 虚拟源 exploreUrl 的分类 url 段 → 插件源对应取数面 (未知值显式报错, 不静默返回空)
        val mangasPage = when (url) {
            MangaPluginSources.EXPLORE_URL_POPULAR -> source.getPopularManga(page)
            MangaPluginSources.EXPLORE_URL_LATEST -> source.getLatestUpdates(page)
            else -> throw IllegalStateException("未知的漫画插件发现分类: $url")
        }
        return mangasPage.toBookListPage(bookSource)
    }

    /** MangasPage → BookListPage (搜索与发现共用同一映射)。 */
    private fun MangasPage.toBookListPage(bookSource: BookSource): BookListPage {
        val books = ArrayList<SearchBook>(mangas.size)
        with(MangaSourceMapper) {
            for (manga in mangas) {
                books.add(
                    manga.toSearchBook(
                        bookSource.bookSourceUrl,
                        bookSource.bookSourceName,
                    )
                )
            }
        }
        return BookListPage(books, hasNextPage)
    }

    override suspend fun getBookInfoAwait(
        bookSource: BookSource,
        book: Book,
        canReName: Boolean,
    ): Book {
        val source = resolveSource(bookSource)
        val sManga = book.toSManga(source)
            ?: throw IllegalStateException("无法从 bookUrl 解析插件源 manga 地址: ${book.bookUrl}")
        val update = source.getMangaUpdate(
            sManga,
            chapters = emptyList(),
            fetchDetails = true,
            fetchChapters = false,
        )
        MangaSourceMapper.applyTo(book, update.manga, canReName)
        return book
    }

    override suspend fun getChapterListAwait(
        bookSource: BookSource,
        book: Book,
    ): Result<List<BookChapter>> = runCatching {
        val source = resolveSource(bookSource)
        val sManga = book.toSManga(source)
            ?: throw IllegalStateException("无法从 bookUrl 解析插件源 manga 地址: ${book.bookUrl}")
        val update = source.getMangaUpdate(
            sManga,
            chapters = emptyList(),
            fetchDetails = false,
            fetchChapters = true,
        )
        val chapterList = with(MangaSourceMapper) {
            update.chapters.mapIndexed { index, sChapter ->
                sChapter.toBookChapter(book, index)
            }
        }
        // 与规则链同构: updateBook 负责 reverse/index/totalChapterNum 等目录簿记。
        // 目录不预反转, 走 updateBook 默认反转 (仅 TVBox 卷头需正序簿记才预反转, 漫画无卷头);
        // reverseToc=true 时保持插件返回顺序, 与小说"目录倒序"语义一致
        BookChapterList.updateBook(book, chapterList)
    }.onFailure {
        if (it is CancellationException) throw it
        currentCoroutineContext().ensureActive()
    }

    override suspend fun getContentAwait(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
    ): String {
        val source = resolveSource(bookSource)
        val sChapter = SChapter.create().apply { url = bookChapter.url }
        val pages = source.getPageList(sChapter)
        val content = with(MangaSourceMapper) {
            pages.toImgContent(sourceId = source.id) { page ->
                // 延迟解析型插件源: imageUrl 未给时回退 HttpSource.getImageUrl (可抛, 逐页容错; 取消照常上抛)
                runCatching { (source as? HttpSource)?.getImageUrl(page) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrNull()
            }
        }
        if (content.isBlank()) {
            throw IllegalStateException("插件源未返回图片 ${bookChapter.title}")
        }
        return content
    }

    private suspend fun resolveSource(bookSource: BookSource) =
        MangaSourceMapper.sourceIdOf(bookSource.bookSourceUrl)
            ?.let { MangaExtensionManager.getSource(it) }
            ?: throw IllegalStateException(
                "漫画插件源未装载: ${bookSource.bookSourceName} (${bookSource.bookSourceUrl})"
            )

    /**
     * 插件源图片内部协议形态（正文与缓存 key 共用, 单一事实来源
     * [io.legado.app.help.image.PluginImageUrl]）。
     *
     * Book → 插件侧 SManga (url 由 bookUrl 反解)。
     * lib1.6 契约: getMangaUpdate 入参是"已有 manga" (上游 SMangaImpl.title 为 lateinit,
     * 扩展允许原样返回或仅部分覆写入参), 宿主已知字段必须预填, 不能只带 url。
     */
    private fun Book.toSManga(source: eu.kanade.tachiyomi.source.Source): SManga? {
        val sourceId = MangaSourceMapper.sourceIdOf(origin) ?: return null
        val mangaUrl = MangaSourceMapper.mangaUrlOf(bookUrl, sourceId) ?: return null
        return SManga.create().apply {
            url = mangaUrl
            title = name
            author = this@toSManga.author
            thumbnail_url = coverUrl
            description = intro
        }
    }
}
