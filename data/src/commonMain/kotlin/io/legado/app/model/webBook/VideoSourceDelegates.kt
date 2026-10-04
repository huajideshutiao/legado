package io.legado.app.model.webBook

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookListPage
import io.legado.app.data.entities.BookSource
import kotlin.concurrent.Volatile

/**
 * 视频插件源 (虚拟 BookSource) 的四路取数委派契约, 形状对齐 [MangaSourceDelegate]。
 *
 * 插件源以 bookSourceType=video 的虚拟 [BookSource] 行落地 (anime-plugin:// 前缀 URL),
 * [WebBook] 四个取数方法入口经 [getOrNull] 守卫把命中行转交本实现, 其余书源链路零感知;
 * 宿主 (app 端) 实现并经 [register] 注册, 未注册或 [handles] 不命中时走原规则解析链。
 */
interface VideoSourceDelegate {

    /** 该书源是否由本委派处理 (虚拟插件源行身份判定)。 */
    fun handles(bookSource: BookSource): Boolean

    /** 插件源搜索 (对应 WebBook.getBookListAwait, isSearch=true; page 从 1 起)。 */
    suspend fun getBookListAwait(
        bookSource: BookSource,
        key: String,
        page: Int,
    ): BookListPage

    /** 插件源书籍详情 (对应 WebBook.getBookInfoAwait; 字段写回 [book] 并返回)。 */
    suspend fun getBookInfoAwait(
        bookSource: BookSource,
        book: Book,
        canReName: Boolean,
    ): Book

    /** 插件源目录 (对应 WebBook.getChapterListAwait)。 */
    suspend fun getChapterListAwait(
        bookSource: BookSource,
        book: Book,
    ): Result<List<BookChapter>>

    /** 插件源正文 (对应 WebBook.getContentAwait; 返回可播地址串, 经视频内容解析链进播放器)。 */
    suspend fun getContentAwait(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
    ): String
}

object VideoSourceDelegates {
    @Volatile
    private var impl: VideoSourceDelegate? = null

    /** 宿主启动早期注册一次 (app 端 registerAndroidWebBookProviders)。 */
    fun register(impl: VideoSourceDelegate) {
        this.impl = impl
    }

    fun getOrNull(): VideoSourceDelegate? = impl
}
