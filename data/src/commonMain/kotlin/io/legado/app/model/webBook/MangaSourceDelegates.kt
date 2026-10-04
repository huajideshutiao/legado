package io.legado.app.model.webBook

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookListPage
import io.legado.app.data.entities.BookSource
import kotlin.concurrent.Volatile

/**
 * 漫画插件源 (虚拟 BookSource) 的四路取数委派契约。
 *
 * 插件源以 bookSourceType=image 的虚拟 [BookSource] 行落地 (稳定 URL 标识 + header
 * 承载插件请求头), 自动进入书源管理/搜索范围/换源; [WebBook] 四个取数方法入口经
 * [getOrNull] 守卫把命中行转交本实现, 其余书源链路零感知。
 *
 * 接口只声明 shared 数据类型 (Book/BookChapter/BookListPage), 不感知插件侧类型;
 * 宿主 (app 端) 实现并经 [register] 注册, 未注册或 [handles] 不命中时走原规则解析链。
 */
interface MangaSourceDelegate {

    /** 该书源是否由本委派处理 (虚拟插件源行身份判定)。 */
    fun handles(bookSource: BookSource): Boolean

    /**
     * 插件源搜索 (对应 WebBook.getBookListAwait, isSearch=true; page 从 1 起)。
     */
    suspend fun getBookListAwait(
        bookSource: BookSource,
        key: String,
        page: Int,
    ): BookListPage

    /**
     * 插件源书籍详情 (对应 WebBook.getBookInfoAwait; 字段写回 [book] 并返回)。
     */
    suspend fun getBookInfoAwait(
        bookSource: BookSource,
        book: Book,
        canReName: Boolean,
    ): Book

    /**
     * 插件源目录 (对应 WebBook.getChapterListAwait)。
     */
    suspend fun getChapterListAwait(
        bookSource: BookSource,
        book: Book,
    ): Result<List<BookChapter>>

    /**
     * 插件源正文 (对应 WebBook.getContentAwait; 返回 `<img src="...">` 拼接串,
     * 复用 ChapterContentParserShared 提取链进入漫画阅读器)。
     */
    suspend fun getContentAwait(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
    ): String
}

object MangaSourceDelegates {
    @Volatile
    private var impl: MangaSourceDelegate? = null

    /** 宿主启动早期注册一次 (app 端 registerAndroidWebBookProviders)。 */
    fun register(impl: MangaSourceDelegate) {
        this.impl = impl
    }

    fun getOrNull(): MangaSourceDelegate? = impl
}
